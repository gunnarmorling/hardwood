/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

/// Schema element in Parquet file metadata.
///
/// @param name column or group name
/// @param type physical type of this element, or `null` for group nodes
/// @param typeLength byte length of the values for [PhysicalType#FIXED_LEN_BYTE_ARRAY]; for any other
///         physical type, it denotes the maximum bit length used to store a value; or, `null` when the field is absent
/// @param repetitionType repetition level (required, optional, or repeated), or `null` when the field is
///         absent, which a root element may be
/// @param numChildren number of child elements for group nodes, or `null` for primitive nodes
/// @param convertedType legacy converted type annotation, or `null` if absent
/// @param scale decimal scale (number of digits after the decimal point), or `null` if not a decimal
/// @param precision decimal precision (total number of digits), or `null` if not a decimal
/// @param fieldId Thrift field id from the schema, or `null` if absent
/// @param logicalType logical type annotation, or `null` if absent
/// @see <a href="https://parquet.apache.org/docs/file-format/metadata/#file-metadata">File Format – File Metadata</a>
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public record SchemaElement(
        String name,
        PhysicalType type,
        Integer typeLength,
        RepetitionType repetitionType,
        Integer numChildren,
        ConvertedType convertedType,
        Integer scale,
        Integer precision,
        Integer fieldId,
        LogicalType logicalType) {

    /// Creates a root group element.
    ///
    /// @param name        group name; the root group is named `"schema"` by convention
    /// @param numChildren number of child elements, zero or more
    /// @return the group element
    /// @throws IllegalArgumentException if `numChildren` is negative
    public static SchemaElement root(String name, int numChildren) {
        return group(name, null, numChildren, null);
    }

    /// Creates a group element.
    ///
    /// @param name group name
    /// @param repetitionType repetition level
    /// @param numChildren number of child elements, zero or more
    /// @return the group element
    /// @throws IllegalArgumentException if `numChildren` is negative
    public static SchemaElement group(String name, RepetitionType repetitionType, int numChildren) {
        return group(name, repetitionType, numChildren, null);
    }

    /// Creates a group element.
    ///
    /// @param name group name
    /// @param repetitionType nullable repetition level
    /// @param numChildren number of child elements, zero or more
    /// @param logicalType logical type annotation, or `null` for none
    /// @return the group element
    /// @throws IllegalArgumentException if `numChildren` is negative
    public static SchemaElement group(String name, RepetitionType repetitionType, int numChildren,
            LogicalType logicalType) {
        if (numChildren < 0) {
            String s = "Group " + name + " requires a child count of zero or more, not " + numChildren;
            throw new IllegalArgumentException(s);
        }
        return new SchemaElement(name, null, null, repetitionType, numChildren, null, null, null, null, logicalType);
    }

    /// Creates a primitive element
    ///
    /// @param name column name
    /// @param type physical type
    /// @param repetitionType nullable repetition level
    /// @return the primitive element
    /// @throws IllegalArgumentException if `type` is `null`, or is
    ///         [PhysicalType#FIXED_LEN_BYTE_ARRAY], which needs a width
    public static SchemaElement primitive(String name, PhysicalType type, RepetitionType repetitionType) {
        return primitive(name, type, repetitionType, null);
    }

    /// Creates a primitive element.
    ///
    /// @param name column name
    /// @param type physical type
    /// @param repetitionType nullable repetition level
    /// @param logicalType nullable logical type annotation
    /// @return the primitive element
    /// @throws IllegalArgumentException if `type` is `null`, or is
    ///         [PhysicalType#FIXED_LEN_BYTE_ARRAY], which needs a width
    public static SchemaElement primitive(String name, PhysicalType type, RepetitionType repetitionType,
            LogicalType logicalType) {
        if (type == null) {
            throw new IllegalArgumentException(
                    "Primitive element " + name + " requires a physical type; a null type denotes a group");
        }
        if (type == PhysicalType.FIXED_LEN_BYTE_ARRAY) {
            throw new IllegalArgumentException("FIXED_LEN_BYTE_ARRAY column " + name
                    + " requires a positive type length; use fixedLengthPrimitive instead");
        }
        return new SchemaElement(name, type, null, repetitionType, null, null, null, null, null, logicalType);
    }

    /// Creates a [PhysicalType#FIXED_LEN_BYTE_ARRAY] element.
    ///
    /// @param name column name
    /// @param typeLength fixed byte length: must be positive
    /// @param repetitionType nullable repetition level
    /// @return the fixed-length primitive element
    /// @throws IllegalArgumentException if `typeLength` is not positive
    public static SchemaElement fixedLengthPrimitive(String name, int typeLength, RepetitionType repetitionType) {
        return fixedLengthPrimitive(name, typeLength, repetitionType, null);
    }

    /// Creates a [PhysicalType#FIXED_LEN_BYTE_ARRAY] element.
    ///
    /// @param name column name
    /// @param typeLength fixed byte length: must be positive
    /// @param repetitionType nullable repetition level
    /// @param logicalType nullable logical type annotation
    /// @return the fixed-length primitive element
    /// @throws IllegalArgumentException if `typeLength` is not positive
    public static SchemaElement fixedLengthPrimitive(String name, int typeLength, RepetitionType repetitionType,
            LogicalType logicalType) {
        if (typeLength <= 0) {
            String s = "FIXED_LEN_BYTE_ARRAY column " + name + " requires a positive type length, not " + typeLength;
            throw new IllegalArgumentException(s);
        }
        return new SchemaElement(name, PhysicalType.FIXED_LEN_BYTE_ARRAY, typeLength, repetitionType, null, null, null,
                null, null, logicalType);
    }

    /// Returns a copy of this element carrying `typeLength`.
    ///
    /// The field has two meanings, one per physical type. For
    /// [PhysicalType#FIXED_LEN_BYTE_ARRAY] it is the byte length of every value, which
    /// [#fixedLengthPrimitive] sets directly. For every other physical type it is the
    /// maximum bit length used to store a value, and this method is the only way to set it:
    ///
    /// ```java
    /// // a low-cardinality INT32 whose values fit in 3 bits
    /// primitive("tag", PhysicalType.INT32, RepetitionType.REQUIRED).withTypeLength(3)
    /// ```
    ///
    /// @param typeLength the byte length or the maximum bit length, one or more
    /// @return a copy of this element with `typeLength` set
    /// @throws IllegalArgumentException if `typeLength` is not positive, or this element is a group
    public SchemaElement withTypeLength(int typeLength) {
        if (typeLength <= 0) {
            String s = "Column " + name + " requires a positive type length, not " + typeLength;
            throw new IllegalArgumentException(s);
        }
        if (isGroup()) {
            throw new IllegalArgumentException("Group " + name + " cannot carry a type length");
        }
        return new SchemaElement(name, type, typeLength, repetitionType, numChildren, convertedType, scale, precision,
                fieldId, logicalType);
    }

    /// Returns `true` if this element is a group node (has no physical type).
    public boolean isGroup() {
        return type == null;
    }

    /// Returns `true` if this element is a primitive node (has a physical type).
    public boolean isPrimitive() {
        return type != null;
    }
}
