/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.hardwood.internal.thrift.ThriftCompactConstants.ElementType;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;

/// Hand-rolled Thrift Compact Protocol struct builder for reader tests.
///
/// Fields are written in ascending id order so the short-form field header
/// (delta-encoded id) applies; a gap larger than 15 falls back to the long form.
/// Every struct must be terminated with [#stop()] before [#build()].
///
/// The static header composers — [#fieldHeader], [#listHeader], [#longFormListHeader] and
/// [#mapTypes] — are the same nibble packing exposed on its own, for tests that hand a reader
/// a byte sequence rather than building a struct.
public final class ThriftStructBuilder {

    private final ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
    private short lastFieldId;

    /// Field header byte: the field-id delta in the high nibble, the wire type in the low nibble.
    public static byte fieldHeader(int fieldIdDelta, FieldType type) {
        return (byte) ((fieldIdDelta << 4) | type.code());
    }

    /// Short-form list header byte: the element count in the high nibble, the element type in the
    /// low. A count of [ThriftCompactConstants#LONG_FORM_SIZE] or more does not fit the nibble —
    /// see [#longFormListHeader].
    public static byte listHeader(int size, ElementType elementType) {
        return listHeader(size, elementType.code());
    }

    /// The same for an element type [ElementType] does not name, such as the `2` a writer may put
    /// in the element nibble for `bool` instead of the de-facto standard `1`.
    public static byte listHeader(int size, byte elementCode) {
        if (size >= ThriftCompactConstants.LONG_FORM_SIZE) {
            throw new IllegalArgumentException(size + " elements need the long form, not the size nibble");
        }
        return (byte) ((size << 4) | elementCode);
    }

    /// Long-form list header byte: the size nibble saturated, so the count follows as a varint the
    /// caller writes itself.
    public static byte longFormListHeader(ElementType elementType) {
        return (byte) ((ThriftCompactConstants.LONG_FORM_SIZE << 4) | elementType.code());
    }

    /// The single byte a map writes after its size: the key type in the high nibble, the value
    /// type in the low nibble.
    public static byte mapTypes(ElementType key, ElementType value) {
        return (byte) ((key.code() << 4) | value.code());
    }

    public ThriftStructBuilder field(int id, FieldType type) {
        short delta = (short) (id - lastFieldId);
        if (delta > 0 && delta <= 15) {
            buffer.put(fieldHeader(delta, type));
        }
        else {
            buffer.put(type.code());
            writeZigzag(id);
        }
        lastFieldId = (short) id;
        return this;
    }

    public ThriftStructBuilder boolList(boolean... values) {
        putListHeader(values.length, ElementType.BOOL);
        for (boolean value : values) {
            buffer.put(value ? FieldType.Codes.BOOLEAN_TRUE : FieldType.Codes.BOOLEAN_FALSE);
        }
        return this;
    }

    public ThriftStructBuilder binaryList(byte[]... values) {
        putListHeader(values.length, ElementType.BINARY);
        for (byte[] value : values) {
            writeVarint(value.length);
            buffer.put(value);
        }
        return this;
    }

    public ThriftStructBuilder i32List(int... values) {
        putListHeader(values.length, ElementType.I32);
        for (int value : values) {
            writeZigzag(value);
        }
        return this;
    }

    public ThriftStructBuilder i64List(long... values) {
        putListHeader(values.length, ElementType.I64);
        for (long value : values) {
            writeZigzag(value);
        }
        return this;
    }

    /// Writes a `list<struct>` of already-built struct bodies.
    public ThriftStructBuilder structList(byte[]... structs) {
        putListHeader(structs.length, ElementType.STRUCT);
        for (byte[] struct : structs) {
            buffer.put(struct);
        }
        return this;
    }

    public ThriftStructBuilder emptyStructList(int count) {
        byte[][] structs = new byte[count][];
        for (int i = 0; i < count; i++) {
            structs[i] = new byte[]{ ThriftCompactConstants.STOP }; // empty struct: immediate STOP
        }
        return structList(structs);
    }

    public ThriftStructBuilder i32(int value) {
        writeZigzag(value);
        return this;
    }

    public ThriftStructBuilder doubleValue(double value) {
        buffer.putDouble(value);
        return this;
    }

    public ThriftStructBuilder i64(long value) {
        writeZigzag(value);
        return this;
    }

    public ThriftStructBuilder binary(byte[] value) {
        writeVarint(value.length);
        buffer.put(value);
        return this;
    }

    /// Writes an already-built struct as the value of the field just opened. Field ids
    /// restart within a nested struct, so the nested body is built by its own builder.
    public ThriftStructBuilder nested(byte[] struct) {
        buffer.put(struct);
        return this;
    }

    /// Writes bytes verbatim, for wire shapes no typed writer here produces — a list header
    /// with a size the elements do not back, a map body, an oversized varint.
    public ThriftStructBuilder raw(int... bytes) {
        for (int b : bytes) {
            buffer.put((byte) b);
        }
        return this;
    }

    /// Writes a list header without the elements, so a caller can follow it with [#raw] bytes
    /// or with fewer elements than it declares.
    public ThriftStructBuilder listHeaderOnly(int size, ElementType elementType) {
        putListHeader(size, elementType);
        return this;
    }

    public ThriftStructBuilder stop() {
        buffer.put(ThriftCompactConstants.STOP);
        return this;
    }

    public byte[] build() {
        byte[] out = new byte[buffer.position()];
        buffer.flip();
        buffer.get(out);
        return out;
    }

    private void putListHeader(int size, ElementType elementType) {
        if (size < ThriftCompactConstants.LONG_FORM_SIZE) {
            buffer.put(listHeader(size, elementType));
        }
        else {
            buffer.put(longFormListHeader(elementType));
            writeVarint(size);
        }
    }

    private void writeVarint(long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            buffer.put((byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        buffer.put((byte) (v & 0x7F));
    }

    private void writeZigzag(long value) {
        writeVarint((value << 1) ^ (value >> 63));
    }
}
