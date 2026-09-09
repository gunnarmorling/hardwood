/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import dev.hardwood.BufferOutputFile;
import dev.hardwood.OutputFile;

/// [OutputFile] backed by a growable in-memory buffer, behind [OutputFile#inMemory()].
///
/// The write-side counterpart to `ByteBufferInputFile`: the file is accumulated on the heap
/// and grows with it, nothing about the destination having to be sized before a file whose
/// length is only known once it has been written. The accumulated bytes are retrieved after
/// [#close()], as a view of them with [#buffer()] or a copy with [#toByteArray()].
public final class ByteBufferOutputFile implements BufferOutputFile {

    /// The accumulated bytes. `ByteArrayOutputStream` hands them out only as a copy, which for
    /// a destination whose whole point is to give the file back is a second copy of the file
    /// per caller; the subclass is here to expose a buffer over the array instead.
    private static final class Sink extends ByteArrayOutputStream {

        ByteBuffer view() {
            return ByteBuffer.wrap(buf, 0, count).slice();
        }
    }

    private final Sink sink = new Sink();
    private boolean created;
    private boolean closed;
    private boolean discarded;

    @Override
    public void create() {
        if (created) {
            throw new IllegalStateException("OutputFile already created");
        }
        created = true;
    }

    @Override
    public void write(ByteBuffer data) {
        requireCreated();
        int length = data.remaining();
        if (data.hasArray()) {
            // Append the backing array directly: staging the payload in a fresh byte[] first
            // would double the copy on the way into the sink, which shows up as allocation in
            // every benchmark and test that writes through this file.
            sink.write(data.array(), data.arrayOffset() + data.position(), length);
            data.position(data.position() + length);
        }
        else {
            byte[] chunk = new byte[length];
            data.get(chunk);
            sink.writeBytes(chunk);
        }
    }

    @Override
    public long position() {
        requireCreated();
        return sink.size();
    }

    @Override
    public void close() {
        if (discarded) {
            return;
        }
        closed = true;
    }

    @Override
    public void discard() {
        discarded = true;
        sink.reset();
    }

    @Override
    public ByteBuffer buffer() {
        requireFinished();
        return sink.view();
    }

    /// Returns a copy of the bytes written so far.
    public byte[] toByteArray() {
        requireFinished();
        return sink.toByteArray();
    }

    private void requireFinished() {
        if (discarded) {
            throw new IllegalStateException("OutputFile was discarded");
        }
        if (!closed) {
            throw new IllegalStateException("OutputFile not closed");
        }
    }

    private void requireCreated() {
        if (!created) {
            throw new IllegalStateException("OutputFile not created");
        }
    }
}
