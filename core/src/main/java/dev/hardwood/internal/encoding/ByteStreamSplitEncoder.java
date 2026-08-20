/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

/// Encoder for BYTE_STREAM_SPLIT, the inverse of [ByteStreamSplitDecoder].
///
/// A value's K bytes are scattered across K streams, stream `k` holding byte `k` of every value
/// in turn, and the streams are written one after another. The encoded length is exactly the
/// plain length — this encoding changes no page's size at all.
///
/// What it changes is what the codec afterwards can do. Neighbouring floating-point values
/// typically agree in their exponent and high mantissa bits and differ in the low ones, so a
/// stream of like-positioned bytes is far more repetitive than the interleaved values are. Its
/// whole payoff is therefore a property of the compression that follows it, which is why it is
/// asked for rather than chosen by measurement.
public final class ByteStreamSplitEncoder {

    private ByteStreamSplitEncoder() {
    }

    /// Splits `count` fixed-width values held end to end in `data` starting at `from`.
    ///
    /// @param data the little-endian value bytes, `byteWidth` per value
    /// @param from index of the first byte to encode
    /// @param count how many values to encode
    /// @param byteWidth the type's byte width
    /// @return the encoded bytes, `count * byteWidth` of them
    public static byte[] encode(byte[] data, int from, int count, int byteWidth) {
        byte[] out = new byte[Math.multiplyExact(count, byteWidth)];
        for (int k = 0; k < byteWidth; k++) {
            int stream = k * count;
            int source = from + k;
            for (int i = 0; i < count; i++) {
                out[stream + i] = data[source];
                source += byteWidth;
            }
        }
        return out;
    }
}
