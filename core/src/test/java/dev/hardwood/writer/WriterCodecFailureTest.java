/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.compression.Compressor;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.CompressionCodec;

import static dev.hardwood.writer.WriterTestSupport.oneColumn;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A codec that fails is reported as the [IOException] every public write method declares.
///
/// Compression happens under `ParquetFileWriter.flushRowGroup` and nowhere else, and it is
/// reached from a callback that cannot declare a checked exception — so the failure leaves
/// `ColumnChunkBuffer` as an `UncheckedIOException`. All three public methods that flush reach
/// it, and each of them promises an `IOException`, so each is asserted rather than the one that
/// happened to be noticed. The wrapper's message is what names the codec and the page, so it is
/// the message the caller is left with; the codec's own exception stays the cause.
class WriterCodecFailureTest {

    /// Fails on every page body. `getName` is answered so the writer is configured exactly as it
    /// would be for a working codec.
    private static final Compressor FAILING = new Compressor() {
        @Override
        public byte[] compress(byte[] data, int offset, int length) throws IOException {
            throw new IOException("codec unavailable");
        }

        @Override
        public String getName() {
            return "FAILING";
        }
    };

    /// A row group per record, so a flush happens inside the write call rather than only at
    /// close. `GZIP` names a codec that is not `UNCOMPRESSED`, which is the one value that
    /// skips compression entirely; the compressor above is what actually runs.
    private static WriterConfig flushPerRecord() {
        return WriterConfig.builder()
                .codec(CompressionCodec.GZIP)
                .rowGroupTargetRows(1)
                .build();
    }

    @Test
    void writeBatchReportsACodecFailureAsIOException() throws Exception {
        ParquetFileWriter writer = ParquetFileWriter.create(
                new ByteBufferOutputFile(), oneColumn(), flushPerRecord(), FAILING);

        assertThatThrownBy(() -> writer.columnWriter().writeBatch(batch -> batch.ints(0, new int[] {1, 2})))
                .isInstanceOf(IOException.class)
                .hasMessage("Failed to GZIP-compress a page body")
                .hasRootCauseMessage("codec unavailable");
    }

    @Test
    void writeRowReportsACodecFailureAsIOException() throws Exception {
        ParquetFileWriter writer = ParquetFileWriter.create(
                new ByteBufferOutputFile(), oneColumn(), flushPerRecord(), FAILING);
        RowWriter rows = writer.rowWriter();

        assertThatThrownBy(() -> {
            // The row writer stages records and submits them in batches, so the flush that
            // fails may be triggered by any one of them.
            for (int i = 0; i < 4096; i++) {
                int value = i;
                rows.writeRow(row -> row.setInt(0, value));
            }
            writer.close();
        }).isInstanceOf(IOException.class)
          .hasMessage("Failed to GZIP-compress a page body")
          .hasRootCauseMessage("codec unavailable");
    }

    @Test
    void closeReportsACodecFailureAsIOException() throws Exception {
        // No row-group target reached while writing, so the only flush is the one close does.
        ParquetFileWriter writer = ParquetFileWriter.create(new ByteBufferOutputFile(), oneColumn(),
                WriterConfig.builder().codec(CompressionCodec.GZIP).build(), FAILING);
        writer.columnWriter().writeBatch(batch -> batch.ints(0, new int[] {1, 2, 3}));

        assertThatThrownBy(writer::close)
                .isInstanceOf(IOException.class)
                .hasMessage("Failed to GZIP-compress a page body")
                .hasRootCauseMessage("codec unavailable");
    }
}
