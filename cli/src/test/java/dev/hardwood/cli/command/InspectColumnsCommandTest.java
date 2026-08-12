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

    @Test
    void rejectsRemoteUri() {
        Cli.Result result = Cli.launch("inspect", "columns", "-f", "gs://bucket/data.parquet");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("not implemented yet");
    }
}
