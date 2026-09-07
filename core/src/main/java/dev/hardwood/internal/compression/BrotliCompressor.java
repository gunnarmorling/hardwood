/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.compression;

import java.io.IOException;

import com.aayushatharva.brotli4j.encoder.Encoder;

import dev.hardwood.writer.ParquetWriteException;

/// [Compressor] for the BROTLI codec, the inverse of [BrotliDecompressor]. Compresses at
/// brotli4j's default quality, which is the densest and slowest of the codecs the writer
/// produces.
public class BrotliCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] data, int offset, int length) {
        BrotliLoader.ensureLoaded();
        try {
            return Encoder.compress(data, offset, length);
        }
        catch (IOException | RuntimeException e) {
            throw new ParquetWriteException("Brotli compression failed", e);
        }
    }

    @Override
    public String getName() {
        return "BROTLI";
    }
}
