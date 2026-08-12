/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InspectColumnsCommandTest implements InspectColumnsCommandContract {

    @Override
    public String plainFile() {
        return getClass().getResource("/plain_uncompressed.parquet").getPath();
    }

    @Override
    public String pageIndexFile() {
        return getClass().getResource("/column_index_pushdown.parquet").getPath();
    }

    @Override
    public String nonexistentFile() {
        return "nonexistent.parquet";
    }

    /// The unencoded size is the one column that predicts read-side memory:
    /// compressed and uncompressed both measure the encoded form, so a
    /// dictionary-encoded string column looks cheap next to what it costs to
    /// materialise. Only `BYTE_ARRAY` columns carry it.
    ///
    /// Asserted here rather than on the shared contract because covering it
    /// there would mean uploading another fixture to the S3 test bucket, and
    /// reading a footer over S3 does not change how size statistics parse.
    @Test
    void populatesUnencodedSizeForByteArrayColumns() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f",
                getClass().getResource("/size_statistics_test.parquet").getPath());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("Unencoded");
        // `name` is the only BYTE_ARRAY column; its unencoded size is 15 B.
        assertThat(result.output()).contains("15 B");
        // `tags` and `score` are not BYTE_ARRAY, so they have no unencoded size.
        assertThat(result.output()).contains("-");
    }

    private String sizeStatisticsFile() {
        return getClass().getResource("/size_statistics_test.parquet").getPath();
    }

    /// The detail mode is the non-interactive twin of the dive facts pane:
    /// the same derived quantities, one row per row group, then the named
    /// level buckets.
    @Test
    void columnDetailPrintsDerivedCountsAndNamedLevels() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--column", "tags.list.element");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("tags.list.element", "INT32", "max def 3", "max rep 1");
        // def [1, 1, 0, 3] and rep [4, 1]: 5 values over 4 records, 3 present.
        assertThat(result.output()).contains("Definition levels (all row groups, max 3)");
        assertThat(result.output()).contains("tags null", "tags empty", "element null", "element present");
        assertThat(result.output()).contains("Repetition levels (all row groups, max 1)");
        assertThat(result.output()).contains("new record", "tags.list");
    }

    /// Level histograms combine by addition, so the file-wide block is exact
    /// rather than a sample. Narrowing to one row group says so in the title.
    @Test
    void rowGroupNarrowsTheHistogramsToOne() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--column", "tags.list.element", "--row-group", "0");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("Definition levels (RG #0, max 3)");
        assertThat(result.output()).contains("Repetition levels (RG #0, max 1)");
    }

    /// Fan-out counts level slots per record, so on a column that cannot
    /// repeat it is always 1.00 and says nothing. It is withheld rather than
    /// printed, matching what the dive facts pane shows for the same chunk.
    @Test
    void columnDetailWithholdsFanOutForANonRepeatedColumn() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--column", "name");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("max rep 0");
        assertThat(result.output()).doesNotContain("1.00");
    }

    @Test
    void levelRowsCarryNoTrailingWhitespace() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--column", "tags.list.element");

        assertThat(result.output().lines())
                .as("a zero-share bucket draws no bar, so its row must not end in the bar separator")
                .allSatisfy(line -> assertThat(line).isEqualTo(line.stripTrailing()));
    }

    @Test
    void rowGroupWithoutColumnIsRejected() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--row-group", "0");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("--row-group requires --column");
    }

    @Test
    void unknownColumnPathIsRejected() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--column", "no.such.column");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("no.such.column");
    }

    @Test
    void outOfRangeRowGroupIsRejected() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", sizeStatisticsFile(),
                "--column", "tags.list.element", "--row-group", "7");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("7");
    }

    @Test
    void rejectsRemoteUri() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", "gs://bucket/data.parquet");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("not implemented yet");
    }
}
