<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# Query Controls

Look-it-up reference for the predicate and projection controls. For worked examples and the I/O
behavior of each control — predicate pushdown, projection, row limits, splits, and skip — see
[Predicate Pushdown, Projection, Limits, and Splits](../how-to/query-controls.md).

## Supported filter predicates

| Category | Supported |
|---|---|
| Comparison operators | `eq`, `notEq`, `lt`, `ltEq`, `gt`, `gtEq` |
| Set operators | `in` (int, long), `inStrings` |
| Null operators | `isNull`, `isNotNull` (any type) |
| Combinators | `and`, `or`, `not` (`and` / `or` accept varargs for three or more conditions) |
| Column form | Leaf columns only, by name or dot-separated path (`address.city`); group, `LIST`, and `MAP` names and leaves below a repeated group are rejected |

All predicates, including those wrapped in `not`, are pushed down to the statistics level for
row-group and page skipping. Filters work with all reader types — `RowReader`, `ColumnReader`,
`AvroRowReader`, and across multi-file readers.

## Predicate literals by column type

A column takes the literal types listed for its logical type, or — where it carries none — for its
physical type.

| Column | Literal | Compared as |
|---|---|---|
| `DATE` (`INT32`) | `LocalDate` | days since the Unix epoch |
| `TIME` (`INT32` millis, `INT64` micros / nanos) | `LocalTime` | the column's time unit |
| `TIMESTAMP` (`INT64`) | `Instant` | the column's time unit |
| `DECIMAL` (`INT32`, `INT64`, `FIXED_LEN_BYTE_ARRAY`, `BYTE_ARRAY`) | `BigDecimal` | the represented value, under all four physical types |
| `UUID` (`FIXED_LEN_BYTE_ARRAY(16)`) | `UUID` | the 16 bytes, unsigned |
| `FLOAT16` (`FIXED_LEN_BYTE_ARRAY(2)`) | `float` | numeric, widened to `float` |
| `GEOMETRY`, `GEOGRAPHY` | `intersects(xmin, ymin, xmax, ymax)` | bounding-box overlap |
| `BYTE_ARRAY` (`STRING`, `ENUM`, `JSON`, `BSON`, or unannotated) | `String`, `inStrings` | unsigned lexicographic |
| `INT32` | `int`, `in(int...)` | signed |
| `INT64` | `long`, `in(long...)` | signed |
| `FLOAT`, `DOUBLE` | `float`, `double` | numeric |
| `BOOLEAN` | `boolean` | equality only (`eq`, `notEq`) |

A literal applied to a column that does not carry the matching logical type throws
`IllegalArgumentException` at reader creation: a `LocalDate` against a plain `INT32` column, a
`BigDecimal` or `UUID` against a plain `FIXED_LEN_BYTE_ARRAY` one. A `BigDecimal` literal must in
addition carry a scale the column's scale represents exactly — `99.999` against a
`DECIMAL(scale = 2)` column throws `ArithmeticException` rather than rounding.

Raw physical-type predicates (`int`, `long`, etc.) remain available for columns without logical
types or for filtering on the underlying physical value directly. A `String` literal against a
`DECIMAL` stored as `BYTE_ARRAY` is the exception and throws `IllegalArgumentException`: that
column orders by the represented value, so it answers neither the ordering nor the equality a
byte-string predicate asks for. Filter it with a `BigDecimal`.

`eq` on a `DECIMAL` stored as `BYTE_ARRAY` is pruned by neither the Bloom filter nor the
dictionary: such a column may hold one number under more than one byte string, so a miss on the
literal's own bytes does not prove the value absent. Statistics pruning applies as for any other
`DECIMAL`.

## Column projection forms

| Form | Description |
|------|-------------|
| `ColumnProjection.all()` | Read all columns (default) |
| `ColumnProjection.columns("id", "name")` | Read specific columns by name |
| `ColumnProjection.columns("address")` | Select an entire struct and all its children |
| `ColumnProjection.columns("address.city")` | Select a specific nested field (dot notation) |
