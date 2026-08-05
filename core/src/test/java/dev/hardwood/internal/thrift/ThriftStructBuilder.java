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

/// Hand-rolled Thrift Compact Protocol struct builder for reader tests.
///
/// Fields are written in ascending id order so the short-form field header
/// (delta-encoded id) applies; a gap larger than 15 falls back to the long form.
/// Every struct must be terminated with [#stop()] before [#build()].
final class ThriftStructBuilder {

    // Thrift Compact Protocol field type codes.
    static final byte TYPE_I32 = 0x05;
    static final byte TYPE_I64 = 0x06;
    static final byte TYPE_BINARY = 0x08;
    static final byte TYPE_LIST = 0x09;
    static final byte TYPE_STRUCT = 0x0C;

    // Thrift Compact Protocol list element type codes.
    private static final byte ELEM_BOOL = 0x01;
    private static final byte ELEM_I32 = 0x05;
    private static final byte ELEM_I64 = 0x06;
    private static final byte ELEM_BINARY = 0x08;
    private static final byte ELEM_STRUCT = 0x0C;

    private final ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
    private short lastFieldId;

    ThriftStructBuilder field(int id, byte type) {
        short delta = (short) (id - lastFieldId);
        if (delta > 0 && delta <= 15) {
            buffer.put((byte) ((delta << 4) | (type & 0x0F)));
        }
        else {
            buffer.put(type);
            writeZigzag(id);
        }
        lastFieldId = (short) id;
        return this;
    }

    ThriftStructBuilder boolList(boolean... values) {
        listHeader(values.length, ELEM_BOOL);
        for (boolean value : values) {
            buffer.put((byte) (value ? 0x01 : 0x02));
        }
        return this;
    }

    ThriftStructBuilder binaryList(byte[]... values) {
        listHeader(values.length, ELEM_BINARY);
        for (byte[] value : values) {
            writeVarint(value.length);
            buffer.put(value);
        }
        return this;
    }

    ThriftStructBuilder i32List(int... values) {
        listHeader(values.length, ELEM_I32);
        for (int value : values) {
            writeZigzag(value);
        }
        return this;
    }

    ThriftStructBuilder i64List(long... values) {
        listHeader(values.length, ELEM_I64);
        for (long value : values) {
            writeZigzag(value);
        }
        return this;
    }

    ThriftStructBuilder emptyStructList(int count) {
        listHeader(count, ELEM_STRUCT);
        for (int i = 0; i < count; i++) {
            buffer.put((byte) 0); // empty struct: immediate STOP
        }
        return this;
    }

    ThriftStructBuilder i32(int value) {
        writeZigzag(value);
        return this;
    }

    ThriftStructBuilder i64(long value) {
        writeZigzag(value);
        return this;
    }

    ThriftStructBuilder binary(byte[] value) {
        writeVarint(value.length);
        buffer.put(value);
        return this;
    }

    /// Writes an already-built struct as the value of the field just opened. Field ids
    /// restart within a nested struct, so the nested body is built by its own builder.
    ThriftStructBuilder nested(byte[] struct) {
        buffer.put(struct);
        return this;
    }

    ThriftStructBuilder stop() {
        buffer.put((byte) 0);
        return this;
    }

    byte[] build() {
        byte[] out = new byte[buffer.position()];
        buffer.flip();
        buffer.get(out);
        return out;
    }

    private void listHeader(int size, byte elementType) {
        if (size < 15) {
            buffer.put((byte) ((size << 4) | (elementType & 0x0F)));
        }
        else {
            buffer.put((byte) (0xF0 | (elementType & 0x0F)));
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
