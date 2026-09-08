/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.conversion;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;

import static org.assertj.core.api.Assertions.assertThat;

/// Which annotations a column can carry, as [LogicalTypeConverter#conversionFault] answers it.
///
/// The answer must match what the conversions themselves accept: too lenient and a column
/// reaches a conversion that cannot decode it, too strict and a file that reads today stops
/// opening. `LogicalTypeValidator` states the writer's rule, which is deliberately stricter,
/// and the cases below pin the two apart where they differ.
class ConversionFaultTest {

    @Test
    void anAnnotationItsPhysicalTypeCarriesHasNoFault() {
        assertThat(fault(PhysicalType.BYTE_ARRAY, null, new LogicalType.StringType())).isNull();
        assertThat(fault(PhysicalType.INT32, null, new LogicalType.DateType())).isNull();
        assertThat(fault(PhysicalType.INT64, null, timestamp())).isNull();
        assertThat(fault(PhysicalType.FIXED_LEN_BYTE_ARRAY, 2, new LogicalType.Float16Type())).isNull();
        assertThat(fault(PhysicalType.FIXED_LEN_BYTE_ARRAY, 16, new LogicalType.UuidType())).isNull();
        assertThat(fault(PhysicalType.FIXED_LEN_BYTE_ARRAY, 12, new LogicalType.IntervalType())).isNull();
    }

    @Test
    void anUnannotatedColumnHasNoFault() {
        assertThat(fault(PhysicalType.INT64, null, null)).isNull();
    }

    @Test
    void anAnnotationItsPhysicalTypeCannotCarryIsFaulted() {
        assertThat(fault(PhysicalType.INT64, null, new LogicalType.DateType()))
                .isEqualTo("DATE is read from INT32, but the column is INT64");
        assertThat(fault(PhysicalType.INT32, null, new LogicalType.StringType()))
                .isEqualTo("STRING is read from BYTE_ARRAY, but the column is INT32");
    }

    @Test
    void aFixedWidthAnnotationOnTheWrongWidthIsFaulted() {
        assertThat(fault(PhysicalType.FIXED_LEN_BYTE_ARRAY, 3, new LogicalType.Float16Type()))
                .isEqualTo("FLOAT16 is exactly 2 bytes, but the column declares 3");
        assertThat(fault(PhysicalType.FIXED_LEN_BYTE_ARRAY, 8, new LogicalType.UuidType()))
                .isEqualTo("UUID is exactly 16 bytes, but the column declares 8");
    }

    /// The wrong physical type is reported before the width, because a column that is not
    /// fixed-width has no width to be wrong about.
    @Test
    void theWrongPhysicalTypeIsReportedAheadOfTheWidth() {
        assertThat(fault(PhysicalType.INT64, null, new LogicalType.Float16Type()))
                .isEqualTo("FLOAT16 is read from FIXED_LEN_BYTE_ARRAY, but the column is INT64");
    }

    /// Reading accepts either width for `TIME` and `INT` whatever the unit or bit width,
    /// where the writer pins `TIME(MILLIS)` to `INT32`. Applying the writer's rule here
    /// would refuse files that read today.
    @Test
    void readingIsLenientWhereWritingIsStrict() {
        assertThat(fault(PhysicalType.INT64, null,
                new LogicalType.TimeType(true, LogicalType.TimeUnit.MILLIS))).isNull();
        assertThat(fault(PhysicalType.INT64, null, new LogicalType.IntType(8, true))).isNull();
    }

    /// Geometry and geography payloads are carried through untouched, so no physical type
    /// is imposed on them.
    @Test
    void anOpaquePayloadImposesNoPhysicalType() {
        assertThat(fault(PhysicalType.INT32, null, new LogicalType.GeometryType(null))).isNull();
    }

    private static String fault(PhysicalType type, Integer typeLength, LogicalType logicalType) {
        return LogicalTypeConverter.conversionFault(type, typeLength, logicalType);
    }

    private static LogicalType.TimestampType timestamp() {
        return new LogicalType.TimestampType(true, LogicalType.TimeUnit.MILLIS);
    }
}
