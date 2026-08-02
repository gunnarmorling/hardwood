/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.predicate.dictionary;

import java.util.Arrays;

import dev.hardwood.internal.reader.Dictionary;

/// Shared utilities for evaluating equality / membership predicates against a row group's
/// dictionaries.
public final class DictionaryFilterSupport {

    private DictionaryFilterSupport() {
    }

    public static boolean valueAbsent(RowGroupDictionaryFilterSource dictionaries, int columnIndex, int value) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.IntDictionary dict)) {
            return false;
        }

        for (int entry : dict.values()) {
            if (entry == value) {
                return false;
            }
        }

        return true;
    }

    public static boolean valueAbsent(RowGroupDictionaryFilterSource dictionaries, int columnIndex, long value) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.LongDictionary dict)) {
            return false;
        }
        for (long entry : dict.values()) {
            if (entry == value) {
                return false;
            }
        }
        return true;
    }

    /// Single-value dictionary check for `FLOAT` values.
    ///
    /// A dictionary holds the column chunk's exact stored values, so `Float.compare` — the same
    /// total order every `FLOAT` matcher applies — decides membership exactly. `±0` needs no
    /// carve-out: a dictionary holding only `-0.0f` proves `+0.0f` absent.
    public static boolean valueAbsent(RowGroupDictionaryFilterSource dictionaries, int columnIndex, float value) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.FloatDictionary dict)) {
            return false;
        }
        for (float entry : dict.values()) {
            if (Float.compare(entry, value) == 0) {
                return false;
            }
        }
        return true;
    }

    /// Single-value dictionary check for `DOUBLE` values. See the `FLOAT` overload for why `±0` and
    /// `NaN` need no special handling.
    public static boolean valueAbsent(RowGroupDictionaryFilterSource dictionaries, int columnIndex, double value) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.DoubleDictionary dict)) {
            return false;
        }
        for (double entry : dict.values()) {
            if (Double.compare(entry, value) == 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean valueAbsent(RowGroupDictionaryFilterSource dictionaries, int columnIndex, byte[] value) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.ByteArrayDictionary dict)) {
            return false;
        }
        for (byte[] entry : dict.values()) {
            if (Arrays.equals(entry, value)) {
                return false;
            }
        }
        return true;
    }

    public static boolean absentAll(RowGroupDictionaryFilterSource dictionaries, int columnIndex, int[] values) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.IntDictionary dict)) {
            return false;
        }
        for (int entry : dict.values()) {
            for (int value : values) {
                if (entry == value) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean absentAll(RowGroupDictionaryFilterSource dictionaries, int columnIndex, long[] values) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.LongDictionary dict)) {
            return false;
        }
        for (long entry : dict.values()) {
            for (long value : values) {
                if (entry == value) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean absentAll(RowGroupDictionaryFilterSource dictionaries, int columnIndex, byte[][] values) {
        if (dictionaries == null
                || !(dictionaries.forColumn(columnIndex) instanceof Dictionary.ByteArrayDictionary dict)) {
            return false;
        }
        for (byte[] entry : dict.values()) {
            for (byte[] value : values) {
                if (Arrays.equals(entry, value)) {
                    return false;
                }
            }
        }
        return true;
    }

}
