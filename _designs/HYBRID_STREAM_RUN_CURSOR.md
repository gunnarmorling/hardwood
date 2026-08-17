# Design: run-structured hybrid-stream cursor

**Status: Implemented (opt-in).** Tracking issue: #726.

## Goal

Consume Parquet RLE/bit-packing hybrid streams *run by run* instead of
materializing and re-scanning page-sized `int[]` arrays, for **definition
levels** and **dictionary indices** on flat dictionary-encoded columns. A
pull-based run cursor exposes constant (RLE) runs in O(1) and unpacks bit-packed
runs only into bounded chunks. The flat column assembler drives two cursors in
lockstep, so value scatter and validity construction skip the materialise–walk
cycle entirely.

The path is a reader-only optimisation. It requires no Parquet format change and
no writer change. It ships **opt-in** via the `hardwood.cursor-decode`
[ReaderConfig](READER_CONFIG.md) option (default off) while it matures; the
materializing path remains the stable default and the behavioural oracle.

## Background & motivation

Definition levels, repetition levels, and dictionary indices are encoded with
the RLE/bit-packing hybrid: a sequence of *runs*, each either an RLE run
(`value` repeated `length` times) or a bit-packed run (`length` distinct
values). The materializing path flattens every stream into an `int[numValues]`
and lets downstream passes re-scan it element by element. That
materialise-then-walk cycle does unnecessary CPU work whenever the run structure
already carries the answer:

- A **definition-level stream** that is a single RLE run of `maxDef` (all
  present) — the materializing path fills the entire `int[numValues]`, then the
  assembler walks it checking each value. The fused path handles this in O(1).
- **Long null runs** in nullable columns — the materializing path expands the
  run, the assembler walks the zeros, and the dictionary-index stream is
  consumed for positions that will be discarded. The fused path skips both
  streams across the null run.
- **Low-cardinality index runs** — a single dictionary entry repeated thousands
  of times is expanded into the index array, then each element triggers a
  dictionary lookup. The fused path resolves the value once and does a bulk
  `Arrays.fill`.

The cost being removed is **CPU time and memory bandwidth**: unpacking values
that need not be unpacked, walking arrays that encode information already
available in the run header, and polluting cache lines with page-sized
intermediates when batch-sized buffers suffice. (The underlying `int[]` buffers
are already pooled since #814 via `LevelScratch` and a `ThreadLocal` index
array, so allocation pressure is not the concern — work reduction is.)

Issue #721 takes the narrow slice (detect "the whole page is one RLE run of
`maxDef`"); this design is the general form that handles every run shape.

## Scope

**In scope:**

- Flat columns (`maxRepetitionLevel == 0`) with optional leaves
  (`maxDefinitionLevel > 0`).
- Flat columns (`maxRepetitionLevel == 0`) with required leaves
  (`maxDefinitionLevel == 0`) — index-only fused path.
- Data pages encoded `RLE_DICTIONARY` or `PLAIN_DICTIONARY`.
- Definition-level hybrid stream and dictionary-index hybrid stream, consumed
  via `HybridStreamCursor`.
- All physical dictionary types handled by `FlatColumnWorker` (INT32, INT64,
  FLOAT, DOUBLE, BYTE_ARRAY / FIXED_LEN_BYTE_ARRAY).
- `DataPageV1` and `DataPageV2` (the fused gate is encoding- and level-shape-
  based; a page version only changes how the level/value regions are sliced).
- Transparent fallback to the materializing path when the gate does not fire.
- Behavioural parity with the materializing path (oracle tests).

**Out of scope:**

- **Repetition levels** and nested columns (`maxRepetitionLevel > 0`). Nested
  assembly consumes def/rep with irregular lookahead; start with the high-value
  regular flat case. `NestedColumnWorker` never advertises fused support.
- PLAIN (non-dictionary) data pages, including plain pages that still share a
  column dictionary.
- BOOLEAN columns (values use `RLE`, not `RLE_DICTIONARY`).
- Removing the materializing path (kept as default and oracle until the fused
  path is unconditionally trusted).

## Design

### `HybridStreamCursor`

A pull-based, single-use, forward-only cursor over one RLE/bit-packing hybrid
stream. API:

| Method | Role |
|--------|------|
| `advance()` | Load the next run; `false` at end-of-stream |
| `isRle()` / `value()` | Constant-run branch: value known in O(1) |
| `remaining()` | Values left in the current run |
| `unpack(dst, off, max)` | Bit-packed branch: fill up to `max` values |
| `skip(count)` | Advance past `count` values (O(1) for RLE; bit arithmetic for packed) |

**Lifetime safety.** The cursor is built on the decode thread from a **private
copy** of the encoded byte slice, then consumed later on the drain thread. The
source buffer is typically a pooled decompression region overwritten by the next
page decode. Owning the bytes eliminates a time-of-use race without locking. The
copy is of the small encoded stream, not of a materialized value array.

**Bit-unpack kernel.** `HybridStreamCursor.decodeBitPacked` intentionally
duplicates the equivalent logic in `RleBitPackingHybridDecoder.decodeBitPacked`.
Sharing state would re-introduce the lifetime dependency the copy was designed
to break. Optimisations to one must be mirrored in the other (cross-reference
comments document this).

### Fused path gate

`PageDecoder` enables the fused path when **all** of:

1. The column worker advertises support via `supportsFusedPath()` — only
   `FlatColumnWorker`, and only when `hardwood.cursor-decode` resolved to true.
2. Page encoding is `RLE_DICTIONARY` or `PLAIN_DICTIONARY`.
3. `maxRepetitionLevel == 0` (flat).

Two modes are distinguished by `maxDefinitionLevel`:

- **Def+Index fused** (`maxDefinitionLevel > 0`): the decoder constructs two
  `HybridStreamCursor` instances (def levels and dictionary indices) and stores
  them on the `Page` alongside the typed `Dictionary`. Accessors
  `Page.defLevelCursor()` and `Page.indexCursor()` are both non-null.

- **Index-only fused** (`maxDefinitionLevel == 0`): no def-level stream exists.
  The decoder constructs only an index cursor. `Page.defLevelCursor()` is null;
  `Page.indexCursor()` and `Page.dictionary()` are non-null.

When neither mode fires, behaviour is unchanged: levels and indices materialize
through `RleBitPackingHybridDecoder`.

### Fused drain (`FlatColumnWorker`)

`assemblePage` detects fused pages and routes into the appropriate drain:

**Def+Index fused** (`defLevelCursor != null`): `copyPageDataFused` interleaves
def-level consumption with index scatter:

| Def-level run | Action |
|---------------|--------|
| RLE, value == `maxDefinitionLevel` | Bulk-scatter indices via `copyIndexValues`; validity range set in O(1) |
| RLE, value < `maxDefinitionLevel` | `fillNulls` (no index consume); validity bits for absents in O(1) |
| Bit-packed | Unpack a chunk into `tempDefs`, coalesce consecutive present/absent positions into sub-runs, then bulk `copyIndexValues` / `fillNulls` |

**Index-only fused** (`defLevelCursor == null`, `indexCursor != null`):
`copyPageDataIndexOnly` drives the index cursor directly. All values are present
(required column), so no validity bitmap management is needed and no null fills
occur — a straight call to `copyIndexValues`.

Within `copyIndexValues` the index cursor uses the same run dispatch:

| Index run | Action |
|-----------|--------|
| RLE | `Arrays.fill` (or typed bulk write) from `dictionary[value]`, O(repeat count) |
| Bit-packed | Unpack into `tempIndices` (length ≤ batch capacity), then scatter |
| Bit-width 0 | Empty stream; every value is dictionary entry 0 — constant fill, no advance |

`tempDefs` / `tempIndices` are worker-scoped buffers sized to **batch capacity**,
not page size — keeping the working set small enough to stay L1/L2 resident
instead of touching the full page-sized pooled arrays on the materializing path.

### Validity bitmap

`FlatColumnWorker` keeps a packed `long[]` validity bitmap (set bit = present).
The fused path uses `BitmapWords.setRange` for bulk present runs instead of the
per-value `markNulls` walk. Absent slots are zeroed by `fillNulls` so recycled
batch arrays stay deterministic at null positions (matching the materializing
path, which overwrites every slot from a freshly decoded page array).

## Implementation map

| Component | Role |
|-----------|------|
| `HybridStreamCursor` | Run cursor over one hybrid stream |
| `PageDecoder` | Two-mode gate; build def+index or index-only cursors instead of `int[]` |
| `Page` | Optional `defLevelCursor` / `indexCursor` / `dictionary` |
| `Dictionary.decodePage(HybridStreamCursor, …)` | Fused page factory (null value arrays) |
| `Dictionary.decodePageIndexOnly(HybridStreamCursor, …)` | Index-only page factory (required columns) |
| `FlatColumnWorker` | `supportsFusedPath`, `copyPageDataFused`, `copyPageDataIndexOnly`, `copyIndexValues` |
| `ColumnWorker` | Threads `supportsFusedPath()` into `PageDecoder` |
| `ParquetFileReader` | Resolves `hardwood.cursor-decode` (default `"false"`) |
| `ReaderConfig` | String option bag; see [READER_CONFIG.md](READER_CONFIG.md) |

Configuration is resolved once at `ParquetFileReader.open` and threaded to
column/row reader factories. Unknown option keys are ignored but logged at
`WARNING`.

## Verification

### Correctness

`FusedRunCursorParityTest` runs the materializing path and the fused path over
the same fixtures and asserts element-by-element value and validity parity.
Fixtures include multi-type samples and `run_cursor_tiny_pages.parquet`, which
covers:

1. A cursor outliving its source decompression buffer (byte-copy safety).
2. A page whose values all map to a single dictionary entry (bit-width-0 index
   stream).

`HybridStreamCursorTest` covers pure RLE, mixed RLE + bit-packed, and mid-run
`skip`.

### Performance measurement

Multicore end-to-end wall-clock is bound by decompression and pipeline
coordination and does not resolve a decode-CPU change at a small scale. Validation
uses **single-core JMH**:

- `CursorDecodeBenchmark` — `HardwoodContext.create(1)`, `@Threads(1)`,
  UNCOMPRESSED dictionary corpus, A/B via `hardwood.cursor-decode`.
- Scenarios (from `performance-testing/generate_cursor_data.py`):

  | Scenario | Exercises |
  |----------|-----------|
  | `all_present` | All-present def-level RLE (generalizes #721) |
  | `null_heavy` | All-null RLE runs and present stretches (~50% null in blocks) |
  | `low_card` | RLE-rich dictionary index stream (dict size 4, long index runs) |
  | `requiredFloor` | Required column (dict size 256, 8-bit indices) |
  | `requiredLowCard` | Required column (dict size 4, long RLE index runs) |
  | `requiredHighCard` | Required column (dict size 4096, 12-bit indices) |

## Limitations & follow-ups

- **Plain data pages with a column dictionary** — need a plain-index path or
  partial materialisation.
- **Repetition-level cursors / nested assembly** — separate design; nested
  lookahead does not map cleanly onto the flat fused drain.
- **Default-on** — flip `hardwood.cursor-decode` default (and eventually retire
  the key) only after parity and field confidence match the materializing path.
- **Bit-unpack vectorization (#680)** — re-evaluate after fusion against a
  CPU-time profile; far fewer values are unpacked on RLE-heavy streams, so the
  payoff may shrink.
