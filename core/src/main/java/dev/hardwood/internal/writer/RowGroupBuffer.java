/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.hardwood.OutputFile;
import dev.hardwood.internal.compression.Compressor;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnEncoding;

/// Buffers the column chunks of a single row group. A record range's shredded levels are
/// appended across all columns; at flush the column chunks are written contiguously in
/// schema order, each recording the file offset at which its first page lands.
public final class RowGroupBuffer {

    private final FileSchema schema;
    private final ColumnChunkBuffer[] columns;
    private int rowCount;

    /// @param schema the file schema
    /// @param pageTargetBytes buffered bytes after which a data page is cut
    /// @param encodings each leaf column's resolved encoding policy, in schema order
    /// @param statisticsTruncationLength the maximum `BYTE_ARRAY` `min` / `max` bound length
    /// @param compressor compresses each page body before it is buffered
    /// @param codec the codec `compressor` applies, recorded in each chunk's metadata
    public RowGroupBuffer(FileSchema schema, int pageTargetBytes, long rowGroupBufferTargetBytes,
                          ColumnEncoding[] encodings, int statisticsTruncationLength,
                          Compressor compressor, CompressionCodec codec) {
        this.schema = schema;
        this.columns = new ColumnChunkBuffer[schema.getColumnCount()];
        // An equal share each, so the whole schema's buffers start at one row group's worth
        // however many columns it has.
        long budgetBytesPerColumn = rowGroupBufferTargetBytes / columns.length;
        for (int c = 0; c < columns.length; c++) {
            columns[c] = new ColumnChunkBuffer(schema.getColumn(c), pageTargetBytes, budgetBytesPerColumn,
                    encodings[c], statisticsTruncationLength, compressor, codec);
        }
    }

    /// Shreds the same record range into every column and advances the row count.
    ///
    /// @param shredder bound to the current batch
    /// @param sources the current batch's value sources, one per column
    /// @param from index of the first record to append
    /// @param count number of records to append
    public void appendRecords(RecordShredder shredder, ColumnSource[] sources, int from, int count) {
        for (int c = 0; c < columns.length; c++) {
            columns[c].append(shredder, sources[c], c, from, count);
        }
        rowCount += count;
    }

    /// The largest slice of `[from, from + count)`, halving down from `count`, that this row group
    /// can take without passing `room` — or one record, which goes in whatever it costs, a record
    /// not being divisible across row groups.
    ///
    /// The bound is what a slice can cost rather than what it will, so this is a floor on how much
    /// could be appended rather than the most that would fit. That is all it has to be: the row
    /// group is cut on what it turns out to hold, so an over-cautious slice costs an extra reading
    /// of a number every buffer already tracks, never a row group cut short.
    ///
    /// Halving rather than searching for the largest fit keeps this to a dozen evaluations of the
    /// bound in the worst case, and to one wherever a whole slice fits — which is every slice of a
    /// row group but its last few.
    public int sliceThatFits(RecordShredder shredder, ColumnSource[] sources, int from, int count,
                             long room) {
        int slice = count;
        while (slice > 1 && maxRetainedBytesFor(shredder, sources, from, slice) > room) {
            slice >>= 1;
        }
        return slice;
    }

    /// The most appending `[from, from + count)` can add across every column.
    private long maxRetainedBytesFor(RecordShredder shredder, ColumnSource[] sources, int from,
                                     int count) {
        long bytes = 0;
        for (int c = 0; c < columns.length; c++) {
            long leaves = shredder.leafRange(c, from, count);
            bytes += columns[c].maxRetainedBytesFor(sources[c], count,
                    (int) (leaves >>> Integer.SIZE), (int) leaves, shredder.phantomLayers(c));
        }
        return bytes;
    }

    /// The number of records buffered so far.
    public int rowCount() {
        return rowCount;
    }

    /// Records a row group may hold, whatever the configured targets say.
    ///
    /// A chunk accumulates its records into `int`-indexed buffers — the value store, the index
    /// store, the level stores — and the ceiling below is the largest any of them can reach. Each
    /// record costs every column at least one entry, and every entry costs either a stored value
    /// or a level byte, so a group of this many records is one that no column can take another
    /// record of.
    public static final int MAX_ROWS = Integer.MAX_VALUE - 8;

    /// The bytes this row group retains, summed across its column chunks. The writer flushes the
    /// row group once this reaches the configured target.
    ///
    /// The sum is over columns rather than over values, so it costs the same whether the group
    /// holds a thousand records or a million, and it is read once per appended slice.
    public long retainedBytes() {
        long bytes = 0;
        for (ColumnChunkBuffer column : columns) {
            bytes += column.retainedBytes();
        }
        return bytes;
    }

    /// Whether no rows have been buffered.
    public boolean isEmpty() {
        return rowCount == 0;
    }

    /// Starts the next row group, keeping the buffers this one grew. A row group's worth of
    /// retained values is the writer's largest allocation, so rebuilding these per group would
    /// make the write path's garbage scale with the number of row groups rather than with one.
    public void reset() {
        for (ColumnChunkBuffer column : columns) {
            column.reset();
        }
        rowCount = 0;
    }

    /// Writes the buffered column chunks to `out` in schema order and returns the row
    /// group's metadata.
    public RowGroup flushTo(OutputFile out) throws IOException {
        List<ColumnChunk> chunks = new ArrayList<>(columns.length);
        long totalByteSize = 0;
        for (int c = 0; c < columns.length; c++) {
            long chunkStartOffset = out.position();
            ColumnMetaData meta = columns[c].flushTo(out, schema.getColumn(c), chunkStartOffset);
            chunks.add(new ColumnChunk(meta, null, null, null, null, ""));
            totalByteSize += meta.totalUncompressedSize();
        }
        return new RowGroup(chunks, totalByteSize, rowCount);
    }
}
