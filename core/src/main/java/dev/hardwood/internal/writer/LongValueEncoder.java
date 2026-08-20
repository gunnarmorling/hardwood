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
import dev.hardwood.internal.encoding.DeltaBinaryPackedEncoder;
import dev.hardwood.internal.encoding.LongDictionaryEncoder;
import dev.hardwood.internal.encoding.PlainEncoder;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.writer.ColumnEncoding;

/// [ValueEncoder] for `INT64` columns.
final class LongValueEncoder extends ValueEncoder {

    private long[] plain;      // this chunk's stored values, grown as the row group fills
    private int plainCount;
    private final long[] window;
    private final LongDictionaryEncoder dictionary; // null when dictionary encoding is disabled
    private final boolean unsignedOrder;
    private LongStatisticsCollector statistics;

    private LongColumnSource source;
    private int size;
    private int windowBase;
    private int windowLength;

    LongValueEncoder(int pageValues, boolean buildDictionary, boolean unsignedOrder) {
        this.unsignedOrder = unsignedOrder;
        this.statistics = new LongStatisticsCollector(unsignedOrder);
        this.plain = new long[Math.max(1, pageValues)];
        this.window = new long[Math.max(1, pageValues)];
        this.dictionary = buildDictionary ? new LongDictionaryEncoder() : null;
    }

    @Override
    void reset(ColumnSource source) {
        this.source = (LongColumnSource) source;
        this.size = source.size();
        this.windowBase = 0;
        this.windowLength = 0;
    }

    private long valueAt(int index) {
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
        long value = valueAt(valueIndex);
        int index = dictionary.indexOf(value);
        return index >= 0 ? index : dictionary.add(value);
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
        statistics = new LongStatisticsCollector(unsignedOrder);
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
        append(dictionary.values()[dictionaryIndex]);
    }

    @Override
    long dictionaryPlainBytes() {
        return (long) dictionary.size() * Long.BYTES;
    }

    @Override
    void dropDictionary() {
        dictionary.clear();
    }

    private void append(long value) {
        if (plainCount == plain.length) {
            plain = Arrays.copyOf(plain, grownCapacity(plain.length));
        }
        plain[plainCount++] = value;
    }

    @Override
    byte[] encode(ColumnEncoding encoding, int from, int count) {
        return switch (encoding) {
            case PLAIN -> PlainEncoder.encodeLongs(plain, from, count);
            case DELTA_BINARY_PACKED -> DeltaBinaryPackedEncoder.encodeLongs(plain, from, count);
            case BYTE_STREAM_SPLIT -> ByteStreamSplitEncoder.encode(
                    PlainEncoder.encodeLongs(plain, from, count), 0, count, Long.BYTES);
            default -> throw unsupported(encoding, PhysicalType.INT64);
        };
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
        return Long.SIZE;
    }
}
