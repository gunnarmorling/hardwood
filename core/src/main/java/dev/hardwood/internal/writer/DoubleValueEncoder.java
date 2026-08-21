/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.util.Arrays;

import dev.hardwood.internal.encoding.ByteStreamSplitEncoder;
import dev.hardwood.internal.encoding.LongDictionaryEncoder;
import dev.hardwood.internal.encoding.PlainEncoder;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.writer.ColumnEncoding;

/// [ValueEncoder] for `DOUBLE` columns. Values intern by their raw IEEE-754 bit pattern through
/// an `INT64` [LongDictionaryEncoder] — the 8-byte little-endian `PLAIN` layout of a `DOUBLE`
/// and of its `INT64` bits is identical, so the dictionary body is encoded straight from the
/// bits.
final class DoubleValueEncoder extends ValueEncoder {

    private double[] plain;      // this chunk's stored values, grown as the row group fills
    private int plainCount;
    private final double[] window;
    private final LongDictionaryEncoder dictionary; // null when dictionary encoding is disabled
    private DoubleStatisticsCollector statistics = new DoubleStatisticsCollector();

    private DoubleColumnSource source;
    private int size;
    private int windowBase;
    private int windowLength;

    DoubleValueEncoder(int pageValues, boolean buildDictionary) {
        this.plain = new double[Math.max(1, pageValues)];
        this.window = new double[Math.max(1, pageValues)];
        this.dictionary = buildDictionary ? new LongDictionaryEncoder() : null;
    }

    @Override
    void reset(ColumnSource source) {
        this.source = (DoubleColumnSource) source;
        this.size = source.size();
        this.windowBase = 0;
        this.windowLength = 0;
    }

    private double valueAt(int index) {
        if (index >= windowBase + windowLength) {
            windowBase = index;
            windowLength = Math.min(window.length, size - index);
            source.copyInto(windowBase, window, 0, windowLength);
        }
        return window[index - windowBase];
    }

    @Override
    boolean dictionaryCapable() {
        return dictionary != null;
    }

    @Override
    int intern(int valueIndex) {
        long bits = Double.doubleToRawLongBits(valueAt(valueIndex));
        int index = dictionary.indexOf(bits);
        return index >= 0 ? index : dictionary.add(bits);
    }

    @Override
    int dictionarySize() {
        return dictionary.size();
    }

    @Override
    long exactDistinctCount() {
        return dictionary != null ? dictionary.size() : UNKNOWN_DISTINCT_COUNT;
    }

    @Override
    byte[] encodeDictionaryBody() {
        return PlainEncoder.encodeLongs(dictionary.values(), 0, dictionary.size());
    }

    @Override
    void startChunk() {
        statistics = new DoubleStatisticsCollector();
        if (dictionary != null) {
            dictionary.clear();
        }
        plainCount = 0;
    }

    @Override
    void store(int valueIndex) {
        append(valueAt(valueIndex));
    }

    @Override
    void storeDictionaryValue(int dictionaryIndex) {
        append(Double.longBitsToDouble(dictionary.values()[dictionaryIndex]));
    }

    @Override
    long dictionaryPlainBytes() {
        return (long) dictionary.size() * Long.BYTES;
    }

    @Override
    void dropDictionary() {
        dictionary.clear();
    }

    private void append(double value) {
        if (plainCount == plain.length) {
            plain = Arrays.copyOf(plain, grownCapacity(plain.length));
        }
        plain[plainCount++] = value;
    }

    @Override
    void encodeInto(ByteArrayBuilder out, ColumnEncoding encoding, int from, int count) {
        int at = out.reserve(PlainEncoder.fixedWidthLength(count, Double.BYTES));
        switch (encoding) {
            case PLAIN -> PlainEncoder.encodeDoubles(plain, from, count, out.array(), at);
            case BYTE_STREAM_SPLIT -> ByteStreamSplitEncoder.splitDoubles(plain, from, count, out.array(), at);
            default -> throw unsupported(encoding, PhysicalType.DOUBLE);
        }
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
        return Double.SIZE;
    }
}
