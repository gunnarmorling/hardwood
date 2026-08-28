/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.predicate;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThat;

/// Bloom-filter pruning for FLOAT and DOUBLE columns containing only `-0.0`.
///
/// Statistics are disabled in the fixture so these assertions exercise bloom-filter decisions
/// directly.
class BloomFilterSignedZeroPushDownTest {

    private static final Path FIXTURE =
            Paths.get("src/test/resources/bloom_filter_signed_zero_test.parquet");

    private static ParquetFileReader reader;
    private static InputFile inputFile;
    private static RowGroup rowGroup;
    private static FileSchema schema;

    @BeforeAll
    static void open() throws Exception {
        inputFile = InputFile.of(FIXTURE);
        reader = ParquetFileReader.open(inputFile);
        rowGroup = reader.getFileMetaData().rowGroups().getFirst();
        schema = FileSchema.fromSchemaElements(reader.getFileMetaData().schema());
    }

    @AfterAll
    static void close() throws Exception {
        reader.close();
    }

    @Test
    void floatSignedZeroUsesBloomFilter() {
        assertThat(statisticsDrop(FilterPredicate.eq("float_value", 0.0f))).isFalse();
        assertThat(bloomDrop(FilterPredicate.eq("float_value", 0.0f))).isTrue();
        assertThat(bloomDrop(FilterPredicate.eq("float_value", -0.0f))).isFalse();
    }

    @Test
    void doubleSignedZeroUsesBloomFilter() {
        assertThat(statisticsDrop(FilterPredicate.eq("double_value", 0.0))).isFalse();
        assertThat(bloomDrop(FilterPredicate.eq("double_value", 0.0))).isTrue();
        assertThat(bloomDrop(FilterPredicate.eq("double_value", -0.0))).isFalse();
    }

    @Test
    void nanRemainsConservative() {
        assertThat(bloomDrop(FilterPredicate.eq("float_value", Float.NaN))).isFalse();
        assertThat(bloomDrop(FilterPredicate.eq("double_value", Double.NaN))).isFalse();
    }

    private static boolean bloomDrop(FilterPredicate filter) {
        ResolvedPredicate resolved = FilterPredicateResolver.resolve(filter, schema);
        return RowGroupFilterEvaluator.canDropRowGroup(resolved, rowGroup,
                new RowGroupBloomFilterSource(inputFile, rowGroup));
    }

    private static boolean statisticsDrop(FilterPredicate filter) {
        ResolvedPredicate resolved = FilterPredicateResolver.resolve(filter, schema);
        return RowGroupFilterEvaluator.canDropRowGroup(resolved, rowGroup);
    }
}
