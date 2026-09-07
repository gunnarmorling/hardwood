# Design: the exception model for reads

Status: implemented

## The question a failure has to answer

A caller whose read has failed makes one decision: try again, or stop. Everything
else — what to log, what to report, whether to fail the job — follows from it.

Hardwood answers that question by exception type, so a caller can act on the type
alone without reading the message.

## Two categories

| Category | Means | Trying again |
|---|---|---|
| **Transport** | reading the file failed: a disk error, an S3 failure after its own retries have run out, a connection reset | may succeed |
| **The file** | the file was read and is not valid Parquet: a bad magic number, a corrupt footer, a dictionary page the metadata misplaces, values that do not decode | will fail the same way |
| **Unsupported** | the file is correct and Hardwood cannot read it: Parquet Modular Encryption, an encoding not implemented, a codec whose library is absent | will fail the same way, until something changes |

The third is not a read failure at all. Nothing is wrong with the file or the
transport; the caller asked for something this library does not do, and the fix
is a dependency, a different writer, or a different tool. It keeps the type it
already has.

## The types

**Transport**

- `IOException` — checked, everywhere: opening a file, reading its metadata, and
  every call that advances a read.

**The file**

- `ParquetReadException` — unchecked, extends `RuntimeException`.
- `SchemaIncompatibleException extends ParquetReadException` — two files in one
  read whose schemas cannot be reconciled.

**Unsupported**

- `UnsupportedOperationException` — already what a missing codec library and an
  unimplemented encoding raise. Encryption joins them.

## What a caller is told

> Trying again may help: `IOException`
> Trying again will not: `ParquetReadException`, `UnsupportedOperationException`

The compiler enforces the first: a read cannot be written without deciding what
to do when a read fails. The two on the second line differ in what to
do instead — one means the file is beyond saving, the other that this library is
the wrong tool or is missing a dependency.

## The read path declares `IOException`

`RowReader.hasNext`, `RowReader.next`, `RowReader.close`, `ColumnReader.nextBatch`
and `ColumnReader.close` reach the file, and their signatures say so. The accessors — `getInt`, `getLong`, `isNull` and
the rest — read an already-decoded batch and declare nothing, so the checked
exception stops at the three methods that do I/O and does not spread through
value-reading code.

This costs callers almost nothing, because `ParquetFileReader.open` is already
checked: a scan is written inside a method that handles `IOException` before it
reads its first row.

```java
try (ParquetFileReader reader = ParquetFileReader.open(file);
     RowReader rows = reader.rowReader()) {
    while (rows.hasNext()) {
        rows.next();
        total += rows.getLong("amount");
    }
}
```

`UncheckedIOException` is not part of this contract. It remains what the JDK
intends it to be — the wrapper used where an interface forbids a checked
exception — so it appears only where a caller adapts a `RowReader` to something
like `Iterator` or `Stream`, whose functional interfaces cannot declare one. That
is the caller's boundary to draw, not something the reader imposes.

`close()` is checked with them. A reader that owns its input files closes them,
and closing a file can fail — deleting the local cache behind an S3 read, for
one. `ParquetFileReader.close` already declares `IOException` and reports such a
failure properly, collecting one per file and suppressing the rest;
`RowGroupIterator.close` reaches the same files and degrades the failure to a
warning nobody sees. Declaring it on the two readers makes all three agree and
removes the swallow. `RowReader`, `ColumnReader` and `ParquetFileReader` declare [java.io.Closeable],
which is what `ParquetFileWriter` and `InputFile` already declare. It is
[AutoCloseable] with `close()` narrowed to `IOException` and a contract that
closing twice is harmless — both true of these, and the second already relied on.
Try-with-resources needs only `AutoCloseable`, so nothing about how they are used
changes.

`ParquetFileReader` is in this list because it is inconsistent today: its
`close()` already throws `IOException` while it declares only `AutoCloseable`.
Adding a supertype breaks nothing.

## Which failures are which

**Transport.** A read of the file that did not complete, wherever it happens:
opening the file, reading its metadata, fetching a chunk, a region or a page —
the underlying `InputFile` failing, S3 exhausting its own retries.

**The file.** Everything that is decided by what the bytes say:

| | |
|---|---|
| magic bytes, file length, footer length | the file is not Parquet, or does not describe itself |
| footer parse | the metadata does not decode |
| page index parse; a column index and offset index that disagree on the page count | |
| dictionary page offsets, lengths and placement | the footer puts a page where one cannot be |
| bloom filter offsets and lengths | |
| page CRC mismatch; page header parse; value decoding | the data does not match what the file says about it |
| cross-file schema mismatch; a fixed-width column with no width | `SchemaIncompatibleException` |

An encrypted footer or encrypted columns raise `UnsupportedOperationException`,
alongside the absent codec library and the unimplemented encoding.
`EncryptedParquetException` is deleted: it lives in `dev.hardwood.internal`, is
named in no public signature, and said only what the message says.

A row group whose page-index region spans more than `Integer.MAX_VALUE` bytes joins
them. The file is correct and another reader can open it; this one coalesces a row
group's indexes into a single read and will not fetch a region it cannot address
with an `int`, which is a limit of this library rather than a fault in the file.
That it is a limit worth keeping is a separate question, tracked as #1113 — this
design decides only which category it belongs to while it stands.

## Decode failures

The read path catches `Throwable` where its tasks meet: the page retriever, the
page decoder and the batch assembler. What arrives there is whatever the decoders
and codec libraries raise — an `ArrayIndexOutOfBoundsException` from a dictionary
index the file got wrong, an `IllegalStateException` from an RLE run header that
cannot be, an `ArithmeticException` from a length that does not fit.

These become `ParquetReadException`, with the original kept as the cause.

This is a judgement about which mistake to make. A Hardwood defect reaching one of
those catch sites — a null dereference, an off-by-one — is relabelled as a problem
with the file, which is wrong. Left alone, every corrupt file raises an
`ArrayIndexOutOfBoundsException` out of a reader, which reads as a Hardwood defect
to every user who hits one, and that is the far more common case. Keeping the
original as the cause means a maintainer reading the stack trace still sees what
actually threw.

`Error` propagates untouched. An `OutOfMemoryError` is neither the file's fault
nor something a caller retries.

## What does not change

**Programming errors keep their types.** Accessing a column outside the
projection, calling a primitive accessor on a null field, calling `next()` past
the end, using a `ColumnReader` accessor before `nextBatch()`, asking for a codec
whose library is absent. None of these are read failures; the caller's code is
wrong, and no file is involved.

**Accessors stay unchecked.** `getInt`, `getLong`, `isNull` and the rest read a
decoded batch. Only the three methods that fetch bytes declare `IOException`.

**The writer is untouched, because it already does this.** `create`, `writeRow`,
`writeBatch` and `close` all declare `IOException`; everything else it raises —
an unknown column, a value outside its annotation's range, a `REQUIRED` field
left unset, a codec it refuses — is misuse of its API, typed accordingly.

There is no third category on the write side and so no `ParquetWriteException`.
The reader meets files it did not make and must say when one is wrong; the writer
makes the file, so its only failures are the caller's mistakes and the
destination's. A type with nothing to cover is not worth defining ahead of a
failure that needs it.

## What this breaks

Two changes, in opposite directions, and they belong in one release.

**Loud.** `hasNext`, `next` and `nextBatch` gain `throws IOException`, so a caller
that reads outside a checked context stops compiling. Most do not: `open` is
already checked, so the enclosing method already handles `IOException`. What
breaks is code that catches tightly around `open` and loops outside the `try`.

**Silent.** A caller catching `IOException` around `open` no longer catches a
malformed file, because that is now a `ParquetReadException`. The code still
compiles and the handler stops running.

There is no way to avoid the second while making the categories honest. A
`ParquetReadException extends IOException` would keep those handlers working, but
it would put corrupt files back in the category whose whole meaning is "try
again".

Together, the loud break forces every caller to revisit its error handling at
exactly the moment the silent one would otherwise slip past. Separately, the
silent one ships alone and is found in production. They land in the 1.1.0 Beta
cycle, with a release note.

## Two defects this settles

`RowGroupIterator.close` closes the input files it owns and logs an `IOException`
as a warning instead of reporting it. Declaring `close` removes the reason it was
written that way.

`ColumnChunkBuffer.compress` raises an `UncheckedIOException` when a codec fails,
from inside a `LevelSink` callback that cannot declare a checked exception. That
is the right wrapper in the right place, but it then escapes `writeBatch`,
`writeRow` and `close`, each of which declares `IOException` and should be what a
caller sees. Compression happens under `ParquetFileWriter.flushRowGroup` and
nowhere else, and all three reach the codec through it, so that is where the
wrapper is unwrapped — the innermost frame that can declare what it is, the way
`FileMetadataCache.getFileChecked` already unwraps its own. Unwrapping in the
three public methods instead would leave the same fix written three times and one
more to write for the next method that flushes.

## Prior art

**parquet-java** — the reference implementation — separates the two categories
the same way. Content failures are unchecked and live in their own family:

```
RuntimeException
└── ParquetRuntimeException            (abstract)
    ├── ParquetDecodingException       read-side content failures
    ├── ParquetEncodingException       write-side
    ├── InvalidRecordException
    ├── InvalidFileOffsetException
    ├── InvalidSchemaException         schema failures, same family
    └── ParquetCryptoRuntimeException  encryption, same family
```

I/O stays `IOException`, and its read path declares it: `readNextRowGroup()`,
`readNextFilteredRowGroup()` and `close()` are all checked.

Three of this design's decisions are the ones parquet-java already made — a
separate unchecked family for content failures, schema failures inside it rather
than beside it, and encryption inside it rather than under `IOException`.

**JDBC** splits its tree on the same question this design asks:
`SQLTransientException` against `SQLNonTransientException`, where transient means
the operation "might be able to succeed when the operation is retried without any
intervention by application code". Retryability is the axis the JDK itself reaches
for when that is what a caller must decide.

**Jackson** takes the other route: `JsonProcessingException`, and the
`JsonParseException` for malformed input beneath it, extend `IOException`. That
works because its API is checked throughout, so a second family would buy
nothing. It is the shape this design would have if content failures stayed under
`IOException` — one category, and no way to ask whether retrying helps.

**A shared base.** parquet-java's `ParquetRuntimeException` is abstract, with read
and write as siblings beneath it.

Here the equivalent would be `dev.hardwood.ParquetException`, abstract, extending
`RuntimeException`, in the package that already holds `Hardwood`, `InputFile` and
`OutputFile` — the neutral ground between reader and writer. `ParquetReadException`
and a later `ParquetWriteException` would sit under it, and
`SchemaIncompatibleException` under the read half where it belongs.

It is named here and not written. A base class with one subclass says nothing a
caller can use, and there is no `ParquetWriteException` to give it a second.
Adding a supertype to a released class is source- and binary-compatible, so
waiting costs nothing and guessing early risks a parent shaped for a child that
never arrives.

## Where a read is declared, and where it is wrapped

A checked exception cannot travel through a `Runnable`, a `Supplier`, a mapping
function passed to `computeIfAbsent`, or an `Iterator` method. Those are the only
places an I/O failure is wrapped in an `UncheckedIOException`. Everywhere else the
method declares `IOException`, however far from the API boundary it sits.

`FileMetadataCache` publishes the rule as a pair: `getFile` for the callers that
cannot declare, `getFileChecked` for the ones that can. Planning is an ordinary
method on an ordinary call path, so it takes the checked one — reading a footer is
not different from reading a bloom filter on the same line, only what the frame
above it is allowed to say about it is.

The alternative — wrapping everywhere and unwrapping once at the API — was
considered and is worse. It gives up the one thing the declaration buys: a
signature that says walking the work list can reach the disk. `firstRowGroupSkip`
and `buildColumnReaders` were each planning every file at a point nobody expected
I/O, and both were found by inspection rather than by the compiler.

## Which reads are on the path

`BloomFilterSource` and `RowGroupDictionaryFilterSource` read the file, and say so.
They are the last of the read path that claimed to be pure.

Saying so is not the same as doing it in the right place. Resolving the column's
filter belongs in `RowGroupFilterEvaluator`, with the guards that decide whether
the read is worth doing at all: that statistics did not already drop the row group,
that the operator is one a probe can answer, that an earlier probe has not already
proved the value absent. `BloomFilterSupport` and `DictionaryFilterSupport` take
what they test — given this filter, does it prove the value absent — and declare
nothing.

Both resolutions sit either side of the `||` that composes them, so a dictionary is
not read once the bloom filter has decided, and the NaN case is guarded at the
float call sites so a filter that could not decide a NaN is not read for one.

## Documentation

`docs/content/reference/error-handling.md` is organised around the retry rule: one
row per exception, saying whether another attempt can help.
