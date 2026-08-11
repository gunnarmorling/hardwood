/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;
import dev.hardwood.metadata.ColumnOrder;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [ColumnOrderReader], decoding the `ColumnOrder` Thrift union.
///
/// Each union entry is a struct holding one member field (an empty marker struct) followed by a
/// STOP byte.
class ColumnOrderReaderTest {

    /// Struct terminator.
    private static final byte STOP = ThriftCompactConstants.STOP;

    /// The header byte of a union member: the field-id delta in the high nibble, and STRUCT in
    /// the low nibble because every member is an empty marker struct.
    private static byte member(int fieldId) {
        return (byte) ((fieldId << 4) | FieldType.Codes.STRUCT);
    }

    private static ColumnOrder decode(byte... bytes) throws Exception {
        return ColumnOrderReader.read(new ThriftCompactReader(ByteBuffer.wrap(bytes)));
    }

    @Test
    void decodesTypeDefinedOrder() throws Exception {
        // field id 1 (TYPE_ORDER); empty marker struct STOP; union STOP
        assertThat(decode(member(1), STOP, STOP))
                .isEqualTo(ColumnOrder.TYPE_DEFINED_ORDER);
    }

    @Test
    void decodesIeee754TotalOrder() throws Exception {
        // field id 2 (IEEE_754_TOTAL_ORDER)
        assertThat(decode(member(2), STOP, STOP))
                .isEqualTo(ColumnOrder.IEEE754_TOTAL_ORDER);
    }

    @Test
    void decodesUnknownUnionMemberAsUnknown() throws Exception {
        // field id 3 — a future union member Hardwood does not recognize
        assertThat(decode(member(3), STOP, STOP))
                .isEqualTo(ColumnOrder.UNKNOWN);
    }

    @Test
    void decodesEmptyUnionAsUnknown() throws Exception {
        // An empty union (immediate STOP) carries no member; treat leniently as UNKNOWN.
        assertThat(decode(STOP)).isEqualTo(ColumnOrder.UNKNOWN);
    }
}
