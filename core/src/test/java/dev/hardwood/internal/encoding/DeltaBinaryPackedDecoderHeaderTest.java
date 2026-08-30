/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The header decides how the decoder sizes its buffers and how far it reads, and it comes out of
/// the file, so every field of it is hostile input. These are the shapes a corrupt or malicious
/// page can take that a round trip against a well-behaved encoder never produces.
class DeltaBinaryPackedDecoderHeaderTest {

    @Test
    void refusesAZeroMiniblockCount() {
        assertThatThrownBy(() -> decode(header(128, 0, 5, 100), 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid miniblock count: 0");
    }

    @Test
    void refusesANegativeMiniblockCount() {
        // A ULEB128 wide enough to overflow the int reaches the header as a negative count, and a
        // negative divisor divides evenly (128 % -1 == 0), so the divisibility check does not stop
        // it either. Unrefused it reaches new int[-1].
        assertThatThrownBy(() -> decode(header(128, -1, 5, 100), 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid miniblock count: -1");
    }

    @Test
    void refusesABlockSizeThatTheMiniblockCountDoesNotDivide() {
        assertThatThrownBy(() -> decode(header(128, 3, 5, 100), 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not divisible");
    }

    @Test
    void refusesABitWidthWiderThanALong() {
        byte[] page = concat(concat(header(128, 1, 5, 100), zigzag(3)), new byte[] { (byte) 200 });

        assertThatThrownBy(() -> decode(page, 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid bit width: 200");
    }

    @Test
    void sizesItsBufferByTheValueCountAndNotByTheBlockSize() throws IOException {
        // A block size of 2^30 is legal — a multiple of 128, divisible by the miniblock count — and
        // says nothing about how many values the page holds, which is five. Sizing the decode
        // buffer from the block size instead asks for a 8 GiB long[] and takes the reader out with
        // an OutOfMemoryError on a page that decodes in microseconds.
        byte[] page = concat(concat(header(1 << 30, 1, 5, 100), zigzag(3)), new byte[] { 0 });

        assertThat(decode(page, 5)).containsExactly(100, 103, 106, 109, 112);
    }

    private static int[] decode(byte[] page, int count) throws IOException {
        int[] output = new int[count];
        new DeltaBinaryPackedDecoder(page, 0).readInts(output, null, 0);
        return output;
    }

    private static byte[] header(int blockSize, int miniblockCount, int totalValueCount, long firstValue) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUleb128(out, blockSize);
        writeUleb128(out, miniblockCount);
        writeUleb128(out, totalValueCount);
        out.writeBytes(zigzag(firstValue));
        return out.toByteArray();
    }

    private static byte[] zigzag(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long encoded = (value << 1) ^ (value >> 63);
        do {
            int b = (int) (encoded & 0x7F);
            encoded >>>= 7;
            out.write(encoded != 0 ? b | 0x80 : b);
        } while (encoded != 0);
        return out.toByteArray();
    }

    private static void writeUleb128(ByteArrayOutputStream out, int value) {
        long remaining = value & 0xFFFFFFFFL;
        do {
            int b = (int) (remaining & 0x7F);
            remaining >>>= 7;
            out.write(remaining != 0 ? b | 0x80 : b);
        } while (remaining != 0);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(first);
        out.writeBytes(second);
        return out.toByteArray();
    }
}
