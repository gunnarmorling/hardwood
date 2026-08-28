<!--

     SPDX-License-Identifier: CC-BY-SA-4.0

     Copyright The original authors

     Licensed under the Creative Commons Attribution-ShareAlike 4.0 International License;
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at https://creativecommons.org/licenses/by-sa/4.0/

-->
# Thrift metadata parser: malformed-input policy

**Status:** Completed

`dev.hardwood.internal.thrift` turns the bytes of a Parquet footer, page header and page
index into the metadata records the rest of the reader plans against. It is the first code
to touch a file and the only code that touches it before any bound has been established, so
every value it produces is attacker-controlled until it has been checked.

This document defines what the package does with input that does not match the format, and
the shape the readers share so that policy holds at every field.

## Policy

### A field whose wire type does not match the schema is skipped

Thrift's own forward-compatibility rule: a reader that does not recognise a field consumes
it by its declared type and moves on. The declared type is enough to skip the field
correctly whatever it contains, so the cost is bounded to that one field and the struct
continues to parse. Readers apply this uniformly through
`ThriftCompactReader.acceptField(FieldHeader, byte)`, which returns `true` with the reader
positioned on the value when the types agree, and otherwise skips the field and returns
`false`:

```java
case 3: // num_rows
    if (reader.acceptField(header, Codes.I64)) {
        numRows = reader.readNonNegativeI64("FileMetaData.num_rows");
    }
    break;
```

The expected type is named, never a raw hex literal — `0x08` and `0x09` differ by one bit
and pick a different branch in silence.

### A collection whose element type does not match is never decoded as if it did

A list header declares one element type for the whole collection. Decoding elements as
some other type consumes the wrong number of bytes per element and desynchronises the
stream: from that point on, value bytes are read as field headers and the rest of the
enclosing struct — the whole footer, in the case of `FileMetaData.schema` — is misread
rather than merely lost.

Every list read therefore gates on the declared element type before decoding an element,
and skips the collection element-wise when it disagrees. What happens next depends on
whether the field is required:

| | wrong element type |
|---|---|
| required field | `IOException` naming the field and both types |
| optional field | elements skipped, field reported absent, logged at `WARNING` |

The split follows from what "absent" can mean. An optional field already has a
representation for *not there* that its consumers handle, so a list that cannot be decoded
can use it: the file loses one informational field and stays readable. A required field has
no such representation — reporting `RowGroup.columns` as an empty list would answer a query
with zero rows instead of failing it, and silent wrong answers are worse than a failed read.

Two methods on `ThriftCompactReader` implement the gate, and the typed list reads
(`readStructList`, `readStringList`, `readBinaryList`, `readBoolArray`,
`readOptionalI64Array`) are built on them:

- `requireListHeader(elementType, fieldName)` — throws on mismatch
- `acceptListHeader(elementType, fieldName)` — returns `null` on mismatch, after logging

### Sizes, counts and offsets are validated where they are read

A length or offset from the file reaches an allocation, an array index or a
`ByteBuffer.slice` downstream. Checked at the point of use it produces an
`IndexOutOfBoundsException` or a `NegativeArraySizeException` — unchecked, unattributable,
and outside the `IOException` contract the metadata path advertises. Checked at the point
of read it produces a controlled error naming the field, which
`ParquetMetadataReader` then attributes to the file.

Readers use `readNonNegativeI32(fieldName)` / `readNonNegativeI64(fieldName)` for every
field the format defines as a size, count, length or file offset — in the footer readers as
well as the page-header readers.

Collection sizes are bounded by the bytes remaining in the buffer, since every Thrift
element occupies at least one byte on the wire. This applies to the long-form list count
and to the map size in `skipField`; a count that overflows `int` is rejected rather than
truncated.

### The page index is internally consistent before it is indexed

`ColumnIndex` carries seven per-page members. `null_pages` is required and defines the page
count; `min_values`, `max_values`, `null_counts` and `nan_counts` must have exactly that
many entries, and the two level histograms a whole multiple of it. `ColumnIndexReader`
rejects a chunk where they disagree, so page filtering never indexes one array with another
array's length.

The page count also has to agree across the two structs: `ColumnIndex` describes the pages
that `OffsetIndex` locates. The two are parsed together in
`PageFilterEvaluator.readIndexPair`, which cross-checks them there.

### An unsupported feature fails, it does not read the wrong bytes

`ColumnChunk.file_path` places a column's data in a different file. Hardwood does not
support the split-file layout; reading its own file at `data_page_offset` regardless would
return whatever happens to sit at that offset. The reader therefore carries `file_path` on
`ColumnChunk` and refuses at the point the chunk's bytes would be read: `requireSameFile()` on
the record, called before the scan takes its first look at a row group, before the dictionary
and bloom-filter sources that prune it, and in the CLI's page and dictionary readers.

The scan checks every chunk of a row group at once, not the projected ones one at a time,
because the page-index region is fetched as a single span across all of them: one chunk
pointing elsewhere misplaces the region for the rest.

The refusal sits there rather than in the footer parse so that the metadata of such a file
stays readable. Its schema, row groups and statistics are all decodable and describe the file
correctly; only the data lives elsewhere. Failing the parse would take a file that `hardwood
inspect` can explain and make it unopenable, which is the opposite of what a diagnostic tool
is for.

### Failures name the file, the row group and the column

The footer is read once per file and `ParquetMetadataReader` attributes its failures. The
page index is read once per column chunk, from `PageFilterEvaluator` and from
`RowGroupIterator`, and those failures carry the file, row group and column at the call
site.

## Record shapes

Per-page metadata is held in primitive arrays: `ColumnIndex.nullPages` is a `boolean[]`,
alongside the `long[]` count arrays. Page filtering reads each of them once per page, and a
primitive array makes that a load rather than a pointer chase and an unbox. It also removes
the question of what a `null` element would mean, which the boxed shape left to an
invariant nothing stated.

An absent optional array stays `null`, distinct from a zero-length one the writer recorded
as empty.
