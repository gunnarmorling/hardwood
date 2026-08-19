# Write-path interop gate (#9, stage 14)

**Status: Complete.** Tracking issues: #9, #907. Delivery stage 14 (Gate) of
[WRITER_SUPPORT.md](WRITER_SUPPORT.md). This document is the reference the gate is
implemented against and the contract every later writer increment extends.

## Context

Stages 1–13 produce every primitive physical type, in every column shape the reader
supports, with paging, row-group cadence, nulls, nesting, dictionary encoding,
compression, statistics, and logical-type annotations. Every one of them landed with a
test that reads the produced file back — through Hardwood's own reader, and through
DuckDB.

Neither reader can observe the defect class that matters most on a write path: bytes that
only a permissive decoder accepts. #901 is what that costs. A column chunk whose
dictionary held exactly one entry produced an `RLE_DICTIONARY` index stream with no run
header — the page body was the bit-width byte `0x00` and nothing else. Hardwood's decoder
short-circuits on a zero bit width and never reads the stream; DuckDB is lenient in the
same place. Both suites passed on a file that parquet-java and PyArrow both reject, on a
shape as ordinary as a constant column.

Fixing #901 pinned that encoder's bytes. It did not close the class. Every increment from
here multiplies the produced shapes — the row-oriented layer, the remaining codecs, the
delta and byte-stream-split encoders, page indexes, Bloom filters — and each one is a
fresh opportunity to emit a stream only Hardwood can read.

## Goal

A test that a strict, independent implementation has to pass on files Hardwood writes, run
on every PR, covering every shape stages 1–13 can produce.

The acceptance criterion from #907: the gate fails against the writer as of the parent
commit of the #901 fix, and passes after it.

## The strict reader

**parquet-java** (`org.apache.parquet`, 1.17.1) is the reader. It is the canonical
implementation, so what it accepts is the operative definition of a conformant file, and
it is already on `parquet-testing-runner`'s test classpath — the module carries
`parquet-avro`, `parquet-hadoop` and `hadoop-common` at test scope and runs on every PR,
so the gate needs no new dependency and no new CI job.

PyArrow also catches this defect class, and its failure modes differ from parquet-java's
per physical type. It is not part of the gate: adding it means wiring a Python step into
the Java build for a second opinion on the same question. It remains the ad-hoc
verification recipe it is today.

### Reading through the Group API, not Avro

The module's existing read-direction comparison reads reference rows through
`AvroParquetReader`, which maps Parquet onto Avro's type system. That mapping is a
restriction: Avro cannot represent several annotations stage 13 emits — `UUID`,
`FLOAT16`, `INTERVAL`, the unsigned integer widths — so an Avro-based gate would have to
exclude parts of the matrix for reasons that have nothing to do with whether the bytes are
conformant.

The gate reads through parquet-java's Group API instead — `ParquetReader<Group>` over
`GroupReadSupport` — which materializes any valid file with no object model in the way.
A read failure therefore means the bytes are bad, which is precisely what the gate is
asserting. The Group path exercises the same decoders the Avro path does, including the
`DictionaryValuesReader` that rejected #901.

The footer is read separately through parquet-java's `ParquetFileReader`, so the gate also
covers the metadata a value comparison cannot see: the declared encodings, the column-chunk
statistics, and the `column_orders` that give those statistics their meaning.

## What the gate asserts

Per file, four things:

1. **It reads.** parquet-java materializes every row without throwing. This alone is the
   #901 check.
2. **The values agree.** Every value parquet-java produces equals the value that was
   written — not merely what Hardwood reads back. Nulls, list and map cardinalities, and
   empty-versus-absent repeated values are compared as written.
3. **The metadata agrees.** parquet-java parses the footer, and the column-chunk
   statistics it exposes — `min`, `max`, `null_count` — match the written data under
   *its* comparator, which it derives from the annotation and the footer's
   `column_orders`. Agreement here is the first cross-implementation check on stage 13b:
   an order the two implementations disagree about produces bounds that would prune a row
   group holding a matching row.
4. **The case covered what it claims to.** The encodings say the case actually produced
   the dictionary, `PLAIN` fallback or plain-only chunk it exists to cover, and the layout
   cases walk parquet-java's page readers to count the data pages they crossed. Without
   this a change to the writer's encoding choice or page sizing would silently empty an
   axis rather than fail it.

   This one has to read the **page** value encodings, not the column chunk's `encodings`
   list. That list always contains `PLAIN` — a dictionary page body is itself `PLAIN` — so
   a chunk that overflowed its dictionary and fell back mid-chunk declares exactly what a
   dictionary-only chunk declares, and asserting over it cannot fail. Each data page header
   carries its own encoding, and those do separate the two.

## The matrix

The floor is a **single-entry dictionary per physical type** — the #901 shape — in every
repetition shape.

Beyond the floor, the matrix varies one axis at a time against a representative base
rather than taking the full cross product, which would multiply to hundreds of files for
coverage the axes already give independently. Every axis is swept across all seven
writable physical types (`BOOLEAN`, `INT32`, `INT64`, `FLOAT`, `DOUBLE`, `BYTE_ARRAY`,
`FIXED_LEN_BYTE_ARRAY`), since the value encoders are per-type and that is where an
encoding defect lives.

| Axis | Values |
|------|--------|
| Repetition | `REQUIRED`; `OPTIONAL` all-present; `OPTIONAL` with interleaved nulls; `OPTIONAL` all-null |
| Encoding | single-entry dictionary; multi-entry dictionary; dictionary overflowing to mid-chunk `PLAIN`; dictionary disabled |
| Codec | `UNCOMPRESSED`; `ZSTD` |
| Layout | one page (pinned exactly); several pages; several row groups |

Two further groups are enumerated rather than swept, because their shapes differ too much
to parameterize:

- **Nesting** — `REQUIRED` and `OPTIONAL` structs, nested structs, lists (including empty
  lists, absent lists, lists of lists, lists of structs), and maps (including maps of
  lists and maps of structs). These are the shapes that carry repetition and definition
  level streams, which a flat column does not exercise at all.
- **Logical types** — every annotation stage 13 emits, including the `UNKNOWN` of a
  `NullType` column, read back through parquet-java's schema so that both the annotation
  and the values it governs are checked. The bounds are asserted as the true extremes of
  the row's own values, reduced with parquet-java's comparator for that column, rather than
  merely as `min <= max`: most annotations carry values that sort identically under every
  candidate order, so a consistency check passes for them whichever order the writer used.

Only `UNCOMPRESSED` and `ZSTD` appear on the codec axis because those are the only codecs
the writer produces today. Stage 19 adds the rest, and extends this axis with them.

## Writer identification

A reader that cannot parse `created_by` cannot tell which implementation produced the
file, and applies its writer-specific correctness workarounds by default: under the
PARQUET-251 heuristic, parquet-java discards the deprecated `min`/`max` of a `BINARY` or
`FIXED_LEN_BYTE_ARRAY` column written by a writer it cannot identify. Hardwood writes only
the modern `min_value`/`max_value`, which that heuristic does not gate, so a parseable
identifier is what keeps the outcome from depending on which statistics fields the writer
happens to emit.

The gate therefore asserts both halves for every case: that `VersionParser` parses the
identifier into the `hardwood` application with a semantic version, and that
`CorruptStatistics.shouldIgnoreStatistics` consequently returns false for the two types it
gates. This is the class of defect only a strict reader can observe — the suites that read
back through Hardwood and DuckDB see a well-formed file either way.

## Extension contract

Every writer increment after this one extends the matrix with the shapes it introduces,
in the same commit that introduces them. Concretely:

| Increment | Extension |
|-----------|-----------|
| 15 (parseable `created_by`) | The footer assertions gain that parquet-java's `VersionParser` accepts the identifier |
| 16 (row-oriented `RowWriter`) | The record-shaped entry point: a `rowWritten` axis on the flat sweep, row-written struct / list / map / list-of-struct / list-of-list shapes and a multi-batch nested run in `WriterNestedInteropTest`, and `RowWriterLogicalTypeInteropTest` — the external oracle for `PhysicalValueConverter`, whose expectations come from Avro's conversions rather than from the inverse Hardwood decodes with |
| 18 (S3 `OutputFile`) | None — the backend does not change the bytes |
| 19 (codecs, delta / BSS encoders) | The codec axis gains the remaining codecs; the encoding axis gains the delta and byte-stream-split forms |
| 20 (parallel encoding) | None — the output is byte-identical by construction, which its own tests assert |
| 21 (row-group-global dictionary selection) | The encoding axis loses the mid-chunk `PLAIN` fallback and gains the whole-chunk choice |
| 22 (page index) | The footer assertions gain the OffsetIndex and ColumnIndex, read through parquet-java |
| 23 (Bloom filters) | The footer assertions gain the Bloom filter, read and probed through parquet-java |

## Placement

`parquet-testing-runner`, alongside the read-direction comparison it inverts. Three test
classes, split by what they parameterize over rather than by size:

- `WriterInteropTest` — the swept flat matrix, plus the footer, statistics and encoding
  assertions. `TypeFixture` holds the per-physical-type half — how to declare a column,
  fill a batch with its values, read one back out of a `Group`, and what bounds its values
  imply — and `InteropCase` describes one point of the matrix, with the values a pure
  function of the row index so the same description drives the writer and the assertion.
- `WriterNestedInteropTest` — the enumerated `struct`, `LIST` and `MAP` shapes.
- `WriterLogicalTypeInteropTest` — the annotation table, pairing each Hardwood
  `LogicalType` with the parquet-java `LogicalTypeAnnotation` it must read back as, plus
  the two sort orders that differ from their physical type's own (unsigned integers and
  binary `DECIMAL`).

A shared `ParquetJavaReader` helper wraps the Group reader, the footer reader, the page
walk and the `created_by` check, so the parquet-java surface the gate depends on sits in
one place. It is separate from `Utils`, which serves the read direction and carries the
Avro comparison.

None of the classes touch the `parquet-testing` fixture repository: the gate's inputs are
files Hardwood writes, so it does not need the clone the read-direction tests do.
