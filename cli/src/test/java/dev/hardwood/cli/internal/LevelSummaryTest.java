/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Pins the schema-derived level labels against the shapes that change
/// the outcome: a LIST, a MAP, a flat required column and a flat
/// optional one. The labels are what make a histogram readable, so a
/// change to the walk shows up here rather than in a screenshot.
class LevelSummaryTest {

    private static FileSchema fixtureSchema() throws Exception {
        Path file = Path.of(LevelSummaryTest.class.getResource("/dive_screenshots_fixture.parquet").toURI());
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            return reader.getFileSchema();
        }
    }

    private static ColumnSchema column(FileSchema schema, String dottedName) {
        for (ColumnSchema candidate : schema.getColumns()) {
            if (candidate.fieldPath().matchesDottedName(dottedName)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("no such column: " + dottedName);
    }

    private static ColumnMetaData chunkMetaData(String dottedName) throws Exception {
        Path file = Path.of(LevelSummaryTest.class.getResource("/dive_screenshots_fixture.parquet").toURI());
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            for (ColumnChunk chunk : reader.getFileMetaData().rowGroups().get(0).columns()) {
                if (chunk.metaData().pathInSchema().matchesDottedName(dottedName)) {
                    return chunk.metaData();
                }
            }
        }
        throw new IllegalArgumentException("no such column: " + dottedName);
    }

    private static LevelSummary summaryOf(FileSchema schema, String dottedName) throws Exception {
        return LevelSummary.of(schema, column(schema, dottedName), chunkMetaData(dottedName));
    }

    /// Restates a chunk's declared value count so the consistency check
    /// has something to disagree with — no writer in reach produces a
    /// chunk whose histogram contradicts its own header.
    private static ColumnMetaData withNumValues(ColumnMetaData source, long numValues) {
        return new ColumnMetaData(source.type(), source.encodings(), source.pathInSchema(), source.codec(),
                numValues, source.totalUncompressedSize(), source.totalCompressedSize(),
                source.keyValueMetadata(), source.dataPageOffset(), source.dictionaryPageOffset(),
                source.statistics(), source.geospatialStatistics(), source.bloomFilterOffset(),
                source.bloomFilterLength(), source.encodingStats(), source.sizeStatistics());
    }

    @Test
    void listLabelsNameTheUserFacingFieldNotTheSyntheticListNode() throws Exception {
        FileSchema schema = fixtureSchema();
        ColumnSchema websites = column(schema, "websites.list.element");

        assertThat(LevelSummary.definitionLabels(schema, websites))
                .containsExactly("websites null", "websites empty", "element null", "element present");
        assertThat(LevelSummary.repetitionLabels(schema, websites))
                .containsExactly("new record", "websites.list");
    }

    @Test
    void mapLabelsNameTheMapFieldNotKeyValue() throws Exception {
        FileSchema schema = fixtureSchema();
        ColumnSchema value = column(schema, "names.common.key_value.value");

        assertThat(LevelSummary.definitionLabels(schema, value))
                .containsExactly("names null", "common null", "common empty", "value null", "value present");
        assertThat(LevelSummary.repetitionLabels(schema, value))
                .containsExactly("new record", "names.common.key_value");
    }

    @Test
    void flatRequiredColumnHasOnlyThePresentLevel() throws Exception {
        FileSchema schema = fixtureSchema();
        ColumnSchema id = column(schema, "id");

        assertThat(LevelSummary.definitionLabels(schema, id)).containsExactly("id present");
        assertThat(LevelSummary.repetitionLabels(schema, id)).containsExactly("new record");
    }

    @Test
    void flatOptionalColumnHasNullAndPresent() throws Exception {
        FileSchema schema = fixtureSchema();
        ColumnSchema confidence = column(schema, "confidence");

        assertThat(LevelSummary.definitionLabels(schema, confidence))
                .containsExactly("confidence null", "confidence present");
    }

    /// A struct member's label names the member, not the struct: the
    /// enclosing struct already has its own level below it.
    @Test
    void structMemberLabelsNameTheMember() throws Exception {
        FileSchema schema = fixtureSchema();
        ColumnSchema xmin = column(schema, "bbox.xmin");

        assertThat(LevelSummary.definitionLabels(schema, xmin))
                .containsExactly("bbox null", "xmin null", "xmin present");
    }

    @Test
    void listColumnDerivesRecordsPresentValuesAndAverages() throws Exception {
        FileSchema schema = fixtureSchema();
        LevelSummary summary = summaryOf(schema, "websites.list.element");

        // num_values 300, rep [150, 150], def [0, 0, 0, 300], unencoded 8130.
        assertThat(summary.hasRecords()).isTrue();
        assertThat(summary.records()).isEqualTo(150L);
        assertThat(summary.presentValues()).isEqualTo(300L);
        assertThat(summary.avgFanOut()).isEqualTo(2.0);
        assertThat(summary.hasAvgListLength()).isTrue();
        assertThat(summary.avgListLength()).isEqualTo(2.0);
        assertThat(summary.hasUnencoded()).isTrue();
        assertThat(summary.unencodedBytes()).isEqualTo(8130L);
        assertThat(summary.avgValueSize()).isEqualTo(27.1);
        assertThat(summary.mismatch()).isNull();
    }

    /// A required, non-repeated `BYTE_ARRAY` writes no histograms at all,
    /// but every value is present by definition — so the present-value
    /// count is still known, and with it the average value size.
    @Test
    void flatRequiredByteArrayStillHasAnAverageValueSize() throws Exception {
        FileSchema schema = fixtureSchema();
        LevelSummary summary = summaryOf(schema, "id");

        assertThat(summary.hasDefinitionHistogram()).isFalse();
        assertThat(summary.presentValues()).isEqualTo(150L);
        assertThat(summary.records()).isEqualTo(150L);
        assertThat(summary.unencodedBytes()).isEqualTo(1800L);
        assertThat(summary.avgValueSize()).isEqualTo(12.0);
        assertThat(summary.hasAvgListLength()).isFalse();
        assertThat(summary.mismatch()).isNull();
    }

    /// Present size statistics do not imply every field is set: a
    /// non-`BYTE_ARRAY` column carries histograms but no unencoded size.
    @Test
    void nonByteArrayColumnHasHistogramsButNoUnencodedSize() throws Exception {
        FileSchema schema = fixtureSchema();
        LevelSummary summary = summaryOf(schema, "confidence");

        assertThat(summary.hasDefinitionHistogram()).isTrue();
        assertThat(summary.presentValues()).isEqualTo(150L);
        assertThat(summary.hasUnencoded()).isFalse();
    }

    @Test
    void columnWithoutSizeStatisticsHasNoSummary() throws Exception {
        FileSchema schema = fixtureSchema();

        assertThat(summaryOf(schema, "metric_a")).isNull();
    }

    @Test
    void levelRowsCarryLabelsCountsAndShares() throws Exception {
        FileSchema schema = fixtureSchema();
        LevelSummary summary = summaryOf(schema, "websites.list.element");

        assertThat(summary.definitionLevels())
                .extracting(LevelSummary.LevelRow::label, LevelSummary.LevelRow::count)
                .containsExactly(
                        tuple("websites null", 0L),
                        tuple("websites empty", 0L),
                        tuple("element null", 0L),
                        tuple("element present", 300L));
        assertThat(summary.definitionLevels().get(3).share()).isEqualTo(1.0);
        assertThat(summary.repetitionLevels())
                .extracting(LevelSummary.LevelRow::label, LevelSummary.LevelRow::count)
                .containsExactly(tuple("new record", 150L), tuple("websites.list", 150L));
    }

    /// A bucket holding one value in a million still has to read as
    /// present, so a non-zero share never rounds away to an empty bar.
    @Test
    void barUsesEighthBlocksForSubCellResolution() {
        assertThat(LevelSummary.bar(1.0, 4)).isEqualTo("████");
        assertThat(LevelSummary.bar(0.5, 4)).isEqualTo("██");
        assertThat(LevelSummary.bar(0.0, 4)).isEmpty();
        assertThat(LevelSummary.bar(0.03, 4)).isEqualTo("▏");
    }

    /// The bar is the first thing worth dropping on a narrow pane and the
    /// count the last, so the level and its label survive every width.
    @Test
    void narrowWidthsDropTheBarThenThePercentage() {
        List<LevelSummary.LevelRow> rows = List.of(
                new LevelSummary.LevelRow(0, "websites null", 0L, 0.0),
                new LevelSummary.LevelRow(1, "element present", 300L, 1.0));

        assertThat(LevelSummary.renderLevels(rows, 60).get(1)).contains("█").contains("100.0%");
        assertThat(LevelSummary.renderLevels(rows, 50).get(1)).doesNotContain("█").contains("100.0%");
        assertThat(LevelSummary.renderLevels(rows, 40).get(1)).doesNotContain("%").contains("300");
        assertThat(LevelSummary.renderLevels(rows, 40).get(1)).contains("element present");
    }

    /// The check is what makes the screen worth opening on a suspect
    /// file, so it has to fire on a chunk whose declared count and
    /// histogram disagree rather than rendering both without comment.
    @Test
    void mismatchedValueCountIsReported() throws Exception {
        FileSchema schema = fixtureSchema();
        ColumnSchema websites = column(schema, "websites.list.element");
        ColumnMetaData original = chunkMetaData("websites.list.element");
        ColumnMetaData tampered = withNumValues(original, 299L);

        LevelSummary summary = LevelSummary.of(schema, websites, tampered);

        assertThat(summary.mismatch()).contains("299").contains("300");
    }
}
