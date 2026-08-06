/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import dev.hardwood.internal.encoding.BinaryDictionaryEncoder;
import dev.hardwood.internal.encoding.PlainEncoder;
import dev.hardwood.metadata.Statistics;

/// [ValueEncoder] for binary columns. Serves both `BYTE_ARRAY` (variable length, a 4-byte
/// length prefix per value) and `FIXED_LEN_BYTE_ARRAY` (a schema-declared fixed width, no
/// prefix), selected by `typeLength` (`null` for `BYTE_ARRAY`). Values intern through a
/// content-keyed [BinaryDictionaryEncoder], and statistics use unsigned lexicographic order with
/// `BYTE_ARRAY` bound truncation.
final class BinaryValueEncoder extends ValueEncoder {

    private final byte[][] plain;
    private final byte[][] window;
    private final BinaryDictionaryEncoder dictionary; // null when dictionary encoding is disabled
    private final BinaryStatisticsCollector statistics;
    private final Integer typeLength; // null for BYTE_ARRAY, the fixed width for FIXED_LEN_BYTE_ARRAY

    private BinaryColumnSource source;
    private int size;
    private int windowBase;
    private int windowLength;

    BinaryValueEncoder(int pageValues, boolean enableDictionary, Integer typeLength,
                       int statisticsTruncationLength) {
        this.plain = new byte[pageValues][];
        this.window = new byte[pageValues][];
        this.dictionary = enableDictionary ? new BinaryDictionaryEncoder() : null;
        // FIXED_LEN_BYTE_ARRAY bounds are written whole and always exact — a fixed width already
        // bounds them — so only BYTE_ARRAY truncates. Integer.MAX_VALUE disables truncation, since
        // no value can be longer than that.
        this.statistics = new BinaryStatisticsCollector(
                typeLength == null ? statisticsTruncationLength : Integer.MAX_VALUE);
        this.typeLength = typeLength;
    }

    private boolean fixedLength() {
        return typeLength != null;
    }

    @Override
    void reset(ColumnSource source) {
        this.source = (BinaryColumnSource) source;
        this.size = source.size();
        this.windowBase = 0;
        this.windowLength = 0;
    }

    private byte[] valueAt(int index) {
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
    int intern(int valueIndex, long dictionaryLimitBytes) {
        byte[] value = valueAt(valueIndex);
        int index = dictionary.indexOf(value);
        if (index >= 0) {
            return index;
        }
        // The dictionary body is PLAIN: a 4-byte length prefix per BYTE_ARRAY value, raw bytes
        // for FIXED_LEN_BYTE_ARRAY. Size the fallback trigger against that framed body.
        long framedBytes = dictionary.contentBytes() + (fixedLength() ? 0L : (long) Integer.BYTES * dictionary.size());
        long newValueBytes = value.length + (fixedLength() ? 0L : Integer.BYTES);
        if (framedBytes + newValueBytes > dictionaryLimitBytes) {
            return DICTIONARY_OVERFLOW;
        }
        return dictionary.add(value);
    }

    @Override
    int dictionarySize() {
        return dictionary.size();
    }

    @Override
    byte[] encodeDictionaryBody() {
        return fixedLength()
                ? PlainEncoder.encodeFixedLenByteArrays(dictionary.values(), 0, dictionary.size(), typeLength)
                : PlainEncoder.encodeByteArrays(dictionary.values(), 0, dictionary.size());
    }

    @Override
    void appendPlain(int slot, int valueIndex) {
        plain[slot] = valueAt(valueIndex);
    }

    @Override
    byte[] encodePlain(int count) {
        return fixedLength()
                ? PlainEncoder.encodeFixedLenByteArrays(plain, 0, count, typeLength)
                : PlainEncoder.encodeByteArrays(plain, 0, count);
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
        return fixedLength() ? (long) typeLength * Byte.SIZE
                : (long) (Integer.BYTES + valueAt(valueIndex).length) * Byte.SIZE;
    }
}
