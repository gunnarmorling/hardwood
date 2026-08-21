# Write-path coverage assertion (#9, stage 30)

**Status: Proposed.** Tracking issue: #990. Delivery stage 30 (Gate) of
[WRITER_SUPPORT.md](WRITER_SUPPORT.md). Extends the gate established in
[WRITER_INTEROP_GATE.md](WRITER_INTEROP_GATE.md).

## Context

The interop gate asserts that parquet-java reads what Hardwood writes. Its matrix varies one
axis at a time against a representative base, and its extension contract says every writer
increment extends that matrix with the shapes it introduces, in the same commit that
introduces them.

Whether an increment honoured that contract is a judgement call, and a missed extension is
invisible: from outside, a cell no test ever produced looks exactly like a covered one. The
gate itself already recognizes the problem one level down — it asserts that each case
produced the dictionary, `PLAIN` chunk or page count it exists to cover, "without this a
change to the writer's encoding choice or page sizing would silently empty an axis rather
than fail it". That reasoning stops at the case. Nothing applies it to the matrix as a whole.

Stage 19 is what makes the gap material. The writer now produces six codecs and resolves five
named encoding policies plus `AUTO`, each legal on a different subset of the seven writable
physical types. The space multiplied; the sweep did not, and by construction cannot: it varies
one axis at a time, so `DELTA_BYTE_ARRAY` never meets a codec other than the base case and
`BYTE_STREAM_SPLIT` never meets a `FIXED_LEN_BYTE_ARRAY` of a length other than eight.

Two further gaps sit in the annotation half of the gate, where the tables are enumerated by
hand:

- `IntType` appears at four of its eight width/sign combinations. `TimeType` and
  `TimestampType` appear at three of six each, with the unit confounded with
  `isAdjustedToUTC` — MILLIS only ever adjusted, MICROS only ever local — so a path that drops
  the flag on one unit cannot fail. `DecimalType` appears at no carrier's precision boundary
  and never with `scale == precision`.
- The boundary values are physical rather than logical. `TypeFixture` carries
  `Integer.MIN_VALUE`, `NaN`, `-0.0`, the empty binary and bytes above `0x7f`. An annotation
  declares a **narrower** range — `LogicalTypeValueRange` computes it exactly — and no test
  writes a column at that range's ends. Unsigned `INT(8)` is never written at 0 and 255,
  `DECIMAL(2, 9)` never at ±999999999, `TIME(MILLIS)` never at 86399999.

The ends of a range are where the statistics comparator is fragile, which is what makes them
worth reaching. An unsigned `INT(32)` maximum is stored as `-1` and must compare unsigned; a
binary `DECIMAL` bound needs sign extension; a `BYTE_ARRAY` maximum of all-`0xff` bytes past
`statisticsTruncationLength` must truncate *and* increment, or it stops bounding the column.
The gate already reduces bounds with parquet-java's comparator, so a value written at the end
of its range is checked against an independent implementation of the order — but only if some
test writes one.

## Goal

Every combination the writer can produce is either produced by some test, or waived with a
stated reason. A combination that is neither fails the build.

## The mechanism

Three parts: a domain derived from the writer's own capability tables, an observation recorded
from the bytes each test produced, and a verdict that diffs them.

### Observation

Every write-path test already funnels its file through `ParquetJavaReader`, so that is where
observation belongs. `readPages` and `readFooter` record what they walked past into a
`CoverageRegistry`:

- from each data page header, the `(physical type, page value encoding, codec, repetition
  shape)` the page declared, plus the column's `FIXED_LEN_BYTE_ARRAY` length where it has one;
- from the footer schema, each column's `(annotation, carrier physical type, dictionary or
  not)`.

No test opts in. `WriterInteropTest`, `WriterNestedInteropTest`, `WriterLogicalTypeInteropTest`
and `RowWriterLogicalTypeInteropTest` contribute by running, and so does every test added
later.

What is recorded is what parquet-java found in the file, never what the test intended to
write. This is the same distinction the gate draws when it reads page-level value encodings
rather than the column chunk's `encodings` union: a writer that silently stopped producing an
encoding would still satisfy a registry keyed on intent.

The third registry has no file to read. `LogicalTypeValueRange` governs values as they are
offered to the writer, so range coverage is recorded by the helper that writes them: the
`(annotation, boundary class)` pair each column's values reached, and each rejection a test
asserted.

### Domain

Hand-listing the domain would reproduce the problem it exists to solve, so every dimension is
derived:

| Dimension | Derived from |
|---|---|
| Physical types | `PhysicalType.values()`, less `INT96`, whose refusal carries its own pinned-reason test |
| Page encodings | `ColumnEncoding` × its per-type legality, resolved to the encodings a page can declare: `AUTO` reaches `PLAIN` and `RLE_DICTIONARY`, every other policy names one outright |
| Codecs | `CompressionCodec.values()`, split into the six produced and the two refused |
| Repetition shapes | `InteropCase.Nullability`, plus the nested shapes `WriterNestedInteropTest` enumerates |
| Annotation kinds | `LogicalType.class.getPermittedSubclasses()` |
| Annotation parameters | Declared per parameterized kind (below) |
| Boundary values | `LogicalTypeValueRange`, per annotation |

The sealed-hierarchy walk is what makes the domain grow on its own: a member added to
`LogicalType` extends the writer and fails the verdict in the same commit, rather than
extending one and leaving the other to be noticed.

Two capability tables the domain needs are not reachable from the test module today, and move
rather than being mirrored — a mirrored table drifts, which is the defect this design exists
to catch:

- `ColumnEncoding.supports(PhysicalType)` is package-private in `dev.hardwood.writer`. The
  legality table moves to `dev.hardwood.internal.writer`, where the enum and the test both
  read it.
- `LogicalTypeValueRange` computes `min`, `max` and the binary-`DECIMAL` `unscaledBound` and
  exposes none of them. It gains accessors, so the boundary values a test writes are the ones
  the writer itself derives.

Both targets are internal packages, which sibling modules may depend on directly.

### The required projections

The full cross product — physical types × encodings × codecs × repetition shapes ×
annotations — is neither reachable nor meaningful. What is required instead is a set of
pairwise projections, each admitted because a defect class lives in that pair and in no
smaller one:

| Projection | Cells | The defect it targets |
|---|---|---|
| physical type × page encoding | 23 | The value encoders are per type. This is the #901 class: a stream only a lenient decoder accepts. |
| page encoding × codec | 36 | Framing over an unusual page body — the axis stage 19 widened on both sides at once. |
| physical type × repetition shape | 28 | The level streams and the value stream are written together and read together. |
| `FIXED_LEN_BYTE_ARRAY` length × page encoding | 16 | `BYTE_STREAM_SPLIT` scatters by byte position and `DELTA_BYTE_ARRAY` shares prefixes; both are length-sensitive, and the flat fixture pins the length at eight. |
| annotation × {dictionary, non-dictionary} | ~90 | An annotation's comparator governs the chunk's bounds in either storage form, and the dictionary path reaches them through a different accumulator. |
| annotation × boundary class | ~115 | Below. |

Around 300 required cells, each with a stated reason, against a cross product in the tens of
thousands. The projections accumulate independently, so the existing one-axis-at-a-time sweep
fills most of them without being restructured.

### Annotation parameters

The sealed walk enumerates kinds. The parameterized kinds declare their own domain, chosen so
that each parameter varies independently of the others:

| Kind | Required points |
|---|---|
| `IntType` | {8, 16, 32, 64} × {signed, unsigned} — 8 |
| `TimeType` | {MILLIS, MICROS, NANOS} × {adjusted, local} — 6 |
| `TimestampType` | {MILLIS, MICROS, NANOS} × {adjusted, local} — 6 |
| `DecimalType` | per carrier — `INT32`, `INT64`, `BYTE_ARRAY`, `FIXED_LEN_BYTE_ARRAY` — at precision 1 and the carrier's maximum, each at scale 0 and at scale equal to the precision |

The carrier maximum comes from `LogicalTypeValidator`, which already computes it: nine digits
for `INT32`, eighteen for `INT64`, `maxFixedPrecision(typeLength)` for a fixed carrier. Scale
equal to precision is the point at which a bound derived by arithmetic on the precision
overflows, and no test writes one today.

`VariantType` is waived: the writer rejects it, and the rejection is asserted where
`LogicalTypeValidator` raises it.

### Boundary classes

Per annotation, five classes, derived from the range the annotation itself declares:

| Class | Required of |
|---|---|
| `MIN`, `MAX` | A bounded annotation, at the ends `LogicalTypeValueRange` computes |
| `INTERIOR` | Every annotation |
| `BELOW_MIN`, `ABOVE_MAX` | A bounded annotation, asserted **rejected** through both write APIs |

An annotation that bounds nothing — `INT(32)` on an `INT32`, `INT(64)` on an `INT64`, and
every annotation over a binary or floating-point carrier — takes the physical type's own
extremes in place of `MIN` and `MAX`, and requires no rejection. Those are the cells where the
comparator matters most: the unsigned maximum is spelled as a negative, and only an unsigned
comparison orders it correctly.

`UNKNOWN` is the degenerate case. `LogicalTypeValueRange` reports it as holding no value at
all, so its required classes are the nulls its columns carry and the rejection of any value.

The rejection classes are required through **both** write APIs. `LogicalTypeValueRange` is
applied by the columnar batch path and by `RowWriter` alike, so a check present in one and
missing from the other is exactly the defect the pair exists to catch.

## Waivers

A cell that cannot be covered is waived as a `(cell, reason, issue)` record. `BROTLI` is the
standing example: parquet-java resolves it through a Hadoop codec name that ships in neither
parquet-java nor Hadoop, so every `BROTLI` cell is waived against the DuckDB coverage in
`WriterDifferentialTest` and the pinned-reason test that already states why.

A waiver is a claim about the world, so it is checked in both directions: **a waived cell that
is nonetheless observed fails the verdict**. Without that, a waiver outlives its reason — a
parquet-java release that gains the codec would leave `BROTLI` waived by inertia, which is
precisely what the existing `parquetJavaHasNoBrotliCodec` test exists to prevent for one
codec and what this generalizes to every cell.

## Placement and mechanics

`parquet-testing-runner`, beside the gate it extends. `CoverageRegistry` and the waiver list
sit next to `ParquetJavaReader`, which is the module's single point of contact with the strict
reader.

The verdict spans test classes, so it cannot be an `@AfterAll`, and Surefire may fork per
class, so it cannot rely on a static registry surviving to the end of the run. A
`TestExecutionListener`, registered through `META-INF/services`, appends the run's
observations to `target/write-coverage/` on `testPlanExecutionFinished`; a second Surefire
execution then runs `WriteCoverageVerdictTest`, which merges what the forks recorded and
asserts the projections. The intermediate files are the report: a failure names the empty
cells rather than a count.

## Landing

The first verdict against the current suite reports every gap this document names and, in all
likelihood, gaps it does not. The mechanism therefore lands with those gaps waived against
#990, and the waivers are burned down after — the same shape as the `parquet.thrift` field
oracle, which landed with its unverified fields enumerated as PENDING rather than waiting
until none remained. A single commit that filled every gap first would hide which gaps were
real, and the burndown is where the defects are.

## Non-goals

- **Replacing the sweep.** The projections say which cells must be reached, not how. The
  matrix, the nested enumeration and the annotation tables remain what produces the files;
  this asserts that between them they leave nothing out.
- **Asserting values.** A cell is covered when a conformant file containing it was produced
  and read. What the values had to be is the gate's assertion, unchanged.
- **Covering the read path.** The read direction's corpus is the `parquet-testing` fixtures,
  whose contents no assertion of ours governs. Coverage there is a question about the
  corpus, not about a producible space.
- **Measuring code coverage.** The domain is the writer's declared capabilities, not its
  lines. A branch reached by no test and a capability produced by no test are different
  questions, and only the second one has a matrix.
