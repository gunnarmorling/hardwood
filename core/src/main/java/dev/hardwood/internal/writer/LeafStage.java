/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.util.Arrays;

import dev.hardwood.Validity;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.writer.ColumnBatch;

/// Accumulates one leaf column's values while the row-oriented layer stages a batch, then
/// hands them to a [ColumnBatch] as the typed array that column's setter takes.
///
/// A row writer appends one entry per instance of the leaf's enclosing scope, so the array
/// is dense: a null carries a placeholder value the writer never encodes, and a value under
/// an absent struct ancestor carries one too — the columnar contract gives every leaf a slot
/// per instance and ignores the ones its levels mark absent.
///
/// The arrays are reused across batches. Only [#reset()] returns the fill cursor to zero,
/// so the same buffers serve the whole file.
abstract sealed class LeafStage {

    private static final int INITIAL_CAPACITY = 16;

    /// Number of entries staged so far.
    int count;

    /// Per-entry null mask, `true` at a null entry. Cleared in full by [#reset()], so the
    /// entries beyond [#count] are always `false` and the array can be handed to
    /// [Validity#ofNulls] untrimmed.
    boolean[] nulls = new boolean[INITIAL_CAPACITY];

    /// Whether any entry staged in this batch is null, so an all-present column skips the
    /// mask entirely.
    boolean hasNulls;

    static LeafStage forType(PhysicalType type) {
        return switch (type) {
            case BOOLEAN -> new BooleanStage();
            case INT32 -> new IntStage();
            case INT64 -> new LongStage();
            case FLOAT -> new FloatStage();
            case DOUBLE -> new DoubleStage();
            case BYTE_ARRAY -> new BinaryStage(false);
            case FIXED_LEN_BYTE_ARRAY -> new BinaryStage(true);
            case INT96 -> throw new IllegalArgumentException("INT96 is not supported by the writer");
        };
    }

    /// Reserves the next entry, growing both the value and the null arrays, and returns its
    /// index. The entry is marked non-null; [#appendNull()] overrides that.
    ///
    /// The growth replaces the value array, so a caller must take the index into a local and
    /// store through it afterwards — `values[reserve()] = value` would evaluate the array
    /// reference before the growth and write into the array that was just replaced.
    final int reserve() {
        ensureValueCapacity(count + 1);
        if (count == nulls.length) {
            nulls = Arrays.copyOf(nulls, nulls.length * 2);
        }
        return count++;
    }

    /// Appends a null entry: a placeholder value the writer never encodes, marked null so
    /// the column's [Validity] carries it.
    final void appendNull() {
        int index = reserve();
        nulls[index] = true;
        hasNulls = true;
        clearAt(index);
    }

    /// Appends a placeholder for an entry an absent ancestor makes unreachable. It is not
    /// marked null: a `REQUIRED` leaf carries no validity at all, and the levels its
    /// ancestor emits already mark the slot absent.
    final void appendPlaceholder() {
        clearAt(reserve());
    }

    /// Drops every entry staged since `mark`, undoing a row that failed mid-way.
    final void truncate(int mark) {
        for (int i = mark; i < count; i++) {
            nulls[i] = false;
        }
        dropValues(mark, count);
        count = mark;
        // `hasNulls` is not recomputed: a spurious `true` costs an all-false mask, never a
        // wrong value, and rescanning the mask on every rolled-back row would not pay.
    }

    final void reset() {
        Arrays.fill(nulls, false);
        dropValues(0, count);
        hasNulls = false;
        count = 0;
    }

    /// Releases whatever the entries in `[from, to)` accounted for beyond their slot. Only a
    /// variable-width column has anything to release.
    void dropValues(int from, int to) {
    }

    /// The staged payload of a variable-width column, which bounds how much a batch can hold
    /// beyond its row count. Zero for the fixed-width types, whose size the row count implies.
    long variableWidthBytes() {
        return 0;
    }

    /// Hands the staged values to the batch through the setter for this column's physical
    /// type, trimmed to the staged count — [ColumnBatch] derives the batch's item count from
    /// the array length.
    abstract void fill(ColumnBatch batch, int columnIndex);

    abstract void ensureValueCapacity(int required);

    /// Writes the type's zero into the slot, so a placeholder never carries a stale value
    /// from an earlier batch.
    abstract void clearAt(int index);

    /// The validity to hand to the batch, or `null` when the column is all-present.
    final Validity validity() {
        return hasNulls ? Validity.ofNulls(nulls) : null;
    }

    static int grownLength(int current, int required) {
        int length = Math.max(current, INITIAL_CAPACITY);
        while (length < required) {
            length *= 2;
        }
        return length;
    }

    static final class IntStage extends LeafStage {

        private int[] values = new int[INITIAL_CAPACITY];

        void append(int value) {
            int index = reserve();
            values[index] = value;
        }

        @Override
        void ensureValueCapacity(int required) {
            if (values.length < required) {
                values = Arrays.copyOf(values, grownLength(values.length, required));
            }
        }

        @Override
        void clearAt(int index) {
            values[index] = 0;
        }

        @Override
        void fill(ColumnBatch batch, int columnIndex) {
            int[] trimmed = values.length == count ? values : Arrays.copyOf(values, count);
            Validity validity = validity();
            if (validity == null) {
                batch.ints(columnIndex, trimmed);
            }
            else {
                batch.ints(columnIndex, trimmed, validity);
            }
        }
    }

    static final class LongStage extends LeafStage {

        private long[] values = new long[INITIAL_CAPACITY];

        void append(long value) {
            int index = reserve();
            values[index] = value;
        }

        @Override
        void ensureValueCapacity(int required) {
            if (values.length < required) {
                values = Arrays.copyOf(values, grownLength(values.length, required));
            }
        }

        @Override
        void clearAt(int index) {
            values[index] = 0L;
        }

        @Override
        void fill(ColumnBatch batch, int columnIndex) {
            long[] trimmed = values.length == count ? values : Arrays.copyOf(values, count);
            Validity validity = validity();
            if (validity == null) {
                batch.longs(columnIndex, trimmed);
            }
            else {
                batch.longs(columnIndex, trimmed, validity);
            }
        }
    }

    static final class FloatStage extends LeafStage {

        private float[] values = new float[INITIAL_CAPACITY];

        void append(float value) {
            int index = reserve();
            values[index] = value;
        }

        @Override
        void ensureValueCapacity(int required) {
            if (values.length < required) {
                values = Arrays.copyOf(values, grownLength(values.length, required));
            }
        }

        @Override
        void clearAt(int index) {
            values[index] = 0f;
        }

        @Override
        void fill(ColumnBatch batch, int columnIndex) {
            float[] trimmed = values.length == count ? values : Arrays.copyOf(values, count);
            Validity validity = validity();
            if (validity == null) {
                batch.floats(columnIndex, trimmed);
            }
            else {
                batch.floats(columnIndex, trimmed, validity);
            }
        }
    }

    static final class DoubleStage extends LeafStage {

        private double[] values = new double[INITIAL_CAPACITY];

        void append(double value) {
            int index = reserve();
            values[index] = value;
        }

        @Override
        void ensureValueCapacity(int required) {
            if (values.length < required) {
                values = Arrays.copyOf(values, grownLength(values.length, required));
            }
        }

        @Override
        void clearAt(int index) {
            values[index] = 0d;
        }

        @Override
        void fill(ColumnBatch batch, int columnIndex) {
            double[] trimmed = values.length == count ? values : Arrays.copyOf(values, count);
            Validity validity = validity();
            if (validity == null) {
                batch.doubles(columnIndex, trimmed);
            }
            else {
                batch.doubles(columnIndex, trimmed, validity);
            }
        }
    }

    static final class BooleanStage extends LeafStage {

        private boolean[] values = new boolean[INITIAL_CAPACITY];

        void append(boolean value) {
            int index = reserve();
            values[index] = value;
        }

        @Override
        void ensureValueCapacity(int required) {
            if (values.length < required) {
                values = Arrays.copyOf(values, grownLength(values.length, required));
            }
        }

        @Override
        void clearAt(int index) {
            values[index] = false;
        }

        @Override
        void fill(ColumnBatch batch, int columnIndex) {
            boolean[] trimmed = values.length == count ? values : Arrays.copyOf(values, count);
            Validity validity = validity();
            if (validity == null) {
                batch.booleans(columnIndex, trimmed);
            }
            else {
                batch.booleans(columnIndex, trimmed, validity);
            }
        }
    }

    /// Stages both variable-width `BYTE_ARRAY` and fixed-width `FIXED_LEN_BYTE_ARRAY`
    /// columns, which differ only in the batch setter they end up in.
    static final class BinaryStage extends LeafStage {

        private static final byte[] EMPTY = new byte[0];

        private final boolean fixedWidth;
        private byte[][] values = new byte[INITIAL_CAPACITY][];
        private long payloadBytes;

        BinaryStage(boolean fixedWidth) {
            this.fixedWidth = fixedWidth;
        }

        void append(byte[] value) {
            int index = reserve();
            values[index] = value;
            payloadBytes += value.length;
        }

        @Override
        void ensureValueCapacity(int required) {
            if (values.length < required) {
                values = Arrays.copyOf(values, grownLength(values.length, required));
            }
        }

        @Override
        void clearAt(int index) {
            values[index] = EMPTY;
        }

        @Override
        long variableWidthBytes() {
            return payloadBytes;
        }

        @Override
        void dropValues(int from, int to) {
            for (int i = from; i < to; i++) {
                if (values[i] != null) {
                    payloadBytes -= values[i].length;
                    values[i] = null;
                }
            }
        }

        @Override
        void fill(ColumnBatch batch, int columnIndex) {
            byte[][] trimmed = values.length == count ? values : Arrays.copyOf(values, count);
            Validity validity = validity();
            if (fixedWidth) {
                if (validity == null) {
                    batch.fixed(columnIndex, trimmed);
                }
                else {
                    batch.fixed(columnIndex, trimmed, validity);
                }
            }
            else if (validity == null) {
                batch.bytes(columnIndex, trimmed);
            }
            else {
                batch.bytes(columnIndex, trimmed, validity);
            }
        }
    }
}
