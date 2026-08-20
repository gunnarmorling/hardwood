/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.util.Arrays;

import dev.hardwood.internal.encoding.PlainEncoder;
import dev.hardwood.metadata.Statistics;

/// [ValueEncoder] for `BOOLEAN` columns. Booleans are never dictionary-encoded — a two-value
/// dictionary cannot beat the bit-packed `PLAIN` layout — so every page is `PLAIN`.
///
/// The chunk's values are retained one bit each rather than one `boolean` each. The row group's
/// flush trigger charges a `BOOLEAN` value the bit it occupies `PLAIN`-encoded, so a `boolean[]`
/// store — a byte per value — would hold eight times what `rowGroupTargetBytes` was told it may,
/// which at the default target is a gigabyte for a single column.
final class BooleanValueEncoder extends ValueEncoder {

    /// This chunk's stored values, one bit each: value `i` is bit `i & 63` of `plain[i >>> 6]`.
    /// `plainCapacity` counts values rather than words, so the store grows on the same schedule
    /// and against the same ceiling as the fixed-width encoders'.
    private long[] plain;
    private int plainCapacity;
    private int plainCount;
    private final boolean[] window;
    private BooleanStatisticsCollector statistics = new BooleanStatisticsCollector();

    private BooleanColumnSource source;
    private int size;
    private int windowBase;
    private int windowLength;

    BooleanValueEncoder(int pageValues) {
        this.plainCapacity = Math.max(1, pageValues);
        this.plain = new long[wordsFor(plainCapacity)];
        this.window = new boolean[Math.max(1, pageValues)];
    }

    /// The number of 64-bit words holding `values` bits, computed in `long` because the value
    /// ceiling rounds up past `Integer.MAX_VALUE`.
    private static int wordsFor(int values) {
        return Math.toIntExact(((long) values + Long.SIZE - 1) >>> 6);
    }

    @Override
    void reset(ColumnSource source) {
        this.source = (BooleanColumnSource) source;
        this.size = source.size();
        this.windowBase = 0;
        this.windowLength = 0;
    }

    private boolean valueAt(int index) {
        if (index >= windowBase + windowLength) {
            windowBase = index;
            windowLength = Math.min(window.length, size - index);
            source.copyInto(windowBase, window, 0, windowLength);
        }
        return window[index - windowBase];
    }

    @Override
    boolean dictionaryCapable() {
        return false;
    }

    @Override
    int intern(int valueIndex) {
        throw new UnsupportedOperationException("BOOLEAN columns are never dictionary-encoded");
    }

    @Override
    void storeDictionaryValue(int dictionaryIndex) {
        throw new UnsupportedOperationException("BOOLEAN columns are never dictionary-encoded");
    }

    @Override
    long dictionaryPlainBytes() {
        return 0;
    }

    @Override
    void dropDictionary() {
        // Nothing to drop: a BOOLEAN chunk never builds a dictionary.
    }

    @Override
    int dictionarySize() {
        return 0;
    }

    @Override
    long exactDistinctCount() {
        return statistics.distinctCount();
    }

    @Override
    byte[] encodeDictionaryBody() {
        throw new UnsupportedOperationException("BOOLEAN columns are never dictionary-encoded");
    }

    @Override
    void startChunk() {
        statistics = new BooleanStatisticsCollector();
        plainCount = 0;
    }

    @Override
    void store(int valueIndex) {
        if (plainCount == plainCapacity) {
            plainCapacity = grownCapacity(plainCapacity);
            plain = Arrays.copyOf(plain, wordsFor(plainCapacity));
        }
        // Both arms are written: the store keeps the words it grew across chunks, so a `false`
        // that only skipped setting its bit would read back as whatever the previous chunk left.
        long bit = 1L << plainCount;
        if (valueAt(valueIndex)) {
            plain[plainCount >>> 6] |= bit;
        }
        else {
            plain[plainCount >>> 6] &= ~bit;
        }
        plainCount++;
    }

    @Override
    byte[] encodePlain(int from, int count) {
        return PlainEncoder.encodeBooleans(plain, from, count);
    }

    @Override
    void stat(int valueIndex) {
        statistics.accept(valueAt(valueIndex));
    }

    @Override
    void statNull() {
        statistics.acceptNull();
    }

    @Override
    Statistics statistics() {
        return statistics.toStatistics();
    }

    @Override
    long valueBits(int valueIndex) {
        return 1; // bit-packed
    }
}
