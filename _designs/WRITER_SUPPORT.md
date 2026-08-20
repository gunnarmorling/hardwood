# Writer support (#9)

**Status: In progress.** Tracking issue: #9.

## Context

Hardwood is a read-only Parquet library: every file in the test corpus is produced
ahead of time by `tools/simple-datagen.py` (PyArrow). The 1.0 line shipped reading;
write support is the 1.1 goal.

The read pipeline memory-maps a file of known size and fans out random-access
`readRange(offset, length)` calls across columns and row groups. Writing inverts
this: the output size is unknown until the file is finished, and the Parquet
container is laid out for forward-only production. This document describes the
end-state writer architecture and the order in which it is delivered.

## Scope

The writer milestone (#9) delivers write support for the **full schema model the reader
supports — flat and nested — through a columnar batch API**:

- **All column shapes** — `REQUIRED`, `OPTIONAL`, and `REPEATED` fields, and nested
  groups (structs, lists, maps): definition levels, repetition levels, and Dremel
  shredding, mirroring the reader's nested model. Flat columns (`REQUIRED` / `OPTIONAL`,
  no repetition) — the write-side counterpart of the reader's `FlatRowReader` fast path,
  covering the majority of analytics files — are delivered first as the thinnest slice,
  and nesting is built on that settled contract.
- **Columnar batch input** — the user fills a `ColumnBatch` the writer hands to a
  filler: an aligned slice carrying one typed array per column, addressed by index or
  name, mirroring `ColumnReader`. Nested columns carry per-layer validity and offsets,
  the write-side analog of the reader's `getLayerValidity` / `getLayerOffsets`. The
  writer re-chunks batches into pages and row groups internally. A row-oriented writer
  and integration adapters layer on top later.
- **DataPage V1** as the written page format, for maximum reader compatibility.

Logical-type annotations (STRING, DATE, TIMESTAMP, DECIMAL, UUID, …) are in scope: a
column's physical bytes are written by the primitive-type increment, and the annotation
is serialized onto the schema and converted at the API boundary.

The optional index structures — OffsetIndex, ColumnIndex, and Bloom filters, with the
per-page statistics that drive page-level pruning (including the `DataPageHeader` inline
statistics) — are numbered increments 23–24 below, following the write-support milestone
on the settled surface. Sequenced as separate later milestones, each its own
design: DataPage V2, the Avro write API, and a CLI write/convert command. Sorting-column
metadata and custom record materializers are non-goals. Key-aligned layout control —
forcing a page boundary at key-value changes (one page per key) for point-query workloads
— is likewise a non-goal here; it would be a separate later increment if such demand
surfaces.

## Write model

### Forward-only, footer-last

The Parquet container is written front to back and never seeked backward:

```
PAR1 | <row group 0 pages> | <row group 1 pages> | ... | FileMetaData (thrift) | <footer length: 4 bytes LE> | PAR1
```

Every offset a reader needs lives in the `FileMetaData` footer, which is emitted
last. The writer maintains a running byte position, records page and column-chunk
offsets as it streams them out, and serializes the accumulated metadata at the end.
No random access and no memory mapping are required on the write path.

### Row-group buffering bounds memory

A row group's column chunks are written contiguously, and each column chunk's
metadata (compressed and uncompressed sizes, page offsets, statistics) is only known
once its bytes have been encoded. The writer therefore **encodes and buffers a full
row group's columns in memory, then flushes them in column order**. The configured
row-group size (default 128 MiB of uncompressed data) bounds peak memory — this is
the write-side inverse of the reader's whole-file mmap. Page size (default 1 MiB)
bounds the granularity within a column chunk.

Three nested layout tiers stack here — a **page** encodes/compresses a slice of a
column, a **column chunk** is one column's pages for one row group, and a **row group**
holds one column chunk per column. All three are internal; only the page-size and
row-group-size targets are user-visible, as `WriterConfig` knobs.

### Ingestion cadence

Data arrives as **`ColumnBatch` objects** — an aligned slice carrying one typed array
per column. `ParquetFileWriter.writeBatch` takes a filler: it creates the batch bound to
the schema, passes it to the filler to be populated, then submits it, so there is no
separate build or submit step to forget. Because the batch is schema-bound, its columns
can be addressed by index or name and every identifier is validated as values are added:
an unknown name, an out-of-range index, a non-`INT32` column, or setting the same column
twice (by either index or name) all fail eagerly rather than at write time. A batch is
atomic: every column's array must have the same length, which is the batch's row count,
and a ragged batch is rejected. The batch is only an *arrival* unit and is independent of the three
layout tiers: the writer distributes a batch's values into the per-column page buffers,
cuts pages at the page-size target, and appends encoded pages to the per-column column
chunk buffers. When the buffered row group crosses the row-group-size target it is
flushed — column chunks written in schema order, offsets recorded — and the buffers
reset. A batch larger than the row-group target is split at the boundary, so peak
memory stays bounded by the row-group size regardless of batch size.

Row-group boundaries are chosen by the writer from the size target; there is no
explicit boundary method. A caller holding whole columns submits them as one large
batch and the writer slices it into pages and row groups; a streaming producer submits
many small batches and discards each after handing it over.

### Null representation

An `OPTIONAL` column carries its nulls as a `Validity` — the same type the reader returns
from `getLeafValidity()`, promoted to the neutral `dev.hardwood` package so it is shared
read/write vocabulary rather than reader-owned. Because `Validity` is an interface, the
representation is chosen by the factory the caller uses: `NO_NULLS` (a singleton, no
allocation), `of(long[])` for a packed present-bitmap, `ofNulls(boolean[])` to bridge a
plain mask, and, in a later increment, a sparse form for the few-nulls case. The writer
consumes whichever it is given the same way — `nextNull(from, end)` walks the null
positions per page — so a new representation is a drop-in with no change to the write API.
The polarity is null-centric (`Validity.isNull`), matching the reader, so a value read
back as null is written by marking that row null.

The values array is full length — one slot per row — and the entry at a null row is
ignored. The common all-present case needs no `Validity` at all: the mask-less setter is
the all-present form for both `REQUIRED` and `OPTIONAL` columns. A `boolean[]` overload is
kept as convenience sugar over `Validity.ofNulls` (`nulls[i] == true` ⇒ null); it is the
only null form whose length is validated against the values, since a `Validity` has no
intrinsic length. A null mask is rejected on a `REQUIRED` column. `Validity` remains
`@Experimental` — its shape may still shift — so the writer overload that takes it is
experimental too until the concept is stabilized alongside the zero-copy `ColumnVector`
SPI.

The mask is lowered to definition levels at page seal: a flat `OPTIONAL` column has
`maxDefinitionLevel == 1`, so each row's level is `1` when present and `0` when null. The
levels are RLE/bit-packed (bit width 1) by `LevelEncoder` over the shared
`RleBitPackingHybridEncoder`, and only the non-null values are `PLAIN`-encoded. A DataPage
V1 body is therefore `[4-byte LE def-level length][RLE def-levels][PLAIN non-null values]`,
with the page header's `num_values` counting all rows including nulls — the exact layout
the reader parses. An all-present optional column encodes its levels as a single RLE run,
which is what lets the reader take its all-present fast path. A `REQUIRED` column has no
level stream and writes its values directly.

Internally a batch's per-column arrays sit behind a bulk **value-source seam**
(`IntColumnSource` and its per-type siblings: a `size()` plus a `copyInto` that fills a
reused page-sized primitive buffer), with an `int[]`-backed implementation. Encoders,
statistics and dictionary building consume page-sized ranges through this seam rather
than the caller's array directly, so intermediate memory stays bounded to one page. A
public `ColumnVector` / `IntColumn` SPI over the same seam — letting a caller write from
a custom columnar container without an intervening copy, plus a zero-copy fast path when
a caller's buffer already holds contiguous little-endian `PLAIN` bytes — is a later
additive layer, sequenced after nulls and dictionary settle the value and validity
facets it must expose. The primitive-array setters are sugar over the seam, so adding
the SPI never changes the `writeBatch` signature or the row-group machinery.

### Nested representation

`REPEATED` fields and nested groups shred into the same page layout as flat columns,
with a repetition-level stream ahead of the definition levels — a DataPage V1 body of
`[rep levels][def levels][values]`, both level streams RLE/bit-packed via `LevelEncoder`.
Rep and def levels are computed from the batch's per-layer validity and offsets (the
write-side inverse of the reader's Dremel assembly) by a `RecordShredder`. The detailed
shredding algorithm and the nested `ColumnBatch` input contract are settled in
`_designs/WRITER_NESTED.md` (delivery stage 5) and implemented structs → lists → maps in
stages 6–8.

### OutputFile abstraction

A sequential write counterpart to `InputFile`, far simpler than the random-access
read interface:

```java
public interface OutputFile extends Closeable {
    void create() throws IOException;        // Acquire resources
    void write(ByteBuffer data) throws IOException;
    long position();                          // Running byte offset written so far
    void close() throws IOException;          // Finalize; file is valid only after this returns
}
```

- **Local backend** (`internal.writer.ChannelOutputFile`): a buffered `FileChannel`.
  Writes to a temporary sibling path and atomically renames on `close()`, so a failed
  write never leaves a truncated file presented as valid.
- **In-memory backend** (`internal.writer.ByteBufferOutputFile`): a growable buffer,
  the write-side counterpart to `ByteBufferInputFile`, used for tests and round-trips.
- **S3 backend** (`internal.writer.S3OutputFile`, increment 21): sequential writes buffer
  to the multipart part size and upload parts; `close()` completes the multipart upload.
  In-flight bytes are bounded to the part size times a small concurrency multiple, so a
  fast producer cannot outrun the uploads; `CreateMultipartUpload` is deferred until the
  first part flushes, with a single `PutObject` for an output that never exceeds one part.
  It reuses the read-side S3 / SigV4 stack (`_designs/S3_OBJECT_STORAGE.md`,
  `_designs/S3_ZERO_SDK.md`).

A file is valid only after `close()` returns successfully. A writer abandoned before
`close()` produces no footer and therefore no readable file.

## Component architecture

Writer components live in `core`, in packages parallel to the reader, so encoders and
the thrift codec sit alongside their decode counterparts as shared substrate.

| Layer | Package | Components |
|-------|---------|------------|
| Public API | `dev.hardwood.writer` | `ParquetFileWriter`, `ColumnBatch`, `WriterConfig` |
| Public API | `dev.hardwood` | `OutputFile` |
| Public API | `dev.hardwood.schema` | `FileSchema.Builder` (produces the existing immutable `FileSchema`) |
| Orchestration | `dev.hardwood.internal.writer` | `RowGroupBuffer`, `ColumnChunkBuffer`, `PageBuilder`, `RecordShredder` (rep/def-level computation for nested columns), `IntColumnSource` (value-source seam), `StatisticsCollector`, `OutputFile` backends |
| Value encoding | `dev.hardwood.internal.encoding` | `PlainEncoder`, `RleBitPackingHybridEncoder`, `LevelEncoder`, `DictionaryEncoder` (alongside the existing decoders) |
| Compression | `dev.hardwood.internal.compression` | `Compressor` / `CompressorFactory` (alongside `Decompressor`) |
| Metadata serialization | `dev.hardwood.internal.thrift` | `ThriftCompactWriter`, `FileMetaDataWriter`, `SchemaElementWriter`, `RowGroupWriter`, `ColumnChunkWriter`, `ColumnMetaDataWriter`, `PageHeaderWriter` (the `*Writer` inverses of the existing `*Reader`s) |

The thrift `*Writer` classes are pure struct serializers, the inverse of the
`*Reader`s and co-located with them; the `internal.writer` orchestration types
carry the distinct `*Buffer` / `*Builder` names so they do not collide.

### Page construction

Assembling a data page — lowering definition levels, `PLAIN`- or dictionary-encoding
the values, framing the header (`num_values`, sizes, CRC) and, later, compressing the
body — is a single seam, `PageBuilder`. It is a **per-seal operation over already-filled
buffers, not a stateful object that owns them**: the value, null, and level arrays stay
in `ColumnChunkBuffer`, primitive and type-specialized, so appends remain bulk
`copyInto` copies rather than per-value calls and each physical type keeps its own
primitive buffer without a generic boxed page. This keeps two independent axes apart —
per-type *buffer management* in the chunk buffer, per-encoding *body assembly* in
`PageBuilder`.

Through stage 6 the only page shape is `[def levels?][PLAIN values]` — struct
shredding only deepens the definition levels, it adds no body variant — so
`ColumnChunkBuffer` assembles it inline (its `sealPage`). `PageBuilder` is introduced at
**stage 7**, when list shredding prepends the repetition-level stream
(`[rep levels][def levels][values]`); `RLE_DICTIONARY` bodies (stage 9) and compressed
bodies (stage 10) then add further body and framing variants that would otherwise
accrete as branches inside the chunk buffer. Extracting the seam at the first variant,
rather than earlier, lets it take its shape from the variants it must actually span
instead of from the single `PLAIN` case.

### Schema construction

The writer reuses the immutable `FileSchema` / `SchemaNode` model unchanged — files
it writes are read back through the same model. A `FileSchema.Builder` constructs
that model programmatically and computes max definition and repetition levels. It takes
both flat fields (name, physical type, repetition) and nested groups (structs, lists,
maps); a logical-type overload (and the `FIXED_LEN_BYTE_ARRAY` type length plus `DECIMAL`
scale/precision) arrives with the logical-type increment. A schema the writer cannot yet
produce is rejected at construction rather than mid-write.

Logical types are written in two parts. The **annotation** — the `LogicalType` union
and the legacy `converted_type`/`scale`/`precision` fields on `SchemaElement` — is
serialized by a `LogicalTypeWriter` (the inverse of the existing `LogicalTypeReader`),
so a written column reads back as `STRING`/`DATE`/`DECIMAL`/etc. The **value
conversion** — accepting `String`/`LocalDate`/`Instant`/`BigDecimal` and lowering them
to physical values — is the inverse of the reader's `LogicalTypeConverter` and rides
with the row-oriented API; the columnar API takes physical values directly.

### Encoding strategy and `WriterConfig`

The writer auto-selects sensible per-column encodings and exposes overrides through
`WriterConfig`; the CLI surface stays minimal.

- **Levels**: definition and repetition levels are RLE/bit-packed via `LevelEncoder`
  (flat schemas have no repetition levels; nested columns add a repetition-level stream).
- **Values**: `PLAIN` is the correctness baseline. `RLE_DICTIONARY` is the default for
  eligible columns, matching the reader's dictionary fast paths; a column chunk that is
  not dictionary-friendly is written `PLAIN` instead. The dictionary-vs-`PLAIN` choice is
  made per column chunk from its cardinality — incrementally with a mid-chunk `PLAIN`
  fallback on dictionary-size overflow initially (stage 9), then as a row-group-global
  decision taken once the group is buffered (stage 18), so no chunk mixes encodings. That
  automatic choice is the `AUTO` encoding policy a leaf column carries by default; the
  optional delta and byte-stream-split encodings are the other policies it may be given
  instead, per column or file-wide (stage 19).
- **Compression**: `UNCOMPRESSED` first, then `GZIP` / `SNAPPY` / `ZSTD` / `LZ4_RAW` /
  `BROTLI` — the existing codec libraries are bidirectional, so the encode side reuses
  them. `LZ4`'s deprecated Hadoop framing and `LZO` are refused rather than written
  (stage 19).
  The default codec is `ZSTD` when the zstd-jni library is on the classpath and
  `UNCOMPRESSED` otherwise, so a caller who did not ask to compress is not forced to
  carry the optional dependency; selecting a codec explicitly still requires its library.

`WriterConfig` knobs: row-group size, page size, encoding policy (file-wide and per leaf
column), codec, statistics truncation length, the row layer's precision-loss policy, and
the written `created_by` string.

### `created_by`

The default identifier follows the `<app> version <version> (build <hash>)` convention
Parquet readers parse — `hardwood version 1.1.0 (build a093aab)`, with a `-dirty` suffix
on the hash when the working tree was not clean at build time. The version and hash are
baked into `hardwood-core` at build time by `dev.hardwood.internal.BuildInfo`, which reads
a filtered resource populated by the parent POM's `capture-git-info` step; a build that
cannot determine either component reports `unknown` in its place, which stays parseable.

The convention is not cosmetic. A reader that cannot parse this field cannot tell which
implementation wrote the file, and applies its writer-specific correctness workarounds by
default: parquet-java's PARQUET-251 heuristic discards the deprecated `min` / `max` of a
`BINARY` or `FIXED_LEN_BYTE_ARRAY` column written by an unidentifiable writer. Hardwood
writes only the modern `min_value` / `max_value`, which that heuristic does not gate, so a
parseable identifier is what keeps the outcome from depending on which statistics fields
the writer happens to emit.

### Statistics

`StatisticsCollector` accumulates `min` / `max` / `null_count` per column chunk
during encoding and writes them into `ColumnMetaData`, so produced files support
reader-side predicate pushdown. `min`/`max` ordering follows the column's
`ColumnOrder` (the same ordering the reader honors on read), so written statistics
are pruning-correct. The bounds are the preferred `min_value` / `max_value` — never the
deprecated `min` / `max` — and each is flagged exact via `is_min_value_exact` /
`is_max_value_exact`, so a reader may treat `min_value == max_value` as proof that a whole
chunk holds a single value. Long `BYTE_ARRAY` `min`/`max` are truncated per the format's
binary min/max truncation rule, keeping statistics bounded while remaining valid for
pruning; a truncated bound is flagged **inexact** (`is_*_value_exact = false`), since it is
then only a bound and not the actual extreme.

These are **column-chunk** statistics, feeding row-group pruning. Per-page statistics — the
`DataPageHeader` inline statistics and the OffsetIndex / ColumnIndex structures that enable
page-level skipping — arrive with page index writing (increment 23), and Bloom filters with
increment 24. A file is valid without any of them.

## Threading model

The first implementation is **single-threaded**. Correctness is the priority for a
writer, and a half-correct file is worthless.

The architecture leaves parallelism open without a public-API change: within a row
group, columns are encoded independently into separate byte buffers and only
concatenated in column order at flush, mirroring the reader's per-column workers. A
later increment can encode columns in parallel and pipeline row-group encoding
against the flush of the previous group. This machinery is added only once the
single-threaded writer is correct.

## Validation strategy

Every increment lands with tests that prove the produced bytes are a valid Parquet
file readable by independent implementations, so no unverified write code accumulates
in `main`. Cross-implementation reads are the primary check, graded by strictness: a
strict reader establishes conformance, a lenient one adds a second decoder's agreement
on the values. The read-side oracle is established in
`_designs/DIFFERENTIAL_TESTING.md` and the writer runs it in reverse.

1. **Strict-reader interop gate (primary)**: hardwood writes a file, parquet-java reads
   it, and the values are asserted to agree. parquet-java is the canonical
   implementation, so what it accepts is the operative definition of a conformant file.
   A permissive consumer agreeing on the values does not establish conformance: an
   encoder can emit a stream that only lenient decoders accept, and neither a
   hardwood-to-hardwood round trip nor a lenient engine can see it. The floor of the
   matrix is a single-entry dictionary per physical type — the shape where a zero-bit
   index stream carries no run header, which hardwood and DuckDB both accept and
   parquet-java rejects — and the matrix grows with the shapes each later increment
   introduces. `parquet-testing-runner` hosts the gate: it already carries
   `parquet-avro`, `parquet-hadoop` and `hadoop-common` at test scope, runs on every
   PR, and is where the reverse direction (parquet-java writes, hardwood reads)
   already lives. Settled in [WRITER_INTEROP_GATE.md](WRITER_INTEROP_GATE.md).
2. **DuckDB differential**: hardwood writes a file, then DuckDB reads it
   through `read_parquet('<path>')` and the values are asserted to agree. This is the
   exact inverse of [DifferentialReadTest], which has DuckDB read fixtures hardwood
   also reads; here DuckDB is the consumer of hardwood-written bytes. Because DuckDB
   shares no code with hardwood, agreement proves the bytes are spec-correct rather
   than merely self-consistent. Each row carries a synthetic index column so the
   comparison is robust to scan order (`ORDER BY` that column). Boundary values
   (signed extremes, all-null columns once nullable columns land, empty row groups)
   are written explicitly to stress the encoders.
3. **Round-trip**: write with hardwood, read back with hardwood, assert value and
   null equality. This is the fast inner-loop check and pins reader/writer agreement
   on details DuckDB does not surface (e.g. exact encodings, statistics).
4. **Property/fuzz** (later increments): random in-scope schemas and data round-tripped
   and run through the DuckDB differential, to surface edge cases beyond the
   hand-written cases (dictionary overflow, page and row-group boundaries).

Neither reader needs new tooling: DuckDB is already a `test`-scope dependency
(`org.duckdb:duckdb_jdbc`), and parquet-java is already on `parquet-testing-runner`'s
test classpath. Reading hardwood-written files with PyArrow is used for ad-hoc
verification but is not part of the automated suite.

## Delivery plan

Each increment is a shippable PR that adds user-visible capability and lands with the
validation above, so `main` never holds functionality that cannot produce a readable
file.

The sequencing is **dimension-first**: prove the thinnest slice through each
architectural dimension — paging, row-group cadence, nulls, nesting, dictionary pages,
compression, statistics — on a single type (`INT32`) before going wide. Type, encoding, and codec **breadth** is the same proven mechanism repeated, so
it is deferred until every dimension is settled; otherwise a late dimension would
reshape an already-multiplied surface (adding nulls, repetition levels, or the streaming
API after N typed methods exist rewrites all N).

Once the shape is settled, a second principle takes over: the remainder is ranked by
whether it gates *use* of the writer, not by how much it widens it. A caller needs an
ergonomic entry point, documentation, and somewhere to write to; codec and encoding
breadth, and encode-time optimizations, extend a writer that is already usable without
them. The interop gate leads that group, because it is the only check on the shapes
every later increment introduces, and the increments it precedes multiply them.

Each increment carries a **Kind**: *Dimension* (changes the shape of the solution),
*Breadth* (more of a proven mechanism), *Layer* (additive API), *Optimization*, *Gate*
(a validation barrier the increments after it depend on), *Spike* (design-only), or
*Docs* (user-facing documentation).

| # | Increment | Kind | New capability | Roadmap boxes | Completed |
|---|-----------|------|----------------|---------------|-----------|
| 1 | Tracer: flat `REQUIRED INT32`, one page / one row group, `PLAIN`, uncompressed. `OutputFile` (local + in-memory), `ThriftCompactWriter`, page-header + footer serialization. | Dimension | Produces a real, readable file | 1 (ThriftCompactWriter), 3.2 (page header ser.), 4.1/4.2 (chunk/row-group ser.), 5.2 (FileMetaData ser.) | [x] |
| 2 | Page chunking within a column chunk: a large `INT32` column written as multiple size-bounded `PLAIN` pages instead of one, replacing the single-page guard. Internal — the columnar API is unchanged. Each page carries a CRC-32 checksum over its on-disk body. | Dimension | Large columns written safely, bounded page size | 6.2 (multi-page data writing), 3.2 (CRC write) | [x] |
| 3 | **Row-group cadence** (`REQUIRED INT32` only): the `ColumnBatch` submission API, multi-row-group append, size-based auto-flush (page + row-group targets), and `WriterConfig`. Locks how the caller feeds data and how the file is banded into row groups. | Dimension | The public write cadence is settled | 6.2 (row-group size tracking, automatic flushing) | [x] |
| 4 | Nullable columns (`OPTIONAL INT32`): definition levels via `LevelEncoder`, and how nulls ride inside a `ColumnBatch`. | Dimension | The null / def-level data model is settled | 2.3, 3.3 | [x] |
| 5 | **Nested write design**: the shredding model (rep/def-level computation from struct / list / map nesting), the nested `ColumnBatch` input contract (per-layer validity + offsets, the write-side analog of `getLayerValidity` / `getLayerOffsets`), and `FileSchema.Builder` group / repeated-field support. Produces `_designs/WRITER_NESTED.md`, the reference the shredding increments implement against. | Spike | The nested write contract is settled | 6.3 (design) | [x] |
| 6 | **Struct shredding** (`INT32` leaves): `REQUIRED` / `OPTIONAL` nested groups — definition levels of depth > 1 and per-layer validity, no repetition yet. | Dimension | Nested structs written and read back | 3.3 (multi-level def), 6.3 | [x] |
| 7 | **List shredding** (`INT32` leaves): `REPEATED` fields — repetition levels via `LevelEncoder`, offset-driven nested input. | Dimension | The repetition-level data model is settled | 3.3 (rep levels), 6.3 | [x] |
| 8 | **Map shredding** (`INT32` leaves): key/value repeated group, reusing the list machinery. | Dimension | The full nested shape (structs, lists, maps) is settled | 6.3 | [x] |
| 9 | Dictionary encoding (`INT32`): dictionary page + `RLE_DICTIONARY` indices + plain fallback, exercised on nullable and nested columns so the level + dictionary-index page layout is proven together. Settled in `_designs/WRITER_DICTIONARY.md`. | Dimension | Dictionary column-chunk layout proven, incl. nulls and nesting | 2.2 | [x] |
| 10 | Compression on the write path (`INT32`, one codec). | Dimension | Compress step + compressed/uncompressed size accounting proven | 6.2 (page compression) | [x] |
| 11 | Column statistics (`INT32`: `min`/`max`/`null_count`, `ColumnOrder`-correct) accumulated during encode. | Dimension | Produced files support pushdown | 9.1 (stats) | [x] |
| 12 | All primitive physical types (incl. `FIXED_LEN_BYTE_ARRAY` type length and `BYTE_ARRAY` min/max truncation), each inheriting paging, nulls, nesting, dictionary, compression and stats. Truncated `BYTE_ARRAY` bounds are flagged inexact (`is_min_value_exact` / `is_max_value_exact` = false), extending the exactness the fixed-width types write unconditionally as true. Variable-width values end the constant-bytes-per-row assumption, so the row-group flush moves from the fixed rows-per-group proxy (`rowGroupTargetBytes / (columnCount × 4)`) to tracking the actual buffered uncompressed bytes. Settled in `_designs/WRITER_PRIMITIVE_TYPES.md`; delivered in two stacked increments (12a fixed-width `BOOLEAN`/`INT64`/`FLOAT`/`DOUBLE`, 12b variable-width `BYTE_ARRAY`/`FIXED_LEN_BYTE_ARRAY` with truncation). | Breadth | Write any column type, flat or nested | 2.1, 9.1 (truncation) | [x] |
| 13 | Logical-type annotations: `LogicalTypeWriter` serializes the `LogicalType` union and legacy `converted_type`/`scale`/`precision`; `FileSchema.Builder` logical-type overload. Both annotations are emitted together for every type with a legacy equivalent (STRING, DATE, DECIMAL, the INT/UINT widths, TIME/TIMESTAMP millis+micros, ENUM, JSON, BSON, `LIST`, `MAP`), the union taking read precedence and the `converted_type` kept for pre-union readers; only types without a legacy equivalent (UUID, FLOAT16, NANOS units, VARIANT, GEOMETRY/GEOGRAPHY) are union-only, and INTERVAL is `converted_type`-only because its union member is reserved but undefined. This makes stage 13 additive to the `converted_type`-only annotations stages 6–8 already write. An annotation also redefines the column's `min`/`max` ordering, so the statistics collectors become logical-type-aware and the footer's `column_orders` — required by the format wherever bounds are written — is emitted. Settled in `_designs/WRITER_LOGICAL_TYPES.md`; delivered in two stacked increments (13a annotations, 13b order-correct statistics and `column_orders`). | Breadth | Columns read back with their logical type (STRING, DATE, TIMESTAMP, DECIMAL, …) | 6.4 (annotation) | [x] |
| 14 | **Write-path interop gate**: `parquet-testing-runner` reads hardwood-written files with parquet-java and asserts value agreement, covering a single-entry dictionary per physical type as its floor and every shape increments 1–13 can produce. Closes the defect class where only hardwood and a lenient engine accept the bytes; the suites that precede it read back through hardwood and DuckDB, neither of which can observe it. Every increment after this one extends the matrix with the shapes it adds. Settled in `_designs/WRITER_INTEROP_GATE.md`. | Gate | Produced files are proven conformant, not merely self-consistent | — | [x] |
| 15 | **Parseable `created_by`**: `WriterConfig.DEFAULT_CREATED_BY` follows the `<app> version <version> (build <hash>)` convention Parquet readers parse, rather than a bare application name they reject. `hardwood-core` gains the build-info plumbing that identifier needs (`dev.hardwood.internal.BuildInfo` over a filtered resource fed by the parent POM's `capture-git-info` step), which `hardwood-cli`'s `Version` also consumes so the plumbing exists once. Detailed above under `created_by`. Surfaced by increment 14, whose footer assertions extend with parquet-java's `VersionParser` accepting the identifier and its PARQUET-251 heuristic consequently sparing binary statistics. | Dimension | Produced files carry a version identifier consumers can parse | — | [x] |
| 16 | **Row-oriented `RowWriter`**: an ergonomic layer on the columnar core, obtained from `ParquetFileWriter.rowWriter()` and mirroring the read side's `rowReader()`. `writeRow` takes a filler over a `StructBuilder`, with `ListBuilder`/`MapBuilder` for nesting, so fields are addressed by their user-visible names rather than by leaf paths carrying the synthetic `list.element` / `key_value` segments. Setters mirror the reader's accessors one for one and convert logical-type values to their physical representation through `PhysicalValueConverter`, the inverse of `LogicalTypeConverter`. The layer is an adapter: it transposes staged rows into a `ColumnBatch` and submits them through `writeBatch`, so every guarantee of stages 1–15 holds unchanged, asserted by writing the same data both ways and requiring byte-identical files. A file writer serves one API or the other, latched on first use. Values whose precision the column cannot hold are rejected by default, with `WriterConfig.precisionLossPolicy(TRUNCATE)` opting into dropping the digits that do not fit; a value the column cannot represent at all is rejected under either. Settled in `_designs/WRITER_ROW_API.md`. | Layer | Mainstream-friendly API | 6.1 (`RowWriter`), 6.4 (value conversion) | [x] |
| 16a | **Annotation range checks on the columnar API**: `ColumnBatch` rejects a value outside the range its column's annotation declares, as the row layer already does. An annotation narrows what a physical type may hold — an `INT(8)` `INT32` to `[-128, 128)`, a `UINT_8` to `[0, 256)`, a `DECIMAL(p, s)` to unscaled values of at most `p` digits — and the columnar setters currently take any value the physical type can carry, so a caller can produce a file whose values fall outside the range its own annotation declares. A `uint8` consumer reads 300 back as 44, and the bounds written into the column's statistics describe values the annotation says cannot exist. This is per-value validation of the kind `ColumnBatch` already performs — `validateBinaryValues` checks every `FIXED_LEN_BYTE_ARRAY` value against the declared type length — extended to the numeric annotations. Closing it makes rejection a property of the writer rather than of which of the two APIs the caller picked. Settled in `_designs/WRITER_ANNOTATION_RANGES.md`. | Gate | Neither write API can produce a value outside its column's declared range | 6.4 (annotation) | [x] |
| 17 | **Flat write benchmark**: `FlatWriteBenchmark` in `performance-testing/micro-benchmarks` — Hardwood's columnar and row-oriented APIs against parquet-java's `ExampleParquetWriter` over a seeded, taxi-shaped six-column fixture held in memory, with every writer setting matched across contenders and the produced file sizes reported next to the times. The write-side counterpart to `FlatPerformanceTest`, and the baseline the throughput stages that follow are argued against. Settled in `_designs/FLAT_WRITE_BENCHMARK.md`. | Benchmark | A measured write baseline | — | [x] |
| 18 | **Row-group-global dictionary selection** (#975): replace the per-column-chunk optimistic build with mid-chunk `PLAIN` fallback (stage 9) with a choice made once the row group is fully buffered — each column chunk is encoded `RLE_DICTIONARY` or `PLAIN` as a whole from its true cardinality, so no chunk mixes encodings and no dictionary page is written for a chunk that ends up `PLAIN`. Trades a second encode pass and higher peak buffer occupancy for the optimal per-chunk choice; the payoff scales with the variable-width types from stage 12, where indices are far smaller than the values and the dictionary byte-limit is a poor proxy for whether encoding pays. With the whole group buffered the choice follows from the exact cardinality — and, where the byte-limit proxy is weakest, a direct comparison of the two encodings' sizes — so no predictive threshold is needed; the stage-9 streaming abort heuristic does not apply here. The public consequence is `WriterConfig.dictionaryPageLimitBytes`, which exists only as that predictive threshold and is removed with the fallback it triggers; the memory bound the analysis still needs is derived from `rowGroupTargetBytes` rather than configured. Nothing has shipped that depends on the option, and the stage precedes the writer's user documentation so the option never reaches a published page. Settled in `_designs/WRITER_DICTIONARY_SELECTION.md`. | Optimization | Optimal, uniform per-chunk encoding choice | 2.2 | [x] |
| 19 | **Remaining codecs + optional delta and byte-stream-split encoders** (#976). Every codec `CompressionCodec` names is either produced or refused with a reason specific to why, and the four optional encodings become part of a single per-leaf-column encoding policy on `WriterConfig` — `AUTO` (the stage 18 size comparison), `PLAIN`, or one of the four — set file-wide or per leaf path and validated against each column's physical type at writer creation. The policy subsumes `WriterConfig.enableDictionary`, which is removed with it: dictionary-or-`PLAIN` is the whole of what `AUTO` decides, so the boolean was a two-valued encoding policy named before there were encodings to name it with, and `enableDictionary(false)` is a file-wide `PLAIN` exactly. As with stage 18's `dictionaryPageLimitBytes`, nothing has shipped that depends on it and the stage precedes the writer's user documentation, so one concept rather than two reaches a published page. The optional encodings are selected rather than inferred: the stage 18 comparison decides between a dictionary and `PLAIN` from what the buffer already retains, where a delta encoding's size is a property of value order and byte-stream-split's is a property of the codec that follows it, neither of which is decidable without a trial encode. Every codec is a new axis value on the stage 14 interop gate, and the optional encodings are ones that gate's readers must read back, so this breadth lands inside the gated series rather than after it. Settled in `_designs/WRITER_CODECS_AND_ENCODINGS.md`; delivered in two stacked increments (19a codecs, 19b encodings). | Breadth | Full codec / encoding choice | 2.4, 2.5 | [x] |
| 20 | User-facing documentation under `docs/content/` for the writer public API (`OutputFile`, `ParquetFileWriter`, `FileSchema.Builder`): a `how-to` guide and a `reference` page, covering the settled surface including nesting, the row-oriented layer, and the codec, encoding and dictionary options stages 18–19 settle. | Docs | Documented, stable public API | — | [ ] |
| 21 | S3 `OutputFile` backend: sequential multipart upload — buffer to the part size, upload parts, complete on `close()`. In-flight bytes bounded to the part size times a small concurrency multiple; lazy `CreateMultipartUpload` deferred to the first part flush, with a single `PutObject` fallback for a sub-part output. Reuses the read-side S3 / SigV4 stack. | Layer | Write directly to object storage | — | [ ] |
| 22 | Parallel column encoding + row-group pipelining. | Optimization | Write throughput | — | [ ] |
| 23 | **Page index writing**: per-column-chunk OffsetIndex (page locations) and ColumnIndex (per-page `min`/`max`, null counts, boundary order), written after the row group's pages and referenced from the footer, so the reader can skip individual pages. Extends the column-chunk statistics of increment 11 to page granularity; the `DataPageHeader` inline `statistics` are the pre-index fallback covered here. Truncation and `is_*_value_exact` follow the increment 11 / 12 rules. Its own design. | Layer | Page-level pruning on produced files | 9.2 | [ ] |
| 24 | **Bloom filter writing**: a split-block Bloom filter per eligible column chunk (XXHASH64), serialized with its header and referenced from the column metadata, for equality-predicate pruning where `min`/`max` do not help. Its own design. | Layer | Bloom-filter pruning on produced files | 9.3 | [ ] |
| 25 | **Cardinality sketch after the analysis cap** (#979): keep deciding a chunk's encoding once its dictionary outgrows the analysis cap, rather than defaulting to `PLAIN` because the count is no longer known. The cap fires, the values are materialized and the hash table released as they are today, but a bounded-error distinct counter carries on, and at flush the stage-18 comparison runs against its estimate; a chunk the estimate puts clearly ahead on a dictionary rebuilds one in a pass over its retained values. Decouples the memory bound from the encoding decision, which stage 18 conflates: a column whose dictionary is large but whose values repeat enough to pay for it is written `PLAIN` today whenever the cap fires. Only worth doing if the cap is tightened to buy back encode throughput, which is what makes the case common. | Optimization | The cap bounds memory without deciding encodings | — | [ ] |
| 26 | **Row groups of about N MB on disk** (#980): a target on a row group's *produced* size, alongside the uncompressed-bytes target that bounds memory today, flushing at whichever is reached first. On-disk size is what governs read parallelism and split sizing, and it is currently unaskable: the same 16 MiB target produces 1 MB row groups for one column and 16 MB for another. With each chunk's encoding now settled at flush, the ratio of produced to buffered bytes is measurable per row group and can steer the next one. | Layer | Row-group size a reader can plan against | — | [ ] |
| 27 | **Byte-based page cuts** (#981): cut a data page when its *encoded* bytes reach `pageTargetBytes`, rather than at an entry count computed once per file from the widest column's `PLAIN` width. The target is honoured for one column per file today: at a 1 MiB target, a `DOUBLE` column's pages land on it while a two-bit dictionary column's are 32× under and a `BOOLEAN` column's 64× under, each paying a page header and a compression call for 16–32 KB of data. Stage 18 settles a chunk's encoding before any page is produced and retains every value's exact size, so the estimate's two guesses — an assumed `BYTE_ARRAY` length, and a `PLAIN` width for a chunk encoded at a few bits per value — are no longer needed. Wants a row cap alongside the byte target, whose default belongs with page index writing (23) rather than here, since page size is also pruning granularity. | Optimization | `pageTargetBytes` means what it says, for every column | — | [ ] |
| 28 | **`distinct_count` for the chunks that cannot state it** (#982): the exact cardinality is written today wherever a chunk still held what it counted with when its encoding was chosen, and for `BOOLEAN` chunks, which know it without a dictionary. It is absent for a chunk whose dictionary outgrew the analysis cap and for one written with dictionary encoding disabled. The sketch of 25 cannot serve this — the format asks for the count of distinct values occurring, not an estimate — but pass two holds every value of the chunk, so an exact count without a hash structure is available from a sort over a fixed-width column's values, at a cost worth weighing against the field's value. | Breadth | Cardinality metadata on every chunk | — | [ ] |
| 29 | **Caller-controlled row-group boundaries** (#985): `ParquetFileWriter.endRowGroup()` closes the open row group and starts a new one, so a boundary can fall where the writer cannot see that it should — a sort-order break, a partition-key change, an upstream batch boundary, or a row count no byte target expresses. Row groups are cut today only by `rowGroupTargetBytes` and the arrival of data, and the batch split at that bound is the only cut point that exists; nothing lets a caller say the rows it is about to write belong in a new group. The byte target stays the automatic bound and the explicit switch is an additional trigger, so a group closes at whichever comes first. A call with nothing buffered is a no-op, so calling it unconditionally at every partition boundary cannot produce an empty row group, and the row layer drains its staged rows before the cut so the byte-identical equivalence between the two write APIs holds. The switch also carries an optional `WriterConfig`, which supersedes the one in force for the row groups that follow: `ColumnMetaData` holds a codec and an encoding list per column chunk, so the format lets both change from one row group to the next, and a boundary the caller places is the only point at which naming a new one is meaningful. Everything in `WriterConfig` is already per row group or narrower — page and row-group targets, statistics truncation, precision-loss policy, encoding policies, codec — except `created_by`, which the footer states once and which is therefore rejected when a passed configuration would change it. The new configuration governs the group that starts; the group being closed is written with the one in force while its rows were buffered. It is validated and its compressor resolved before the open group is closed, as `create` resolves before `out.create()`, so a configuration the writer cannot honour leaves both the file and the writer intact. Amending rather than replacing wants `WriterConfig.toBuilder()`, which does not exist yet. Hardwood reads such files already — `PageDecoder` resolves a decompressor per chunk and dive reports a codec histogram per row group — and produces none, so this stage also supplies the mixed-codec fixtures the read path is written for. Its own design, and its own docs update, stage 20 having shipped by then. Arrow C++ (`NewRowGroup`) and arrow-rs (`next_row_group`) expose the boundary control; neither, nor parquet-java, lets the settings change with it. | Layer | Row groups aligned to what the caller knows, and settings that can follow the data | 6.2 | [ ] |

Increments 1–4 settle the flat dimensions on `INT32`; 5–8 settle the nested shape —
design, then struct / list / map shredding — on `INT32`; 9–11 finish the remaining
dimensions (dictionary, compression, statistics) on the now flat-and-nested shape; 12–13
are breadth on the settled shape. Increment 14 then gates everything those thirteen
produce against a strict reader, 15 fixes the first thing that gate found, and 16 makes
the result usable by an external caller with the row-oriented layer. Between them, 16a
carries the range checks the row layer established back to the columnar API, so which
entry point a caller picks does not decide whether an out-of-range value is caught, and 17
measures what the two entry points cost against the incumbent — the baseline every
throughput claim after it is argued against, and the increment that quantified what 18 is
worth. Then 18 makes the encoding choice optimal rather than optimistic and 19 completes
the codec and encoding breadth, both inside the gate that 14 established, before 20
documents the whole of it. Those eight are the content of `1.1.0.Beta1` — a
conformance-gated, ergonomic, measured, optimally-encoding, documented writer, local
output only.

Increment 21 opens `1.1.0.Beta2` with the object-store backend, and 22 optimizes the
encode underneath it with parallel column encoding. Together, increments 1–22 constitute
the write-support milestone (#9); 23–24 follow it, adding the optional index structures
(page indexes and Bloom filters, with the per-page statistics that make page-level
pruning possible) on the settled surface. Increments 25–29 follow it too, as refinements
of what the milestone settles rather than steps that depend on one another: 25 lets the
dictionary analysis stay a memory bound without also being an encoding verdict, 26 gives a
row group a size a reader can plan against, 27 makes the page target mean what it says for
every column rather than the widest one, 28 fills in the cardinality metadata for the
chunks that cannot state it, and 29 hands the caller a boundary the milestone leaves
entirely to the byte target. None is sequenced against 23–24, though 27's row cap is a
decision better taken alongside 23. Only 29 among them adds public API; 26 and 29 are the
two ends of the same question — how large a row group is, and where one ends — so a
decision taken in either is worth carrying to the other. Sequenced as separate later milestones, each
its own design and sequence: DataPage V2, the Avro write adapter, and a CLI write/convert
command.

## User documentation

User-facing documentation under `docs/content/` is delivered at stage 20, the last
increment of `1.1.0.Beta1`, once every stage that shapes the writer's public surface has
landed: the row-oriented layer (16), the dictionary options (18), and the encoding policy
and codec values (19), which replace the last of those options with the policy that
subsumes it. Documenting the provisional `INT32`-only surface earlier would be
throwaway, and documenting it between 16 and 18 would mean writing the `WriterConfig`
option matrix twice, so deferring until the surface is settled is a recorded, intentional
exception to the CLAUDE.md rule that a new public API ships with a docs update — not an
oversight. Stage 15 changes only the default value of a `WriterConfig` option, which the
stage 20 reference page documents along with the rest.

The increments after stage 20 extend that surface additively rather than reshaping it, so
each carries its own docs update in the normal way: stage 21 adds an S3 `OutputFile`
factory, and stage 29 an `endRowGroup()` on `ParquetFileWriter`, which the how-to guide
covers alongside the size targets it complements. Stage 22 and the index stages that
follow are internal encode decisions and additive file structures with no public API
surface of their own.

## Roadmap reconciliation

`ROADMAP.md` remains the fine-grained capability inventory; this document is the plan
of record for writer architecture and sequencing. The mapping column above ties each
increment to the roadmap boxes it completes, so those boxes are ticked as increments
land. Phase 6 of `ROADMAP.md` points here.
