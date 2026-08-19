<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# SchemaElement

`dev.hardwood.metadata.SchemaElement` represents one element in the flat schema list in a Parquet footer. The list uses depth-first order.

`FileSchema.fromSchemaElements(List<SchemaElement>)` builds a schema tree from this list. `FileSchema.toSchemaElements()` creates the list again.

!!! note

    Use [`FileSchema.builder(String)`](../how-to/metadata.md) to build a schema for writing. The builder checks the complete schema and calculates child counts. Use `SchemaElement` when you work with footer metadata.

## Factories

The static factories build the common element kinds. Each factory sets the fields for its kind.

| Factory | Builds |
|---|---|
| `root(name, numChildren)` | the root group, with no repetition |
| `group(name, repetition, numChildren)` | a group |
| `group(name, repetition, numChildren, logicalType)` | a group with a logical type |
| `primitive(name, type, repetition)` | a primitive column |
| `primitive(name, type, repetition, logicalType)` | a primitive column with a logical type |
| `fixedLengthPrimitive(name, typeLength, repetition)` | a `FIXED_LEN_BYTE_ARRAY` column |
| `fixedLengthPrimitive(name, typeLength, repetition, logicalType)` | a `FIXED_LEN_BYTE_ARRAY` column with a logical type |

```java
List<SchemaElement> elements = List.of(
        root("schema", 3),
        primitive("id", PhysicalType.INT64, RepetitionType.REQUIRED),
        primitive("name", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, new LogicalType.StringType()),
        fixedLengthPrimitive("uuid", 16, RepetitionType.REQUIRED, new LogicalType.UuidType()));

FileSchema schema = FileSchema.fromSchemaElements(elements);
```

## Repetition

A valid root has no repetition. Other valid elements have a repetition value.

`root` sets the root repetition to `null`. The group and primitive factories take the repetition value as an argument.

`fromSchemaElements` accepts a missing repetition value. It uses `REQUIRED` for the root and `OPTIONAL` for other elements.

## Name

A valid Parquet footer has a name for each schema element. The Thrift field is required.

A malformed or truncated footer can omit the name. The reader then creates an element with `name == null`. The factories pass a null name through without a check.

## Type length

`typeLength` has a different meaning for each physical type:

| Physical type | Meaning |
|---|---|
| `FIXED_LEN_BYTE_ARRAY` | the byte length of each value |
| any other physical type | the maximum bit length needed to store any value |

Use `fixedLengthPrimitive` to create an `FIXED_LEN_BYTE_ARRAY` (called FLBA henceforth) column with its byte length. Use `withTypeLength(int)` to set or replace `typeLength` on a primitive element.

```java
// An INT32 column with a maximum bit length of 3 - all values of this column can be stored with 3 bits.
SchemaElement tag = primitive("tag", PhysicalType.INT32, RepetitionType.REQUIRED)
        .withTypeLength(3);
```

`typeLength` can be `null` in raw footer metadata. A writer must provide a positive length for an FLBA column. A data reader also needs this length to decode FLBA values.

`fixedLengthPrimitive` sets the physical type to `FIXED_LEN_BYTE_ARRAY`. It takes the width as its second argument. `FileSchema.Builder.addColumn` takes the width as its fourth argument.

`primitive` rejects `FIXED_LEN_BYTE_ARRAY`. Use `fixedLengthPrimitive` for that type.

## Errors

The factories throw `IllegalArgumentException` for these inputs:

| Condition | Factory |
|---|---|
| `type` is `null` | `primitive` |
| `type` is `FIXED_LEN_BYTE_ARRAY` | `primitive` |
| `numChildren` is negative | `group`, `root` |
| `typeLength` is zero or less | `fixedLengthPrimitive`, `withTypeLength` |
| the element is a group | `withTypeLength` |

A factory checks one element. Rules for the complete list stay in `fromSchemaElements`. The method consumes the list in depth-first order and builds the schema tree.

## Canonical constructor

The record constructor takes the components in Hardwood record-component order:

```java
new SchemaElement(name, type, typeLength, repetitionType, 
                  numChildren, convertedType, scale, precision, 
                  fieldId, logicalType);
```

Use the constructor when the factories do not cover the metadata. The factories do not set `convertedType`, `scale`, `precision`, or `fieldId`.

Use the constructor for:

- a legacy `ConvertedType` annotation, such as `ConvertedType.LIST` or `ConvertedType.MAP`;
- legacy decimal metadata with `scale` and `precision`;
- a Thrift `fieldId`;
- a complete element decoded from a footer.

## Node kind

| Method | Returns `true` when |
|---|---|
| `isGroup()` | `type` is `null` |
| `isPrimitive()` | `type` is not `null` |

A null physical type marks a group. `primitive` rejects a null type because it would create a group.
