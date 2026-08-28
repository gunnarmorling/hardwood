/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.util.Arrays;

/// Encoder for DELTA_BINARY_PACKED, the inverse of [DeltaBinaryPackedDecoder].
///
/// Values are stored as the differences between neighbours: a block records the smallest delta
/// it holds, and each delta is packed as its distance above that minimum, so a column whose
/// values move in small steps packs into a few bits per value however large the values
/// themselves are.
///
/// ```text
/// HEADER: block_size (ULEB128) | miniblock_count (ULEB128) | total_count (ULEB128) | first_value (zigzag)
/// BLOCK:  min_delta (zigzag) | bit_width per miniblock (1 byte each) | miniblock data...
/// ```
///
/// **Deltas wrap.** They are computed in the column's own width, so an `INT32` column stepping
/// from `Integer.MAX_VALUE` to `Integer.MIN_VALUE` has a delta of 1 rather than one that
/// overflows the type. [DeltaBinaryPackedDecoder] adds the block minimum and the residue back
/// together in `long` arithmetic and narrows to `int` on the way out, and that narrowing is what
/// recovers the original value: the low bits of the sum are the type's own arithmetic either
/// way.
///
/// **The minimum is signed, the residue unsigned.** Taking the smallest delta as a signed value
/// is what keeps a descending column cheap — its deltas are negative, and measuring them against
/// a negative minimum leaves residues near zero. Comparing them unsigned instead would make
/// every negative delta look enormous and drive the bit width to the full type, which encodes
/// perfectly and costs an order of magnitude more than it needs to.
///
/// @see <a href="https://github.com/apache/parquet-format/blob/master/Encodings.md">Parquet Encodings</a>
public final class DeltaBinaryPackedEncoder {

    /// Values per block, the reference implementations' choice and a multiple of 128 as the
    /// format requires.
    static final int BLOCK_SIZE = 128;

    /// Miniblocks per block, so each holds 32 values — a whole number of bytes at every width.
    static final int MINIBLOCK_COUNT = 4;

    static final int VALUES_PER_MINIBLOCK = BLOCK_SIZE / MINIBLOCK_COUNT;

    private DeltaBinaryPackedEncoder() {
    }

    /// Encodes `count` `INT32` values starting at `offset`.
    ///
    /// @param values the backing array
    /// @param offset index of the first value
    /// @param count how many values to encode
    /// @return the encoded bytes
    public static byte[] encodeInts(int[] values, int offset, int count) {
        ByteSink out = new ByteSink(count * Integer.BYTES / 2 + 32);
        if (count == 0) {
            writeHeader(out, 0, 0);
            return out.toByteArray();
        }
        writeHeader(out, count, values[offset]);

        long[] deltas = new long[BLOCK_SIZE];
        int previous = values[offset];
        int remaining = count - 1;
        int next = offset + 1;
        while (remaining > 0) {
            int inBlock = Math.min(BLOCK_SIZE, remaining);
            for (int i = 0; i < inBlock; i++) {
                int value = values[next + i];
                // Subtracted in the column's own width, so a step across the type's ends wraps
                // to the short delta it is rather than one no `int` can hold. Widening the
                // wrapped `int` keeps it signed, which is what bounds the residues below: two
                // deltas in `[-2^31, 2^31)` differ by less than 2^32, so no residue ever needs
                // more than the 32 bits the format allows an `INT32` column.
                deltas[i] = value - previous;
                previous = value;
            }
            writeBlock(out, deltas, inBlock);
            next += inBlock;
            remaining -= inBlock;
        }
        return out.toByteArray();
    }

    /// Encodes `count` `INT64` values starting at `offset`.
    ///
    /// @param values the backing array
    /// @param offset index of the first value
    /// @param count how many values to encode
    /// @return the encoded bytes
    public static byte[] encodeLongs(long[] values, int offset, int count) {
        ByteSink out = new ByteSink(count * Long.BYTES / 2 + 32);
        if (count == 0) {
            writeHeader(out, 0, 0);
            return out.toByteArray();
        }
        writeHeader(out, count, values[offset]);

        long[] deltas = new long[BLOCK_SIZE];
        long previous = values[offset];
        int remaining = count - 1;
        int next = offset + 1;
        while (remaining > 0) {
            int inBlock = Math.min(BLOCK_SIZE, remaining);
            for (int i = 0; i < inBlock; i++) {
                long value = values[next + i];
                // This wraps where the two values straddle the type's ends, and so does the
                // residue taken from it below. Both wrap by the same modulus the decoder adds
                // them back in, so the pair cancels and the value returns exact.
                deltas[i] = value - previous;
                previous = value;
            }
            writeBlock(out, deltas, inBlock);
            next += inBlock;
            remaining -= inBlock;
        }
        return out.toByteArray();
    }

    private static void writeHeader(ByteSink out, int totalCount, long firstValue) {
        out.writeUleb128(BLOCK_SIZE);
        out.writeUleb128(MINIBLOCK_COUNT);
        out.writeUleb128(totalCount);
        out.writeZigzag(firstValue);
    }

    /// Writes one block: its minimum delta, a bit width per miniblock, then each delta's
    /// distance above that minimum.
    ///
    /// The minimum is the *signed* smallest delta, so a block whose values descend records a
    /// negative minimum and its residues stay small. The residues are then read unsigned, which
    /// is what lets a width of 32 or 64 carry a difference that no signed value of the type
    /// could hold.
    private static void writeBlock(ByteSink out, long[] deltas, int count) {
        long minDelta = deltas[0];
        for (int i = 1; i < count; i++) {
            if (deltas[i] < minDelta) {
                minDelta = deltas[i];
            }
        }
        long[] residues = deltas;
        int width = 0;
        for (int i = 0; i < count; i++) {
            residues[i] = deltas[i] - minDelta;
            int valueWidth = Long.SIZE - Long.numberOfLeadingZeros(residues[i]);
            if (valueWidth > width) {
                width = valueWidth;
            }
        }

        out.writeZigzag(minDelta);

        // A miniblock holding no value of this block declares a zero bit width and occupies no
        // bytes; one holding any value is written whole, the decoder taking its length from the
        // width alone. The values padding a partly-filled miniblock are the block minimum, whose
        // residue is zero.
        int usedMiniblocks = (count + VALUES_PER_MINIBLOCK - 1) / VALUES_PER_MINIBLOCK;
        for (int m = 0; m < MINIBLOCK_COUNT; m++) {
            out.write(m < usedMiniblocks ? width : 0);
        }
        if (width == 0) {
            return;
        }
        int padded = usedMiniblocks * VALUES_PER_MINIBLOCK;
        Arrays.fill(residues, count, padded, 0L);
        for (int m = 0; m < usedMiniblocks; m++) {
            out.pack(residues, m * VALUES_PER_MINIBLOCK, VALUES_PER_MINIBLOCK, width);
        }
    }

    /// A growable byte buffer with the varint and bit-packing writes this encoding needs.
    private static final class ByteSink {

        private byte[] buffer;
        private int length;

        ByteSink(int initialCapacity) {
            this.buffer = new byte[Math.max(32, initialCapacity)];
        }

        void write(int b) {
            ensure(1);
            buffer[length++] = (byte) b;
        }

        void writeUleb128(long value) {
            long v = value;
            while ((v & ~0x7FL) != 0) {
                write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            write((int) v);
        }

        void writeZigzag(long value) {
            writeUleb128((value << 1) ^ (value >> 63));
        }

        void pack(long[] values, int offset, int count, int bitWidth) {
            ensure(BitPacker.packedLength(count, bitWidth));
            length += BitPacker.pack(values, offset, count, bitWidth, buffer, length);
        }

        private void ensure(int extra) {
            if (length + extra > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(length + extra, buffer.length * 2));
            }
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buffer, length);
        }
    }
}
