<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# SchemaElement

`dev.hardwood.metadata.SchemaElement` is one entry in the flat, depth-first schema list stored in a Parquet file footer. It maps one to one onto the Thrift `SchemaElement` in the Parquet format.

`FileSchema.fromSchemaElements(List<SchemaElement>)` turns such a list into a schema tree. `FileSchema.toSchemaElements()` turns it back.

!!! note

    To build a schema for writing, use [`FileSchema.builder(String)`](../how-to/metadata.md) instead. It validates the whole schema and computes child counts for you. Use `SchemaElement` when you work with footer metadata directly.

## Factories

Static factories cover the common element kinds. Each one sets only the components that its kind uses.

| Factory | Builds |
|---|---|
| `root(name, numChildren)` | the root group, which has no repetition |
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

### Repetition

The root element has no repetition, and every other element has one. `root` builds the first case, and `group` and the primitive factories take the repetition for the second.

A footer that breaks this rule still reads. `fromSchemaElements` supplies a missing repetition, `REQUIRED` for a root and `OPTIONAL` for any other element.

### Name

`name` may be `null`. The factories pass it through without a check. A footer always carries a name, because the Thrift field is required, so a `null` name comes from code rather than from a file.

### Type length

`typeLength` has two meanings, one per physical type:

| Physical type | Meaning | How to set it |
|---|---|---|
| `FIXED_LEN_BYTE_ARRAY` | byte length of every value | `fixedLengthPrimitive` |
| any other | maximum bit length used to store a value | `withTypeLength(int)` |

`withTypeLength` returns a copy of an element with the field set:

```java
// a low-cardinality INT32 whose values fit in 3 bits
primitive("tag", PhysicalType.INT32, RepetitionType.REQUIRED).withTypeLength(3)
```

The field is optional in both cases. A column of any type can leave it out.

`fixedLengthPrimitive` sets the physical type to `FIXED_LEN_BYTE_ARRAY` itself, so it takes no `PhysicalType`. It takes the width **second**. `FileSchema.Builder.addColumn` takes the width last.

`primitive` rejects `FIXED_LEN_BYTE_ARRAY`, because that type needs a width. Use `fixedLengthPrimitive` for it.

### Errors

Every factory throws `IllegalArgumentException` for these:

| Condition | Factory |
|---|---|
| `type` is `null` | `primitive` |
| `type` is `FIXED_LEN_BYTE_ARRAY` | `primitive` |
| `numChildren` is negative | `group`, `root` |
| `typeLength` is zero or less | `fixedLengthPrimitive`, `withTypeLength` |
| the element is a group | `withTypeLength` |

A factory checks only one element. Rules that span the whole list stay in `fromSchemaElements`. For example, a root that declares two children followed by only one element fails there, not in the factory.

## Canonical constructor

The record constructor takes all ten components in Hardwood record-component order:

```java
new SchemaElement(name, type, typeLength, repetitionType, numChildren,
        convertedType, scale, precision, fieldId, logicalType);
```

Use it for metadata the factories do not cover. No factory sets `convertedType`, `scale`, `precision`, or `fieldId`, so these cases need the constructor:

- a legacy `ConvertedType` annotation, such as `ConvertedType.LIST` or `ConvertedType.MAP`
- a decimal, which carries `scale` and `precision`
- a Thrift `fieldId`
- a full element decoded from a footer, where every component must survive

## Node kind

| Method | Returns `true` when |
|---|---|
| `isGroup()` | `type` is `null` |
| `isPrimitive()` | `type` is not `null` |

A `null` physical type is what marks an element as a group. This is why `primitive` rejects a `null` type: it would build a group.
