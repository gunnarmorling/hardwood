/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.compression;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;

import dev.hardwood.reader.ParquetReadException;

/// Decompressor for Brotli compressed data.
public class BrotliDecompressor implements Decompressor {

    @Override
    public byte[] decompress(ByteBuffer compressed, int uncompressedSize) {
        BrotliLoader.ensureLoaded();

        // Brotli4j has no direct ByteBuffer API, so extract to a byte array.
        byte[] compressedBytes = new byte[compressed.remaining()];
        compressed.duplicate().get(compressedBytes);

        // BrotliInputStream decodes through DecoderJNI directly. The one-shot
        // Decoder.decompress()/DirectDecompress path returns its output wrapped in a
        // Netty ByteBuf, which would otherwise force io.netty:netty-buffer onto the
        // classpath just to unwrap the bytes.
        byte[] decompressed;
        boolean trailingBytes;
        try (BrotliInputStream in = new BrotliInputStream(new ByteArrayInputStream(compressedBytes))) {
            decompressed = in.readNBytes(uncompressedSize);
            trailingBytes = in.read() != -1;
        }
        catch (IOException | RuntimeException e) {
            // The source is a byte array, so an IOException here is the decoder
            // rejecting the stream rather than anything reaching a file.
            throw new ParquetReadException("Brotli decompression failed", e);
        }

        // Outside the catch, and deliberately: the stream decoded, so a length that disagrees
        // with the header is what the bytes say rather than the decoder refusing them. Raised
        // inside, these would be caught by the arm above and reported as the generic failure
        // with their own message demoted to a cause.
        if (decompressed.length != uncompressedSize) {
            throw new ParquetReadException(
                    "Brotli decompression size mismatch: expected " + uncompressedSize +
                            ", got " + decompressed.length);
        }
        if (trailingBytes) {
            throw new ParquetReadException(
                    "Brotli decompression produced more than the expected " + uncompressedSize + " bytes");
        }

        return decompressed;
    }

    @Override
    public String getName() {
        return "BROTLI";
    }
}
