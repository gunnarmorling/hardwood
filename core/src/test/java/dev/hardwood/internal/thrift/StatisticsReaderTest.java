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

import dev.hardwood.metadata.Statistics;

import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_BINARY;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I64;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_LIST;
import static org.assertj.core.api.Assertions.assertThat;

class StatisticsReaderTest {

    @Test
    void readsNanCountAlongsidePreferredBounds() throws IOException {
        byte[] thrift = struct()
                .field(3, TYPE_I64).i64(2)
                .field(5, TYPE_BINARY).binary(bytes(9))
                .field(6, TYPE_BINARY).binary(bytes(1))
                .field(9, TYPE_I64).i64(5)
                .stop().build();

        Statistics stats = read(thrift);

        assertThat(stats.minValue()).isEqualTo(bytes(1));
        assertThat(stats.maxValue()).isEqualTo(bytes(9));
        assertThat(stats.nullCount()).isEqualTo(2L);
        assertThat(stats.nanCount()).isEqualTo(5L);
        assertThat(stats.isMinMaxDeprecated()).isFalse();
    }

    @Test
    void reportsAbsentNanCountAsNull() throws IOException {
        // A writer that predates nan_count, or a non-floating-point column, omits it.
        // That is distinct from a column known to hold no NaN values.
        byte[] thrift = struct()
                .field(3, TYPE_I64).i64(0)
                .field(5, TYPE_BINARY).binary(bytes(4))
                .field(6, TYPE_BINARY).binary(bytes(0))
                .stop().build();

        assertThat(read(thrift).nanCount()).isNull();
    }

    @Test
    void readsZeroNanCount() throws IOException {
        // Zero is the value that proves a floating-point chunk holds no NaN.
        byte[] thrift = struct()
                .field(9, TYPE_I64).i64(0)
                .stop().build();

        assertThat(read(thrift).nanCount()).isEqualTo(0L);
    }

    @Test
    void skipsWrongTypedNanCountWithoutCorruptingLaterFields() throws IOException {
        // Field 9 typed as a list rather than an i64 must be skipped, leaving the
        // trailing field to parse.
        byte[] thrift = struct()
                .field(9, TYPE_LIST).i64List(1, 2)
                .field(10, TYPE_I64).i64(7)
                .stop().build();

        assertThat(read(thrift).nanCount()).isNull();
    }

    @Test
    void fallsBackToDeprecatedBoundsWithNanCountPresent() throws IOException {
        byte[] thrift = struct()
                .field(1, TYPE_BINARY).binary(bytes(8))
                .field(2, TYPE_BINARY).binary(bytes(2))
                .field(9, TYPE_I64).i64(3)
                .stop().build();

        Statistics stats = read(thrift);

        assertThat(stats.minValue()).isEqualTo(bytes(2));
        assertThat(stats.maxValue()).isEqualTo(bytes(8));
        assertThat(stats.isMinMaxDeprecated()).isTrue();
        assertThat(stats.nanCount()).isEqualTo(3L);
    }

    private static Statistics read(byte[] thrift) throws IOException {
        return StatisticsReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));
    }

    private static ThriftStructBuilder struct() {
        return new ThriftStructBuilder();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
