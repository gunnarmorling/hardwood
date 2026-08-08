/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowGroupPredicate;
import dev.hardwood.schema.ColumnProjection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnReaderWindowTest {

    private static final Path FIXTURE =
            Paths.get("src/test/resources/filter_pushdown_int.parquet");

    @Test
    void singleColumnWindowSpansBatchesAndRowGroups() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            assertThat(ids(reader.buildColumnReader("id")
                    .batchSize(8)
                    .skip(95)
                    .head(20)
                    .build()))
                    .containsExactlyElementsOf(range(96, 115));
        }
    }

    @Test
    void skipZeroIsNoOpAndPastEndIsEmpty() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            assertThat(ids(reader.buildColumnReader(0).skip(0).head(3).build()))
                    .containsExactly(1L, 2L, 3L);
            assertThat(ids(reader.buildColumnReader("id").skip(300).build()))
                    .isEmpty();
            assertThat(ids(reader.buildColumnReader("id").skip(301).build()))
                    .isEmpty();
        }
    }

    @Test
    void projectedColumnsUseTheSameWindow() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE));
             ColumnReaders columns = reader.buildColumnReaders(
                             ColumnProjection.columns("id", "value"))
                     .batchSize(7)
                     .skip(95)
                     .head(20)
                     .build()) {
            List<Long> ids = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            while (columns.nextBatch()) {
                int count = columns.getRecordCount();
                long[] idBatch = columns.getColumnReader("id").getLongs();
                long[] valueBatch = columns.getColumnReader("value").getLongs();
                for (int i = 0; i < count; i++) {
                    ids.add(idBatch[i]);
                    values.add(valueBatch[i]);
                }
            }
            assertThat(ids).containsExactlyElementsOf(range(96, 115));
            assertThat(values).containsExactlyElementsOf(ids);
        }
    }

    @Test
    void filteredWindowCountsMatchingRows() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            assertThat(ids(reader.buildColumnReader("id")
                    .filter(FilterPredicate.gt("id", 150L))
                    .batchSize(7)
                    .skip(20)
                    .head(5)
                    .build()))
                    .containsExactly(171L, 172L, 173L, 174L, 175L);
        }
    }

    @Test
    void filteredProjectionUsesTheSameLogicalWindow() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE));
             ColumnReaders columns = reader.buildColumnReaders(
                             ColumnProjection.columns("id", "value"))
                     .filter(FilterPredicate.gt("id", 150L))
                     .batchSize(7)
                     .skip(20)
                     .head(5)
                     .build()) {
            List<Long> ids = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            while (columns.nextBatch()) {
                int count = columns.getRecordCount();
                long[] idBatch = columns.getColumnReader("id").getLongs();
                long[] valueBatch = columns.getColumnReader("value").getLongs();
                for (int i = 0; i < count; i++) {
                    ids.add(idBatch[i]);
                    values.add(valueBatch[i]);
                }
            }
            assertThat(ids).containsExactly(171L, 172L, 173L, 174L, 175L);
            assertThat(values).containsExactlyElementsOf(ids);
        }
    }

    @Test
    void rowGroupFilterDefinesTheSkipOrigin() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            List<RowGroup> rowGroups = reader.getFileMetaData().rowGroups();
            RowGroupPredicate lastTwoGroups = RowGroupPredicate.byteRange(
                    midpoint(rowGroups.get(1)), FIXTURE.toFile().length());

            assertThat(ids(reader.buildColumnReaders(ColumnProjection.columns("id"))
                    .filter(lastTwoGroups)
                    .skip(50)
                    .head(3)
                    .build()))
                    .containsExactly(151L, 152L, 153L);
        }
    }

    @Test
    void multiFileWindowUsesTheGlobalOffset() throws Exception {
        List<InputFile> files = InputFile.ofPaths(
                Paths.get("src/test/resources/multi_file_part0.parquet"),
                Paths.get("src/test/resources/multi_file_part1.parquet"));

        try (ParquetFileReader reader = ParquetFileReader.openAll(files)) {
            assertThat(ids(reader.buildColumnReaders(ColumnProjection.columns("id"))
                    .skip(140)
                    .head(20)
                    .build()))
                    .containsExactlyElementsOf(range(140, 159));
        }
    }

    @Test
    void invalidBoundsAreRejectedByBothBuilders() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FIXTURE))) {
            assertThatThrownBy(() -> reader.buildColumnReader("id").head(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("head row count must be positive");
            assertThatThrownBy(() -> reader.buildColumnReader("id").skip(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("skip must be non-negative");
            assertThatThrownBy(() -> reader.buildColumnReaders(
                    ColumnProjection.columns("id")).head(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("head row count must be positive");
            assertThatThrownBy(() -> reader.buildColumnReaders(
                    ColumnProjection.columns("id")).skip(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("skip must be non-negative");
        }
    }

    private static List<Long> ids(ColumnReader reader) {
        List<Long> ids = new ArrayList<>();
        try (reader) {
            while (reader.nextBatch()) {
                int count = reader.getRecordCount();
                long[] batch = reader.getLongs();
                for (int i = 0; i < count; i++) {
                    ids.add(batch[i]);
                }
            }
        }
        return ids;
    }

    private static List<Long> ids(ColumnReaders readers) {
        List<Long> ids = new ArrayList<>();
        try (readers) {
            ColumnReader reader = readers.getColumnReader("id");
            while (readers.nextBatch()) {
                int count = readers.getRecordCount();
                long[] batch = reader.getLongs();
                for (int i = 0; i < count; i++) {
                    ids.add(batch[i]);
                }
            }
        }
        return ids;
    }

    private static long midpoint(RowGroup rowGroup) {
        long compressedSize = 0;
        for (ColumnChunk column : rowGroup.columns()) {
            compressedSize += column.metaData().totalCompressedSize();
        }
        return rowGroup.columns().get(0).chunkStartOffset() + compressedSize / 2;
    }

    private static List<Long> range(long startInclusive, long endInclusive) {
        return LongStream.rangeClosed(startInclusive, endInclusive).boxed().toList();
    }
}
