/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.util.Arrays;

/// Encoder for RLE/Bit-Packing Hybrid encoding, the inverse of
/// [RleBitPackingHybridDecoder]. Used for definition/repetition levels (via
/// [LevelEncoder]) and for a data page's dictionary index stream.
///
/// Values that repeat at least eight times in a row are emitted as an RLE run; shorter
/// stretches are bit-packed in groups of eight. Emitting a single RLE run for a constant
/// stream is what lets the reader take its all-present fast path on a fully-populated
/// optional column. The bit-packed byte layout is little-endian bit order, matching the
/// decoder: value `i` of a group occupies bits `[i·bitWidth, (i+1)·bitWidth)`.
///
/// A zero-bit stream — the dictionary indices of a chunk whose dictionary holds a single
/// entry — is one RLE run whose values occupy no bytes, so only the run header is written.
/// The header cannot be skipped even though it carries no value: a decoder reads the run
/// length from the stream rather than from the page's value count.
public final class RleBitPackingHybridEncoder {

    private final int bitWidth;

    private byte[] buffer = new byte[64];
    private int length;

    // Values buffered for the current, not-yet-decided run (up to one group of eight).
    private final int[] bufferedValues = new int[8];
    private int numBufferedValues;

    // Run-length tracking for the value currently being repeated.
    private int previousValue;
    private int repeatCount;

    // Open bit-packed run: the index of its header byte (-1 when none is open) and the
    // number of eight-value groups written into it so far.
    private int bitPackedRunHeaderIndex = -1;
    private int bitPackedGroupCount;

    private boolean finished;

    /// @param bitWidth number of bits per value, 0–32
    public RleBitPackingHybridEncoder(int bitWidth) {
        if (bitWidth < 0 || bitWidth > 32) {
            throw new IllegalArgumentException("Invalid RLE bit width: " + bitWidth + ". Must be between 0 and 32");
        }
        this.bitWidth = bitWidth;
    }

    /// Appends `count` values starting at `offset`.
    public void writeInts(int[] values, int offset, int count) {
        for (int i = 0; i < count; i++) {
            writeInt(values[offset + i]);
        }
    }

    /// Appends a single value.
    public void writeInt(int value) {
        if (finished) {
            throw new IllegalStateException("Encoder already finished");
        }
        if (bitWidth == 0) {
            // Zero bits leave only one representable value, so the whole stream is one RLE
            // run and the value itself needs no bytes. The run header is still required: a
            // decoder takes the run length from the stream rather than from the page's value
            // count, and reads past the end without it.
            repeatCount++;
            return;
        }
        if (value == previousValue) {
            repeatCount++;
            if (repeatCount >= 8) {
                // Certain to become an RLE run; keep counting and defer the emit.
                return;
            }
        }
        else {
            if (repeatCount >= 8) {
                writeRleRun();
            }
            repeatCount = 1;
            previousValue = value;
        }

        bufferedValues[numBufferedValues++] = value;
        if (numBufferedValues == 8) {
            writeOrAppendBitPackedRun();
        }
    }

    /// Finishes the stream and returns the encoded bytes. The encoder must not be written
    /// to afterwards.
    public byte[] toByteArray() {
        if (!finished) {
            if (bitWidth == 0) {
                if (repeatCount > 0) {
                    writeRleRun();
                }
            }
            else if (repeatCount >= 8) {
                writeRleRun();
            }
            else if (numBufferedValues > 0) {
                Arrays.fill(bufferedValues, numBufferedValues, 8, 0);
                writeOrAppendBitPackedRun();
                endPreviousBitPackedRun();
            }
            else {
                endPreviousBitPackedRun();
            }
            finished = true;
        }
        return Arrays.copyOf(buffer, length);
    }

    private void writeOrAppendBitPackedRun() {
        if (bitPackedGroupCount >= 63) {
            // A bit-packed run header counts groups in the upper bits of one byte, so a
            // run holds at most 63 groups; start a fresh one past that.
            endPreviousBitPackedRun();
        }
        if (bitPackedRunHeaderIndex == -1) {
            // Reserve the header byte; its final value is only known once the run ends.
            write(0);
            bitPackedRunHeaderIndex = length - 1;
        }
        packGroup();
        numBufferedValues = 0;
        // The buffered values are now written as a bit-packed group, so they must not also
        // be counted toward a later RLE run.
        repeatCount = 0;
        bitPackedGroupCount++;
    }

    private void endPreviousBitPackedRun() {
        if (bitPackedRunHeaderIndex == -1) {
            return;
        }
        buffer[bitPackedRunHeaderIndex] = (byte) ((bitPackedGroupCount << 1) | 1);
        bitPackedRunHeaderIndex = -1;
        bitPackedGroupCount = 0;
    }

    private void writeRleRun() {
        // Close any open bit-packed run before switching to an RLE run.
        endPreviousBitPackedRun();
        writeUnsignedVarInt(repeatCount << 1);
        int byteCount = (bitWidth + 7) / 8;
        for (int i = 0; i < byteCount; i++) {
            write((previousValue >>> (i * 8)) & 0xFF);
        }
        repeatCount = 0;
        numBufferedValues = 0;
    }

    /// Packs the eight buffered values into `bitWidth` bytes, LSB-first, matching the
    /// decoder's little-endian bit order. The layout is [BitPacker]'s, shared with
    /// DELTA_BINARY_PACKED's 32-value miniblocks.
    private void packGroup() {
        int packed = BitPacker.packedLength(8, bitWidth);
        ensureCapacity(packed);
        length += BitPacker.pack(bufferedValues, 0, 8, bitWidth, buffer, length);
    }

    private void writeUnsignedVarInt(int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        write(v);
    }

    private void write(int b) {
        ensureCapacity(1);
        buffer[length++] = (byte) b;
    }

    private void ensureCapacity(int extra) {
        if (length + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(length + extra, buffer.length * 2));
        }
    }
}
