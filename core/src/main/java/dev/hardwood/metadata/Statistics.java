/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

/// Column chunk statistics for min/max values, null count, and distinct count.
///
/// Used for predicate push-down: row groups whose statistics prove that no rows
/// can match a filter predicate are skipped entirely.
///
/// The `min` / `max` bounds may be **truncated** approximations for long `BYTE_ARRAY` values,
/// in which case `isMinValueExact` / `isMaxValueExact` is `false` and the stored bound only
/// brackets the true extreme (a truncated `minValue` is `<=` every value, a truncated
/// `maxValue` is `>=` every value). A fixed-width bound is always exact.
///
/// @param minValue minimum value encoded as raw bytes (little-endian), or `null` if absent
/// @param maxValue maximum value encoded as raw bytes (little-endian), or `null` if absent
/// @param nullCount number of null values in the column chunk, or `null` if absent
/// @param distinctCount number of distinct values in the column chunk, or `null` if absent
/// @param isMinMaxDeprecated whether the bounds came from the deprecated `min` / `max` fields
/// @param isMinValueExact whether `minValue` is the exact minimum (not a truncated lower bound)
/// @param isMaxValueExact whether `maxValue` is the exact maximum (not a truncated upper bound)
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public record Statistics(
        byte[] minValue,
        byte[] maxValue,
        Long nullCount,
        Long distinctCount,
        boolean isMinMaxDeprecated,
        boolean isMinValueExact,
        boolean isMaxValueExact) {

    /// Statistics whose bounds are exact — the common case for every fixed-width type and any
    /// untruncated bound.
    public Statistics(byte[] minValue, byte[] maxValue, Long nullCount, Long distinctCount,
                      boolean isMinMaxDeprecated) {
        this(minValue, maxValue, nullCount, distinctCount, isMinMaxDeprecated, true, true);
    }
}
