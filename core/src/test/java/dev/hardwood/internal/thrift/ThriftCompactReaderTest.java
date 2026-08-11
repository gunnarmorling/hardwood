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
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.thrift.ThriftCompactConstants.ElementType;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests for ThriftCompactReader, particularly field skipping for complex types.
class ThriftCompactReaderTest {

    /// Struct terminator.
    private static final byte STOP = FieldType.Codes.STOP;

    /// Marks the end of the meaningful bytes, so a test can assert the reader stopped short of it.
    private static final int SENTINEL = 0xFF;

    /// A list header whose size nibble is saturated, meaning the real size follows as a varint.
    private static final int LONG_FORM_SIZE = 15;

    /// Field header byte: field-id delta in the high nibble, wire type in the low nibble.
    private static byte field(int fieldIdDelta, FieldType type) {
        return (byte) ((fieldIdDelta << 4) | type.code());
    }

    /// List header byte: element count in the high nibble, element type in the low nibble.
    private static byte list(int size, ElementType elementType) {
        return (byte) ((size << 4) | elementType.code());
    }

    /// The single byte a map writes after its size: key type in the high nibble, value type in
    /// the low nibble.
    private static byte mapTypes(ElementType key, ElementType value) {
        return (byte) ((key.code() << 4) | value.code());
    }

    /// Verifies that MAP fields are skipped correctly per the Thrift Compact Protocol spec.
    ///
    /// The spec encodes MAP as: varint(size), then if size > 0, a single byte with
    /// key type in the high nibble and value type in the low nibble, followed by
    /// size key-value pairs.
    ///
    /// A previous bug read two separate bytes for key/value types instead of one packed byte.
    @Test
    void skipFieldHandlesMapWithPackedKeyValueTypes() throws IOException {
        // Build a Thrift MAP with 2 entries: map<i32, i32>
        // Format: varint(2), byte(key_type << 4 | value_type), then 2 pairs of zigzag i32
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);

        // MAP size = 2 (varint)
        buffer.put((byte) 2);

        buffer.put(mapTypes(ElementType.I32, ElementType.I32));

        // Entry 1: key=10 (zigzag=20), value=20 (zigzag=40)
        buffer.put((byte) 20); // zigzag(10) = 20
        buffer.put((byte) 40); // zigzag(20) = 40

        // Entry 2: key=30 (zigzag=60), value=40 (zigzag=80)
        buffer.put((byte) 60); // zigzag(30) = 60
        buffer.put((byte) 80); // zigzag(40) = 80

        // Sentinel byte to verify we stopped at the right position
        buffer.put((byte) SENTINEL);

        buffer.flip();

        ThriftCompactReader reader = new ThriftCompactReader(buffer);
        reader.skipField(FieldType.MAP.code());

        // Should have consumed exactly 6 bytes: 1 (size) + 1 (types) + 4 (entries)
        assertThat(reader.getBytesRead()).isEqualTo(6);
    }

    /// Verifies that empty MAP (size=0) is skipped correctly.
    /// An empty map is just a single varint(0) with no type byte.
    @Test
    void skipFieldHandlesEmptyMap() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);

        // MAP size = 0
        buffer.put((byte) 0);

        // Sentinel
        buffer.put((byte) SENTINEL);

        buffer.flip();

        ThriftCompactReader reader = new ThriftCompactReader(buffer);
        reader.skipField(FieldType.MAP.code());

        // Should have consumed exactly 1 byte (the zero size varint)
        assertThat(reader.getBytesRead()).isEqualTo(1);
    }

    /// Verifies that MAP with string keys and struct values is skipped correctly.
    /// This exercises the recursive skipping through complex nested types.
    @Test
    void skipFieldHandlesMapWithStringKeysAndStructValues() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);

        // MAP size = 1
        buffer.put((byte) 1);

        buffer.put(mapTypes(ElementType.BINARY, ElementType.STRUCT));

        // Entry 1 key: binary string "ab" (length=2, then 2 bytes)
        buffer.put((byte) 2); // length varint
        buffer.put((byte) 'a');
        buffer.put((byte) 'b');

        // Entry 1 value: struct with one i32 field (field id=1), then STOP
        buffer.put(field(1, FieldType.I32));
        buffer.put((byte) 42); // zigzag(21) = 42
        buffer.put(STOP);

        // Sentinel
        buffer.put((byte) SENTINEL);

        buffer.flip();

        ThriftCompactReader reader = new ThriftCompactReader(buffer);
        reader.skipField(FieldType.MAP.code());

        // 1 (size) + 1 (types) + 3 (key) + 3 (struct) = 8 bytes
        assertThat(reader.getBytesRead()).isEqualTo(8);
    }

    /// Verifies that nested struct skipping correctly saves/restores field ID context.
    @Test
    void skipStructPreservesFieldIdContext() throws IOException {
        // Build a struct with fields 1 (i32) and 2 (struct with field 1 (i32)) and 3 (i32)
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);

        // Field 1
        buffer.put(field(1, FieldType.I32));
        buffer.put((byte) 10); // zigzag(5) = 10

        // Field 2, a nested struct
        buffer.put(field(1, FieldType.STRUCT));
        // Nested struct: field 1 i32
        buffer.put(field(1, FieldType.I32));
        buffer.put((byte) 20); // zigzag(10) = 20
        buffer.put(STOP); // ends the nested struct

        // Field 3
        buffer.put(field(1, FieldType.I32));
        buffer.put((byte) 30); // zigzag(15) = 30

        // ends the outer struct
        buffer.put(STOP);

        buffer.flip();

        ThriftCompactReader reader = new ThriftCompactReader(buffer);

        // Read field 1
        ThriftCompactReader.FieldHeader f1 = reader.readFieldHeader();
        assertThat(f1.fieldId()).isEqualTo((short) 1);
        int val1 = reader.readI32();
        assertThat(val1).isEqualTo(5);

        // Skip field 2 (struct)
        ThriftCompactReader.FieldHeader f2 = reader.readFieldHeader();
        assertThat(f2.fieldId()).isEqualTo((short) 2);
        reader.skipField(f2.type());

        // Read field 3 - should correctly be field 3, not field 1
        ThriftCompactReader.FieldHeader f3 = reader.readFieldHeader();
        assertThat(f3.fieldId()).isEqualTo((short) 3);
        int val3 = reader.readI32();
        assertThat(val3).isEqualTo(15);

        // Should be at STOP
        ThriftCompactReader.FieldHeader stop = reader.readFieldHeader();
        assertThat(stop).isNull();
    }

    /// A long-form element count larger than the bytes left cannot describe real elements — the
    /// cheapest of them still costs a byte — so it is rejected before any caller pre-sizes a
    /// collection from it.
    @Test
    void listHeaderRejectsCountLargerThanRemainingBytes() {
        // Long-form list of structs, then varint(100).
        ThriftCompactReader reader = reader(list(LONG_FORM_SIZE, ElementType.STRUCT), 0x64, STOP, STOP);

        assertThatThrownBy(reader::readListHeader)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("declares 100 elements")
                .hasMessageContaining("2 bytes remain");
    }

    /// A count past the `int` range would wrap to a negative capacity, which surfaces as an
    /// unchecked `IllegalArgumentException` from the collection constructor rather than as the
    /// controlled, file-attributed `IOException` the footer path contracts for.
    @Test
    void listHeaderRejectsCountOverflowingInt() {
        // varint 0x80 0x80 0x80 0x80 0x08 encodes 2^31, which casts to Integer.MIN_VALUE.
        ThriftCompactReader reader = reader(
                list(LONG_FORM_SIZE, ElementType.STRUCT), 0x80, 0x80, 0x80, 0x80, 0x08, STOP);

        assertThatThrownBy(reader::readListHeader)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2147483648 elements");
    }

    /// The one success case among these tests: the others all assert a rejection, so a bound that
    /// wrongly rejected every long-form list would still pass them — while breaking any file with
    /// 15 or more columns, row groups or key-value pairs, since those all take this path.
    ///
    /// Sizing the list to fill the buffer exactly also pins the comparison at `>` rather than
    /// `>=`: a collection is allowed to end where the buffer does.
    @Test
    void listHeaderAcceptsLongFormCountWithinRemainingBytes() throws IOException {
        // 15 bool elements, each one byte, followed by exactly 15 payload bytes.
        int[] bytes = new int[17];
        bytes[0] = list(LONG_FORM_SIZE, ElementType.BOOL);
        bytes[1] = 0x0F;  // varint(15)
        for (int i = 0; i < 15; i++) {
            bytes[i + 2] = ElementType.BOOL.code();
        }

        ThriftCompactReader.CollectionHeader header = reader(bytes).readListHeader();

        assertThat(header.size()).isEqualTo(15);
        assertThat(header.elementType()).isEqualTo(ElementType.BOOL.code());
    }

    /// A `bool` is the one type encoded differently as a collection element than as a struct
    /// field: the field form carries its value in the type nibble of its header and has no
    /// payload, the element form is a bare byte. Skipping a `list<bool>` by field rules consumes
    /// nothing, so the cursor stays on the first element and every field after the list is read
    /// out of a shifted stream.
    @Test
    void skipFieldConsumesBooleanListElements() throws IOException {
        // List header: 3 bool elements, then three value bytes.
        ThriftCompactReader reader = reader(
                list(3, ElementType.BOOL),
                FieldType.Codes.BOOLEAN_TRUE, FieldType.Codes.BOOLEAN_FALSE, FieldType.Codes.BOOLEAN_TRUE,
                SENTINEL);

        reader.skipField(FieldType.LIST.code());

        assertThat(reader.getBytesRead()).isEqualTo(4);
    }

    /// `BOOLEAN_FALSE` is the other type code a writer may put in the element nibble for `bool`.
    @Test
    void skipFieldConsumesBooleanListElementsDeclaredAsFalse() throws IOException {
        ThriftCompactReader reader = reader(
                (2 << 4) | FieldType.Codes.BOOLEAN_FALSE,
                FieldType.Codes.BOOLEAN_FALSE, FieldType.Codes.BOOLEAN_FALSE,
                SENTINEL);

        reader.skipField(FieldType.LIST.code());

        assertThat(reader.getBytesRead()).isEqualTo(3);
    }

    /// Map keys and values are elements too, so they follow the element encoding.
    @Test
    void skipFieldConsumesBooleanMapEntries() throws IOException {
        // MAP size = 2, then the packed bool/bool key-value type byte and the four element bytes.
        ThriftCompactReader reader = reader(
                0x02, mapTypes(ElementType.BOOL, ElementType.BOOL),
                FieldType.Codes.BOOLEAN_TRUE, FieldType.Codes.BOOLEAN_FALSE,
                FieldType.Codes.BOOLEAN_FALSE, FieldType.Codes.BOOLEAN_TRUE,
                SENTINEL);

        reader.skipField(FieldType.MAP.code());

        assertThat(reader.getBytesRead()).isEqualTo(6);
    }

    private static ThriftCompactReader reader(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return new ThriftCompactReader(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN));
    }
}
