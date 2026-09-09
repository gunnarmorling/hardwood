# Design: the exception model

Status: implemented

## The question a failure has to answer

A caller whose read has failed makes one decision: try again, or stop. Everything
else — what to log, what to report, whether to fail the job — follows from it.
Hardwood answers it by exception type, so a caller can act on the type alone
without reading the message.

> **Trying again may help:** `IOException`
> **Trying again will not:** `ParquetReadException`, `ParquetWriteException`,
> `UnsupportedOperationException`

## The second question: whose fault

Retry-or-stop does not separate a file that is wrong from a call that is wrong, and both
mean stop. So a second axis runs across the first, and it decides what the caller does
next: change their code, or stop trusting the file.

| | Means | Type |
|---|---|---|
| **The file's fault** | the bytes, or the footer, are not what they claim | `ParquetReadException` |
| **The caller's fault** | the reader was asked for something it never held | unspecified — today `IllegalArgumentException`, `ClassCastException`, `NullPointerException`, `IllegalStateException` |

The caller's side is deliberately unspecified. Validating a typed accessor's column cost
4% per accessor and 7–8% end-to-end, above the 3% bar, so the guards were dropped and the
wrong-type call surfaces as whatever the storage cast raises. Leaving the type unstated
keeps the freedom to put a better error back on a path where it turns out to be free.

**A named file means the file is at fault.** A caller's mistake names the column and not
the file: the remedy is in their own code and does not change with the file, and a file
name on such a message reads as a complaint about a file that is fine. This is the part
that gives the `[fileName]` prefix its meaning — if both categories carried it, it would
distinguish nothing.

## The categories

| Category | Means | Type |
|---|---|---|
| **Transport** | reading or writing the file failed: a disk error, an S3 failure after its own retries have run out, a connection reset | `IOException`, checked |
| **The file** | the file was read and is not valid Parquet: a bad magic number, a corrupt footer, a dictionary page the metadata misplaces, values that do not decode | `ParquetReadException`, unchecked |
| **Unsupported** | the file is correct and Hardwood cannot read it | `UnsupportedOperationException` |
| **Unproducible** | the writer could not produce the file, and neither the caller nor the destination is at fault | `ParquetWriteException`, unchecked |

`SchemaIncompatibleException extends ParquetReadException`: two files in one read
whose schemas cannot be reconciled.

## The rule

1. **Reading or writing the file failed** — raise `IOException`, and declare it.
2. **The file is not valid Parquet** — raise `ParquetReadException` where the
   invalidity is detected.
3. **The file is valid and Hardwood cannot read it** — raise
   `UnsupportedOperationException`, and let it travel untouched.

The corollary is the part that is easy to get wrong: **a method declares
`IOException` only if it can reach a file.** Parsing a buffer and decoding a page
cannot, so they do not declare it, and no `catch (IOException)` around them can turn
a corrupt file into a failed read.

## Where `IOException` is declared

`ParquetFileReader.open`/`openAll`, the reader factories, `RowReader.hasNext`,
`next` and `close`, and `ColumnReader`/`ColumnReaders` `nextBatch` and `close`.
Below them, every frame that reaches the file declares it too, including
`PageIterator`, which is a two-method interface rather than a `java.util.Iterator`
precisely so that advancing a column's pages can say it reads.

The accessors — `getInt`, `getLong`, `isNull` — read an already-decoded batch and
declare nothing, so the checked exception stops at the methods that do I/O rather
than spreading through value-reading code. This costs callers almost nothing,
because `open` is already checked:

```java
try (ParquetFileReader reader = ParquetFileReader.open(file);
     RowReader rows = reader.rowReader()) {
    while (rows.hasNext()) {
        rows.next();
        total += rows.getLong("amount");
    }
}
```

`close()` is checked with them: a reader that owns its input files closes them, and
closing a file can fail. `RowReader`, `ColumnReader` and `ParquetFileReader` declare
`Closeable`, as `ParquetFileWriter` and `InputFile` do.

Nothing that cannot reach a file declares it. `ThriftCompactReader` and the readers
built on it parse a `ByteBuffer` already in memory; `Decompressor` and `Compressor`
turn one buffer into another. Their failures are the file's, or the writer's, not
the transport's.

`RowGroupBloomFilterSource` and `RowGroupDictionaryFilterSource` reach the file and
declare it. Resolving a column's filter belongs in `RowGroupFilterEvaluator`, with
the guards that decide whether the read is worth doing at all;
`BloomFilterSupport` and `DictionaryFilterSupport` take the filter they test and
declare nothing.

## Where it is wrapped

A checked exception cannot travel through a `Runnable`, a `Supplier`, or a mapping
function passed to `computeIfAbsent`. Those are the only places an I/O failure is
wrapped in an `UncheckedIOException`, and each wrap is undone by the method
enclosing the lambda — `RowGroupIterator.getSharedMetadata`,
`RowGroupIterator.getColumnPlan` and `FileMetadataCache.getFile`. A wrap lasts one
call and reaches no caller, so the readers unwrap nothing.

`FileMetadataCache.getFile` undoes two layers: the loader runs in a `Supplier` and
wraps, and `CompletableFuture.join` adds a `CompletionException`. Both describe how
the load is scheduled rather than what went wrong.

A boundary counts when the language forbids the declaration, not when an interface
we chose happens to.

## A prefetch that fails is dropped

Fetching a chunk, a region, a plan's first chunk, or planning the next row group
kicks off one speculative fetch of what is expected next. Nothing waits on it, so a
failure has nobody to be reported to: each catches `IOException` where it happens,
logs it at DEBUG and returns. Nothing is wrapped.

Only `IOException` is caught. Anything else — an `Error` above all — leaves the
`Runnable` and reaches the executor's uncaught-exception handling.

Dropping it is safe because of how the fetch is written: the buffer field is
assigned only on success, and the row-group prefetch returns `null` from its mapping
function, so a failed prefetch leaves nothing cached. The demand path then does the
work again on the calling thread and reports to a caller that is waiting. DEBUG
rather than WARN because that report is coming, and a backend outage would otherwise
emit one warning per speculative chunk.

## An annotation the reader cannot use is dropped, not raised

A schema can be wrong on its own terms, and the two ways it can be are answered differently.

A `FIXED_LEN_BYTE_ARRAY` that declares no width cannot be decoded at all — the width sizes
the buffer and spaces the offsets, so without it there are no value boundaries to find.
`FixedWidthValidator` refuses it when the reader is built, over the columns the read
touches, as a `SchemaIncompatibleException`.

An annotation its physical type cannot carry is not like that. A `FLOAT16` column twelve
bytes wide has said two things that cannot both be true, but the twelve-byte values are
perfectly readable — only the annotation is unusable. The format says to read past it: the
"Unsupported Logical Types" section of `LogicalTypes.md`, adopted in parquet-format PR 606,
has readers "ignore both the logical type annotation and column order for that column. Only
the physical type information should be used to process the column's data."

So `FileSchema` drops the annotation as the schema is built, and the column is reported and
read as its physical type. Nothing downstream checks for this, because there is nothing left
to check: the physical accessors work, `getValue` yields the physical value, a logical
accessor fails exactly as it does on any unannotated column of that type, and the statistics
are compared under the physical type's ordering. Column order needs no separate handling —
the only thing it decides is whether a float predicate compares under IEEE 754 total order,
and a column that has lost its `FLOAT16` annotation is rejected by `FilterPredicateResolver`
before that flag is consulted.

**Two causes, one behaviour, two warnings.** An annotation this version does not recognize
at all is dropped where it is parsed, in `LogicalTypeReader`. The distinction is worth
keeping in the message even though the handling is identical: an unrecognized arm means the
file was written against a newer format version and the reader is what is behind, while an
annotation its physical type cannot carry means the writer produced something no version of
the format defines. Only the second is provably wrong, and only the second could ever be a
candidate for refusing the read — never the first, which would break the forward
compatibility the rule exists to provide.

**What is not dropped is what cannot be proven wrong.** A footer that omits `type_length`
states no width for the annotation to contradict, so the annotation is kept and the column
is refused for the missing width instead, by the validator whose message describes that
defect.

## Which failures are which

| | |
|---|---|
| magic bytes, file length, footer length | the file is not Parquet, or does not describe itself |
| footer parse; metadata values that cannot be — a bloom filter header that is not a multiple of 32, a decimal precision of zero, an enum ordinal the format does not define | the metadata does not decode |
| page index parse; a column index and offset index that disagree on the page count | |
| dictionary page offsets, lengths and placement; bloom filter offsets and lengths | the footer puts a page where one cannot be |
| page CRC mismatch; page header parse; decompression; value decoding | the data does not match what the file says about it |
| a fixed-width column that declares no width, raised when the reader is built for the columns it touches | the footer omits what every decode of the column needs |
| cross-file schema mismatch; a fixed-width column with no width | `SchemaIncompatibleException` |

`UnsupportedOperationException` covers an encrypted footer or encrypted columns, an
absent or unloadable codec library, an unimplemented encoding, a column chunk stored
in a separate file, a row group whose page-index region spans more than
`Integer.MAX_VALUE` bytes, and a file over 2 GB opened with the mmap-backed range
cache. Each of those files is correct and another reader will open it. Whether the
last two limits are worth keeping is tracked as #1113.

## A truncated buffer

`ThriftTruncatedException` is a `ParquetReadException` and reads as one everywhere
but a page header. A header is located by peeking a guessed number of bytes in front
of it, and `DataPageHeader.statistics` carries `min_value`/`max_value` bounds the
format puts no ceiling on, so a header can be longer than any fixed guess. The peek
loop doubles on this type and gives up only at the chunk remainder or the peek
ceiling.

That makes the type a contract rather than a label: **every way the parser can run
past the end of its buffer must raise it**, including a field that declares a length
the buffer cannot hold, which is noticed before the bytes are reached. A truncation
raised as a plain `ParquetReadException` stops the loop growing and fails a valid
file. A length that is corrupt rather than merely past the peek is caught by the
ceiling the growing stops at, so erring towards truncation is the safe direction.

## Decode failures

The read path catches `Throwable` where its tasks meet: the page retriever, the page
decoder and the batch assembler. What arrives is whatever the decoders and codec
libraries raise — an `ArrayIndexOutOfBoundsException` from a dictionary index the
file got wrong, an `IllegalStateException` from an RLE run header that cannot be, an
`ArithmeticException` from a length that does not fit. These become
`ParquetReadException`, keeping the original as the cause.

This is a judgement about which mistake to make. A Hardwood defect reaching one of
those sites is relabelled as a problem with the file, which is wrong; left alone,
every corrupt file raises an `ArrayIndexOutOfBoundsException` out of a reader, which
reads as a Hardwood defect to everyone who hits one. The second is far more common.
The cause chain still shows a maintainer what actually threw.

`Error` propagates untouched. An `OutOfMemoryError` is neither the file's fault nor
something a caller retries.

## What keeps its own type

Accessing a column outside the projection, calling a primitive accessor on a null
field, calling `next()` past the end, using a `ColumnReader` accessor before
`nextBatch()`, and the writer's own misuse types — an unknown column, a value
outside its annotation's range, a `REQUIRED` field left unset. The caller's code is
wrong and no file is involved. `ThriftEnumLookup.indexOf` is on this side too: it
maps an enum to a Thrift value on the write path, where a value with no encoding is
a mistake in the calling code.

## A shared base

`dev.hardwood.ParquetException`, abstract, in the package that already holds
`Hardwood`, `InputFile` and `OutputFile`, with `ParquetReadException` and
`ParquetWriteException` beneath it. It is named here and not written: nothing asks
to catch the pair, because a caller is either reading or writing and code that does
both wants to know which half failed. Adding a supertype to a released class is
source- and binary-compatible, so it can be written the day a caller has a reason.

`docs/content/reference/error-handling.md` carries the user-facing table, organised
around the same retry rule.
