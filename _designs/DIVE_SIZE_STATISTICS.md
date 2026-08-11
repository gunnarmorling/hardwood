# Design: size statistics and level histograms in the CLI

**Status: Proposed.** Tracking issue: #870. Builds on the parse landed
under #607.

## Goal

Surface the `SizeStatistics` a file already carries — the unencoded
`BYTE_ARRAY` size and the repetition and definition-level histograms —
on the two CLI surfaces that answer "why is this column this big" and
"where did the nulls come from": the `dive` column-chunk detail screen
and `hardwood inspect columns`.

The level histograms are the only place in the metadata where "the
field was absent" and "the list was empty" are distinguishable.
`ColumnIndex.null_counts` lumps them together, and a raw
`definition_level_histogram` of `[52428, 104857, 39321, 6291456]` says
nothing without the schema to name each bucket. Naming the buckets is
what makes the data readable, and it is derivable from the column's
path through the schema alone.

Both surfaces read the chunk-level statistics in `ColumnMetaData` field
16. The per-page copies in `ColumnIndex` and `OffsetIndex` are read only
to report their presence; displaying them per page is out of scope.

## Screen

Example column is `websites.list.element`, a `LIST<optional STRING>`
inside an optional group (`max def 3`, `max rep 1`). Everything above
the `Size statistics` separator is unchanged.

```
╭ websites.list.element (RG #0) ───────────────────────────╮╭ Drill into ──────────────────────────╮
│ Values                6,488,062                          ││▶ Pages           96 pages            │
│ Nulls                 196,606                            ││  Column index    present · levels    │
│ Uncompressed          31.8 MB                            ││  Offset index    present · unencoded │
│ Compressed            12.4 MB                            ││  Dictionary      present             │
│ Min                   "airport"                          ││                                      │
│ Max                   "wheelchair"                       ││                                      │
│                                                          ││                                      │
│ Size statistics       chunk + 96 pages                   ││                                      │
│ Unencoded             66.0 MB  (+24.0 MB lengths)        ││                                      │
│ Records               1,048,576                          ││                                      │
│ Present values        6,291,456                          ││                                      │
│ Avg fan-out           6.19 slots/record                  ││                                      │
│ Avg list length       7.10  (non-empty)                  ││                                      │
│                                                          ││                                      │
│ Def levels            max 3                              ││                                      │
│   0  websites null        52,428   0.8% ▏                ││                                      │
│   1  websites empty      104,857   1.6% ▏                ││                                      │
│   2  element null         39,321   0.6% ▏                ││                                      │
│   3  element present   6,291,456  97.0% ███████████▋     ││                                      │
│                                                          ││                                      │
│ Rep levels            max 1                              ││                                      │
│   0  new record        1,048,576  16.2% █▉               ││                                      │
│   1  websites.list     5,439,486  83.8% ██████████▏      ││                                      │
╰──────────────────────────────────────────────────────────╯╰──────────────────────────────────────╯
 [Tab] pane  [↑↓] move  [Enter] open  [l] levels  [t] logical types  [Esc] back  │  [?] help  [q] quit
```

The facts pane is a `Paragraph` with no scroll, and the two level
blocks add roughly fourteen lines to a pane that already runs to
seventeen. `l` toggles them, in the same shape as the existing `t`:
handled before the MENU-only early return so it works from either pane,
carried as a flag on `ScreenState.ColumnChunkDetail`, and advertised in
the key bar only when the chunk has a histogram to show. It defaults
off, so the derived rows — which are the summary a reader wants at a
glance — stay visible and the raw buckets become a deliberate step:

```
│ Avg list length       7.10  (non-empty)                  │
│ Levels                [l] to show                        │
```

The level blocks are bounded by `maxDefinitionLevel + 1` and
`maxRepetitionLevel + 1`, so the viewport-virtualization rule in
`CLAUDE.md` does not apply — there is no collection here whose size
tracks the data.

### Menu hints

`Column index` and `Offset index` gain a suffix when the page-level
fields are present, so a reader knows whether drilling in will show
per-page histograms before spending the keystroke:

| Item | Hint |
|---|---|
| `Column index` | `present · levels` when `ColumnIndex` fields 6/7 are set, else `present` |
| `Offset index` | `present · unencoded` when `OffsetIndex` field 2 is set, else `present` |

Per the drill-into recipe in [DIVE_THEME.md](DIVE_THEME.md), a hint
carrying both a fact and an annotation splits: `present` at default fg,
` · levels` at `Theme.dim()`.

## Level labels

Walk the column's `FieldPath` from `FileSchema.getRootNode()` and
collect the nodes whose repetition type is `OPTIONAL` or `REPEATED`, in
order. There are exactly `maxDefinitionLevel` of them; call them
`d₁…d_maxDef`. Definition level `i` names the node the value failed to
reach:

| Condition | Label |
|---|---|
| `i < maxDef`, `d(i+1)` is `REPEATED` | `<parent name> empty` |
| `i < maxDef`, `d(i+1)` is `OPTIONAL` | `<node name> null` |
| `i == maxDef` | `<leaf name> present` |

A `REPEATED` node is named for its parent because the empty collection
is a fact about the field the user knows — `websites empty`, not the
synthetic `websites.list` node the LIST annotation introduces. The same
rule gives a `MAP` the right name, since `key_value` sits under the map
field. An unannotated repeated field has no such wrapper, so its parent
is whatever group encloses it; there the repeated node's own name is
used instead.

Repetition level 0 is always `new record`. Level `i` is the dotted path
of the `i`-th repeated node, relative to the root.

## Derived rows

| Row | Definition |
|---|---|
| `Size statistics` | `chunk + N pages` / `chunk only` / `— (not written)` |
| `Unencoded` | `unencoded_byte_array_data_bytes`, with `4 × present values` as a parenthetical — the per-value length prefixes the field excludes, so the two together are the real PLAIN size. `BYTE_ARRAY` only |
| `Records` | `rep[0]` |
| `Present values` | `def[maxDef]` |
| `Avg fan-out` | `sum(def) / rep[0]`, in slots per record |
| `Avg list length` | non-empty list elements ÷ records holding a non-empty list |
| `Avg value size` | `unencoded / present values`. `BYTE_ARRAY` only |
| level rows | count, share of `sum(def)` or `sum(rep)`, bar |

`Avg list length` needs the level at which the first repeated node
sits. Let `e` be its index in `d₁…d_maxDef`; definition levels below
`e` are the buckets where no list element exists at all. Then

```
elements = sum(def) − Σ(def[i] for i < e)
records  = rep[0]   − Σ(def[i] for i < e)
```

and the row is `elements / records`. It is emitted only when
`maxRepetitionLevel == 1`. With nested repetition a single average has
no unambiguous referent, so the row is omitted rather than computed
against an arbitrary level.

A row is dropped rather than shown as `—` when it would add nothing,
which keeps the flat-column form short instead of scaffolding it with
placeholders. Two cases are distinct:

- **Redundant.** When `maxRepetitionLevel == 0` every value is its own
  record, and when `maxDefinitionLevel == 0` every value is present. The
  quantities are known — both fall back to `num_values` — but `Records`,
  `Present values` and `Avg fan-out` would restate the `Values` row, so
  they are not displayed. `Avg value size` still consumes the
  present-value count, which is what makes it available on a flat
  required `BYTE_ARRAY` column.
- **Unknown.** Where the histogram a quantity needs is absent or empty
  and no such fallback applies, the quantity does not exist and its row
  is dropped.

## Consistency check

The screen is self-checking. `num_values` must equal both `sum(def)`
and `sum(rep)`, and the chunk's `null_count` must equal
`num_values − def[maxDef]`. A writer that disagrees is painted as an
error rather than silently rendered, since that is the class of bug a
reader opens `dive` to find:

```
│ ⚠ Declared vs actual  values 6,488,062, sum(def) 6,488,050        │
```

The check runs on both surfaces. `inspect` emits no colour, so there it
is the `⚠` prefix and the wording alone.

## Degraded forms

A file written before parquet-format 2.10 collapses to one row, with no
empty scaffolding for the rows that cannot be filled:

```
│ Size statistics       — (not written)                    │
```

A flat `BYTE_ARRAY` column has both histograms legitimately omitted by
the spec, and the unencoded size is still the interesting number:

```
│ Size statistics       chunk + 240 pages                  │
│ Unencoded             420.1 MB  (+48.0 MB lengths)       │
│ Avg value size        35 B                               │
│ Def levels            — (max 1, redundant with Nulls)    │
│ Rep levels            — (not repeated)                   │
```

A present but empty histogram is distinct from an absent one — a writer
emits `definition_level_histogram = []` for a required, non-repeated
column — and is reported as the `—` form rather than indexed into.
Non-`BYTE_ARRAY` columns omit the `Unencoded` and `Avg value size` rows
entirely instead of showing a placeholder.

## Narrow terminals

The level rows degrade by dropping their least load-bearing column
first. Thresholds are on the pane's inner width:

| Inner width | Rendered |
|---|---|
| ≥ 56 | level, label, count, percentage, bar |
| 44–55 | level, label, count, percentage |
| < 44 | level, label, count |

Bars are drawn from the eighth-block characters `▏▎▍▌▋▊▉█` so a bar
carries sub-cell resolution at small shares.

## `hardwood inspect columns`

The ranked table gains an `Unencoded` column, summed per column path
across row groups and `-` where the column is not `BYTE_ARRAY` or the
statistics are absent — matching the existing `-` for an unavailable
page count:

```
Rank  Column                   Type        Compressed  Uncompressed  Unencoded  Ratio  # Pages
   1  order.description        BYTE_ARRAY     61.7 MB      184.2 MB   420.1 MB  33.5%      240
   2  order.tags.list.element  BYTE_ARRAY     12.4 MB       31.8 MB    66.0 MB  39.0%       96
   3  order.total_cents        INT64          38.4 MB       96.0 MB          -  40.0%      120
```

A `--column <path>` option prints the per-chunk detail — the same facts
as the dive pane, one row per row group, followed by the histograms:

```
$ hardwood inspect columns -f orders.parquet --column order.tags.list.element

order.tags.list.element  BYTE_ARRAY / String  max def 3  max rep 1

RG  Values     Nulls    Records    Present    Fan-out  Unencoded  Size stats
 0  6,488,062  196,606  1,048,576  6,291,456     6.19    66.0 MB  chunk + 96 pages
 1  6,502,110  201,004  1,048,576  6,301,106     6.20    66.1 MB  chunk + 96 pages

Definition levels (all row groups, max 3)
 0  tags null           104,857   0.8%  ▏
 1  tags empty          209,714   1.6%  ▏
 2  element null         78,642   0.6%  ▏
 3  element present  12,592,562  97.0%  ███████████▋

Repetition levels (all row groups, max 1)
 0  new record        2,097,152  16.2%  █▉
 1  tags.list        10,878,972  83.8%  ██████████▏
```

Level histograms sum element-wise, so the file-wide block is exact
rather than a sample of one row group. `--row-group <n>` narrows both
the table and the histograms to a single row group.

Bars are the same plain characters used by `dive`, so the two surfaces
render identically. The `inspect` commands emit no colour and this does
not change that.

## `LevelSummary`

Both surfaces share one helper,
`dev.hardwood.cli.internal.LevelSummary`, beside the existing `Sizes`
and `Fmt`. It is a record built by a static factory from the column's
schema and metadata, holding the derived scalars, the labelled level
rows, and the consistency verdict. It performs no I/O and no rendering:
`dive` paints it through its own `fact()` helper so labels keep
`Theme.primary()`, and `inspect` paints it through `RowTable`.

The bar rendering and the schema walk live in the same class. Their only
consumer is `LevelSummary` itself, and splitting them out would add two
files with one call site each.

An absent-statistics sentinel lets both callers branch once on
`summary.present()` rather than null-check each row.

## `Theme.error()`

The consistency mismatch is an error state, and `Theme` has no tier for
one. It gains a fifth role — red, with the same truecolor-with-named-
ANSI-fallback structure as `accent()` and `selection()`:

| Method | Truecolor terminals | Named-ANSI terminals |
|---|---|---|
| `error()` | `Style.EMPTY.fg(rgb(220, 50, 47))` | `Style.EMPTY.fg(Color.RED)` |

The RGB is Solarized's red slot, chosen on the same grounds as the
existing two: it survives iTerm2's bold-to-bright remapping and reads
against both Solarized variants.

[DIVE_THEME.md](DIVE_THEME.md) declares its decision tree exhaustive, so
the new tier is added there rather than bolted on: an error state is
checked first, ahead of selection, because a mismatch row must stay
legible even when it is the row under the cursor.

Nothing else in this change introduces colour. Bar length already
encodes magnitude, so colouring the bars would encode the same variable
twice, and the semantic split between present and absent levels is
already carried by the label column. Level rows and counts are body
content — tier 4, `Style.EMPTY`. The `— (not written)` and
`— (not repeated)` strings are parenthetical advisories — tier 5,
`Theme.dim()`.

## Testing

`LevelSummaryTest` covers the label walk against each schema shape that
changes its outcome: a LIST, a MAP, an unannotated repeated field, a
flat required column, a column with absent statistics, and one with a
present-but-empty histogram. It also pins the derived scalars and both
arms of the consistency check.

`DiveRenderTest` gains cases for the pane with levels toggled off, with
them on, and for the not-written form. `InspectColumnsCommand` gains a
test for `--column` and for the `Unencoded` table column.

`ThemeTest` pins `error()` alongside the existing four.

`cli/src/test/resources/dive_screenshots_fixture.parquet` already
carries chunk-level `SizeStatistics` and needs no regeneration. It
covers every branch on its own: `websites.list.element` is the LIST
shape above, `names.common.key_value.value` the MAP shape, `id` a flat
required `BYTE_ARRAY`, `confidence` a `max def 1` column with a real
histogram, and the `metric_*` columns have no `size_statistics` at all.
Its columns carry no page index, so the `chunk only` form is what the
screenshots show.

## Documentation

- `docs/content/reference/cli.md` — the `--column` and `--row-group`
  options.
- The `dive` documentation — the new rows and the `l` key.
- `skills/hardwood-cli/SKILL.md` — the `inspect columns` entries, its
  derived-metric glossary, and the option table, per the agent-skills
  rule in [CONTRIBUTING.md](../CONTRIBUTING.md).
- `_designs/DIVE_THEME.md` — the `error()` tier.
- `docs/content/assets/cli/03-4-rg-column-chunk-detail.svg` regenerated
  via the `screenshots` profile, plus one new capture for the levels
  view.
- `FORMAT_COVERAGE.md` — size statistics have a functional consumer
  once this lands.
- `ROADMAP.md` — the 9.1 Statistics entries.
