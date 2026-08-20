/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

/// Encodes definition (or repetition) levels for a DataPage V1 body, the inverse of the
/// level decoding in the reader's page decoder. Levels are always RLE/bit-packed hybrid
/// in V1, with a bit width derived from the maximum level.
///
/// The returned bytes are the bare hybrid stream; the caller frames a page by prefixing
/// the 4-byte little-endian length, exactly as the reader expects it.
public final class LevelEncoder {

    private LevelEncoder() {
    }

    /// Encodes `count` levels starting at `offset`, using the bit width for `maxLevel`.
    ///
    /// @param levels the level values
    /// @param offset index of the first level to encode
    /// @param count number of levels to encode
    /// @param maxLevel the column's maximum level, which fixes the bit width
    /// @return the RLE/bit-packed hybrid bytes, without the 4-byte length prefix
    public static byte[] encode(int[] levels, int offset, int count, int maxLevel) {
        RleBitPackingHybridEncoder encoder = new RleBitPackingHybridEncoder(bitWidth(maxLevel));
        encoder.writeInts(levels, offset, count);
        return encoder.toByteArray();
    }

    /// Encodes `count` levels starting at `offset` from a byte-backed store, using the bit width
    /// for `maxLevel`. A level is bounded by the schema's nesting depth, so the writer retains a
    /// row group's levels one unsigned byte per entry rather than one `int`.
    ///
    /// @param levels the level values, each read unsigned
    /// @param offset index of the first level to encode
    /// @param count number of levels to encode
    /// @param maxLevel the column's maximum level, which fixes the bit width
    /// @return the RLE/bit-packed hybrid bytes, without the 4-byte length prefix
    public static byte[] encode(byte[] levels, int offset, int count, int maxLevel) {
        RleBitPackingHybridEncoder encoder = new RleBitPackingHybridEncoder(bitWidth(maxLevel));
        for (int i = 0; i < count; i++) {
            encoder.writeInt(levels[offset + i] & 0xFF);
        }
        return encoder.toByteArray();
    }

    /// The largest level a byte-backed level store can hold, and so the deepest nesting the
    /// writer accepts.
    public static final int MAX_STORABLE_LEVEL = 0xFF;

    /// Minimum number of bits needed to represent levels in `[0, maxLevel]`, matching the
    /// reader's `getBitWidth`.
    public static int bitWidth(int maxLevel) {
        if (maxLevel == 0) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(maxLevel);
    }
}
