/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.SizeStatistics;

import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I64;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_LIST;
import static org.assertj.core.api.Assertions.assertThat;

class SizeStatisticsReaderTest {

    @Test
    void readsAllFields() throws IOException {
        byte[] thrift = struct()
                .field(1, TYPE_I64).i64(4096)
                .field(2, TYPE_LIST).i64List(10, 4)
                .field(3, TYPE_LIST).i64List(2, 12)
                .stop().build();

        SizeStatistics stats = SizeStatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(stats.unencodedByteArrayDataBytes()).isEqualTo(4096L);
        assertThat(stats.repetitionLevelHistogram()).containsExactly(10L, 4L);
        assertThat(stats.definitionLevelHistogram()).containsExactly(2L, 12L);
    }

    @Test
    void reportsOmittedFieldsAsNull() throws IOException {
        // Every field is optional. A writer emitting only the definition-level histogram
        // must leave the other two distinguishable from a present-but-empty value.
        byte[] thrift = struct()
                .field(3, TYPE_LIST).i64List(0, 7)
                .stop().build();

        SizeStatistics stats = SizeStatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(stats.unencodedByteArrayDataBytes()).isNull();
        assertThat(stats.repetitionLevelHistogram()).isNull();
        assertThat(stats.definitionLevelHistogram()).containsExactly(0L, 7L);
    }

    @Test
    void readsEmptyStruct() throws IOException {
        byte[] thrift = struct().stop().build();

        SizeStatistics stats = SizeStatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(stats.unencodedByteArrayDataBytes()).isNull();
        assertThat(stats.repetitionLevelHistogram()).isNull();
        assertThat(stats.definitionLevelHistogram()).isNull();
    }

    @Test
    void readsPresentButEmptyHistogram() throws IOException {
        // An empty histogram is a legal encoding and stays distinct from an absent one.
        byte[] thrift = struct()
                .field(2, TYPE_LIST).i64List()
                .stop().build();

        SizeStatistics stats = SizeStatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(stats.repetitionLevelHistogram()).isEmpty();
        assertThat(stats.definitionLevelHistogram()).isNull();
    }

    @Test
    void skipsWrongTypedFieldsWithoutCorruptingLaterFields() throws IOException {
        // A malformed file types field 1 as a list and field 2 as an i64. Both must be
        // skipped cleanly, leaving field 3 to parse.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).i64List(1, 2, 3)
                .field(2, TYPE_I64).i64(99)
                .field(3, TYPE_LIST).i64List(5, 6)
                .stop().build();

        SizeStatistics stats = SizeStatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(stats.unencodedByteArrayDataBytes()).isNull();
        assertThat(stats.repetitionLevelHistogram()).isNull();
        assertThat(stats.definitionLevelHistogram()).containsExactly(5L, 6L);
    }

    @Test
    void skipsUnknownTrailingField() throws IOException {
        // A field id beyond the struct's definition (a later format revision) is skipped.
        byte[] thrift = struct()
                .field(1, TYPE_I64).i64(64)
                .field(9, TYPE_I64).i64(123)
                .stop().build();

        SizeStatistics stats = SizeStatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(stats.unencodedByteArrayDataBytes()).isEqualTo(64L);
    }

    private static ThriftStructBuilder struct() {
        return new ThriftStructBuilder();
    }
}
