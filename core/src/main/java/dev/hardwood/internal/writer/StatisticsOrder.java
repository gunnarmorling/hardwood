/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.schema.ColumnSchema;

/// Decides whether a column chunk's `min` / `max` bounds are well defined.
///
/// A column's sort order comes from its logical type where it has one, and from its physical
/// type otherwise. Most annotations name an order some collector computes — [ValueEncoder]
/// selects it — but parquet-format leaves the ordering of `INTERVAL`, `UNKNOWN`, `VARIANT`,
/// `GEOMETRY`, `GEOGRAPHY`, `LIST`, and `MAP` undefined, and states for `INTERVAL` that no
/// `min` / `max` should be written at all. Those columns write their null count alone: a reader
/// that finds no bound prunes nothing, whereas a bound in an order the reader cannot know would
/// prune away live rows.
final class StatisticsOrder {

    /// Whether this column's `min` / `max` may be written, i.e. whether its sort order is
    /// defined at all.
    static boolean supportsBounds(ColumnSchema column) {
        LogicalType logicalType = column.logicalType();
        if (logicalType == null) {
            return true;
        }
        return switch (logicalType) {
            // Undefined order.
            case LogicalType.IntervalType ignored -> false;
            case LogicalType.NullType ignored -> false;
            case LogicalType.VariantType ignored -> false;
            case LogicalType.GeometryType ignored -> false;
            case LogicalType.GeographyType ignored -> false;
            case LogicalType.ListType ignored -> false;
            case LogicalType.MapType ignored -> false;
            // Every remaining annotation names an order a collector computes.
            case LogicalType.IntType ignored -> true;
            case LogicalType.DecimalType ignored -> true;
            case LogicalType.Float16Type ignored -> true;
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
