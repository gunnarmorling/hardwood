/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Encoder for the PLAIN encoding, the inverse of [PlainDecoder].
public final class PlainEncoder {

    private PlainEncoder() {
    }

    /// Encode INT32 values as little-endian 4-byte words, matching
    /// [PlainDecoder#readInts].
    ///
    /// @param values the values to encode
    /// @return the PLAIN-encoded bytes
    /// @throws ArithmeticException if the encoded size overflows an `int`
    ///         (more than `Integer.MAX_VALUE / 4` values)
    public static byte[] encodeInts(int[] values) {
        return encodeInts(values, 0, values.length);
    }

    /// Encode `length` INT32 values starting at `offset` as little-endian 4-byte words,
    /// matching [PlainDecoder#readInts]. Encoding a sub-range avoids copying a page's
    /// values out of a larger backing array before encoding.
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @return the PLAIN-encoded bytes
    /// @throws ArithmeticException if the encoded size overflows an `int`
    ///         (more than `Integer.MAX_VALUE / 4` values)
    public static byte[] encodeInts(int[] values, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(length, Integer.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(values, offset, length);
        return buffer.array();
    }

    /// Encode `length` INT64 values starting at `offset` as little-endian 8-byte words,
    /// matching [PlainDecoder#readLongs].
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @return the PLAIN-encoded bytes
    /// @throws ArithmeticException if the encoded size overflows an `int`
    public static byte[] encodeLongs(long[] values, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(length, Long.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.asLongBuffer().put(values, offset, length);
        return buffer.array();
    }

    /// Encode `length` FLOAT values starting at `offset` as little-endian IEEE-754 single
    /// words, matching [PlainDecoder#readFloats].
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @return the PLAIN-encoded bytes
    /// @throws ArithmeticException if the encoded size overflows an `int`
    public static byte[] encodeFloats(float[] values, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(length, Float.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values, offset, length);
        return buffer.array();
    }

    /// Encode `length` DOUBLE values starting at `offset` as little-endian IEEE-754 double
    /// words, matching [PlainDecoder#readDoubles].
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @return the PLAIN-encoded bytes
    /// @throws ArithmeticException if the encoded size overflows an `int`
    public static byte[] encodeDoubles(double[] values, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(length, Double.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.asDoubleBuffer().put(values, offset, length);
        return buffer.array();
    }

    /// Encode `length` BOOLEAN values starting at `offset` bit-packed, 8 values per byte,
    /// least-significant bit first, matching [PlainDecoder#readBooleans].
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @return the PLAIN-encoded bytes
    public static byte[] encodeBooleans(boolean[] values, int offset, int length) {
        byte[] packed = new byte[(length + Byte.SIZE - 1) / Byte.SIZE];
        for (int i = 0; i < length; i++) {
            if (values[offset + i]) {
                packed[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return packed;
    }

    /// Encode `length` `BYTE_ARRAY` values starting at `offset`, each as a 4-byte little-endian
    /// length prefix followed by its bytes, matching [PlainDecoder#readByteArrays].
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @return the PLAIN-encoded bytes
    public static byte[] encodeByteArrays(byte[][] values, int offset, int length) {
        long total = 0;
        for (int i = 0; i < length; i++) {
            total += Integer.BYTES + values[offset + i].length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(total)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) {
            byte[] value = values[offset + i];
            buffer.putInt(value.length);
            buffer.put(value);
        }
        return buffer.array();
    }

    /// Encode `length` `FIXED_LEN_BYTE_ARRAY` values starting at `offset` as raw concatenated
    /// bytes (no length prefix — the width is fixed by the schema), matching
    /// [PlainDecoder#readFixedLenByteArray].
    ///
    /// @param values the backing array
    /// @param offset the index of the first value to encode
    /// @param length the number of values to encode
    /// @param typeLength the fixed byte length every value must have
    /// @return the PLAIN-encoded bytes
    /// @throws IllegalArgumentException if any value's length is not `typeLength`
    public static byte[] encodeFixedLenByteArrays(byte[][] values, int offset, int length, int typeLength) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(length, typeLength));
        for (int i = 0; i < length; i++) {
            byte[] value = values[offset + i];
            if (value.length != typeLength) {
                throw new IllegalArgumentException("FIXED_LEN_BYTE_ARRAY value has length " + value.length
                        + " but the column's type length is " + typeLength);
            }
            buffer.put(value);
        }
        return buffer.array();
    }
}
