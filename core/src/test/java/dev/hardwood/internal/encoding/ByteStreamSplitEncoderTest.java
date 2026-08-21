/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.PhysicalType;

import static org.assertj.core.api.Assertions.assertThat;

/// [ByteStreamSplitEncoder] against [ByteStreamSplitDecoder], at every byte width the encoding
/// applies to, and over the floating-point values whose bit patterns are special: `NaN`, the
/// infinities, and both signed zeros.
///
/// The encoding moves no bits, so what it can get wrong is *where* each byte lands — a defect
/// invisible in a size check and visible only in a value that comes back wrong.
class ByteStreamSplitEncoderTest {

    @Test
    void roundTripsFloatEdgeValues() throws IOException {
        // -0.0 differs from 0.0 in one bit of one stream, and NaN's payload sits in the low
        // bytes, so a split that misplaced a stream would return a value that still looks
        // plausible — a quiet NaN, or a zero of the wrong sign.
        float[] values = { 0.0f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1.0f, -1.0f };

        float[] read = roundTripFloats(values);

        for (int i = 0; i < values.length; i++) {
            assertThat(Float.floatToRawIntBits(read[i]))
                    .as("value %d (%s) survives bit for bit", i, values[i])
                    .isEqualTo(Float.floatToRawIntBits(values[i]));
        }
    }

    @Test
    void roundTripsDoubleEdgeValues() throws IOException {
        double[] values = { 0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 1.0, -1.0 };

        double[] read = roundTripDoubles(values);

        for (int i = 0; i < values.length; i++) {
            assertThat(Double.doubleToRawLongBits(read[i]))
                    .as("value %d (%s) survives bit for bit", i, values[i])
                    .isEqualTo(Double.doubleToRawLongBits(values[i]));
        }
    }

    @Test
    void roundTripsManyDoubles() throws IOException {
        // Enough values that every stream is long, which is where an off-by-one in the stream
        // stride shows up rather than cancelling out.
        double[] values = new double[1_000];
        Random random = new Random(20260821L);
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextDouble() * 1_000;
        }

        assertThat(roundTripDoubles(values)).isEqualTo(values);
    }

    @Test
    void roundTripsIntsAndLongs() throws IOException {
        // The format has allowed the integer widths since parquet-format 2.10; they are the same
        // scatter over four and eight streams.
        int[] ints = { 0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 123_456 };
        byte[] intPlain = PlainEncoder.encodeInts(ints, 0, ints.length);
        byte[] intSplit = ByteStreamSplitEncoder.encode(intPlain, 0, ints.length, Integer.BYTES);
        int[] readInts = new int[ints.length];
        new ByteStreamSplitDecoder(intSplit, 0, ints.length, PhysicalType.INT32, null)
                .readInts(readInts, null, 0);
        assertThat(readInts).containsExactly(ints);

        long[] longs = { 0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 987_654_321L };
        byte[] longPlain = PlainEncoder.encodeLongs(longs, 0, longs.length);
        byte[] longSplit = ByteStreamSplitEncoder.encode(longPlain, 0, longs.length, Long.BYTES);
        long[] readLongs = new long[longs.length];
        new ByteStreamSplitDecoder(longSplit, 0, longs.length, PhysicalType.INT64, null)
                .readLongs(readLongs, null, 0);
        assertThat(readLongs).containsExactly(longs);
    }

    @Test
    void roundTripsFixedLenByteArraysAtAnOddWidth() throws IOException {
        // A width that is not a power of two, which is where a stride computed from the type
        // rather than from the declared length would go wrong.
        int width = 3;
        byte[][] values = { { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 }, { (byte) 0xff, 0, (byte) 0x80 } };
        byte[] packed = new byte[values.length * width];
        for (int i = 0; i < values.length; i++) {
            System.arraycopy(values[i], 0, packed, i * width, width);
        }

        byte[] split = ByteStreamSplitEncoder.encode(packed, 0, values.length, width);

        byte[][] read = new byte[values.length][];
        new ByteStreamSplitDecoder(split, 0, values.length, PhysicalType.FIXED_LEN_BYTE_ARRAY, width)
                .readByteArrays(read, null, 0);
        assertThat(read).isEqualTo(values);
    }

    @Test
    void encodesOnlyTheRequestedRange() throws IOException {
        // A page is a range of the chunk's stored bytes, so the starting offset has to be
        // honoured and the values before it left out of the streams.
        double[] all = { -1.0, -2.0, 10.0, 20.0, 30.0, -3.0 };
        byte[] plain = PlainEncoder.encodeDoubles(all, 0, all.length);

        byte[] split = ByteStreamSplitEncoder.encode(plain, 2 * Double.BYTES, 3, Double.BYTES);

        double[] read = new double[3];
        new ByteStreamSplitDecoder(split, 0, 3, PhysicalType.DOUBLE, null).readDoubles(read, null, 0);
        assertThat(read).containsExactly(10.0, 20.0, 30.0);
    }

    @Test
    void producesExactlyThePlainSize() {
        // The encoding reorders bytes and removes none, which is why it is worth choosing only
        // alongside a codec: on its own it saves nothing at all.
        double[] values = new double[100];
        byte[] plain = PlainEncoder.encodeDoubles(values, 0, values.length);

        byte[] split = ByteStreamSplitEncoder.encode(plain, 0, values.length, Double.BYTES);

        assertThat(split.length).isEqualTo(plain.length);
    }

    @Test
    void writesOneStreamPerBytePosition() {
        // The layout stated rather than round-tripped: stream k holds byte k of every value in
        // turn, so a two-value DOUBLE page puts the two first bytes side by side at the front.
        byte[] packed = new byte[16];
        for (int i = 0; i < 16; i++) {
            packed[i] = (byte) i;
        }

        byte[] split = ByteStreamSplitEncoder.encode(packed, 0, 2, Double.BYTES);

        // Value 0 is bytes 0..7, value 1 is bytes 8..15, so stream k is {k, k + 8}.
        for (int k = 0; k < Double.BYTES; k++) {
            assertThat(split[k * 2]).as("stream %d, value 0", k).isEqualTo((byte) k);
            assertThat(split[k * 2 + 1]).as("stream %d, value 1", k).isEqualTo((byte) (k + 8));
        }
    }

    private static float[] roundTripFloats(float[] values) throws IOException {
        byte[] plain = PlainEncoder.encodeFloats(values, 0, values.length);
        byte[] split = ByteStreamSplitEncoder.encode(plain, 0, values.length, Float.BYTES);
        float[] read = new float[values.length];
        new ByteStreamSplitDecoder(split, 0, values.length, PhysicalType.FLOAT, null)
                .readFloats(read, null, 0);
        return read;
    }

    private static double[] roundTripDoubles(double[] values) throws IOException {
        byte[] plain = PlainEncoder.encodeDoubles(values, 0, values.length);
        byte[] split = ByteStreamSplitEncoder.encode(plain, 0, values.length, Double.BYTES);
        double[] read = new double[values.length];
        new ByteStreamSplitDecoder(split, 0, values.length, PhysicalType.DOUBLE, null)
                .readDoubles(read, null, 0);
        return read;
    }
}
