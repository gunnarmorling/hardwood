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
public final class ThriftStructBuilder {

    private final ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
    private short lastFieldId;

    public ThriftStructBuilder field(int id, FieldType type) {
        short delta = (short) (id - lastFieldId);
        if (delta > 0 && delta <= 15) {
            buffer.put((byte) ((delta << 4) | (type.code() & 0x0F)));
        }
        else {
            buffer.put(type.code());
            writeZigzag(id);
        }
        lastFieldId = (short) id;
        return this;
    }

    public ThriftStructBuilder boolList(boolean... values) {
        listHeader(values.length, ElementType.BOOL);
        for (boolean value : values) {
            buffer.put(value ? FieldType.Codes.BOOLEAN_TRUE : FieldType.Codes.BOOLEAN_FALSE);
        }
        return this;
    }

    public ThriftStructBuilder binaryList(byte[]... values) {
        listHeader(values.length, ElementType.BINARY);
        for (byte[] value : values) {
            writeVarint(value.length);
            buffer.put(value);
        }
        return this;
    }

    public ThriftStructBuilder i32List(int... values) {
        listHeader(values.length, ElementType.I32);
        for (int value : values) {
            writeZigzag(value);
        }
        return this;
    }

    public ThriftStructBuilder i64List(long... values) {
        listHeader(values.length, ElementType.I64);
        for (long value : values) {
            writeZigzag(value);
        }
        return this;
    }

    /// Writes a `list<struct>` of already-built struct bodies.
    public ThriftStructBuilder structList(byte[]... structs) {
        listHeader(structs.length, ElementType.STRUCT);
        for (byte[] struct : structs) {
            buffer.put(struct);
        }
        return this;
    }

    public ThriftStructBuilder emptyStructList(int count) {
        byte[][] structs = new byte[count][];
        for (int i = 0; i < count; i++) {
            structs[i] = new byte[]{ 0 }; // empty struct: immediate STOP
        }
        return structList(structs);
    }

    public ThriftStructBuilder i32(int value) {
        writeZigzag(value);
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
        listHeader(size, elementType);
        return this;
    }

    public ThriftStructBuilder stop() {
        buffer.put((byte) 0);
        return this;
    }

    public byte[] build() {
        byte[] out = new byte[buffer.position()];
        buffer.flip();
        buffer.get(out);
        return out;
    }

    private void listHeader(int size, ElementType elementType) {
        if (size < 15) {
            buffer.put((byte) ((size << 4) | (elementType.code() & 0x0F)));
        }
        else {
            buffer.put((byte) (0xF0 | (elementType.code() & 0x0F)));
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
