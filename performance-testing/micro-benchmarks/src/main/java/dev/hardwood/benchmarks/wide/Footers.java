/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.wide;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Reads a Parquet file's footer bytes, so the metadata-decode benchmarks can hand the same
/// bytes to either implementation without measuring the read.
final class Footers {

    private static final int TRAILER_LENGTH = Integer.BYTES + 4;

    private Footers() {
    }

    /// The footer body of `path`: the metadata itself, without the trailing length and magic.
    static byte[] read(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            ByteBuffer trailer = ByteBuffer.allocate(TRAILER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, trailer, size - TRAILER_LENGTH);
            int footerLength = trailer.flip().getInt();
            ByteBuffer buffer = ByteBuffer.allocate(footerLength);
            readFully(channel, buffer, size - TRAILER_LENGTH - footerLength);
            return buffer.array();
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                throw new IOException("Unexpected end of file at " + offset);
            }
            offset += read;
        }
    }
}
