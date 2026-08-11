# Per-File Metadata for Multi-File Readers

**Status:** Completed

## Context

A multi-file `ParquetFileReader` exposed only the first file's `FileMetaData`. Consumers that
need the total physical row count before allocating storage had to reopen every input and parse
its footer independently, even though Hardwood would parse those same footers while reading.

## Public contract

- `getFileCount()` returns the number of physical inputs, in supplied order, without I/O.
- The existing `getFileMetaData()` continues to return the eagerly parsed first footer.
- `getFileMetaData(int)` returns one physical input's footer; index `0` returns that same first
  `FileMetaData` instance.
- Later footers remain lazy. Metadata access is synchronous and may join an asynchronous load
  already started by a data reader.
- Each in-progress, successful, or failed load is retained for the parent reader's lifetime.
  Closing and reopening the parent is the retry boundary after failure and the refresh boundary
  for files changed on the underlying storage.
- Indexed metadata access after parent close fails. `getFileCount()` remains a pure property.
- Inputs must remain unchanged while the parent is open.

An indexed accessor was chosen over iterable or list metadata APIs because it preserves the
checked `IOException` at the exact file access that can fail and does not suggest eager loading.

## Ownership and cache boundary

`ParquetFileReader` owns one `FileMetadataCache` and the input-file lifecycle. The cache keeps a
single future per physical file. Its `PreparedFile` contains only reusable file-wide state:

- the `InputFile`;
- parsed `FileMetaData`;
- derived `FileSchema`;
- the footer's row groups.

Both indexed metadata access and every `RowGroupIterator` use this cache, so neither direction
of access reparses a footer. A failed future remains in the map, preventing an implicit retry in
the same parent reader.

Closing a child row or column reader releases only iterator-local workers and caches. It neither
closes nor invalidates shared inputs. Parent close atomically prevents new cache admissions,
waits for every admitted footer load without holding the lifecycle lock, clears the cached
futures, then closes each owned input. Cache closure is idempotent and never closes inputs itself.

## Per-reader schema state

The cache deliberately does not store `FileColumnOrdinals`, touched-column validation, or other
projection/filter state. For every child reader, `RowGroupIterator` validates its touched columns
against each cached `FileSchema` and creates that iterator's own `FileColumnOrdinals` mapping.
The mapping is by field path and is used consistently for pruning, masks, indexes, bloom filters,
dictionaries, chunk-path checks, and fetch plans, preserving the cross-file ordering guarantees
from #906.

Consequently, `getFileMetaData(int)` can succeed for a file whose schema is incompatible with a
later projection. The incompatibility is reported when the row or column reader is planned,
because only then is the set of touched columns known.

## Concurrency and failure behavior

Reader cursors remain single-consumer objects. The cache's concurrency control exists to
coordinate Hardwood's footer prefetch with synchronous indexed access: one lifecycle boundary
serializes first-file seeding, load admission, and the start of cache closure, while atomic
insertion of one future per index makes all requesters observe the same in-progress result.
Existing exception translation is preserved: synchronous metadata access exposes the original
footer `IOException`, while iterator paths retain their runtime wrapper behavior. Parent close
ignores cached load failures while waiting so that every owned input can still be closed.
