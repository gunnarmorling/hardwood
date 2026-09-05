# Design: read-failure handling in dive, and read context on reader exceptions

**Status: Implemented.** Tracking issues: #1092 (dive), #1093 (core). Supersedes #342.

## Goal

A reader who opens a damaged file in `hardwood dive` should be told what failed
and where in the file it failed, and should still have a session afterwards.

Two things stand between that and the current behaviour, one in each module:

- **`hardwood-core` says too little.** A page whose header will not parse
  reports `Unknown field type: 15`. Nothing names the column, the row group or
  the byte the parse gave up on, so the message cannot be acted on.
- **`hardwood-cli` dies on it.** Every dive screen that reads from the file does
  so inside the render callback or the key handler, neither of which is guarded.

## Part 1 — read context on reader exceptions (#1093)

### What a message carries today

`dev.hardwood.internal.ExceptionContext` centralises enrichment and
`ColumnWorker` applies it in the `catch` blocks of its retrieve, decode and
drain tasks. It adds the file name and nothing else:

```
[data.parquet] Failed to fetch metadata for row group 0
Unknown field type: 15
```

The second form has no prefix at all: it is thrown by
`ThriftCompactReader.skipField` and reaches the caller through a path that never
passes an enrichment point.

### What it should carry

```
[data.parquet] row group 0, column id, page header at byte 41104 (0x00a090) — Unknown field type: 15
```

Every part of that is derivable from state already at hand where the exception
is caught:

| Part | Where it comes from | Cost |
|---|---|---|
| file name | `PageSource.getCurrentFileName()`, already used | none, already paid |
| column | `ColumnWorker.column`, a final field — one worker per column | none |
| row group | the work item the retriever is on | one `int` per page |
| region ("page header", "dictionary page", "page fetch", …) | the catch site knows which read it is guarding | none |
| byte offset | see below | one `long` per page |

A chunk's metadata does not carry the row group it belongs to, so that part
comes from the work item the retriever is holding rather than from anything on
the page — recorded per page alongside the file name, at the same granularity.

### The byte offset

`PageInfo` carries the page's bytes but not the offset they came from, so this
is the one piece that needs new state: a `long` field on `PageInfo`, set by the
page source, which already knows the offset because it just read that range.

That is one field write per page. `ColumnWorker` already keeps per-page state at
exactly this granularity — `fileNameBuffer[slot]` is written once per page in
the retriever — so this adds nothing to the shape of the hot path, and pages are
decoded in the thousands, not the millions.

Within a page, `ThriftCompactReader` knows its own buffer position and can name
it on the throw path at no cost. It reports a position *relative to the buffer*;
the caller that knows the base offset composes the two. Keeping the layering
this way means the Thrift reader stays ignorant of files.

### The rule that protects the read path

> Enrichment runs only inside `catch` blocks. Context that enrichment needs must
> already exist for another reason, or be recorded no more often than once per
> page. Nothing is added inside a per-value decode loop.

A `try` block that does not throw costs nothing on any JVM this project targets;
the exception table is consulted only when an exception is actually raised. The
risk is not the `try`, it is bookkeeping done to feed the message — which is why
the table above accounts for every part, and why only one row has a cost.

**Acceptance:** a before/after benchmark on the flat-scan read path showing no
regression. A design that cannot show that is not accepted, and the byte offset
is the first thing to drop if it cannot.

### Surface

`ExceptionContext` gains a read-context entry point alongside `addFileContext`.
The existing `filePrefix` / `addFileContext` behaviour is unchanged, including
its idempotence check, so an exception that passes two enrichment points is
still prefixed once. The `[file] ` prefix stays first in the message so the
existing check keeps recognising it.

The CLI's own read helpers — `ParquetModel.pageHeaders`, `columnIndex`,
`offsetIndex`, `loadDictionary` — use the same entry point. They read regions
dive navigates to directly rather than through `ColumnWorker`, and
`pageHeaders` in particular walks page headers in a loop and knows the exact
offset of the one that failed.

## Part 2 — central read-failure handling in dive (#1092)

### Why it is central and not per screen

Six screens reach the file from a render or keybar path: `PagesScreen`,
`ColumnIndexScreen`, `OffsetIndexScreen`, `ColumnAcrossRowGroupsScreen`,
`DictionaryScreen` and `DataPreviewScreen`. A per-screen error state solves one
of them and leaves the shape of the bug in the other five, and every future
screen has to remember to opt in. A guard in `DiveApp` covers all of them and
cannot be forgotten.

### The model

`DiveApp` holds a nullable read failure — the message, and the scroll offset
into it. It is app state, not screen state: `ScreenState` stays a description of
what a screen is showing, which is what makes the screens testable as pure
functions.

- **Key dispatch** is guarded. A read failure while handling a key records the
  failure; the navigation stack is left as it was.
- **Render** is guarded, around both `keybarForActive()` and `renderBody()`. A
  failure records itself and paints the overlay in the same frame.
- **While a failure is showing**, keys still reach the screen underneath. This
  is what lets a reader move away from a damaged region: the stack still holds
  the last state that rendered, so `PgUp` from a failed Data preview page loads
  an earlier window and clears the failure, and `Esc` pops the screen. A key
  that fails again simply replaces the failure with the new one.
- **The overlay** is a `ScrollPane` modal, per the navigation model: the
  navigation keys scroll it while there is more message than box.

### What this does not add

No screen gains an error state. `ScreenState` stays a description of what a
screen is showing and the screen handlers stay pure functions of
`(event, model, stack)`, which is what makes them testable without a terminal.

The approach #342 asked for went the other way — an `errorMessage` component on
`ScreenState.DataPreview`, a `hasError()` beside it and error branches through
that screen's handler, renderer and keybar. That shape solves one screen, has to
be repeated five more times, and its one advantage over this design — being able
to page off a damaged region rather than only backing out — falls out here for
free, because the stack still holds the last state that rendered and keys still
reach it.

### The failing bytes

A message names a byte offset; the bytes at that offset are what actually
settle whether a file is truncated, misaligned or written by something that
disagrees with the spec. The overlay reads a bounded window around the offset
when it paints, and shows it.

Re-reading a region that just failed sounds like walking back onto the mine, but
the two failure classes differ exactly here:

- **Malformed but readable** — a Thrift structure that will not parse: a page
  header, a column index, an offset index, a dictionary page header. The read
  succeeded; the parse did not. Reading those bytes again succeeds for the same
  reason it succeeded the first time.
- **Unreadable** — truncation, an I/O or object-store error. There are no bytes
  to show and the re-read fails too. The overlay catches its own read and falls
  back to the message alone.

So this needs no payload on the exception: all the overlay needs is the file, an
offset, and a window it reads itself.

### What the window does and does not cover

Bytes are shown only where the offset is the byte the read stopped on, and only
a Thrift parse reports one. That draws the line in a useful place, because
Thrift structures are the part of a Parquet file that is never compressed — page
bodies are the only compressed thing. The window therefore always shows bytes as
they are on disk, and can never present compressed bytes as though they were
readable.

The same rule is what keeps it honest about the rest. A failure inside a page
body — a decode error, an EOF part way through, a CRC mismatch, a dictionary
index past the end of its dictionary — has no position in the file: for a
compressed column the bytes it names were never on disk, and for the dictionary
case the RLE stream has already been consumed before the bad index is used, so
there is nothing left to point at even in the decoded page. Those report the
region and no bytes.

The honest summary is that this shows bytes for damaged *structure*, not for
wrong *data*, and wrong data is the larger class. What it covers is the class a
reader has no other tool for while inside dive, since the screens that browse
structure are the ones it fires on.

**The window is bounded and positional** — a fixed number of bytes centred on
the offset, read at `offset - window/2`. Nothing scales with the size of the
region that failed: an error in the last bytes of a 1 MB page reads and renders
exactly what an error in its first bytes does.

### What it looks like

Rendered through `Chrome` and `ScrollPane` at 80x24, over a file with its page
header at byte 41104 overwritten. The bytes are the real ones: three clean
little-endian `INT64`s — 5117, 5118, 5119 — running into the overwrite.

```
 hardwood dive │ corrupt-page.parquet │ 158.0 KiB │ 1 RG │ 10000 rows
 Overview › Pages (RG #0 · id)



          ╭ Read failed ─────────────────────────────────────────────╮
          │ Pages · row group 0 · column id                          │
          │                                                          │
          │ Unknown field type: 15                                   │
          │ while reading the page header at byte 41104 (0x00a090)   │
          │                                                          │
          │ 00a078  fd 13 00 00 00 00 00 00  ........                │
          │ 00a080  fe 13 00 00 00 00 00 00  ........                │
          │ 00a088  ff 13 00 00 00 00 00 00  ........                │
          │ 00a090  ff ff ff ff ff ff ff ff  ........                │
          │ 00a098  ff ff ff ff ff ff ff ff  ........                │
          │ 00a0a0  ff ff ff ff ff ff ff ff  ........                │
          │                                                          │
          │ [Esc] back                                               │
          ╰──────────────────────────────────────────────────────────╯



   [↑↓] scroll  [PgDn/PgUp or Shift+↓↑] page  [Esc] back    [?] help   [q] quit
```

Eight bytes to a row, not sixteen. A sixteen-byte row needs about 78 columns of
modal, and `modalArea` caps the box at the terminal width less four, so on an
80-column terminal it clips the ASCII gutter off the right edge. Eight fits a
60-column box, the same width the dictionary modal already uses. Rows are
hard-clipped to the modal width rather than wrapped — `wordWrap` would break
them at spaces and destroy the column grid.

The offset appears in both bases: decimal because a byte count is a count, hex
because that is what the gutter and every hex editor speak. One of the two
always matches whatever the reader is holding, and the hex form is what carries
the eye to the right row of the pane.

The byte the parse stopped on is drawn in `Theme.error()`, and so is the offset
of the row holding it. Nothing is marked in the gutter: `▶` means "Enter acts on
this" everywhere else in dive and borrowing it to mean "this byte is wrong"
would be a lie. Colour carrying that signal alone is consistent with the
navigation model, where colour already marks the cursor.

### Which page the offset belongs to

A column's pages are decoded concurrently and a damaged file usually ruins
several of them, so the failure that gets reported is whichever one lost the
race to signal first. The same file reported byte 4, 394, 784 and 2275 across
four runs of the same test.

The offset is therefore *a* damaged page, not deterministically the first. It
still points at real damage, which is what makes it worth showing, but two
things follow: the pane can show a different page on different visits to the
same file, and a test must not pin the byte — what identifies the failure is
stable, the byte is not.

### Which offsets a window can be read at

An offset is only a file offset for a failure raised over bytes as they sit in
the file — page headers, the column index, the offset index, the dictionary
region, and page data on an uncompressed column. A decode failure inside a
**compressed** page has a position in the decompressed buffer, and no file
offset exists for it: the bytes it names were never in the file. Reading the
file at that number returns unrelated bytes under a confident-looking gutter,
which is worse than showing nothing.

An offset therefore travels with the space it belongs to, and the overlay reads
a window only for file-space offsets. Decoded-space failures report the position
within the page and no hex. Showing them properly would mean carrying the
decompressed window on the exception, which is the payload coupling this design
avoids; it stays available as a later step if the reporting proves worth it.

### The dump

`KvMetadataFormatter.renderArrow` already draws the offset / sixteen bytes /
ASCII layout, privately, from a `String`, with offsets relative to the start of
what it was given and no way to mark a byte. An error view needs absolute file
offsets so they read the same as the message and as `hardwood inspect pages`, a
window centred on the failing byte rather than the file's start, and that byte
marked.

So the layout moves to a shared helper over `(bytes, baseOffset,
highlightOffset, windowBytes)` returning lines, and the Arrow dump is rewritten
in terms of it — one layout, two callers, which is the direction the DRY rule in
CLAUDE.md points anyway. The overlay shows a short window around the failing
byte; the modal already scrolls, so the window does not have to be the whole
region.

### What it does not do

TamboUI's own crash screen paints over an un-cleared buffer, which is why an
escaped exception shows a stack trace interleaved with the previous screen's
labels. That is upstream of this project. This design stops dive reaching that
screen; it does not fix it.

A failure is reported at the granularity the format allows: a page fails as a
unit and the columnar read path has no per-row attribution below it, so the
overlay names a row range and a byte offset, never "record 5127".

It also only ever reports failures the reader **detects**, which is a much
smaller set than "corrupt files". `PLAIN` has no redundancy to check, and an
RLE/bit-packing run header is a varint whose every bit pattern is a syntactically
valid header, so most corruption of page data decodes to wrong values rather
than to an error. Per-page CRCs would catch it and the reader already validates
them, but only `if (pageHeader.crc() != null)` and most writers emit none. That
gap is #1095; nothing in this design narrows or widens it, and no overlay can
show what was never detected.

Nor does this design change how the non-interactive commands report a failure
they do detect, which is #1094.

## Validation

- A fixture per failure region — a clobbered page header, column index,
  dictionary page and offset index — driven through each of the six screens,
  asserting the session survives and the message names the region.
- The hex window for a malformed region, asserting the gutter carries absolute
  file offsets and the marked byte is the one the parser stopped on.
- A failure at the far end of a large page, asserting the window read and the
  rendered overlay are the same size as one at its start.
- A decode failure on a compressed column, asserting the overlay reports a
  within-page position and draws no hex.
- The read-path benchmark from Part 1.
