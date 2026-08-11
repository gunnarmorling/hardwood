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

/// Decides whether a column chunk's `min` / `max` bounds are well defined.
///
/// A column's sort order comes from its logical type where it has one, and from its physical
/// type otherwise, so an annotation can call for an ordering different from the one the
/// physical-type collector implements. Where it does, the bounds are dropped and only the null
/// count is written: a reader that finds no bound prunes nothing, whereas a bound computed in
/// the wrong order would prune away live rows.
///
/// Two groups of columns have no bounds:
///
/// - **Undefined order.** parquet-format leaves the ordering of `INTERVAL`, `UNKNOWN`,
///   `VARIANT`, `GEOMETRY`, `GEOGRAPHY`, `LIST`, and `MAP` undefined, and states for `INTERVAL`
///   that no `min` / `max` should be written at all. These never gain bounds.
/// - **Orders the collectors do not implement yet.** Unsigned integers, `DECIMAL` over a binary
///   physical type (signed big-endian two's complement, not the collector's unsigned
///   lexicographic order), and `FLOAT16`.
final class StatisticsOrder {

    /// Whether this column's `min` / `max` may be written, i.e. whether the collector selected
    /// for its physical type computes bounds in the column's own sort order.
    static boolean supportsBounds(ColumnSchema column) {
        LogicalType logicalType = column.logicalType();
        if (logicalType == null) {
            return true;
        }
        return switch (logicalType) {
            // Unsigned comparison, unlike the signed integer collectors.
            case LogicalType.IntType integer -> integer.isSigned();
            // Signed big-endian two's complement over a binary type, unlike the collector's
            // unsigned lexicographic order; over an integer type it is the collector's order.
            case LogicalType.DecimalType ignored ->
                    column.type() == PhysicalType.INT32 || column.type() == PhysicalType.INT64;
            // Comparison of the represented half-precision value, not of the raw bytes.
            case LogicalType.Float16Type ignored -> false;
            // Undefined order.
            case LogicalType.IntervalType ignored -> false;
            case LogicalType.NullType ignored -> false;
            case LogicalType.VariantType ignored -> false;
            case LogicalType.GeometryType ignored -> false;
            case LogicalType.GeographyType ignored -> false;
            case LogicalType.ListType ignored -> false;
            case LogicalType.MapType ignored -> false;
            // The collector's order is the column's order: signed for the integer-backed
            // annotations, unsigned lexicographic for the string-like ones.
            case LogicalType.DateType ignored -> true;
            case LogicalType.TimeType ignored -> true;
            case LogicalType.TimestampType ignored -> true;
            case LogicalType.StringType ignored -> true;
            case LogicalType.EnumType ignored -> true;
            case LogicalType.JsonType ignored -> true;
            case LogicalType.BsonType ignored -> true;
            case LogicalType.UuidType ignored -> true;
        };
    }

    /// The statistics with the bounds and their exactness flags dropped, keeping the null count.
    static Statistics withoutBounds(Statistics statistics) {
        return new Statistics(null, null, statistics.nullCount(), statistics.distinctCount(), false);
    }

    private StatisticsOrder() {
    }
}
