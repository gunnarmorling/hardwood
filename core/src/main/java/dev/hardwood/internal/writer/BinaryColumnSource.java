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

    /// Copies references to `length` values starting at `srcPos` into `dest` starting at
    /// `destPos`. The referenced arrays are not copied, only the references.
    void copyInto(int srcPos, byte[][] dest, int destPos, int length);
}
