/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.hardwood.OutputFile;
import dev.hardwood.internal.compression.Compressor;
import dev.hardwood.internal.compression.CompressorFactory;
import dev.hardwood.internal.encoding.LevelEncoder;
import dev.hardwood.internal.thrift.FileMetaDataWriter;
import dev.hardwood.internal.thrift.ThriftCompactWriter;
import dev.hardwood.internal.writer.ColumnSource;
import dev.hardwood.internal.writer.RecordShredder;
import dev.hardwood.internal.writer.RowGroupBuffer;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

/// Writes a Parquet file through a columnar batch API.
///
/// This increment writes every primitive physical type — `BOOLEAN`, `INT32`, `INT64`, `FLOAT`,
/// `DOUBLE`, `BYTE_ARRAY`, and `FIXED_LEN_BYTE_ARRAY` — flat `REQUIRED` / `OPTIONAL`, nested
/// inside `REQUIRED` / `OPTIONAL` `struct` groups, and inside `LIST`s and `MAP`s (including lists
/// of lists, lists of structs, and maps of any in-scope value). Data is supplied as
/// [ColumnBatch] slices; the writer packs each column into size-bounded data pages — a
/// levelled column's pages carrying an RLE definition-level stream ahead of the values — and
/// flushes a row group once its buffered data reaches the configured target, so peak memory is
/// bounded regardless of how much is written. Columns are dictionary-encoded by default (a
/// dictionary page plus `RLE_DICTIONARY` index pages), falling back to `PLAIN` when the
/// dictionary grows past the configured limit. Each page body is compressed with the
/// configured codec (`ZSTD` by default, or `UNCOMPRESSED`). All of these are configurable
/// through [WriterConfig]. The row groups and footer are finalized on [#close()].
///
/// The file is produced front to back and is valid only after `close()` returns.
public final class ParquetFileWriter implements Closeable {

    private static final byte[] MAGIC = "PAR1".getBytes(StandardCharsets.UTF_8);
    private static final int FORMAT_VERSION = 1;

    /// Nominal `BYTE_ARRAY` value length assumed when estimating the flush-check stride. Only the
    /// append granularity depends on it; the row group flushes on actual buffered bytes.
    private static final int ASSUMED_BYTE_ARRAY_LENGTH = 16;

    private final OutputFile out;
    private final FileSchema schema;
    private final WriterConfig config;
    /// Records appended before the per-record size is known, to learn it without overshooting a
    /// small row-group target on a batch of large variable-width values.
    private static final int PROBE_RECORDS = 64;

    private final int pageValues;
    private final long rowGroupTargetBits;
    private final RecordShredder shredder;
    private final Compressor compressor;
    private final List<RowGroup> rowGroups = new ArrayList<>();

    // Running actual buffered-bit average, learned across the whole write so the append stride
    // between flush checks lands near the row-group boundary regardless of value width.
    private long cumulativeBits;
    private long cumulativeRecords;

    private RowGroupBuffer current;
    private long numRows;
    private boolean closed;

    private ParquetFileWriter(OutputFile out, FileSchema schema, WriterConfig config, Compressor compressor) {
        this.out = out;
        this.schema = schema;
        this.config = config;
        this.pageValues = pageRowCapacity(config.pageTargetBytes(), schema);
        this.rowGroupTargetBits = Math.multiplyExact(config.rowGroupTargetBytes(), Byte.SIZE);
        this.shredder = new RecordShredder(schema);
        this.compressor = compressor;
        this.current = newRowGroupBuffer();
    }

    private RowGroupBuffer newRowGroupBuffer() {
        return new RowGroupBuffer(schema, pageValues, config.enableDictionary(), config.dictionaryPageLimitBytes(),
                config.statisticsTruncationLength(), compressor, config.codec());
    }

    /// Opens a writer with the default [WriterConfig].
    ///
    /// @param out the destination
    /// @param schema the schema to write
    /// @return an open writer
    /// @throws IOException if the destination cannot be opened
    /// @throws UnsupportedOperationException if the schema has a column of an unsupported
    ///         physical type
    public static ParquetFileWriter create(OutputFile out, FileSchema schema) throws IOException {
        return create(out, schema, WriterConfig.defaults());
    }

    /// Opens a writer, writing the leading magic bytes.
    ///
    /// @param out the destination
    /// @param schema the schema to write
    /// @param config the writer configuration
    /// @return an open writer
    /// @throws IOException if the destination cannot be opened
    /// @throws UnsupportedOperationException if the schema has a column of an unsupported
    ///         physical type, or the configured codec cannot be written
    public static ParquetFileWriter create(OutputFile out, FileSchema schema, WriterConfig config)
            throws IOException {
        for (int c = 0; c < schema.getColumnCount(); c++) {
            ColumnSchema column = schema.getColumn(c);
            if (!isSupportedType(column.type())) {
                throw new UnsupportedOperationException(
                        "Writer does not support " + column.type() + " columns yet; column "
                                + column.name() + " is " + column.type());
            }
        }
        // Resolve the codec before touching the output, so an unsupported codec or a missing
        // codec library fails before any bytes are written rather than leaving a partial file.
        Compressor compressor = new CompressorFactory().getCompressor(config.codec());
        out.create();
        out.write(ByteBuffer.wrap(MAGIC));
        return new ParquetFileWriter(out, schema, config, compressor);
    }

    /// Writes one aligned batch of column values, flushing row groups as the buffered
    /// data crosses the row-group target. A batch that would overflow the current row
    /// group is split at the boundary.
    ///
    /// The writer creates the batch — bound to the schema — passes it to `filler` to be
    /// populated (columns addressed by index or name), then submits it. There is no
    /// separate build or submit step to forget.
    ///
    /// @param filler populates the batch's columns; must cover every column exactly once
    /// @throws IOException if the write fails
    /// @throws IllegalArgumentException if the batch does not cover every column
    public void writeBatch(Consumer<ColumnBatch> filler) throws IOException {
        ensureOpen();
        ColumnBatch batch = new ColumnBatch(schema);
        filler.accept(batch);
        ColumnSource[] sources = batch.completedSources();
        shredder.bind(sources, batch.validities(), batch.structValidities(),
                batch.listValidities(), batch.listOffsets());
        batch.markConsumed();
        int rows = shredder.recordCount();
        int pos = 0;
        while (pos < rows) {
            int n = nextStride(rows - pos);
            long before = current.bufferedBits();
            current.appendRecords(shredder, sources, pos, n);
            cumulativeBits += current.bufferedBits() - before;
            cumulativeRecords += n;
            pos += n;
            if (current.bufferedBits() >= rowGroupTargetBits) {
                flushRowGroup();
            }
        }
    }

    /// How many of the next `remaining` records to append before re-checking the buffered-byte
    /// target. Until the per-record size is measured, a small probe learns it without
    /// overshooting; afterwards the stride is sized to just fill the remaining row-group budget
    /// from the running average (exact for fixed-width columns), so a row group lands on the
    /// target regardless of value width. Capped at one page's worth of entries.
    private int nextStride(int remaining) {
        if (cumulativeRecords == 0) {
            return Math.min(remaining, PROBE_RECORDS);
        }
        long avgBits = Math.max(1, cumulativeBits / cumulativeRecords);
        long remainingBits = Math.max(avgBits, rowGroupTargetBits - current.bufferedBits());
        long stride = Math.min(pageValues, ceilDiv(remainingBits, avgBits));
        return (int) Math.max(1, Math.min(remaining, stride));
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flushRowGroup();
            writeFooter();
        }
        catch (IOException | RuntimeException e) {
            // The footer is incomplete, so the file is not valid. Discard it rather than
            // letting out.close() publish a truncated file.
            try {
                out.discard();
            }
            catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        out.close();
    }

    private void flushRowGroup() throws IOException {
        if (current.isEmpty()) {
            return;
        }
        RowGroup rowGroup = current.flushTo(out);
        rowGroups.add(rowGroup);
        numRows += rowGroup.numRows();
        current = newRowGroupBuffer();
    }

    private void writeFooter() throws IOException {
        FileMetaData metaData = new FileMetaData(
                FORMAT_VERSION,
                schema.toSchemaElements(),
                numRows,
                List.copyOf(rowGroups),
                Map.of(),
                config.createdBy(),
                List.of());

        ThriftCompactWriter footer = new ThriftCompactWriter();
        FileMetaDataWriter.write(footer, metaData);
        byte[] footerBytes = footer.toByteArray();

        out.write(ByteBuffer.wrap(footerBytes));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(footerBytes.length).flip());
        out.write(ByteBuffer.wrap(MAGIC));
    }

    /// Rows per data page whose encoded body fits the page target. A page costs each column's
    /// estimated `PLAIN` value bit width per row plus, for a levelled column, its RLE
    /// definition-level stream; sizing to the widest column's per-row bit cost keeps every
    /// column's page within the target. At least one row so a tiny target still makes progress.
    /// This is a page-level entry-count bound only; the actual row-group flush tracks buffered
    /// bytes, so a variable-width estimate here does not distort the produced file.
    private static int pageRowCapacity(long pageTargetBytes, FileSchema schema) {
        long maxColumnBitsPerRow = 1;
        for (int c = 0; c < schema.getColumnCount(); c++) {
            ColumnSchema column = schema.getColumn(c);
            int defBits = LevelEncoder.bitWidth(column.maxDefinitionLevel());
            maxColumnBitsPerRow = Math.max(maxColumnBitsPerRow, estimatedValueBits(column) + defBits);
        }
        long rows = pageTargetBytes * Byte.SIZE / maxColumnBitsPerRow;
        return (int) Math.max(1, Math.min(rows, Integer.MAX_VALUE));
    }

    /// The estimated `PLAIN` bit width of one of a column's values, used only to bound the
    /// per-page entry count: exact for the fixed-width scalars and for `FIXED_LEN_BYTE_ARRAY`
    /// (its schema type length), and a nominal estimate for `BYTE_ARRAY` (a 4-byte length prefix
    /// plus an assumed value length).
    private static long estimatedValueBits(ColumnSchema column) {
        return switch (column.type()) {
            case BOOLEAN -> 1;
            case INT32, FLOAT -> Integer.SIZE;
            case INT64, DOUBLE -> Long.SIZE;
            case FIXED_LEN_BYTE_ARRAY -> (long) requireTypeLength(column) * Byte.SIZE;
            case BYTE_ARRAY -> (long) (Integer.BYTES + ASSUMED_BYTE_ARRAY_LENGTH) * Byte.SIZE;
            case INT96 -> throw new IllegalArgumentException("INT96 is not supported by the writer");
        };
    }

    private static int requireTypeLength(ColumnSchema column) {
        if (column.typeLength() == null) {
            throw new IllegalArgumentException(
                    "FIXED_LEN_BYTE_ARRAY column " + column.name() + " requires a type length");
        }
        return column.typeLength();
    }

    /// Whether the writer supports producing a column of this physical type.
    private static boolean isSupportedType(PhysicalType type) {
        return switch (type) {
            case BOOLEAN, INT32, INT64, FLOAT, DOUBLE, BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> true;
            case INT96 -> false;
        };
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Writer is closed");
        }
    }
}
