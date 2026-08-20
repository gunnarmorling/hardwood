/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.util.Arrays;

/// Builds a column-chunk dictionary for binary values (`BYTE_ARRAY` / `FIXED_LEN_BYTE_ARRAY`),
/// assigning indices in first-seen order. The binary counterpart of [DictionaryEncoder]; the
/// table keys on byte-array **content** (content hash, content equality).
///
/// The distinct values are kept in index order so the dictionary page can be `PLAIN`-encoded
/// directly from [#values]. [#contentBytes] is the running sum of the distinct values' lengths,
/// which the caller combines with the per-entry framing overhead to size the dictionary body it
/// would write, and so to decide whether writing one pays.
public final class BinaryDictionaryEncoder {

    private static final int EMPTY = -1;

    // Open-addressed hash table with linear probing. slotIndex[s] is the dictionary index in
    // slot s, or EMPTY when free; the value lives at values[slotIndex[s]].
    private int[] slotIndex;
    private int mask;
    private int threshold;

    private byte[][] values;
    private int size;
    private long contentBytes;

    public BinaryDictionaryEncoder() {
        allocateTable(64);
        this.values = new byte[16][];
    }

    /// The index assigned to `value`, or `-1` if it has not been seen. Does not assign.
    public int indexOf(byte[] value) {
        int slot = hash(value) & mask;
        while (slotIndex[slot] != EMPTY) {
            if (Arrays.equals(values[slotIndex[slot]], value)) {
                return slotIndex[slot];
            }
            slot = (slot + 1) & mask;
        }
        return EMPTY;
    }

    /// Assigns and returns the next index for `value`, which the caller has confirmed absent via
    /// [#indexOf]. The bytes are copied: the caller's array is a window over the batch the value
    /// arrived in, which the dictionary must not still be referencing when its page is encoded at
    /// row-group flush.
    public int add(byte[] value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        int index = size;
        // Copy: the caller's array is a window over the batch it came from, which the writer must
        // not still be referencing when the dictionary page is encoded at row-group flush.
        values[size++] = Arrays.copyOf(value, value.length);
        contentBytes += value.length;
        insert(value, index);
        if (size > threshold) {
            resizeTable();
        }
        return index;
    }

    /// Empties the dictionary for a new column chunk, keeping the hash table and the value array
    /// it has grown to but releasing the values themselves. A chunk that gives up its dictionary
    /// has just resolved every one of those values into its own store, so holding the entries
    /// until each slot is overwritten would keep two copies of exactly the data the analysis cap
    /// exists to bound.
    public void clear() {
        Arrays.fill(slotIndex, EMPTY);
        Arrays.fill(values, 0, size, null);
        size = 0;
        contentBytes = 0;
    }

    /// The number of distinct values assigned so far.
    public int size() {
        return size;
    }

    /// The running sum of the distinct values' content lengths (no framing overhead).
    public long contentBytes() {
        return contentBytes;
    }

    /// The distinct values in index order; only indices `[0, size)` are meaningful.
    public byte[][] values() {
        return values;
    }

    private void insert(byte[] value, int index) {
        int slot = hash(value) & mask;
        while (slotIndex[slot] != EMPTY) {
            slot = (slot + 1) & mask;
        }
        slotIndex[slot] = index;
    }

    private void resizeTable() {
        int[] oldIndex = slotIndex;
        allocateTable(oldIndex.length * 2);
        for (int s = 0; s < oldIndex.length; s++) {
            if (oldIndex[s] != EMPTY) {
                insert(values[oldIndex[s]], oldIndex[s]);
            }
        }
    }

    private void allocateTable(int capacity) {
        this.slotIndex = new int[capacity];
        Arrays.fill(slotIndex, EMPTY);
        this.mask = capacity - 1;
        this.threshold = capacity - (capacity >> 2); // 75%
    }

    private static int hash(byte[] value) {
        return Arrays.hashCode(value) * 0x9E3779B1;
    }
}
