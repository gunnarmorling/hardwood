/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.io.ByteArrayOutputStream;

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
}
