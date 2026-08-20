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

/// Shared test contract for the `info` command.
interface InfoCommandContract {

    String plainFile();

    String nonexistentFile();

    /// A file whose key-value metadata mirrors what real writers embed in practice —
    /// PyArrow's own `ARROW:schema`, Spark's `org.apache.spark.sql.parquet.row.metadata`,
    /// and `pandas` — plus two edge cases real writers don't reliably produce on demand:
    /// `short.key=1.2.3` (5 bytes, prints in full) and `empty.key=` (0 bytes).
    String kvMetadataFile();

    @Test
    default void displaysFileInfo() {
        Cli.Result result = Cli.launch("info", "-f", plainFile());

        assertThat(result.exitCode()).isZero();
        // `startsWith`, not exact equality: `plainFile()` carries its own key-value
        // metadata (e.g. PyArrow's `ARROW:schema`), covered separately by
        // `displaysKeyValueMetadataSection()`. This test owns only the six base facts.
        assertThat(result.output()).startsWith("""
                Format Version:    2
                Created By:        parquet-cpp-arrow version 24.0.0
                Row Groups:        1
                Total Rows:        3
                Uncompressed Size: 174 B
                Compressed Size:   174 B""");
    }

    @Test
    default void failsOnNonexistentFile() {
        Cli.Result result = Cli.launch("info", "-f", nonexistentFile());

        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    default void displaysKeyValueMetadataSection() {
        Cli.Result result = Cli.launch("info", "-f", kvMetadataFile());

        assertThat(result.exitCode()).isZero();
        // ARROW:schema itself isn't asserted line-for-line — its bytes are an
        // opaque, PyArrow-internal serialization — but its presence and the
        // alignment it drives (it's the widest *size* entry) are covered by
        // asserting the other four lines around it.
        assertThat(result.output()).contains(
                "Key/Value Metadata (5):",
                "  short.key                                      5 B  1.2.3",
                "  empty.key                                      0 B",
                "  pandas                                       465 B  "
                        + "{\"index_columns\":[\"__index_level_0__\"],\"column_indexes\":[{\"n…",
                "  org.apache.spark.sql.parquet.row.metadata    223 B  "
                        + "{\"type\":\"struct\",\"fields\":[{\"name\":\"order_id\",\"type\":\"long\",…",
                "ARROW:schema");
    }

    @Test
    default void printsSingleKeyValueInFull() {
        Cli.Result result = Cli.launch("info", "-f", kvMetadataFile(), "--kv-key",
                "org.apache.spark.sql.parquet.row.metadata");

        assertThat(result.exitCode()).isZero();
        // Full, untruncated raw value and nothing else — no summary block, no
        // ellipsis — so the output is safe to pipe straight into another tool.
        assertThat(result.output()).isEqualTo("""
                {"type":"struct","fields":[{"name":"order_id","type":"long","nullable":false,\
                "metadata":{}},{"name":"customer","type":"string","nullable":true,"metadata":{}},\
                {"name":"amount","type":"double","nullable":true,"metadata":{}}]}""");
    }

    @Test
    default void failsOnMissingKvKey() {
        Cli.Result result = Cli.launch("info", "-f", kvMetadataFile(), "--kv-key", "does.not.exist");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("does.not.exist");
    }
}
