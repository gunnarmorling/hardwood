/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.schema.ColumnProjection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Opening a reader from metadata the caller already parsed must read the same
/// rows as opening one that parses it, and must not touch the footer again.
class ReuseFileMetadataTest {

    private static final String FILE = "src/test/resources/page_index_test.parquet";

    @Test
    void reusedMetadataReadsTheSameRowsWithoutReReadingTheFooter() throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            FileMetaData metaData;
            long expected;
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(Paths.get(FILE)), context)) {
                metaData = reader.getFileMetaData();
                expected = count(reader);
            }

            // Everything from the start of the Thrift footer to the end of the file,
            // derived from the trailer, is what a reader handed parsed metadata must
            // not ask for. Anything before it is data.
            WatchedInputFile watched = new WatchedInputFile(
                    InputFile.of(Paths.get(FILE)), footerStart(Paths.get(FILE)));
            try (ParquetFileReader reader = ParquetFileReader.open(watched, context, metaData)) {
                assertThat(count(reader)).isEqualTo(expected);
            }
            assertThat(watched.tailReads)
                    .as("a reader given parsed metadata must not read the footer")
                    .isZero();
        }
    }

    /// The metadata is acquired from a reader, which owns and closes the input file
    /// it was given. Reuse therefore has to work from a footer whose own file is
    /// already closed — otherwise the caller would have to keep every file it ever
    /// cached a footer for open.
    @Test
    void metadataOutlivesTheReaderAndTheFileItCameFrom() throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            WatchedInputFile source = new WatchedInputFile(InputFile.of(Paths.get(FILE)), Long.MAX_VALUE);
            FileMetaData metaData;
            long expected;
            try (ParquetFileReader reader = ParquetFileReader.open(source, context)) {
                metaData = reader.getFileMetaData();
                expected = count(reader);
            }
            assertThat(source.closes).as("open() owns the file it is given").isPositive();

            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(Paths.get(FILE)),
                    context, metaData)) {
                assertThat(count(reader)).isEqualTo(expected);
            }
        }
    }

    /// Reuse must not make the new reader's file the caller's to close: the
    /// overload takes ownership exactly like every other `open`.
    @Test
    void aReaderOpenedFromReusedMetadataStillOwnsItsFile() throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            FileMetaData metaData;
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(Paths.get(FILE)), context)) {
                metaData = reader.getFileMetaData();
            }

            WatchedInputFile watched = new WatchedInputFile(InputFile.of(Paths.get(FILE)), Long.MAX_VALUE);
            ParquetFileReader reader = ParquetFileReader.open(watched, context, metaData);
            assertThat(watched.opens).as("the reader opens the file it is given").isPositive();
            assertThat(watched.closes).isZero();
            reader.close();
            assertThat(watched.closes).as("the reader closes the file it opened").isPositive();
        }
    }

    @Test
    void nullMetadataIsRejected() throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            assertThatThrownBy(() -> ParquetFileReader.open(InputFile.of(Paths.get(FILE)), context,
                    (FileMetaData) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /// First byte of the Thrift footer, from the 8-byte trailer.
    private static long footerStart(Path file) throws IOException {
        byte[] trailer = new byte[8];
        long size = java.nio.file.Files.size(file);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            raf.seek(size - 8);
            raf.readFully(trailer);
        }
        long footerLength = (trailer[0] & 0xFFL) | ((trailer[1] & 0xFFL) << 8)
                | ((trailer[2] & 0xFFL) << 16) | ((trailer[3] & 0xFFL) << 24);
        return size - 8 - footerLength;
    }

    private static long count(ParquetFileReader reader) {
        String first = reader.getFileSchema().getColumn(0).fieldPath().toString();
        long rows = 0;
        try (ColumnReaders readers = reader.columnReaders(ColumnProjection.columns(first))) {
            while (readers.nextBatch()) {
                rows += readers.getRecordCount();
            }
        }
        return rows;
    }

    /// Records the lifecycle calls a reader makes on the file it is given, and the
    /// reads that fall at or beyond `tailFrom` — the footer, for a `tailFrom` taken
    /// from the trailer, and nothing at all for [Long#MAX_VALUE].
    private static final class WatchedInputFile implements InputFile {

        private final InputFile delegate;
        private final long tailFrom;
        private int tailReads;
        private int opens;
        private int closes;

        WatchedInputFile(InputFile delegate, long tailFrom) {
            this.delegate = delegate;
            this.tailFrom = tailFrom;
        }

        @Override
        public void open() throws IOException {
            opens++;
            delegate.open();
        }

        @Override
        public ByteBuffer readRange(long position, int length) throws IOException {
            if (position >= tailFrom) {
                tailReads++;
            }
            return delegate.readRange(position, length);
        }

        @Override
        public long length() throws IOException {
            return delegate.length();
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public void close() throws IOException {
            closes++;
            delegate.close();
        }
    }
}
