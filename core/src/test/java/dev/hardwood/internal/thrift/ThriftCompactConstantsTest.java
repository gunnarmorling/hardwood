/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.thrift.ThriftCompactConstants.ElementType;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the Thrift Compact Protocol wire table to the literals the spec names, so the codes stay
/// anchored to the format rather than to themselves.
///
/// Every other test in this package composes its input bytes from [ThriftCompactConstants], the
/// same table the readers dispatch on. A code that drifted would move both sides together and
/// leave those tests green, so this is the one place the values are written out by hand.
///
/// Reference: https://github.com/apache/thrift/blob/master/doc/specs/thrift-compact-protocol.md
class ThriftCompactConstantsTest {

    @Test
    void fieldTypesCarryTheSpecCodes() {
        assertThat(FieldType.BOOLEAN_TRUE.code()).isEqualTo((byte) 0x01);
        assertThat(FieldType.BOOLEAN_FALSE.code()).isEqualTo((byte) 0x02);
        assertThat(FieldType.BYTE.code()).isEqualTo((byte) 0x03);
        assertThat(FieldType.I16.code()).isEqualTo((byte) 0x04);
        assertThat(FieldType.I32.code()).isEqualTo((byte) 0x05);
        assertThat(FieldType.I64.code()).isEqualTo((byte) 0x06);
        assertThat(FieldType.DOUBLE.code()).isEqualTo((byte) 0x07);
        assertThat(FieldType.BINARY.code()).isEqualTo((byte) 0x08);
        assertThat(FieldType.LIST.code()).isEqualTo((byte) 0x09);
        assertThat(FieldType.SET.code()).isEqualTo((byte) 0x0A);
        assertThat(FieldType.MAP.code()).isEqualTo((byte) 0x0B);
        assertThat(FieldType.STRUCT.code()).isEqualTo((byte) 0x0C);
        assertThat(FieldType.UUID.code()).isEqualTo((byte) 0x0D);
    }

    /// The element table mirrors the field table apart from `bool`: a collection declares one
    /// element type where a field packs the value into its own type nibble.
    @Test
    void elementTypesCarryTheSpecCodes() {
        assertThat(ElementType.BOOL.code()).isEqualTo((byte) 0x01);
        assertThat(ElementType.BYTE.code()).isEqualTo((byte) 0x03);
        assertThat(ElementType.I16.code()).isEqualTo((byte) 0x04);
        assertThat(ElementType.I32.code()).isEqualTo((byte) 0x05);
        assertThat(ElementType.I64.code()).isEqualTo((byte) 0x06);
        assertThat(ElementType.DOUBLE.code()).isEqualTo((byte) 0x07);
        assertThat(ElementType.BINARY.code()).isEqualTo((byte) 0x08);
        assertThat(ElementType.LIST.code()).isEqualTo((byte) 0x09);
        assertThat(ElementType.SET.code()).isEqualTo((byte) 0x0A);
        assertThat(ElementType.MAP.code()).isEqualTo((byte) 0x0B);
        assertThat(ElementType.STRUCT.code()).isEqualTo((byte) 0x0C);
        assertThat(ElementType.UUID.code()).isEqualTo((byte) 0x0D);
    }

    /// No element type may collide with `bool`'s two codes: `0x02` is what a writer following the
    /// spec's original numbering puts in the nibble, and readers accept it there.
    @Test
    void noElementTypeClaimsTheLegacyBoolCode() {
        assertThat(ElementType.values())
                .noneMatch(elementType -> elementType != ElementType.BOOL
                        && elementType.code() == FieldType.Codes.BOOLEAN_FALSE);
    }

    @Test
    void stopIsTheZeroByteAndListsSaturateAtFifteen() {
        assertThat(ThriftCompactConstants.STOP).isEqualTo((byte) 0x00);
        assertThat(ThriftCompactConstants.LONG_FORM_SIZE).isEqualTo(15);
    }
}
