/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.function.Consumer;

import dev.hardwood.Experimental;
import dev.hardwood.row.PqInterval;

/// Sets the fields of one struct — a record being written, or a struct nested inside one.
///
/// A record is a struct, so [RowWriter#writeRow] hands the same builder a nested
/// [#setStruct] does. Fields are addressed by the name they carry in the schema; the
/// synthetic `list` / `element` and `key_value` groups a `LIST` or `MAP` introduces are never
/// spelled by the caller.
///
/// ```java
/// rows.writeRow(row -> row
///         .setLong("id", 1)
///         .setString("name", "hardwood")
///         .setStruct("address", address -> address.setString("city", "Berlin")));
/// ```
///
/// A field that is never set is written as null if it is `OPTIONAL`, and fails the record if
/// it is `REQUIRED`; [#setNull] states the same thing explicitly, and a `null` value handed
/// to any of the object-typed setters does too. Setting the same field twice within one
/// scope, naming a field the schema does not have, or using a setter that does not fit the
/// field's declared type all throw at the call.
///
/// The builder is valid only inside the lambda it was passed to. Retaining one and using it
/// afterwards throws [IllegalStateException] rather than writing into a later record.
///
/// **This API is [Experimental]:** the shape may change in future releases.
@Experimental
public interface StructBuilder {

    /// Sets an `INT32` field.
    ///
    /// The value is stored as declared: a `DATE`, `TIME(MILLIS)` or `DECIMAL` annotation on
    /// the column does not change what this setter writes, so it is the raw-value escape
    /// hatch alongside [#setDate] and friends — mirroring the reader, whose `getInt` returns
    /// the stored `int` whatever the annotation. An `INT(8)` or `INT(16)` annotation
    /// range-checks the value against what it can hold, signed or unsigned; `UINT_32` does not,
    /// because every bit pattern is one of its values and a negative `int` is the only way to
    /// spell one above `Integer.MAX_VALUE`.
    ///
    /// @param name the field name
    /// @param value the value
    /// @return this builder, for chaining
    /// @throws IllegalArgumentException if the schema has no such field, the field is not
    ///         `INT32`, the field is already set in this scope, or the value is out of range
    ///         for the field's annotation
    /// @throws IllegalStateException if this builder's scope has ended
    StructBuilder setInt(String name, int value);

    /// Sets an `INT64` field.
    ///
    /// @see #setInt(String, int)
    StructBuilder setLong(String name, long value);

    /// Sets a `FLOAT` field.
    ///
    /// @see #setInt(String, int)
    StructBuilder setFloat(String name, float value);

    /// Sets a `DOUBLE` field.
    ///
    /// @see #setInt(String, int)
    StructBuilder setDouble(String name, double value);

    /// Sets a `BOOLEAN` field.
    ///
    /// @see #setInt(String, int)
    StructBuilder setBoolean(String name, boolean value);

    /// Sets a `BYTE_ARRAY` field annotated `STRING`, `ENUM` or `JSON`, or unannotated, from
    /// the value's UTF-8 bytes. A `null` value sets the field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setString(String name, String value);

    /// Sets a `BYTE_ARRAY` or `FIXED_LEN_BYTE_ARRAY` field from its bytes, which are
    /// referenced rather than copied and must not be mutated until the record is written. A
    /// `FIXED_LEN_BYTE_ARRAY` field requires exactly its declared length. A `null` value sets
    /// the field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setBinary(String name, byte[] value);

    /// Sets an `INT32` field annotated `DATE`, as days since the Unix epoch. A `null` value
    /// sets the field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setDate(String name, LocalDate value);

    /// Sets a field annotated `TIME`, in the unit the annotation declares. A value carrying
    /// finer precision than the unit can hold is rejected, or narrowed, according to the
    /// configured [PrecisionLossPolicy]. A `null` value sets the field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setTime(String name, LocalTime value);

    /// Sets an `INT64` field annotated `TIMESTAMP` with `isAdjustedToUTC = true`, in the unit
    /// the annotation declares. A value carrying finer precision than the unit can hold is
    /// rejected, or narrowed, according to the configured [PrecisionLossPolicy]; one outside
    /// the range that unit spans is rejected under either. A `null` value sets the field null.
    ///
    /// A local-wall-clock column — `isAdjustedToUTC = false` — is written through
    /// [#setLocalTimestamp] instead, mirroring the reader's split between `getTimestamp` and
    /// `getLocalTimestamp`; using the wrong one throws rather than silently reinterpreting
    /// the instant.
    ///
    /// @see #setInt(String, int)
    StructBuilder setTimestamp(String name, Instant value);

    /// Sets an `INT64` field annotated `TIMESTAMP` with `isAdjustedToUTC = false`.
    ///
    /// @see #setTimestamp(String, Instant)
    StructBuilder setLocalTimestamp(String name, LocalDateTime value);

    /// Sets a field annotated `DECIMAL`, in whichever of `INT32`, `INT64`, `BYTE_ARRAY` or
    /// `FIXED_LEN_BYTE_ARRAY` the column declares. The value is rescaled to the declared
    /// scale when that is lossless, and otherwise rejected or truncated according to the
    /// configured [PrecisionLossPolicy]; its unscaled value must fit the declared precision
    /// under either. A `null` value sets the field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setDecimal(String name, BigDecimal value);

    /// Sets a `FIXED_LEN_BYTE_ARRAY(16)` field annotated `UUID`. A `null` value sets the
    /// field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setUuid(String name, UUID value);

    /// Sets a `FIXED_LEN_BYTE_ARRAY(12)` field annotated `INTERVAL`. A `null` value sets the
    /// field null.
    ///
    /// @see #setInt(String, int)
    StructBuilder setInterval(String name, PqInterval value);

    /// Sets a field null. Equivalent to leaving it unset, and available so code that walks
    /// its fields uniformly can say so explicitly.
    ///
    /// @param name the field name
    /// @return this builder, for chaining
    /// @throws IllegalArgumentException if the schema has no such field, the field is
    ///         `REQUIRED`, or the field is already set in this scope
    /// @throws IllegalStateException if this builder's scope has ended
    StructBuilder setNull(String name);

    /// Sets a nested struct field, populating it through `filler`. Leaving the field unset
    /// writes a null struct; entering it with a filler that sets nothing writes a present
    /// struct whose own fields are null.
    ///
    /// @param name the field name
    /// @param filler populates the nested struct
    /// @return this builder, for chaining
    /// @throws IllegalArgumentException if the schema has no such field, the field is not a
    ///         struct group, or the field is already set in this scope
    /// @throws IllegalStateException if this builder's scope has ended
    StructBuilder setStruct(String name, Consumer<StructBuilder> filler);

    /// Sets a `LIST` field, appending its entries through `filler`. An absent list — the
    /// field left unset, or [#setNull] — and an empty list, whose filler appends nothing,
    /// are distinct and both writable.
    ///
    /// @param name the field name
    /// @param filler appends the list's entries
    /// @return this builder, for chaining
    /// @throws IllegalArgumentException if the schema has no such field, the field is not a
    ///         `LIST` group, or the field is already set in this scope
    /// @throws IllegalStateException if this builder's scope has ended
    StructBuilder setList(String name, Consumer<ListBuilder> filler);

    /// Sets a `MAP` field, appending its entries through `filler`. An absent map and an empty
    /// map are distinct and both writable.
    ///
    /// @param name the field name
    /// @param filler appends the map's entries
    /// @return this builder, for chaining
    /// @throws IllegalArgumentException if the schema has no such field, the field is not a
    ///         `MAP` group, or the field is already set in this scope
    /// @throws IllegalStateException if this builder's scope has ended
    StructBuilder setMap(String name, Consumer<MapBuilder> filler);
}
