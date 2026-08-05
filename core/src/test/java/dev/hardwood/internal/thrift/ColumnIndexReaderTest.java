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

import dev.hardwood.metadata.ColumnIndex;

import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I32;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_LIST;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnIndexReaderTest {

    @Test
    void readsCoreFieldsAndSkipsHistogramAndNanCountFields() throws IOException {
        // Fields 6 (repetition_level_histograms), 7 (definition_level_histograms) and
        // 8 (nan_counts) are all list<i64> and must be skipped without affecting fields 1-5.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).boolList(false, true, false)
                .field(2, TYPE_LIST).binaryList(bytes(1), bytes(0), bytes(21))
                .field(3, TYPE_LIST).binaryList(bytes(10), bytes(0), bytes(30))
                .field(4, TYPE_I32).i32(1) // ASCENDING
                .field(5, TYPE_LIST).i64List(0, 3, 0)
                .field(6, TYPE_LIST).i64List(1, 2, 3, 4)
                .field(7, TYPE_LIST).i64List(5, 6, 7, 8)
                .field(8, TYPE_LIST).i64List(0, 0, 0)
                .stop().build();

        ColumnIndex index = ColumnIndexReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(index.nullPages()).containsExactly(false, true, false);
        assertThat(index.minValues()).hasSize(3);
        assertThat(index.minValues().get(0)).isEqualTo(bytes(1));
        assertThat(index.maxValues().get(2)).isEqualTo(bytes(30));
        assertThat(index.boundaryOrder()).isEqualTo(ColumnIndex.BoundaryOrder.ASCENDING);
        assertThat(index.nullCounts()).containsExactly(0L, 3L, 0L);
        assertThat(index.getPageCount()).isEqualTo(3);
    }

    @Test
    void skipsStructTypedFieldSevenWithoutCorruptingLaterFields() throws IOException {
        // A malformed/adversarial file places STRUCT-typed elements at field 7. The reader must
        // skip them cleanly (no geospatial decode) and still parse the surrounding fields.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).boolList(false, false)
                .field(2, TYPE_LIST).binaryList(bytes(1), bytes(2))
                .field(3, TYPE_LIST).binaryList(bytes(9), bytes(9))
                .field(4, TYPE_I32).i32(0)
                .field(5, TYPE_LIST).i64List(2, 5)
                .field(7, TYPE_LIST).emptyStructList(2)
                .field(8, TYPE_LIST).i64List(0, 0)
                .stop().build();

        ColumnIndex index = ColumnIndexReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(index.nullPages()).containsExactly(false, false);
        assertThat(index.nullCounts()).containsExactly(2L, 5L);
        assertThat(index.getPageCount()).isEqualTo(2);
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
