/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.metadata.Statistics;

/// Writer for the Thrift Statistics struct, the inverse of [StatisticsReader].
///
/// Emits the null count (field 3), the distinct count (field 4) where it is known exactly,
/// and the preferred `min_value` / `max_value`
/// (fields 6 / 5) — the sort-order-correct bounds modern readers prefer over the
/// deprecated `min` / `max` (fields 2 / 1), which are not written. A `min` / `max`
/// that is absent (a fully null column) is omitted; the null count is always written.
///
/// Each written bound carries its exactness via `is_max_value_exact` / `is_min_value_exact`
/// (fields 7 / 8), taken from the [Statistics]: a fixed-width bound and an untruncated
/// `BYTE_ARRAY` bound are exact (`true`), while a truncated `BYTE_ARRAY` bound is inexact
/// (`false`) and only brackets the true extreme. A reader may use `min_value == max_value` to
/// prove a whole chunk equals a single value only when both bounds are flagged exact.
///
/// The NaN count (field 9) is written whenever the [Statistics] carries one, which for this
/// writer means every `FLOAT`, `DOUBLE` and `FLOAT16` column chunk — the format requires it
/// there, even when it is zero. Every other type leaves it unset.
public class StatisticsWriter {

    public static void write(ThriftCompactWriter writer, Statistics statistics) {
        short saved = writer.pushFieldIdContext();
        try {
            // 3: null_count
            if (statistics.nullCount() != null) {
                writer.writeFieldBegin(3, ThriftCompactConstants.FieldType.I64);
                writer.writeI64(statistics.nullCount());
            }

            // 4: distinct_count, written only where the writer knows it exactly — the spec asks
            // for the count of distinct values occurring, not an estimate of it.
            if (statistics.distinctCount() != null) {
                writer.writeFieldBegin(4, ThriftCompactConstants.FieldType.I64);
                writer.writeI64(statistics.distinctCount());
            }

            // 5: max_value (preferred over deprecated field 1)
            if (statistics.maxValue() != null) {
                writer.writeFieldBegin(5, ThriftCompactConstants.FieldType.BINARY);
                writer.writeBinary(statistics.maxValue());
            }

            // 6: min_value (preferred over deprecated field 2)
            if (statistics.minValue() != null) {
                writer.writeFieldBegin(6, ThriftCompactConstants.FieldType.BINARY);
                writer.writeBinary(statistics.minValue());
            }

            // 7: is_max_value_exact — whether the stored max_value is the actual maximum or a
            // truncated upper bound. A Thrift compact bool carries its value in the field-type
            // nibble, so there is no body.
            if (statistics.maxValue() != null) {
                writer.writeFieldBegin(7, statistics.isMaxValueExact()
                        ? ThriftCompactConstants.FieldType.BOOLEAN_TRUE
                        : ThriftCompactConstants.FieldType.BOOLEAN_FALSE);
            }

            // 8: is_min_value_exact — whether the stored min_value is the actual minimum or a
            // truncated lower bound.
            if (statistics.minValue() != null) {
                writer.writeFieldBegin(8, statistics.isMinValueExact()
                        ? ThriftCompactConstants.FieldType.BOOLEAN_TRUE
                        : ThriftCompactConstants.FieldType.BOOLEAN_FALSE);
            }

            // 9: nan_count — mandatory under TYPE_ORDER for FLOAT, DOUBLE and FLOAT16, even when
            // zero; a recorded zero is the only thing that proves a chunk holds no NaN. Absent for
            // every other type.
            if (statistics.nanCount() != null) {
                writer.writeFieldBegin(9, ThriftCompactConstants.FieldType.I64);
                writer.writeI64(statistics.nanCount());
            }

            writer.writeFieldStop();
        }
        finally {
            writer.popFieldIdContext(saved);
        }
    }
}
