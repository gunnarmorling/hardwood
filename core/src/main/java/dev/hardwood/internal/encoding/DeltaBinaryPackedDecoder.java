/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.io.IOException;

/// Decoder for DELTA_BINARY_PACKED encoding.
///
/// This encoding stores integers as deltas from consecutive values, organized in blocks
/// and miniblocks. Each block has a minimum delta, and values are stored as
/// (actual_delta - min_delta) to ensure non-negative values that can be efficiently bit-packed.
///
/// Format:
/// ```text
/// HEADER: block_size (ULEB128) | miniblock_count (ULEB128) | total_count (ULEB128) | first_value (zigzag)
/// BLOCK:  min_delta (zigzag) | bitwidths[miniblock_count] | miniblock_data...
/// ```
///
/// Supports INT32 and INT64 physical types.
///
/// @see <a href="https://github.com/apache/parquet-format/blob/master/Encodings.md">Parquet Encodings</a>
public class DeltaBinaryPackedDecoder implements ValueDecoder {

    private final byte[] data;
    private int pos;

    // Header values
    private int blockSize;
    private int miniblockCount;
    private int totalValueCount;
    private long firstValue;
    private int valuesPerMiniblock;

    // Reading state
    private int valuesRead;
    private long lastValue;
    private boolean headerRead;

    // Current block state
    private long minDelta;
    private int[] bitWidths;
    private int currentMiniblock;

    // Pre-allocated miniblock decode buffer (size = valuesPerMiniblock)
    private long[] miniblockBuffer;
    private int bufferPos;
    private int bufferFill;

    public DeltaBinaryPackedDecoder(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
        this.headerRead = false;
        this.valuesRead = 0;
    }

    /// Returns the current read position.
    /// Used by composite decoders (DeltaLengthByteArray, DeltaByteArray) that share the
    /// same byte[] and need to continue reading after this decoder has consumed its portion.
    public int getPos() {
        return pos;
    }

    /// Read a single INT32 value from the stream.
    public int readInt() throws IOException {
        return (int) readLongValue();
    }

    /// Read a single INT64 value from the stream.
    public long readLong() throws IOException {
        return readLongValue();
    }

    /// Read INT64 values directly into a primitive long array.
    @Override
    public void readLongs(long[] output, int[] definitionLevels, int maxDefLevel) throws IOException {
        if (!headerRead) {
            readHeader();
            headerRead = true;
        }
        if (definitionLevels == null) {
            int out = 0;
            int len = output.length;
            while (out < len) {
                if (valuesRead == 0) {
                    output[out++] = firstValue;
                    lastValue = firstValue;
                    valuesRead = 1;
                    continue;
                }
                if (valuesRead >= totalValueCount) {
                    throw new IOException("No more values to read");
                }
                if (bufferPos >= bufferFill) {
                    loadNextMiniblockInternal();
                }
                int canDrain = Math.min(bufferFill - bufferPos, len - out);
                System.arraycopy(miniblockBuffer, bufferPos, output, out, canDrain);
                bufferPos += canDrain;
                out += canDrain;
                valuesRead += canDrain;
            }
        }
        else {
            for (int i = 0; i < output.length; i++) {
                if (definitionLevels[i] == maxDefLevel) {
                    output[i] = readLongValue();
                }
            }
        }
    }

    /// Read INT32 values directly into a primitive int array.
    @Override
    public void readInts(int[] output, int[] definitionLevels, int maxDefLevel) throws IOException {
        if (!headerRead) {
            readHeader();
            headerRead = true;
        }
        if (definitionLevels == null) {
            int out = 0;
            int len = output.length;
            while (out < len) {
                if (valuesRead == 0) {
                    output[out++] = (int) firstValue;
                    lastValue = firstValue;
                    valuesRead = 1;
                    continue;
                }
                if (valuesRead >= totalValueCount) {
                    throw new IOException("No more values to read");
                }
                if (bufferPos >= bufferFill) {
                    loadNextMiniblockInternal();
                }
                int canDrain = Math.min(bufferFill - bufferPos, len - out);
                for (int i = 0; i < canDrain; i++) {
                    output[out++] = (int) miniblockBuffer[bufferPos++];
                }
                valuesRead += canDrain;
            }
        }
        else {
            for (int i = 0; i < output.length; i++) {
                if (definitionLevels[i] == maxDefLevel) {
                    output[i] = (int) readLongValue();
                }
            }
        }
    }

    /// Read a single value as a primitive long (no boxing).
    private long readLongValue() throws IOException {
        if (!headerRead) {
            readHeader();
            headerRead = true;
        }

        if (valuesRead == 0) {
            valuesRead = 1;
            lastValue = firstValue;
            return firstValue;
        }

        if (valuesRead >= totalValueCount) {
            throw new IOException("No more values to read");
        }

        if (bufferPos >= bufferFill) {
            loadNextMiniblockInternal();
        }

        valuesRead++;
        return miniblockBuffer[bufferPos++];
    }

    /// Decide whether we need a new block header or just the next miniblock, then load it.
    private void loadNextMiniblockInternal() throws IOException {
        int valuesFromBlocks = valuesRead - 1;
        if (valuesFromBlocks % blockSize == 0) {
            readBlockHeader();
        }
        else {
            currentMiniblock++;
            loadMiniblock(currentMiniblock);
        }
    }

    private void readHeader() throws IOException {
        blockSize = readUleb128();
        miniblockCount = readUleb128();
        totalValueCount = readUleb128();
        firstValue = readZigzagUleb128();

        if (blockSize <= 0) {
            throw new IOException("Invalid block size: " + blockSize);
        }
        if (miniblockCount == 0) {
            throw new IOException("Invalid miniblock count: 0");
        }
        if (blockSize % miniblockCount != 0) {
            throw new IOException("Block size " + blockSize + " is not divisible by miniblock count " + miniblockCount);
        }

        valuesPerMiniblock = blockSize / miniblockCount;
        bitWidths = new int[miniblockCount];
        miniblockBuffer = new long[valuesPerMiniblock];
        bufferPos = 0;
        bufferFill = 0;
        lastValue = firstValue;
    }

    private void readBlockHeader() throws IOException {
        minDelta = readZigzagUleb128();

        // Read bit widths for all miniblocks in this block
        for (int i = 0; i < miniblockCount; i++) {
            if (pos >= data.length) {
                throw new IOException("Unexpected EOF reading bitwidths");
            }
            int bw = data[pos++] & 0xFF;
            if (bw > 64) {
                throw new IOException("Invalid bit width: " + bw);
            }
            bitWidths[i] = bw;
        }

        currentMiniblock = 0;
        loadMiniblock(0);
    }

    /// Decode one miniblock into miniblockBuffer, applying the prefix sum in-place.
    ///
    /// validValues is capped at (totalValueCount - valuesRead) so the caller never reads
    /// more values than the header declared: the encoder pads the last miniblock with zeros,
    /// but those zeros are not real values and must not be returned.
    private void loadMiniblock(int miniblockIdx) throws IOException {
        int bitWidth = bitWidths[miniblockIdx];
        // Number of actual values in this miniblock (may be < valuesPerMiniblock for the last one)
        int validValues = Math.min(valuesPerMiniblock, totalValueCount - valuesRead);

        if (bitWidth == 0) {
            // All residuals are zero — no bytes consumed
            for (int i = 0; i < validValues; i++) {
                lastValue += minDelta;
                miniblockBuffer[i] = lastValue;
            }
        }
        else {
            int bytesNeeded = (valuesPerMiniblock * bitWidth + 7) / 8;
            if (pos + bytesNeeded > data.length) {
                throw new IOException("Unexpected EOF reading miniblock data: expected " + bytesNeeded
                        + " bytes, got " + (data.length - pos));
            }
            unpackAndPrefixSum(bitWidth, validValues);
            pos += bytesNeeded;
        }

        bufferPos = 0;
        bufferFill = validValues;
    }

    /// Unpack raw residuals from data[pos..] and apply the prefix sum into miniblockBuffer.
    ///
    /// Three paths based on width:
    ///   1-8:  8 values occupy exactly bitWidth bytes; one LE multi-byte load per group of 8.
    ///   9-32: 64-bit accumulator refilled one byte at a time.
    ///   33-64: bit-at-a-time (handles the case where accumulator would overflow at width 57+).
    private void unpackAndPrefixSum(int bitWidth, int validValues) {
        if (bitWidth <= 8 && (valuesPerMiniblock % 8) == 0) {
            unpackAndPrefixSum1to8(bitWidth, validValues);
        }
        else if (bitWidth <= 32) {
            unpackAndPrefixSum9to32(bitWidth, validValues);
        }
        else {
            unpackAndPrefixSum33to64(bitWidth, validValues);
        }
    }

    /// Width 1-8: every 8 values occupy exactly bitWidth bytes.
    /// Load those bytes into a 64-bit word (little-endian) and extract 8 values via shift-and-mask.
    private void unpackAndPrefixSum1to8(int bitWidth, int validValues) {
        long mask = (1L << bitWidth) - 1;
        int groups = valuesPerMiniblock / 8;
        int dataPos = pos;
        int idx = 0;
        for (int g = 0; g < groups; g++) {
            // Load bitWidth bytes as a little-endian long
            long word = 0;
            for (int b = 0; b < bitWidth; b++) {
                word |= (long) (data[dataPos++] & 0xFF) << (b * 8);
            }
            // Extract 8 values packed at bitWidth bits each
            for (int v = 0; v < 8; v++) {
                long residual = (word >>> (v * bitWidth)) & mask;
                if (idx < validValues) {
                    lastValue += minDelta + residual;
                    miniblockBuffer[idx] = lastValue;
                }
                idx++;
            }
        }
    }

    /// Width 9-32: use a 64-bit accumulator refilled one byte at a time.
    /// Max bits in accumulator is bitWidth-1+8 <= 39, safely within a long.
    private void unpackAndPrefixSum9to32(int bitWidth, int validValues) {
        long mask = bitWidth == 32 ? 0xFFFFFFFFL : (1L << bitWidth) - 1;
        long accumulator = 0;
        int bits = 0;
        int dataPos = pos;
        for (int i = 0; i < valuesPerMiniblock; i++) {
            while (bits < bitWidth) {
                accumulator |= (long) (data[dataPos++] & 0xFF) << bits;
                bits += 8;
            }
            long residual = accumulator & mask;
            accumulator >>>= bitWidth;
            bits -= bitWidth;
            if (i < validValues) {
                lastValue += minDelta + residual;
                miniblockBuffer[i] = lastValue;
            }
        }
    }

    /// Width 33-64: bit-at-a-time from data[pos..].
    /// Used instead of the accumulator path because widths 57+ can overfill a 64-bit accumulator.
    private void unpackAndPrefixSum33to64(int bitWidth, int validValues) {
        int bitPosition = 0;
        for (int i = 0; i < valuesPerMiniblock; i++) {
            long residual = 0;
            int bitsRemaining = bitWidth;
            int localBitPos = bitPosition;
            while (bitsRemaining > 0) {
                int byteOffset = localBitPos / 8;
                int bitOffset = localBitPos % 8;
                int bitsAvailable = 8 - bitOffset;
                int bitsToRead = Math.min(bitsAvailable, bitsRemaining);
                // bitsToRead is at most 8, so the mask fits in an int
                long bits = ((data[pos + byteOffset] & 0xFF) >>> bitOffset) & ((1 << bitsToRead) - 1);
                residual |= bits << (bitWidth - bitsRemaining);
                localBitPos += bitsToRead;
                bitsRemaining -= bitsToRead;
            }
            bitPosition += bitWidth;
            if (i < validValues) {
                lastValue += minDelta + residual;
                miniblockBuffer[i] = lastValue;
            }
        }
    }

    private int readUleb128() throws IOException {
        int result = 0;
        int shift = 0;
        int b;
        do {
            if (pos >= data.length) {
                throw new IOException("Unexpected EOF in ULEB128");
            }
            b = data[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    private long readUleb128Long() throws IOException {
        long result = 0;
        int shift = 0;
        int b;
        do {
            if (pos >= data.length) {
                throw new IOException("Unexpected EOF in ULEB128");
            }
            b = data[pos++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    private long readZigzagUleb128() throws IOException {
        long encoded = readUleb128Long();
        // Zigzag decode: (n >>> 1) ^ -(n & 1)
        return (encoded >>> 1) ^ -(encoded & 1);
    }
}
