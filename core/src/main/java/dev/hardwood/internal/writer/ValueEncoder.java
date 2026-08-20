/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.schema.ColumnSchema;

/// The per-physical-type half of a column chunk's value section: the typed value read window
/// over the batch source, the typed `PLAIN` buffer, the (optional) dictionary, and the typed
/// statistics. [ColumnChunkBuffer] owns the type-agnostic half — repetition / definition level
/// streams, page sealing, compression, CRC, and the dictionary-index stream — and drives this
/// encoder value by value as the [RecordShredder] streams a record range in.
///
/// The shredder emits only source positions, so every type-specific line lives here behind a
/// type-agnostic shredder and page sealer. A concrete encoder reads a present value through its
/// window at the shredder's `valueIndex`, feeds it to the dictionary or the `PLAIN` buffer, and
/// extends the statistics; an absent slot only advances the null count.
abstract class ValueEncoder {

    /// Selects the encoder for a column's physical type. `pageValues` sizes the per-page value
    /// buffer and the read window; `enableDictionary` requests dictionary encoding where the type
    /// supports it; `statisticsTruncationLength` bounds `BYTE_ARRAY` `min` / `max` bounds.
    static ValueEncoder forColumn(ColumnSchema column, int pageValues, boolean enableDictionary,
                                  int statisticsTruncationLength) {
        PhysicalType type = column.type();
        boolean unsigned = isUnsigned(column);
        return switch (type) {
            case INT32 -> new IntValueEncoder(pageValues, enableDictionary, unsigned);
            case INT64 -> new LongValueEncoder(pageValues, enableDictionary, unsigned);
            case FLOAT -> new FloatValueEncoder(pageValues, enableDictionary);
            case DOUBLE -> new DoubleValueEncoder(pageValues, enableDictionary);
            case BOOLEAN -> new BooleanValueEncoder(pageValues);
            case BYTE_ARRAY -> new BinaryValueEncoder(pageValues, enableDictionary, null,
                    () -> BinaryStatistics.forColumn(column, statisticsTruncationLength));
            case FIXED_LEN_BYTE_ARRAY -> new BinaryValueEncoder(pageValues, enableDictionary,
                    requireTypeLength(column),
                    () -> BinaryStatistics.forColumn(column, statisticsTruncationLength));
            default -> throw new IllegalArgumentException(
                    "Writer does not support physical type " + type + " for column " + column.name());
        };
    }

    /// The capacity a filled value store grows to: half again as much, so a row group's worth of
    /// values is reached in a bounded number of copies without doubling the overshoot at the
    /// sizes a row group reaches.
    static int grownCapacity(int current) {
        int grown = current + (current >> 1) + 8;
        if (grown < 0 || grown > MAX_STORE_CAPACITY) {
            if (current >= MAX_STORE_CAPACITY) {
                throw new IllegalStateException(
                        "A column chunk cannot hold more than " + MAX_STORE_CAPACITY + " values");
            }
            return MAX_STORE_CAPACITY;
        }
        return grown;
    }

    /// Largest value store a chunk may grow to, below the JVM's array-length ceiling.
    private static final int MAX_STORE_CAPACITY = Integer.MAX_VALUE - 8;

    /// Whether the column's statistics compare unsigned: only the `UINT_*` annotations do.
    private static boolean isUnsigned(ColumnSchema column) {
        return column.logicalType() instanceof LogicalType.IntType integer && !integer.isSigned();
    }

    private static int requireTypeLength(ColumnSchema column) {
        if (column.typeLength() == null) {
            throw new IllegalArgumentException(
                    "FIXED_LEN_BYTE_ARRAY column " + column.name() + " has no type length");
        }
        return column.typeLength();
    }

    /// Rebinds to a new batch's source and resets the value read window. The dictionary and
    /// statistics persist across the batches of one column chunk.
    abstract void reset(ColumnSource source);

    /// Starts a new column chunk: the dictionary and statistics begin again, and the value store
    /// is emptied while keeping the capacity it grew to, so the next row group reuses the buffers
    /// this one sized rather than allocating and regrowing its own.
    abstract void startChunk();

    /// Whether this type is dictionary-encodable at all (`false` for `BOOLEAN`).
    abstract boolean dictionaryCapable();

    /// Interns the present value at `valueIndex`, returning its dictionary index and assigning a
    /// new one in first-seen order if it is unseen. Only called while the chunk still has a
    /// dictionary, which requires [#dictionaryCapable].
    abstract int intern(int valueIndex);

    /// The bytes the dictionary body would occupy if written, which is one half of what a chunk's
    /// encoding is chosen on and the measure the analysis cap bounds.
    abstract long dictionaryPlainBytes();

    /// Appends the dictionary's value at `dictionaryIndex` to the value store, the operation that
    /// turns interned values back into stored ones when a chunk gives up its dictionary.
    abstract void storeDictionaryValue(int dictionaryIndex);

    /// Releases the dictionary's entries, keeping the capacity it reached for the next chunk.
    abstract void dropDictionary();

    /// The number of distinct dictionary values assigned so far.
    abstract int dictionarySize();

    /// The exact number of distinct present values this chunk holds, or [#UNKNOWN_DISTINCT_COUNT]
    /// where the encoder cannot state it — a column encoding without a dictionary has not counted
    /// them. Only meaningful while the chunk still has whatever structure the count comes from.
    abstract long exactDistinctCount();

    /// Stands for a cardinality an encoder cannot state, which keeps `distinct_count` out of the
    /// statistics rather than guessing at it.
    static final long UNKNOWN_DISTINCT_COUNT = -1;

    /// The `PLAIN`-encoded dictionary body — the distinct values in index order.
    abstract byte[] encodeDictionaryBody();

    /// Copies the present value at `valueIndex` into this chunk's value store, appending it after
    /// the values stored before it. The store holds every value the chunk did not intern, for the
    /// whole row group, because a page's bytes are only produced once the row group is complete
    /// and the batch the value arrived in is gone by then.
    abstract void store(int valueIndex);

    /// `PLAIN`-encodes `count` stored values starting at `from`, the value range of one page.
    abstract byte[] encodePlain(int from, int count);

    /// Extends the chunk statistics with the present value at `valueIndex`.
    abstract void stat(int valueIndex);

    /// Extends the chunk statistics with an absent (null) slot.
    abstract void statNull();

    /// The accumulated chunk statistics.
    abstract Statistics statistics();

    /// The uncompressed `PLAIN` bit width of the present value at `valueIndex`, used to size the
    /// buffered row group: a fixed-width type's constant width, or a `BYTE_ARRAY`'s length plus
    /// its 4-byte length prefix.
    abstract long valueBits(int valueIndex);
}
