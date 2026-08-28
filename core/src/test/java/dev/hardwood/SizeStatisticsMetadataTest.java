/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.thrift.ColumnIndexReader;
import dev.hardwood.internal.thrift.OffsetIndexReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnIndex;
import dev.hardwood.metadata.OffsetIndex;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.metadata.SizeStatistics;
import dev.hardwood.reader.ParquetFileReader;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the `SizeStatistics` and level-histogram fields against a file written by
/// a real writer, rather than a hand-rolled Thrift struct.
///
/// The fixture `size_statistics_test.parquet` holds 4 rows in one row group, one page
/// per column:
///
/// - `name` — `BYTE_ARRAY` `("alpha", null, "gamma", "delta")`, so the unencoded size is
///   15 bytes and one of the 4 values is null
/// - `tags` — `LIST` of `INT32` `([1,2], [], null, [3])`, the only column with a
///   repetition-level histogram
/// - `score` — `DOUBLE` `(1.5, NaN, 3.0, null)`
///
/// PyArrow 24.0.0 writes no `nan_count` (`Statistics` field 9) or `nan_counts`
/// (`ColumnIndex` field 8) for any column, including `score`. No available writer emits
/// them, so those two fields are asserted absent here and covered positively by the
/// reader unit tests.
class SizeStatisticsMetadataTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/size_statistics_test.parquet");

    private static final int NAME = 0;
    private static final int TAGS = 1;
    private static final int SCORE = 2;

    @Test
    void surfacesSizeStatisticsFromTheColumnChunk() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().get(0);

            SizeStatistics name = rowGroup.columns().get(NAME).metaData().sizeStatistics();
            assertThat(name).isNotNull();
            assertThat(name.unencodedByteArrayDataBytes()).isEqualTo(15L);
            assertThat(name.definitionLevelHistogram()).containsExactly(1L, 3L);

            SizeStatistics tags = rowGroup.columns().get(TAGS).metaData().sizeStatistics();
            assertThat(tags.unencodedByteArrayDataBytes()).isNull();
            assertThat(tags.repetitionLevelHistogram()).containsExactly(4L, 1L);
            assertThat(tags.definitionLevelHistogram()).containsExactly(1L, 1L, 0L, 3L);
        }
    }

    @Test
    void reportsUnencodedSizeOnlyForByteArrayColumns() throws Exception {
        // unencoded_byte_array_data_bytes is defined only for BYTE_ARRAY data; a writer
        // leaves it unset elsewhere, which must not be read back as a zero size.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().get(0);

            assertThat(rowGroup.columns().get(NAME).metaData().sizeStatistics()
                    .unencodedByteArrayDataBytes()).isEqualTo(15L);
            assertThat(rowGroup.columns().get(SCORE).metaData().sizeStatistics()
                    .unencodedByteArrayDataBytes()).isNull();
        }
    }

    @Test
    void distinguishesAnEmptyHistogramFromAnAbsentOne() throws Exception {
        // The same column reports its repetition-level histogram two ways: present but
        // empty in the chunk's SizeStatistics, absent in the ColumnIndex. Collapsing
        // either to the other would misreport what the writer recorded.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().get(0);
            ColumnChunk name = rowGroup.columns().get(NAME);

            assertThat(name.metaData().sizeStatistics().repetitionLevelHistogram()).isEmpty();
            assertThat(columnIndexOf(name).repetitionLevelHistograms()).isNull();
        }
    }

    @Test
    void surfacesHistogramsAndNullCountsFromTheColumnIndex() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().get(0);

            // One page per column, so each histogram is a single page's worth: two
            // definition-level entries for a maxDef 1 column, four for the list's leaf.
            ColumnIndex name = columnIndexOf(rowGroup.columns().get(NAME));
            assertThat(name.getPageCount()).isEqualTo(1);
            assertThat(name.definitionLevelHistograms()).containsExactly(1L, 3L);
            assertThat(name.nullCounts()).containsExactly(1L);

            ColumnIndex tags = columnIndexOf(rowGroup.columns().get(TAGS));
            assertThat(tags.repetitionLevelHistograms()).containsExactly(4L, 1L);
            assertThat(tags.definitionLevelHistograms()).containsExactly(1L, 1L, 0L, 3L);

            // One page, so a page slice is the whole concatenation — and the stride the
            // record derives from it recovers the column's max levels, 1 and 3.
            assertThat(tags.repetitionLevelHistogram(0)).containsExactly(4L, 1L);
            assertThat(tags.definitionLevelHistogram(0)).containsExactly(1L, 1L, 0L, 3L);
        }
    }

    @Test
    void surfacesPerPageUnencodedSizesFromTheOffsetIndex() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().get(0);

            assertThat(offsetIndexOf(rowGroup.columns().get(NAME)).unencodedByteArrayDataBytes())
                    .containsExactly(15L);
            assertThat(offsetIndexOf(rowGroup.columns().get(SCORE)).unencodedByteArrayDataBytes())
                    .isNull();
        }
    }

    @Test
    void reportsNanCountsAsAbsentWhenTheWriterOmitsThem() throws Exception {
        // `score` holds a NaN, but PyArrow records no count of it. Absent must not be
        // read back as zero: only a recorded zero proves a column holds no NaN.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().get(0);
            ColumnChunk score = rowGroup.columns().get(SCORE);

            assertThat(score.metaData().statistics().nanCount()).isNull();
            assertThat(columnIndexOf(score).nanCounts()).isNull();
        }
    }

    /// Reads a chunk's `ColumnIndex` from its byte range. The page index has no public
    /// reader entry point, so the test slices the file the way `hardwood dive` does.
    private static ColumnIndex columnIndexOf(ColumnChunk chunk) throws Exception {
        return ColumnIndexReader.read(new ThriftCompactReader(
                slice(chunk.columnIndexOffset(), chunk.columnIndexLength())));
    }

    private static OffsetIndex offsetIndexOf(ColumnChunk chunk) throws Exception {
        return OffsetIndexReader.read(new ThriftCompactReader(
                slice(chunk.offsetIndexOffset(), chunk.offsetIndexLength())));
    }

    private static ByteBuffer slice(long offset, int length) throws Exception {
        byte[] file = Files.readAllBytes(FIXTURE);
        return ByteBuffer.wrap(file, Math.toIntExact(offset), length);
    }
}
