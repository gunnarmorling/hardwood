/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.compression;

import java.io.IOException;
import java.util.Arrays;

import org.xerial.snappy.Snappy;

/// [Compressor] for the SNAPPY codec, the inverse of [SnappyDecompressor].
///
/// Produces the raw Snappy block Parquet specifies, not the framed stream `SnappyOutputStream`
/// writes: the page header already records the uncompressed size, so the length-carrying frame
/// would be a second copy of what the reader has.
public class SnappyCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] data, int offset, int length) throws IOException {
        byte[] output = new byte[Snappy.maxCompressedLength(length)];
        int written = Snappy.rawCompress(data, offset, length, output, 0);
        return Arrays.copyOf(output, written);
    }

    @Override
    public String getName() {
        return "SNAPPY";
    }
}
