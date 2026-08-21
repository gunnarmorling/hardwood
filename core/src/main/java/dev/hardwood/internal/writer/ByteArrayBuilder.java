/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/// A [ByteArrayOutputStream] that lends out its backing array instead of copying it.
///
/// `toByteArray()` copies everything written so far, which on the write path means copying a
/// page body to hand it to the compressor and copying a whole column chunk to hand it to the
/// output. Both consumers take an `(array, offset, length)` slice, so the copy buys nothing.
/// [#array()] and [#length()] give them that slice directly.
///
/// The lent array is valid only until the next write, and [#reset()] lets one builder serve
/// every page of a chunk rather than allocating and regrowing one per page.
final class ByteArrayBuilder extends ByteArrayOutputStream {

    ByteArrayBuilder() {
    }

    ByteArrayBuilder(int initialCapacity) {
        super(initialCapacity);
    }

    /// The backing array. Only the first [#length()] bytes are written data, and the array is
    /// replaced by any write that outgrows it.
    byte[] array() {
        return buf;
    }

    /// The number of bytes written so far.
    int length() {
        return count;
    }

    /// Extends the buffer by `length` bytes and returns the offset at which they begin, so an
    /// encoder can produce a section straight into the buffer that will carry it rather than
    /// into an array of its own for the caller to copy in.
    ///
    /// The reserved bytes hold whatever was there before; a caller must write all of them.
    ///
    /// Reserving may replace the backing array, so **take the offset into a local and fetch
    /// [#array()] afterwards** — `fill(array(), reserve(n))` evaluates the array reference
    /// before the growth and writes into the array that was just replaced.
    int reserve(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Cannot reserve a negative length: " + length);
        }
        int at = count;
        int needed = Math.addExact(count, length);
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(needed, buf.length <= MAX_GROWTH ? buf.length * 2 : needed));
        }
        count = needed;
        return at;
    }

    /// Largest length that may be doubled without overflowing an `int`.
    private static final int MAX_GROWTH = Integer.MAX_VALUE / 2;
}
