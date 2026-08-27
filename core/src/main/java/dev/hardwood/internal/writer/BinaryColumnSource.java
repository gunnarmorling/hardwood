/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

/// A [ColumnSource] over a column's binary values (`BYTE_ARRAY` or `FIXED_LEN_BYTE_ARRAY`),
/// each value a `byte[]`, read in page-sized chunks of references.
public interface BinaryColumnSource extends ColumnSource {

    /// The bytes the value at `index` holds, its length prefix excluded, and 0 where the position
    /// holds no value. Every other column's width follows from the schema; this is the one that
    /// has to be read, and reading it is what lets the writer bound a slice before appending it.
    int valueBytesAt(int index);

    /// Copies references to `length` values starting at `srcPos` into `dest` starting at
    /// `destPos`. The referenced arrays are not copied, only the references.
    void copyInto(int srcPos, byte[][] dest, int destPos, int length);
}
