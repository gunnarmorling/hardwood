
# Plan: Parse `SizeStatistics` and NaN counts (#607)

  

**Status: Proposed.** Tracking issue: #607.

  

## Context

  

Parquet 2.10 added a second family of per-chunk and per-page summaries alongside the

existing `Statistics`:

  

-  **`SizeStatistics`** — the unencoded size of `BYTE_ARRAY` data, plus repetition- and

definition-level histograms. The histograms give null and empty-list counts without

decoding a level stream.

-  **NaN counts** — `Statistics.nan_count` and the per-page `ColumnIndex.nan_counts`. NaN

sits outside the min/max total order, so a count of NaN values is the only way to

reason about a floating-point unit from its statistics alone.

  

arrow-cpp writes both by default, so they are present in a large share of files produced

today. Hardwood reads neither: all six Thrift fields fall through to `skipField`, and

`FORMAT_COVERAGE.md` marks every one ❌ against #607.

  

| Struct | Field | Thrift type | Surfaced as |

|---|---|---|---|

| `ColumnMetaData` | 16 `size_statistics` | `SizeStatistics` | `ColumnMetaData.sizeStatistics` |

| `Statistics` | 9 `nan_count` | `i64` | `Statistics.nanCount` |

| `ColumnIndex` | 6 `repetition_level_histograms` | `list<i64>` | `ColumnIndex.repetitionLevelHistograms` |

| `ColumnIndex` | 7 `definition_level_histograms` | `list<i64>` | `ColumnIndex.definitionLevelHistograms` |

| `ColumnIndex` | 8 `nan_counts` | `list<i64>` | `ColumnIndex.nanCounts` |

| `OffsetIndex` | 2 `unencoded_byte_array_data_bytes` | `list<i64>` | `OffsetIndex.unencodedByteArrayDataBytes` |

  

`ColumnIndex` field 7 belongs here because #613 removed its mis-numbered geospatial

branch and left the parse to this plan. `OffsetIndex` field 2 is the per-page counterpart

of the chunk-level unencoded size and is read alongside it.

  

## Out of scope

  

Every consumer of the new data:

  

- Promoting floating-point `StatsDecision` results using `nanCount`. `ALWAYS_MATCHES` is

hardcoded `false` for `FLOAT` / `FLOAT16` / `DOUBLE` in `StatisticsFilterSupport`;

changing that alters which rows the filter evaluates and belongs to a follow-up on #795.

- Deriving null or empty-list counts from the histograms for read planning.

- Surfacing any of it in `hardwood dive`.

- Writing these structures. The writer emits `Statistics` via `StatisticsCollector`; size

statistics and NaN counts are not produced.

  

No existing read path consults the new fields, so no file reads differently than it does

today.

  

## Design

  

### The `SizeStatistics` record

  

A new public record in `dev.hardwood.metadata`, mirroring the Thrift struct:

  

```java
/// Size statistics for a column chunk or page.

/// @param unencodedByteArrayDataBytes total unencoded size of the `BYTE_ARRAY` data, or `null` if absent

/// @param repetitionLevelHistogram count of values at each repetition level `0..maxRepetitionLevel`, or `null` if absent

/// @param definitionLevelHistogram count of values at each definition level `0..maxDefinitionLevel`, or `null` if absent

/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>

public  record  SizeStatistics(
	Long unencodedByteArrayDataBytes,
	List<Long> repetitionLevelHistogram,
	List<Long> definitionLevelHistogram) {
}
```

  

All three Thrift fields are optional and surface as `null` when absent, following

`ColumnIndex.nullCounts` — the existing optional `list<i64>`. An absent histogram stays

distinguishable from an empty one, which a defaulted empty list would lose.

  

### Histogram layout

  

A chunk-level histogram in `SizeStatistics` has `maxLevel + 1` entries; entry *i* is the

number of values at level *i*. The `ColumnIndex` histograms hold the same per page,

**flattened** into one list of `pageCount × (maxLevel + 1)` entries, page-major: page *p*

occupies `[p * (maxLevel + 1), (p + 1) * (maxLevel + 1))`.

  

The flattened layout is carried through unchanged and stated in the record JavaDoc. No

slicing accessor is added: the max level needed to compute a stride comes from

`ColumnSchema`, which the metadata records do not reference.

  

### Additions to existing records

  

Each component is appended to its record, leaving existing positional prefixes unchanged:

  

| Record | New component |

|---|---|

| `ColumnMetaData` | `SizeStatistics sizeStatistics` |

| `Statistics` | `Long nanCount` |

| `ColumnIndex` | `List<Long> repetitionLevelHistograms`, `List<Long> definitionLevelHistograms`, `List<Long> nanCounts` |

| `OffsetIndex` | `List<Long> unencodedByteArrayDataBytes` |

  

Adding a record component changes the canonical constructor, so these are

binary-incompatible additions to `dev.hardwood.metadata`, reported by japicmp in the

`1.1.0` API change report. #613 removed `ColumnIndex.geospatialStatistics` in the same

development cycle.

  

`ColumnMetaData` is reachable from public API through `FileMetaData` → `RowGroup` →

`ColumnChunk`. `ColumnIndex` and `OffsetIndex` are public records with no public reader

entry point — they are reached through `internal.thrift`, as `hardwood-cli` does. This

plan does not change that asymmetry.

  

## Components

  

### `SizeStatisticsReader`

  

New reader in `dev.hardwood.internal.thrift`, structured as the existing struct readers

are: a `read` entry point wrapping `readInternal` in `pushFieldIdContext` /

`popFieldIdContext`, a field-header loop, and a type-tag guard per case falling back to

`skipField`. `GeospatialStatisticsReader` is the template — it likewise mixes a scalar

field with list fields.

  

### `ColumnMetaDataReader`

  

`case 16`, guarded on `0x0C` (STRUCT), delegates to `SizeStatisticsReader.read`.

  

### `StatisticsReader`

  

`case 9`, guarded on `0x06` (I64), reads `nan_count` via `readI64`.

  

### `ColumnIndexReader`

  

`case 6` / `7` / `8`, each guarded on `0x09` (LIST), read `list<i64>` following the

`nullCounts` pattern already in the class: allocate on first sight of the field, presize

from the collection header, `readI64` per element. The class JavaDoc drops "Fields 6–8 are

not yet surfaced and are skipped", and the `default` branch drops its explanatory comment.

  

### `OffsetIndexReader`

  

`case 2`, guarded on `0x09` (LIST), reads the per-page unencoded sizes.

  

## Validation strategy

  

Reading stays tolerant, consistent with every other optional metadata field:

  

- A field whose Thrift type tag does not match the spec is skipped, not rejected. This is

what keeps the mis-typed field 7 case from #608 harmless, and

`ColumnIndexReaderTest.skipsStructTypedFieldSevenWithoutCorruptingLaterFields` pins it.

- A histogram whose length does not match `pageCount × (maxLevel + 1)` is surfaced as

read. The records carry no schema reference to check a stride against, and rejecting a

malformed histogram would fail a read that succeeds today. Consumers validate before

deriving anything.

-  `nan_count` and the histogram entries use `readI64`, not `readNonNegativeI64`. The

non-negative guards exist where a negative value would drive an allocation or a file

offset; these values feed neither.

  

`readListHeader` does not bound the element count it returns, so a malformed size varint

presizes an `ArrayList` to an arbitrary capacity. The new list fields inherit that

exposure from the existing `nullCounts` and `page_locations` cases rather than adding a

new one; bounding it belongs to a separate change across all list-valued metadata fields.

  

## Testing

  

**Reader tests.**  `SizeStatisticsReaderTest` covers all fields present, each field absent,

and a wrong-typed field skipped without disturbing its neighbours. `ColumnIndexReaderTest`

gains parse assertions for fields 6/7/8; its

`skipsStructTypedFieldSevenWithoutCorruptingLaterFields` case stays, now asserting the

histograms come back `null`. `StatisticsReader` and `OffsetIndexReader` get equivalent

coverage for their single new field each.

  

These build Thrift structs by hand. The `ThriftBuilder` helper is currently private to

`ColumnIndexReaderTest` and is lifted into a package-private test class so the new tests

share it.

  

**Fixture-backed test.** A new fixture from `tools/simple-datagen.py`, written with size

statistics and the page index enabled, asserts parsed values against the file's known

shape. Existing fixtures are not regenerated — they are byte-reproducible inputs for

unrelated tests. The fixture carries the three column shapes that make the new fields

non-trivial:

  

- a `BYTE_ARRAY` column with nulls, for `unencodedByteArrayDataBytes` and a two-entry

definition-level histogram;

- a nested `LIST` column, for a repetition-level histogram with more than one non-zero

entry;

- a `DOUBLE` column containing NaN.

  

PyArrow 24.0.0 writes `SizeStatistics` and the page-index histograms by default, so five

of the six fields are asserted against writer-produced bytes. It writes **no** NaN count

for any column, including the one holding a NaN, and no other available writer emits

`Statistics` field 9 or `ColumnIndex` field 8 either. Those two fields therefore have

positive coverage only in the reader unit tests; the fixture asserts them absent, pinning

that an omitted count reads back as `null` rather than zero.

  

The same fixture pins the absent-versus-empty distinction against real bytes: PyArrow

records the repetition-level histogram of a non-repeated column as present-but-empty in

`SizeStatistics` and as absent in the `ColumnIndex`.

  

## Delivery plan

  

One PR. Each commit is independently reviewable, and the test-only move lands before the

behaviour it covers changes.

  

| # | Commit | Contents |

|---|---|---|

| 1 | Extract the test Thrift builder | `ThriftBuilder` lifted out of `ColumnIndexReaderTest` into a package-private test helper in `internal.thrift`. No production change. |

| 2 | `SizeStatistics` record and reader | New record, `SizeStatisticsReader`, `ColumnMetaData.sizeStatistics`, `ColumnMetaDataReader` case 16, reader tests. |

| 3 | Chunk-level NaN count | `Statistics.nanCount` + `StatisticsReader` case 9. |

| 4 | Page-index fields | All three `ColumnIndexReader` cases 6/7/8 together — one edit of the record and its construction sites — plus `OffsetIndexReader` case 2, corrected class JavaDoc, extended `ColumnIndexReaderTest`. |

| 5 | Fixture-backed test | New PyArrow fixture and its assertions. |

| 6 | Docs and coverage | `docs/content/how-to/metadata.md`, `FORMAT_COVERAGE.md`, `ROADMAP.md`. |

  

## User documentation

  

`docs/content/how-to/metadata.md` gains a section on the new chunk-level surface: reading

`sizeStatistics` off `ColumnMetaData` and `nanCount` off `Statistics`, stating the

absent-versus-empty convention and the histogram indexing. Following the how-to kind, it

shows the access pattern without arguing for it.

  

`ColumnIndex` and `OffsetIndex` have no public reader entry point, so their new components

get JavaDoc — including the flattened page-major layout — but no how-to page.

  

## Roadmap reconciliation

  

`FORMAT_COVERAGE.md` moves all six rows off ❌. They become 🟡 — read, on the public

record, no functional consumer — matching how `Statistics.distinct_count` is classified;

parsing without a consumer is not "processed". The `SizeStatistics` section changes from

"struct not read" to a per-field table.

  

`ROADMAP.md` gains checked boxes under 9.1 for the `SizeStatistics` record, its

deserialization and `nan_count`, and under 9.2 for the `ColumnIndex` histogram and NaN

fields and the `OffsetIndex` per-page unencoded sizes.