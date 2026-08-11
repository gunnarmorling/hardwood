/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.LogicalType.TimeUnit;

/// Writer for the `LogicalType` union to Thrift Compact Protocol, the inverse of
/// [LogicalTypeReader].
///
/// A Thrift union is a struct with exactly one field set: the field id selects the member and
/// the value is the member's struct, empty for the unparameterized types. Every nested struct
/// brackets its fields with a field-id context, because compact-protocol field ids are
/// delta-encoded per struct.
///
/// `INTERVAL` has no union member — parquet.thrift reserves field 9 for it but never defined
/// the struct — so it is annotated by the legacy `converted_type` alone and is rejected here.
public class LogicalTypeWriter {

    public static void write(ThriftCompactWriter writer, LogicalType logicalType) {
        short saved = writer.pushFieldIdContext();
        try {
            writeMember(writer, logicalType);
            writer.writeFieldStop();
        }
        finally {
            writer.popFieldIdContext(saved);
        }
    }

    private static void writeMember(ThriftCompactWriter writer, LogicalType logicalType) {
        switch (logicalType) {
            case LogicalType.StringType ignored -> writeEmpty(writer, 1);
            case LogicalType.MapType ignored -> writeEmpty(writer, 2);
            case LogicalType.ListType ignored -> writeEmpty(writer, 3);
            case LogicalType.EnumType ignored -> writeEmpty(writer, 4);
            case LogicalType.DecimalType decimal -> writeDecimal(writer, decimal);
            case LogicalType.DateType ignored -> writeEmpty(writer, 6);
            case LogicalType.TimeType time -> writeTimeStruct(writer, 7, time.isAdjustedToUTC(), time.unit());
            case LogicalType.TimestampType stamp ->
                    writeTimeStruct(writer, 8, stamp.isAdjustedToUTC(), stamp.unit());
            case LogicalType.IntType integer -> writeInt(writer, integer);
            case LogicalType.NullType ignored -> writeEmpty(writer, 11);
            case LogicalType.JsonType ignored -> writeEmpty(writer, 12);
            case LogicalType.BsonType ignored -> writeEmpty(writer, 13);
            case LogicalType.UuidType ignored -> writeEmpty(writer, 14);
            case LogicalType.Float16Type ignored -> writeEmpty(writer, 15);
            case LogicalType.VariantType variant -> writeVariant(writer, variant);
            case LogicalType.GeometryType geometry -> writeGeometry(writer, geometry);
            case LogicalType.GeographyType geography -> writeGeography(writer, geography);
            case LogicalType.IntervalType ignored -> throw new IllegalArgumentException(
                    "INTERVAL has no LogicalType union member and is written as the legacy "
                            + "converted_type only");
        }
    }

    /// Writes an unparameterized member: the union field id carrying an empty struct.
    private static void writeEmpty(ThriftCompactWriter writer, int fieldId) {
        writer.writeFieldBegin(fieldId, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    private static void writeDecimal(ThriftCompactWriter writer, LogicalType.DecimalType decimal) {
        writer.writeFieldBegin(5, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writer.writeFieldBegin(1, FieldType.I32);
        writer.writeI32(decimal.scale());
        writer.writeFieldBegin(2, FieldType.I32);
        writer.writeI32(decimal.precision());
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    /// `TimeType` (union field 7) and `TimestampType` (union field 8) are structurally
    /// identical: a UTC-adjustment flag and a unit.
    private static void writeTimeStruct(ThriftCompactWriter writer, int fieldId,
                                        boolean isAdjustedToUTC, TimeUnit unit) {
        writer.writeFieldBegin(fieldId, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writer.writeBool(1, isAdjustedToUTC);
        writer.writeFieldBegin(2, FieldType.STRUCT);
        short unitContext = writer.pushFieldIdContext();
        writeEmpty(writer, unitFieldId(unit));
        writer.writeFieldStop();
        writer.popFieldIdContext(unitContext);
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    private static int unitFieldId(TimeUnit unit) {
        return switch (unit) {
            case MILLIS -> 1;
            case MICROS -> 2;
            case NANOS -> 3;
        };
    }

    private static void writeInt(ThriftCompactWriter writer, LogicalType.IntType integer) {
        writer.writeFieldBegin(10, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writer.writeFieldBegin(1, FieldType.BYTE);
        writer.writeByte((byte) integer.bitWidth());
        writer.writeBool(2, integer.isSigned());
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    private static void writeVariant(ThriftCompactWriter writer, LogicalType.VariantType variant) {
        writer.writeFieldBegin(16, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writer.writeFieldBegin(1, FieldType.BYTE);
        writer.writeByte(toSpecVersionByte(variant.specVersion()));
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    /// The spec version is an `i8` on the wire, so a version that does not fit is a caller
    /// error rather than a value silently truncated into a different version.
    private static byte toSpecVersionByte(int specVersion) {
        if (specVersion > Byte.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Variant specification version does not fit in an i8: " + specVersion);
        }
        return (byte) specVersion;
    }

    private static void writeGeometry(ThriftCompactWriter writer, LogicalType.GeometryType geometry) {
        writer.writeFieldBegin(17, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writeCrs(writer, geometry.crs());
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    private static void writeGeography(ThriftCompactWriter writer, LogicalType.GeographyType geography) {
        writer.writeFieldBegin(18, FieldType.STRUCT);
        short saved = writer.pushFieldIdContext();
        writeCrs(writer, geography.crs());
        if (geography.edgeInterpolation() != null) {
            // `algorithm` is a Thrift enum, not a union, so it is an i32 of the enum's value.
            writer.writeFieldBegin(2, FieldType.I32);
            writer.writeI32(ThriftEnumLookup.thriftValue(geography.edgeInterpolation()));
        }
        writer.writeFieldStop();
        writer.popFieldIdContext(saved);
    }

    /// The CRS is optional; an absent one is omitted, and the reader substitutes the
    /// `OGC:CRS84` default.
    private static void writeCrs(ThriftCompactWriter writer, String crs) {
        if (crs != null) {
            writer.writeFieldBegin(1, FieldType.BINARY);
            writer.writeString(crs);
        }
    }

}
