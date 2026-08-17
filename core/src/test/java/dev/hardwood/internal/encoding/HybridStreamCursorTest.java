/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HybridStreamCursorTest {
    @Test
    public void testPiecemealUnpack() {
        // Create bit-packed run of 16 values, width=4. Values: 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15
        // Run header for bit-packed is (num_groups << 1) | 1. 16 values = 2 groups. Header = (2<<1)|1 = 5.
        byte[] data = new byte[1 + 8];
        data[0] = 5; // header
        // group 1: 0,1,2,3, 4,5,6,7 -> 0000 0001, 0010 0011, 0100 0101, 0110 0111
        // values are Little Endian. 
        // byte 0: val0 (lower 4) | val1<<4 = 0 | (1<<4) = 0x10
        // byte 1: val2 | val3<<4 = 2 | (3<<4) = 0x32
        // byte 2: 4 | (5<<4) = 0x54
        // byte 3: 6 | (7<<4) = 0x76
        data[1] = 0x10; data[2] = 0x32; data[3] = 0x54; data[4] = 0x76;
        // group 2: 8,9,10,11, 12,13,14,15
        data[5] = (byte)0x98; data[6] = (byte)0xba; data[7] = (byte)0xdc; data[8] = (byte)0xfe;

        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 4);
        cursor.advance();
        int[] out = new int[16];
        int unpacked = cursor.unpack(out, 0, 3);
        assertEquals(3, unpacked);
        assertEquals(0, out[0]); assertEquals(1, out[1]); assertEquals(2, out[2]);
        
        unpacked = cursor.unpack(out, 3, 5);
        assertEquals(5, unpacked);
        for(int i=3; i<8; i++) assertEquals(i, out[i]);
        
        unpacked = cursor.unpack(out, 8, 8);
        assertEquals(8, unpacked);
        for(int i=8; i<16; i++) assertEquals(i, out[i]);
    }

    /// A cursor is consumed on the drain thread, long after the page-decode call
    /// that built it, while its source is a pooled decompression buffer the next
    /// page decode overwrites. The cursor must therefore copy its bytes: mutating
    /// the source after construction must not change what it decodes.
    @Test
    public void testOwnsBytesAcrossSourceMutation() {
        // Bit-packed run of 8 values, width 4: 0,1,2,3,4,5,6,7. Header = (1<<1)|1 = 3.
        byte[] data = new byte[]{3, 0x10, 0x32, 0x54, 0x76};

        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 4);
        // Simulate the decompressor reusing (overwriting) the buffer before the
        // cursor is drained.
        Arrays.fill(data, (byte) 0);

        cursor.advance();
        int[] out = new int[8];
        assertEquals(8, cursor.unpack(out, 0, 8));
        for (int i = 0; i < 8; i++) {
            assertEquals(i, out[i]);
        }
    }

    /// Bit width 0 carries no data: the stream is empty and `advance()` reports
    /// end-of-stream. Consumers (dictionary index scatter) interpret this as
    /// "every value maps to entry 0" themselves.
    @Test
    public void testBitWidthZeroIsEmptyStream() {
        HybridStreamCursor cursor = new HybridStreamCursor(new byte[0], 0, 0, 0);
        assertFalse(cursor.advance());
        assertEquals(0, cursor.remaining());
    }

    /// Definition levels are width-1 streams consumed in batch-bounded chunks
    /// that rarely align to the 8-value bit-packing groups. Unpacking a partial
    /// (< 8) prefix and then the remainder must yield the full run.
    @Test
    public void testPartialUnpackWidthOne() {
        // Bit-packed run of 8 values, width 1: 1,0,1,0,1,0,1,0 -> byte 0b01010101 = 0x55.
        // Header = (1<<1)|1 = 3.
        byte[] data = new byte[]{3, 0x55};
        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 1);
        cursor.advance();

        int[] out = new int[8];
        assertEquals(3, cursor.unpack(out, 0, 3));
        assertEquals(5, cursor.unpack(out, 3, 8));
        int[] expected = {1, 0, 1, 0, 1, 0, 1, 0};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], out[i], "value at " + i);
        }
    }

    /// A pure RLE run: header encodes repeat-count and a literal value follows.
    /// [#advance()] must return `true`, [#isRle()] `true`, [#value()] the
    /// encoded literal, and [#remaining()] the repeat count.
    @Test
    public void testPureRleRun() {
        // RLE run: 5 values each = 3, bit-width = 4.
        // header = (5 << 1) | 0 = 10 = 0x0A; value = 1 byte = 0x03.
        byte[] data = new byte[]{0x0A, 0x03};
        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 4);
        assertTrue(cursor.advance());
        assertTrue(cursor.isRle());
        assertEquals(3, cursor.value());
        assertEquals(5, cursor.remaining());
        cursor.skip(5);
        assertEquals(0, cursor.remaining());
        assertFalse(cursor.advance());
    }

    /// A stream with one RLE run followed by one bit-packed run.
    /// After draining the RLE run [#advance()] must load the packed run with the
    /// correct count and decode correct values.
    @Test
    public void testMixedRleAndBitPackedRuns() {
        // RLE: 4 values each = 7, width = 4.
        //   header = (4 << 1) | 0 = 8 = 0x08; value byte = 0x07.
        // Bit-packed: 1 group of 8 values (0-7), width = 4.
        //   header = (1 << 1) | 1 = 3; 4 data bytes.
        byte[] data = new byte[]{0x08, 0x07, 0x03, 0x10, 0x32, 0x54, 0x76};
        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 4);

        assertTrue(cursor.advance());
        assertTrue(cursor.isRle());
        assertEquals(7, cursor.value());
        assertEquals(4, cursor.remaining());
        cursor.skip(4);

        assertTrue(cursor.advance());
        assertFalse(cursor.isRle());
        assertEquals(8, cursor.remaining());
        int[] out = new int[8];
        assertEquals(8, cursor.unpack(out, 0, 8));
        for (int i = 0; i < 8; i++) {
            assertEquals(i, out[i], "packed value at index " + i);
        }
        assertFalse(cursor.advance());
    }

    /// [#skip()] on an RLE run decrements [#remaining()] without consuming bytes.
    /// [#skip()] on a bit-packed run must advance the underlying bit position so
    /// subsequent [#unpack()] calls yield the correct values.
    @Test
    public void testSkipMidRun() {
        // RLE: 10 values = 5, width = 4. header = (10 << 1) | 0 = 20 = 0x14.
        byte[] rleData = new byte[]{0x14, 0x05};
        HybridStreamCursor rleCursor = new HybridStreamCursor(rleData, 0, rleData.length, 4);
        assertTrue(rleCursor.advance());
        rleCursor.skip(4);
        assertEquals(6, rleCursor.remaining());
        rleCursor.skip(6);
        assertEquals(0, rleCursor.remaining());

        // Bit-packed: 16 values (0-15), width 4. Skip first 5; unpack next 3.
        // header = (2 << 1) | 1 = 5; 8 data bytes.
        byte[] packData = new byte[]{5, 0x10, 0x32, 0x54, 0x76, (byte)0x98, (byte)0xba, (byte)0xdc, (byte)0xfe};
        HybridStreamCursor packCursor = new HybridStreamCursor(packData, 0, packData.length, 4);
        assertTrue(packCursor.advance());
        packCursor.skip(5);
        assertEquals(11, packCursor.remaining());
        int[] out = new int[3];
        assertEquals(3, packCursor.unpack(out, 0, 3));
        assertEquals(5, out[0]);
        assertEquals(6, out[1]);
        assertEquals(7, out[2]);
    }

    /// [#skipCounting] must return the number of values equal to `matchValue`
    /// while consuming them (decrementing [#remaining()]) without writing to
    /// any array. Verified for width-1 (the def-level common case) and width-4.
    @Test
    public void testSkipCounting() {
        // Width-1 stream: 1,0,1,1,0,1,0,1 → byte 0b10110101 = 0xB5.
        // Header = (1<<1)|1 = 3 (1 group of 8 values).
        byte[] data1 = new byte[]{3, (byte) 0xB5};
        HybridStreamCursor c1 = new HybridStreamCursor(data1, 0, data1.length, 1);
        assertTrue(c1.advance());
        assertFalse(c1.isRle());
        assertEquals(8, c1.remaining());

        // Count first 5 for matchValue=1. Values are: 1,0,1,1,0 → 3 matches.
        assertEquals(3, c1.skipCounting(5, 1));
        assertEquals(3, c1.remaining());

        // Count remaining 3 for matchValue=1. Values are: 1,0,1 → 2 matches.
        assertEquals(2, c1.skipCounting(3, 1));
        assertEquals(0, c1.remaining());

        // Width-4 stream: 16 values 0-15; exactly one equals 5.
        // header = (2<<1)|1 = 5; data same as testPiecemealUnpack.
        byte[] data4 = new byte[]{5, 0x10, 0x32, 0x54, 0x76, (byte)0x98, (byte)0xba, (byte)0xdc, (byte)0xfe};
        HybridStreamCursor c4 = new HybridStreamCursor(data4, 0, data4.length, 4);
        assertTrue(c4.advance());
        assertEquals(1, c4.skipCounting(16, 5));
        assertEquals(0, c4.remaining());
    }

    /// [#skipCounting] must handle counts far larger than any batch capacity
    /// without throwing. This is the regression guard for the skipCursorsTo
    /// buffer-overflow bug: before the fix, skipCursorsTo called
    /// unpack(tempDefs, 0, runSkip) where runSkip could be the full page size
    /// (e.g. 50,000) while tempDefs.length was only batchCapacity (e.g. 1,024).
    @Test
    public void testSkipCountingLargeCount() throws java.io.IOException {
        // Width-1 run of 10,000 alternating values (1,0,1,0,…).
        // Each byte = 0x55 = 0b01010101. 10,000 values → 1,250 payload bytes.
        // num_groups = 10000/8 = 1250; bit-packed header = (1250<<1)|1 = 2501.
        // varint-encode 2501: 2501 = 0x09C5 → bytes 0x85, 0x9C, 0x01.
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int header = (1250 << 1) | 1; // 2501
        int h = header;
        while (h > 0x7F) { baos.write((h & 0x7F) | 0x80); h >>>= 7; }
        baos.write(h);
        for (int i = 0; i < 1250; i++) baos.write(0x55);
        byte[] data = baos.toByteArray();

        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 1);
        assertTrue(cursor.advance());
        assertFalse(cursor.isRle());
        assertEquals(10_000, cursor.remaining());

        // Exactly half the values (5,000) equal 1.
        assertEquals(5_000, cursor.skipCounting(10_000, 1));
        assertEquals(0, cursor.remaining());
    }

    /// [#unpack()] documents "only valid when isRle() is false". Calling it on
    /// an RLE run must throw immediately rather than silently running the
    /// bit-packed kernel over the value bytes and returning garbage.
    @Test
    public void testUnpackThrowsOnRleRun() {
        // RLE: 5 values = 3, width = 4. header = (5<<1)|0 = 10; value = 0x03.
        byte[] data = new byte[]{0x0A, 0x03};
        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 4);
        assertTrue(cursor.advance());
        assertTrue(cursor.isRle());

        int[] buf = new int[8];
        assertThrows(IllegalStateException.class, () -> cursor.unpack(buf, 0, 5));
    }

    /// [#skipCounting()] documents "only valid when isRle() is false". Calling
    /// it on an RLE run must throw immediately.
    @Test
    public void testSkipCountingThrowsOnRleRun() {
        byte[] data = new byte[]{0x0A, 0x03};
        HybridStreamCursor cursor = new HybridStreamCursor(data, 0, data.length, 4);
        assertTrue(cursor.advance());
        assertTrue(cursor.isRle());

        assertThrows(IllegalStateException.class, () -> cursor.skipCounting(3, 3));
    }
}
