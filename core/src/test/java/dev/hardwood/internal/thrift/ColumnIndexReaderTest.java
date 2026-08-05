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
    void readsAllFields() throws IOException {
        // Three pages of a maxDef 1 / maxRep 0 column: the definition-level histogram
        // contributes two entries per page, the repetition-level histogram one.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).boolList(false, true, false)
                .field(2, TYPE_LIST).binaryList(bytes(1), bytes(0), bytes(21))
                .field(3, TYPE_LIST).binaryList(bytes(10), bytes(0), bytes(30))
                .field(4, TYPE_I32).i32(1) // ASCENDING
                .field(5, TYPE_LIST).i64List(0, 3, 0)
                .field(6, TYPE_LIST).i64List(4, 3, 4)
                .field(7, TYPE_LIST).i64List(0, 4, 3, 0, 0, 4)
                .field(8, TYPE_LIST).i64List(0, 0, 1)
                .stop().build();

        ColumnIndex index = ColumnIndexReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(index.nullPages()).containsExactly(false, true, false);
        assertThat(index.minValues()).hasSize(3);
        assertThat(index.minValues().get(0)).isEqualTo(bytes(1));
        assertThat(index.maxValues().get(2)).isEqualTo(bytes(30));
        assertThat(index.boundaryOrder()).isEqualTo(ColumnIndex.BoundaryOrder.ASCENDING);
        assertThat(index.nullCounts()).containsExactly(0L, 3L, 0L);
        assertThat(index.repetitionLevelHistograms()).containsExactly(4L, 3L, 4L);
        assertThat(index.definitionLevelHistograms()).containsExactly(0L, 4L, 3L, 0L, 0L, 4L);
        assertThat(index.nanCounts()).containsExactly(0L, 0L, 1L);
        assertThat(index.getPageCount()).isEqualTo(3);
    }

    @Test
    void reportsOmittedOptionalFieldsAsNull() throws IOException {
        // Only fields 1-4 are required. A writer predating the histograms leaves every
        // optional list absent, which stays distinct from a present but empty one.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).boolList(false, false)
                .field(2, TYPE_LIST).binaryList(bytes(1), bytes(2))
                .field(3, TYPE_LIST).binaryList(bytes(9), bytes(9))
                .field(4, TYPE_I32).i32(0)
                .stop().build();

        ColumnIndex index = ColumnIndexReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(index.nullCounts()).isNull();
        assertThat(index.repetitionLevelHistograms()).isNull();
        assertThat(index.definitionLevelHistograms()).isNull();
        assertThat(index.nanCounts()).isNull();
        assertThat(index.getPageCount()).isEqualTo(2);
    }

    @Test
    void skipsStructTypedFieldSevenWithoutCorruptingLaterFields() throws IOException {
        // A malformed/adversarial file places STRUCT-typed elements at field 7, where a
        // list<i64> belongs. The reader must skip them cleanly and still parse the
        // surrounding fields — including field 8, which follows the malformed one.
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
        assertThat(index.definitionLevelHistograms()).isNull();
        assertThat(index.nanCounts()).containsExactly(0L, 0L);
        assertThat(index.getPageCount()).isEqualTo(2);
    }

    @Test
    void skipsMultiByteStructElementsAtHistogramFieldWithoutDesync() throws IOException {
        // Field 6 is typed list<struct> with bodies several bytes long. Decoding those
        // bytes as varints would leave the cursor mid-struct and shift every later field,
        // so the element type — not just the field type — has to drive the skip.
        byte[] payload = struct()
                .field(1, TYPE_I32).i32(1234567)
                .field(2, TYPE_I32).i32(7654321)
                .stop().build();
        byte[] thrift = struct()
                .field(1, TYPE_LIST).boolList(true)
                .field(2, TYPE_LIST).binaryList(bytes(1))
                .field(3, TYPE_LIST).binaryList(bytes(2))
                .field(4, TYPE_I32).i32(0)
                .field(6, TYPE_LIST).structList(payload, payload)
                .field(7, TYPE_LIST).i64List(3, 9)
                .field(8, TYPE_LIST).i64List(11)
                .stop().build();

        ColumnIndex index = ColumnIndexReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));

        assertThat(index.repetitionLevelHistograms()).isNull();
        assertThat(index.definitionLevelHistograms()).containsExactly(3L, 9L);
        assertThat(index.nanCounts()).containsExactly(11L);
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
