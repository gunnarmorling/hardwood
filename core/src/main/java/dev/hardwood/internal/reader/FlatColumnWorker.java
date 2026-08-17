/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.util.Arrays;
import java.util.concurrent.Executor;

import dev.hardwood.internal.compression.DecompressorFactory;
import dev.hardwood.internal.encoding.HybridStreamCursor;
import dev.hardwood.internal.predicate.ColumnBatchMatcher;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.schema.ColumnSchema;

/// Per-column pipeline that decodes pages in parallel and assembles flat batches.
///
/// Extends [ColumnWorker] with flat-specific assembly: arraycopy of typed values
/// and null tracking via a packed `long[]` validity bitmap (set-bit-= -present).
public class FlatColumnWorker extends ColumnWorker<BatchExchange.Batch> {

    private long[] currentValidity;
    private final ColumnBatchMatcher columnFilter;
    private final boolean flushOnFilterBoundaries;
    private final boolean cursorDecodeEnabled;
    /// Tracks whether any absent (null) leaf has been seen in the current
    /// batch; cleared by [#publishCurrentBatch]. When still false at publish
    /// time, [BatchExchange.Batch#validity] is set to `null` to signal
    /// "all leaves present in this batch."
    private boolean currentBatchHasAbsents;

    private HybridStreamCursor currentDefLevelCursor;
    private HybridStreamCursor currentIndexCursor;
    private int cursorLogicalPosition;
    private int[] tempIndices;
    private int[] tempDefs;

    /// Creates a new flat column worker.
    ///
    /// @param pageSource yields [PageInfo] objects for this column
    /// @param exchange the output exchange for assembled batches
    /// @param column the column schema
    /// @param batchCapacity rows per batch
    /// @param decompressorFactory for creating page decompressors
    /// @param decodeExecutor executor for decode tasks
    /// @param maxRows maximum rows to assemble (0 = unlimited)
    /// @param columnFilter optional drain-side per-column filter that runs against every
    ///                    published batch, writing matches into the batch's `matches`
    ///                    array. `null` leaves the worker on the existing path — no
    ///                    filter evaluation.

    public FlatColumnWorker(PageSource pageSource, BatchExchange<BatchExchange.Batch> exchange,
                            ColumnSchema column, int batchCapacity,
                            DecompressorFactory decompressorFactory,
                            Executor decodeExecutor, long maxRows,
                            ColumnBatchMatcher columnFilter) {
        this(pageSource, exchange, column, batchCapacity, decompressorFactory,
              decodeExecutor, maxRows, columnFilter, false, false);
    }

    /// @param flushOnFilterBoundaries whether the drain flushes the current batch at
    ///                    row-group boundaries where the filter-always-matches decision
    ///                    changes. Must be uniform across all workers of one projection —
    ///                    batches stay row-aligned only when every worker flushes at the
    ///                    same rows.
    public FlatColumnWorker(PageSource pageSource, BatchExchange<BatchExchange.Batch> exchange,
                            ColumnSchema column, int batchCapacity,
                            DecompressorFactory decompressorFactory,
                            Executor decodeExecutor, long maxRows,
                            ColumnBatchMatcher columnFilter, boolean flushOnFilterBoundaries,
                            boolean cursorDecodeEnabled) {
        super(pageSource, exchange, column, batchCapacity, decompressorFactory,
              decodeExecutor, maxRows);
        this.columnFilter = columnFilter;
        this.flushOnFilterBoundaries = flushOnFilterBoundaries;
        this.cursorDecodeEnabled = cursorDecodeEnabled;
    }

    @Override
    protected boolean supportsFusedPath() {
        return cursorDecodeEnabled;
    }

    @Override
    void initDrainState() {
        currentValidity = maxDefinitionLevel > 0 ? new long[(batchCapacity + 63) >>> 6] : null;
        currentBatchHasAbsents = false;
        if (cursorDecodeEnabled) {
            tempIndices = new int[batchCapacity];
            tempDefs = new int[batchCapacity];
        }
    }

    @Override
    boolean flushOnFilterAlwaysMatchesTransition() {
        return flushOnFilterBoundaries;
    }

    /// Writes the mask a matcher would produce when every record matches: all-ones
    /// for `[0, recordCount)` bits, tail bits of the last active word zeroed, words
    /// beyond the active range untouched.
    private static void fillAllOnes(long[] words, int recordCount) {
        int fullWords = recordCount >>> 6;
        int tail = recordCount & 63;
        Arrays.fill(words, 0, fullWords, -1L);
        if (tail != 0) {
            words[fullWords] = -1L >>> (64 - tail);
        }
    }

    @Override
    void assemblePage(Page page, PageRowMask mask) {
        if (page.defLevelCursor() != null) {
            // Optional column fused path: def-level + index cursors paired.
            this.currentDefLevelCursor = page.defLevelCursor();
            this.currentIndexCursor = page.indexCursor();
            this.cursorLogicalPosition = 0;
        } else if (page.indexCursor() != null) {
            // Index-only fused path: required dictionary column (no def levels).
            this.currentDefLevelCursor = null;
            this.currentIndexCursor = page.indexCursor();
            this.cursorLogicalPosition = 0;
        } else {
            this.currentDefLevelCursor = null;
            this.currentIndexCursor = null;
        }

        if (mask.isAll()) {
            copyPageRange(page, 0, page.size());
            return;
        }
        int intervalCount = mask.intervalCount();
        for (int i = 0; i < intervalCount; i++) {
            if (done) {
                return;
            }
            copyPageRange(page, mask.start(i), mask.end(i));
        }
    }

    /// Copies values at page-relative offsets `[rangeStart, rangeEnd)` into
    /// the current batch, publishing and rolling over as the batch fills and
    /// stopping early when `maxRows` is reached.
    private void copyPageRange(Page page, int rangeStart, int rangeEnd) {
        int pagePosition = rangeStart;

        if (currentDefLevelCursor != null || currentIndexCursor != null) {
            skipCursorsTo(pagePosition);
        }

        while (pagePosition < rangeEnd) {
            int spaceInBatch = batchCapacity - rowsInCurrentBatch;
            int toCopy = Math.min(spaceInBatch, rangeEnd - pagePosition);

            // Respect maxRows: limit the copy to remaining budget
            if (maxRows > 0) {
                long remaining = maxRows - totalRowsAssembled;
                if (remaining <= 0) {
                    finishDrain();
                    return;
                }
                toCopy = (int) Math.min(toCopy, remaining);
            }

            if (currentDefLevelCursor != null) {
                copyPageDataFused(page, rowsInCurrentBatch, toCopy);
                cursorLogicalPosition += toCopy;
            } else if (currentIndexCursor != null) {
                copyPageDataIndexOnly(page, rowsInCurrentBatch, toCopy);
                cursorLogicalPosition += toCopy;
            } else {
                copyPageData(page, pagePosition, rowsInCurrentBatch, toCopy);
            }

            rowsInCurrentBatch += toCopy;
            totalRowsAssembled += toCopy;
            pagePosition += toCopy;

            if (rowsInCurrentBatch >= batchCapacity) {
                publishCurrentBatch();
                if (done) {
                    return;
                }
            }

            // Check if we've hit the limit after publishing
            if (maxRows > 0 && totalRowsAssembled >= maxRows) {
                if (rowsInCurrentBatch > 0) {
                    publishCurrentBatch();
                }
                finishDrain();
                return;
            }
        }
    }

    @Override
    void publishCurrentBatch() {
        if (done) {
            return;
        }
        currentBatch.recordCount = rowsInCurrentBatch;
        currentBatch.validity = (currentValidity != null && currentBatchHasAbsents)
                ? Arrays.copyOf(currentValidity, (rowsInCurrentBatch + 63) >>> 6)
                : null;
        currentBatch.fileName = currentBatchFileName;
        currentBatch.filterAlwaysMatches = flushOnFilterBoundaries && currentBatchFilterAlwaysMatches;

        if (columnFilter != null) {
            if (currentBatchFilterAlwaysMatches) {
                // Statistics proved every record of this batch's row group matches the
                // predicate: emit the all-ones mask directly instead of evaluating records.
                fillAllOnes(currentBatch.matches, rowsInCurrentBatch);
            }
            else {
                // Drain-side filter: evaluate while the just-filled value array is hot in
                // this drain core's L1. Writes into currentBatch.matches in place.
                columnFilter.test(currentBatch, currentBatch.matches);
            }
        }

        long t0 = System.nanoTime();
        try {
            if (!exchange.publish(currentBatch)) {
                done = true; // stopped during publish
                return;
            }
            currentBatch = exchange.takeBatch();
            if (currentBatch == null) {
                done = true; // stopped during take
                return;
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = true;
            return;
        }
        publishBlockNanos += System.nanoTime() - t0;
        batchesPublished++;

        rowsInCurrentBatch = 0;
        if (currentValidity != null) {
            Arrays.fill(currentValidity, 0L);
        }
        currentBatchHasAbsents = false;
        // The freshly-taken batch is recycled; clear its dictionary slot so the
        // next batch rebuilds dictIndex state from scratch (the dictIndices
        // array is retained and overwritten in place).
        if (currentBatch.values instanceof BinaryBatchValues bbv) {
            bbv.dictionary = null;
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    private void copyPageData(Page page, int srcPos, int destPos, int length) {
        Object values = currentBatch.values;
        switch (page) {
            case Page.IntPage p -> {
                System.arraycopy(p.values(), srcPos, (int[]) values, destPos, length);
                markNulls(p.definitionLevels(), srcPos, destPos, length);
            }
            case Page.LongPage p -> {
                System.arraycopy(p.values(), srcPos, (long[]) values, destPos, length);
                markNulls(p.definitionLevels(), srcPos, destPos, length);
            }
            case Page.FloatPage p -> {
                System.arraycopy(p.values(), srcPos, (float[]) values, destPos, length);
                markNulls(p.definitionLevels(), srcPos, destPos, length);
            }
            case Page.DoublePage p -> {
                System.arraycopy(p.values(), srcPos, (double[]) values, destPos, length);
                markNulls(p.definitionLevels(), srcPos, destPos, length);
            }
            case Page.BooleanPage p -> {
                System.arraycopy(p.values(), srcPos, (boolean[]) values, destPos, length);
                markNulls(p.definitionLevels(), srcPos, destPos, length);
            }
            case Page.ByteArrayPage p -> {
                BinaryBatchValues bbv = (BinaryBatchValues) values;
                byte[][] pageValues = p.values();
                boolean fixedLen = physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY;
                for (int i = 0; i < length; i++) {
                    byte[] val = pageValues[srcPos + i];
                    int dest = destPos + i;
                    if (val != null) {
                        bbv.appendAt(dest, val, 0, val.length);
                    }
                    else if (!fixedLen) {
                        bbv.appendAt(dest, EMPTY_BYTES, 0, 0);
                    }
                    // FIXED_LEN null: pre-filled trivial offsets are kept;
                    // bytes content at this slot is undefined scratch.
                }
                // Record per-value dictionary indices so stringAt can intern; a
                // no-op for non-string columns, and plain/null values fall back
                // to the packed-byte path (see BinaryBatchValues#recordDictIndices).
                bbv.recordDictIndices(p.dictIndices(), p.dictionary(), srcPos, destPos, length);
                markNulls(p.definitionLevels(), srcPos, destPos, length);
            }
        }
    }

    /// Records a validity bit for each value just copied. Set bit means the
    /// leaf at that position is **present** (`def == maxDefinitionLevel`);
    /// absent positions leave their bit clear.
    ///
    /// On the no-absents fast path the bitmap is left untouched — a `null`
    /// validity at publish-time is the sparse representation of "every leaf
    /// in this batch is present", so setting bits we'd then drop is wasted
    /// work. The first absent encountered switches the bitmap on by
    /// backfilling the bits for all previously-seen present values
    /// (`[0, destPos + i)`); subsequent values then maintain the bitmap
    /// normally. When `defLevels` is `null` the page has no def-level stream,
    /// which implies every leaf in the page is present — only touch the
    /// bitmap if it was already switched on by an earlier absent in the
    /// batch.
    private void markNulls(int[] defLevels, int srcPos, int destPos, int length) {
        if (currentValidity == null) {
            return;
        }
        if (defLevels == null) {
            if (currentBatchHasAbsents) {
                BitmapWords.setRange(currentValidity, destPos, destPos + length);
            }
            return;
        }
        for (int i = 0; i < length; i++) {
            if (defLevels[srcPos + i] < maxDefinitionLevel) {
                if (!currentBatchHasAbsents) {
                    currentBatchHasAbsents = true;
                    BitmapWords.setRange(currentValidity, 0, destPos + i);
                }
            }
            else if (currentBatchHasAbsents) {
                int bit = destPos + i;
                currentValidity[bit >>> 6] |= 1L << bit;
            }
        }
    }

    private void skipCursorsTo(int targetPos) {
        int toSkip = targetPos - cursorLogicalPosition;
        if (toSkip <= 0) {
            return;
        }

        if (currentDefLevelCursor != null) {
            // Def+index fused path: skip both cursors in lockstep.
            int requestedSkip = toSkip;
            while (toSkip > 0) {
                if (currentDefLevelCursor.remaining() == 0) {
                    if (!currentDefLevelCursor.advance()) {
                        break;
                    }
                }
                int runSkip = Math.min(toSkip, currentDefLevelCursor.remaining());

                if (currentDefLevelCursor.isRle()) {
                    if (currentDefLevelCursor.value() == maxDefinitionLevel) {
                        skipIndexCursor(runSkip);
                    }
                    currentDefLevelCursor.skip(runSkip);
                } else {
                    int presentCount = currentDefLevelCursor.skipCounting(runSkip, maxDefinitionLevel);
                    skipIndexCursor(presentCount);
                }
                toSkip -= runSkip;
            }
            if (toSkip > 0) {
                int walked = requestedSkip - toSkip;
                throw new IllegalStateException("Insufficient RLE/Bit-Packing data: walked "
                        + walked + " of " + requestedSkip + " requested values");
            }
            cursorLogicalPosition = targetPos;
        } else {
            // Index-only fused path: skip only the index cursor.
            skipIndexCursor(toSkip);
            cursorLogicalPosition = targetPos;
        }
    }

    private void skipIndexCursor(int toSkip) {
        if (currentIndexCursor == null || currentIndexCursor.bitWidth() == 0) {
            return;
        }
        int requestedSkip = toSkip;
        while (toSkip > 0) {
            if (currentIndexCursor.remaining() == 0) {
                if (!currentIndexCursor.advance()) {
                    break;
                }
            }
            int runSkip = Math.min(toSkip, currentIndexCursor.remaining());
            currentIndexCursor.skip(runSkip);
            toSkip -= runSkip;
        }
        if (toSkip > 0) {
            int walked = requestedSkip - toSkip;
            throw new IllegalStateException("Insufficient RLE/Bit-Packing data: walked "
                    + walked + " of " + requestedSkip + " requested values");
        }
    }

    /// Index-only fused drain for required dictionary columns (`maxDefLevel == 0`).
    /// All values are present — no validity bitmap, no null fills — just scatter
    /// dictionary values run-by-run from the index cursor.
    private void copyPageDataIndexOnly(Page page, int destPos, int length) {
        copyIndexValues(page, destPos, length);
    }

    private void copyPageDataFused(Page page, int destPos, int length) {
        if (currentDefLevelCursor.remaining() == 0) {
            currentDefLevelCursor.advance();
        }
        if (currentDefLevelCursor.isRle()
                && currentDefLevelCursor.value() == maxDefinitionLevel
                && currentDefLevelCursor.remaining() >= length) {
            processPresentRange(page, destPos, length);
            currentDefLevelCursor.skip(length);
            return;
        }

        int copied = 0;
        while (copied < length) {
            if (currentDefLevelCursor.remaining() == 0) {
                if (!currentDefLevelCursor.advance()) {
                    break;
                }
            }
            int toCopy = Math.min(length - copied, currentDefLevelCursor.remaining());
            if (currentDefLevelCursor.isRle()) {
                if (currentDefLevelCursor.value() == maxDefinitionLevel) {
                    processPresentRange(page, destPos + copied, toCopy);
                } else {
                    processAbsentRange(page, destPos + copied, toCopy);
                }
                currentDefLevelCursor.skip(toCopy);
            } else {
                int unpacked = currentDefLevelCursor.unpack(tempDefs, 0, toCopy);
                // Coalesce consecutive present/absent def levels into runs so the
                // index scatter stays bulk on present stretches (and null fills
                // stay bulk on absent stretches) — a per-value loop here would
                // reduce a null-heavy page to a slow value-at-a-time index decode.
                int i = 0;
                while (i < unpacked) {
                    boolean present = tempDefs[i] == maxDefinitionLevel;
                    int runStart = i;
                    do {
                        i++;
                    } while (i < unpacked && (tempDefs[i] == maxDefinitionLevel) == present);
                    int runLen = i - runStart;
                    int dst = destPos + copied + runStart;
                    if (present) {
                        processPresentRange(page, dst, runLen);
                    } else {
                        processAbsentRange(page, dst, runLen);
                    }
                }
            }
            copied += toCopy;
        }
        if (copied < length) {
            throw new IllegalStateException("Insufficient RLE/Bit-Packing data: decoded "
                    + copied + " of " + length + " requested values");
        }
    }

    private void processPresentRange(Page page, int destPos, int count) {
        if (currentBatchHasAbsents) {
            BitmapWords.setRange(currentValidity, destPos, destPos + count);
        }
        copyIndexValues(page, destPos, count);
    }

    private void processAbsentRange(Page page, int destPos, int count) {
        if (!currentBatchHasAbsents) {
            currentBatchHasAbsents = true;
            BitmapWords.setRange(currentValidity, 0, destPos);
        }
        fillNulls(page, destPos, count);
    }

    private void copyIndexValues(Page page, int destPos, int count) {
        if (currentIndexCursor == null) {
            return;
        }
        // Bit width 0 carries no index bytes: every value maps to dictionary
        // entry 0 (the whole page references a single dictionary entry). The
        // cursor faithfully represents an empty stream, so drive the constant
        // fill here rather than advancing it.
        if (currentIndexCursor.bitWidth() == 0) {
            copySingleIndexRepeated(page, destPos, 0, count);
            return;
        }
        int copied = 0;
        while (copied < count) {
            if (currentIndexCursor.remaining() == 0) {
                if (!currentIndexCursor.advance()) {
                    break;
                }
            }
            int toCopy = Math.min(count - copied, currentIndexCursor.remaining());

            if (currentIndexCursor.isRle()) {
                int indexValue = currentIndexCursor.value();
                copySingleIndexRepeated(page, destPos + copied, indexValue, toCopy);
                currentIndexCursor.skip(toCopy);
            } else {
                unpackBitPackedIndices(page, destPos + copied, toCopy);
            }
            copied += toCopy;
        }
        if (copied < count) {
            throw new IllegalStateException("Insufficient RLE/Bit-Packing data: decoded "
                    + copied + " of " + count + " requested values");
        }
    }

    private void unpackBitPackedIndices(Page page, int destPos, int toCopy) {
        Object values = currentBatch.values;
        Dictionary dict = page.dictionary();
        switch (dict) {
            case Dictionary.IntDictionary d -> currentIndexCursor.unpackAndLookupInts(d.values(), (int[]) values, destPos, toCopy);
            case Dictionary.LongDictionary d -> currentIndexCursor.unpackAndLookupLongs(d.values(), (long[]) values, destPos, toCopy);
            case Dictionary.FloatDictionary d -> currentIndexCursor.unpackAndLookupFloats(d.values(), (float[]) values, destPos, toCopy);
            case Dictionary.DoubleDictionary d -> currentIndexCursor.unpackAndLookupDoubles(d.values(), (double[]) values, destPos, toCopy);
            case Dictionary.ByteArrayDictionary d -> {
                int unpacked = currentIndexCursor.unpack(tempIndices, 0, toCopy);
                copyMappedByteArrayIndices(d, tempIndices, destPos, unpacked);
            }
            case null -> throw new IllegalStateException("Page dictionary is null during index decoding for column '" + column.fieldPath() + "'");
        }
    }

    private void copySingleIndexRepeated(Page page, int destPos, int index, int count) {
        Object values = currentBatch.values;
        Dictionary dict = page.dictionary();
        switch (dict) {
            case Dictionary.IntDictionary d -> Arrays.fill((int[]) values, destPos, destPos + count, d.values()[index]);
            case Dictionary.LongDictionary d -> Arrays.fill((long[]) values, destPos, destPos + count, d.values()[index]);
            case Dictionary.FloatDictionary d -> Arrays.fill((float[]) values, destPos, destPos + count, d.values()[index]);
            case Dictionary.DoubleDictionary d -> Arrays.fill((double[]) values, destPos, destPos + count, d.values()[index]);
            case Dictionary.ByteArrayDictionary d -> copyRepeatedByteArrayIndex(d, index, destPos, count);
            case null -> throw new IllegalStateException("Page dictionary is null during index decoding for column '" + column.fieldPath() + "'");
        }
    }

    private void copyRepeatedByteArrayIndex(Dictionary.ByteArrayDictionary d, int index, int destPos, int count) {
        BinaryBatchValues bbv = (BinaryBatchValues) currentBatch.values;
        byte[] val = d.values()[index];
        for (int i = 0; i < count; i++) {
            bbv.appendAt(destPos + i, val, 0, val.length);
        }
        bbv.recordRepeatedDictIndex(d, destPos, count, index);
    }

    private void copyMappedByteArrayIndices(Dictionary.ByteArrayDictionary d, int[] indices, int destPos, int count) {
        BinaryBatchValues bbv = (BinaryBatchValues) currentBatch.values;
        byte[][] dictVals = d.values();
        for (int i = 0; i < count; i++) {
            byte[] val = dictVals[indices[i]];
            bbv.appendAt(destPos + i, val, 0, val.length);
        }
        bbv.recordMappedDictIndices(d, indices, destPos, count);
    }

    /// Writes the null representation for `count` absent leaves at `destPos`.
    ///
    /// The batch value array is recycled across batches, so a null slot is not
    /// implicitly zero — it holds whatever a prior batch wrote there. The
    /// materializing path overwrites every slot (including null ones, which are
    /// zero in the freshly-decoded page array), so the fused path must zero the
    /// null slots too to keep the value at absent positions deterministic.
    private void fillNulls(Page page, int destPos, int count) {
        Object values = currentBatch.values;
        switch (page.dictionary()) {
            case Dictionary.IntDictionary ignored -> Arrays.fill((int[]) values, destPos, destPos + count, 0);
            case Dictionary.LongDictionary ignored -> Arrays.fill((long[]) values, destPos, destPos + count, 0L);
            case Dictionary.FloatDictionary ignored -> Arrays.fill((float[]) values, destPos, destPos + count, 0f);
            case Dictionary.DoubleDictionary ignored -> Arrays.fill((double[]) values, destPos, destPos + count, 0d);
            case Dictionary.ByteArrayDictionary ignored -> {
                BinaryBatchValues bbv = (BinaryBatchValues) values;
                // FIXED_LEN_BYTE_ARRAY: the pre-zeroed offset slots are kept as-is for
                // null positions, matching the materialising path which also skips
                // appendAt for FIXED_LEN nulls (the bytes content is undefined scratch).
                // Variable-length types need an explicit zero-length entry so offsets
                // stay consistent across the batch.
                boolean fixedLen = physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY;
                if (!fixedLen) {
                    for (int i = 0; i < count; i++) {
                        bbv.appendAt(destPos + i, EMPTY_BYTES, 0, 0);
                    }
                }
                // Clear dictIndices for nulls so we don't accidentally intern them
                bbv.fillNullDictIndices(destPos, count);
            }
            case null -> { /* no dictionary: fused path is dictionary-only */ }
        }
    }
}
