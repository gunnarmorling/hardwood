/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import dev.hardwood.internal.ExceptionContext;
import dev.hardwood.internal.compression.DecompressorFactory;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.reader.ParquetReadException;
import dev.hardwood.schema.ColumnSchema;

/// Per-column pipeline that decodes pages in parallel and assembles batches.
///
/// Two long-lived virtual threads per column:
///
/// - **Retriever VThread:** Pulls [PageInfo] objects from a [PageSource],
///   submits decode tasks to the provided executor. Throttles itself
///   when the gap between submitted and drained pages reaches `MAX_INFLIGHT_PAGES`.
///
/// - **Drain VThread:** Reads decoded pages from a circular reorder buffer in
///   sequence order, assembles them into batches via subclass-specific logic,
///   and publishes to the [BatchExchange].
///
/// The reorder buffer is an [AtomicReferenceArray] indexed by
/// `seqNum % MAX_INFLIGHT_PAGES`. This avoids the GC pressure of
/// `ConcurrentHashMap` (no integer boxing, no Node allocations).
/// Decode tasks store their result via `set()` and unpark the drain thread.
///
/// @param <B> the batch type (e.g. [BatchExchange.Batch] for flat, [NestedBatch] for nested)
public abstract class ColumnWorker<B> implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ColumnWorker.class.getName());

    /// Decoded page paired with its [PageRowMask]. Stored in the reorder
    /// buffer so the drain receives both the decoded values and the per-page
    /// row selection in a single read.
    record DecodedPage(Page page, PageRowMask mask) {}

    /// Sentinel value stored in the reorder buffer to signal end-of-stream.
    private static final DecodedPage EMPTY_SENTINEL =
            new DecodedPage(new Page.IntPage(new int[0], null, null, 0, -1), PageRowMask.ALL);

    private final PageSource pageSource;
    private final DecompressorFactory decompressorFactory;
    private final Executor decodeExecutor;

    /// Whether the fixed-size-list read fast path may engage. Defaults to `true`;
    /// nested workers override it from the reader's context option. It is a no-op
    /// for flat columns (the fast path requires `maxRepetitionLevel == 1`).
    protected boolean fixedListFastPathEnabled = true;

    final BatchExchange<B> exchange;
    final ColumnSchema column;
    final PhysicalType physicalType;
    final int batchCapacity;
    final int maxDefinitionLevel;

    // === Circular reorder buffer: decode tasks write, drain thread reads ===
    private final AtomicReferenceArray<DecodedPage> reorderBuffer;

    // Level buffers share the lifecycle of their reorder-buffer slot. The
    // retriever throttle prevents reuse until the drain has consumed the page.
    private final PageDecoder.LevelScratch[] levelScratchBuffer;

    // === File name per reorder-buffer slot (retriever writes, drain reads) ===
    // Visibility: retriever writes fileNameBuffer[slot] before submitting the
    // decode task. The decode task's volatile write to reorderBuffer[slot]
    // happens-after the retriever's plain write. The drain's volatile read of
    // reorderBuffer[slot] sees the fileName via the happens-before chain.
    //
    // Slot reuse safety: the retriever may only reuse a slot once consumePosition
    // has advanced past it (throttle: nextSeq - consumePosition < MAX_INFLIGHT_PAGES).
    // drainReadyPages reads fileNameBuffer[slot] before incrementing consumePosition,
    // so the previous occupant's fileName is always read before being overwritten.
    // Any future change to the throttle or to the read-then-increment ordering must
    // preserve this invariant.
    private final String[] fileNameBuffer;

    // Per-slot filter-always-matches flag, written by the retriever alongside
    // fileNameBuffer[slot] under the same happens-before chain: whether the page's
    // row group was proven by statistics to match the filter in full.
    private final boolean[] filterAlwaysMatchesBuffer;

    // === Drain position (only modified by drain thread, read by retriever for throttle) ===
    private volatile int consumePosition;

    // === Pipeline control ===
    /// Set when the worker should stop, for any of three reasons: the consumer
    /// called [#close()], the drain reached natural EOF or the configured
    /// `maxRows` (via [#finishDrain()]), or an error was raised
    /// (via [#signalError(Throwable)]). Both VThreads exit promptly when set.
    volatile boolean done;
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    // === Thread references (for unpark) ===
    volatile Thread retrieverThread;
    volatile Thread drainThread;

    // === In-flight decode tasks (tracked so close() can await them) ===
    private final Set<CompletableFuture<Void>> inFlightDecodes = ConcurrentHashMap.newKeySet();

    /// Sentinel for the `maxRows` / row-limit contract meaning "no limit".
    static final long UNLIMITED = 0L;

    // === Drain assembly state (drain thread only) ===

    /// Whether a filter is installed, so that the reader downstream returns a subset of
    /// what the drain assembles. Two things follow: `maxRows` counts matching rows rather
    /// than scanned ones (see [#activeMaxRows]), and batches are kept homogeneous in
    /// whether statistics decided them, so a reader can act on a whole batch at a time.
    /// Read from the page source when the drain starts, which is where it first matters.
    boolean filterActive;

    /// The cap the drain is enforcing. Starts at the constructor's `maxRows`, except that when
    /// [#filterActive] it drops to [#UNLIMITED] — for the remainder of the read —
    /// at the first page whose row group statistics did not prove to match the filter in
    /// full. Up to that point every assembled row is a matching row, so the drain can
    /// count them against the cap; past it only the filtering reader downstream can.
    /// Written and read on the drain thread only.
    long activeMaxRows;

    long totalRowsAssembled;
    B currentBatch;
    int rowsInCurrentBatch;

    /// File name of the file being assembled into the current batch.
    /// Written only by the drain thread.
    String currentBatchFileName;

    /// Whether every page of the current batch comes from a row group whose statistics
    /// prove the filter matches all rows. Only maintained (with batch flushes on
    /// transitions) when [#flushOnFilterAlwaysMatchesTransition] is `true`.
    boolean currentBatchFilterAlwaysMatches;

    // === Instrumentation (drain thread only) ===
    long publishBlockNanos;
    int batchesPublished;

    /// Creates a new column worker.
    ///
    /// @param pageSource yields [PageInfo] objects for this column
    /// @param exchange the output exchange for assembled batches
    /// @param column the column schema
    /// @param batchCapacity rows per batch
    /// @param decompressorFactory for creating page decompressors
    /// @param decodeExecutor executor for decode tasks
    /// @param maxRows maximum rows to assemble (0 = unlimited). The drain stops
    ///        after assembling this many rows and publishes the partial batch.
    ///        With a filter installed the cap is on matching rows, and the drain
    ///        applies it only as far as statistics prove every row it assembles
    ///        matches — see [#activeMaxRows].
    protected ColumnWorker(PageSource pageSource, BatchExchange<B> exchange, ColumnSchema column,
                           int batchCapacity, DecompressorFactory decompressorFactory,
                           Executor decodeExecutor, long maxRows) {
        this.pageSource = pageSource;
        this.exchange = exchange;
        this.column = column;
        this.physicalType = column.type();
        this.batchCapacity = batchCapacity;
        this.maxDefinitionLevel = column.maxDefinitionLevel();
        this.decompressorFactory = decompressorFactory;
        this.decodeExecutor = decodeExecutor;
        this.activeMaxRows = maxRows;
        this.reorderBuffer = new AtomicReferenceArray<>(MAX_INFLIGHT_PAGES);
        this.levelScratchBuffer = new PageDecoder.LevelScratch[MAX_INFLIGHT_PAGES];
        for (int i = 0; i < levelScratchBuffer.length; i++) {
            levelScratchBuffer[i] = new PageDecoder.LevelScratch();
        }
        this.fileNameBuffer = new String[MAX_INFLIGHT_PAGES];
        this.filterAlwaysMatchesBuffer = new boolean[MAX_INFLIGHT_PAGES];
    }

    /// Initializes subclass-specific drain state (called at the start of `runDrain`).
    abstract void initDrainState();

    /// Assembles a single decoded page into the current batch.
    /// `mask` selects which records of the page to keep — [PageRowMask#ALL]
    /// when filter pushdown is inactive (or matched the whole page), otherwise
    /// a tighter per-page mask.
    abstract void assemblePage(Page page, PageRowMask mask);

    /// Publishes the current batch to the [BatchExchange] and takes a new free batch.
    abstract void publishCurrentBatch();

    /// Whether the drain should flush the current batch when crossing a row-group
    /// boundary that changes the filter-always-matches flag. Only worth it when a
    /// filter is installed — a homogeneous batch lets a reader skip evaluating the
    /// whole of it — and for an unfiltered read the extra flushes would shrink
    /// batches for nothing.
    boolean flushOnFilterAlwaysMatchesTransition() {
        return filterActive;
    }

    /// Starts both virtual threads. Must be called once.
    ///
    /// Thread fields are assigned before `start()` so an early
    /// `unparkRetriever()` from the drain cannot observe a null reference and
    /// silently drop the unpark.
    public void start() {
        this.drainThread = Thread.ofVirtual().unstarted(this::runDrain);
        this.retrieverThread = Thread.ofVirtual().unstarted(this::runRetriever);
        drainThread.start();
        retrieverThread.start();
    }

    /// Signals the worker to stop and blocks until the pipeline has fully quiesced:
    /// both VThreads have exited and every in-flight decode task has completed.
    ///
    /// This is required so that callers can safely release resources owned by the
    /// underlying [dev.hardwood.InputFile] (mapped or direct byte buffers, HTTP
    /// connections, etc.) without risking a SIGSEGV from a decode task still
    /// reading from a freed buffer.
    @Override
    public void close() {
        done = true;
        exchange.finish();  // signals BatchExchange's timeout loops to exit
        LockSupport.unpark(retrieverThread);
        LockSupport.unpark(drainThread);
        // `finish()` only sets a flag, and a drain blocked inside the exchange is waiting on a
        // queue rather than on that flag: it re-reads it when its 10 ms timed queue operation
        // expires, which close() then inherits through the join below — once per column, since
        // ColumnReaders closes them one at a time. The interrupt releases it at once. Unparking
        // is not enough on its own: ArrayBlockingQueue's timed operations go through
        // AQS.ConditionObject.awaitNanos, which treats a bare unpark as spurious and re-parks
        // for the remainder of the window. The unpark above is still needed for the drain's
        // other wait, the LockSupport.park() in runDrain that waits on a decode task.
        //
        // Only the drain is interrupted, and only because it does no I/O: every InputFile
        // access happens on the retriever (via PageSource.next) or on a decode task. That
        // matters — FileChannel is an InterruptibleChannel, so interrupting a thread inside a
        // channel operation, or one that enters a channel operation with its interrupt flag
        // already set, closes the channel for every reader sharing it (see MappedInputFile's
        // note on its larger-than-2 GB path). Anything that gives the drain thread its own
        // InputFile access — for instance fetching on demand instead of parking when the
        // reorder buffer is empty — must drop this interrupt first.
        drainThread.interrupt();

        try {
            retrieverThread.join();
            drainThread.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // The retriever has exited, so no new decode tasks will be submitted.
        // Drain any that are still running. Tasks that hadn't yet started early-return
        // via the `done` check in decode(), so this typically waits only on the small
        // number that were mid-execution when `done` was set.
        CompletableFuture<?>[] pending = inFlightDecodes.toArray(new CompletableFuture<?>[0]);
        if (pending.length > 0) {
            try {
                CompletableFuture.allOf(pending).join();
            }
            catch (Exception ignored) {
                // decode tasks call signalError on failure; nothing to re-raise here
            }
        }
    }

    /// Whether the pipeline has stopped producing batches (for any reason —
    /// natural EOF, `maxRows`, error, or [#close()]).
    public boolean isFinished() {
        return done;
    }

    // ==================== Retriever VThread ====================

    private long sourceNanos;
    private long throttleNanos;
    private int totalPagesSubmitted;
    private int throttleParks;

    private void runRetriever() {
        try {
            LOG.log(System.Logger.Level.DEBUG,
                    "[{0}] ColumnWorker started, maxOutstanding={1}, batchCapacity={2}",
                    column.name(), MAX_INFLIGHT_PAGES, batchCapacity);

            PageDecoder pageDecoder = null;
            int nextSeq = 0;

            long t0;
            PageInfo pageInfo;
            while (!done) {
                // Pull next page from source
                t0 = System.nanoTime();
                pageInfo = pageSource.next();
                sourceNanos += System.nanoTime() - t0;
                if (pageInfo == null) {
                    break;
                }

                // Create/update PageDecoder when column metadata changes (file transitions)
                if (pageDecoder == null || !pageDecoder.isCompatibleWith(pageInfo.columnMetaData())) {
                    pageDecoder = new PageDecoder(
                            pageInfo.columnMetaData(),
                            pageInfo.columnSchema(),
                            decompressorFactory,
                            fixedListFastPathEnabled);
                }

                // Throttle: park while too many pages are in flight
                t0 = System.nanoTime();
                while (!done && nextSeq - consumePosition >= MAX_INFLIGHT_PAGES) {
                    throttleParks++;
                    LockSupport.park();
                }
                throttleNanos += System.nanoTime() - t0;
                if (done) {
                    break;
                }

                // Submit decode task to executor (reuses pooled threads, no VThread per page)
                int seq = nextSeq++;
                totalPagesSubmitted++;
                int slot = seq % MAX_INFLIGHT_PAGES;
                fileNameBuffer[slot] = pageSource.getCurrentFileName();
                filterAlwaysMatchesBuffer[slot] = pageSource.isCurrentFilterAlwaysMatches();
                PageInfo pi = pageInfo;
                PageDecoder rdr = pageDecoder;
                CompletableFuture<Void> f = CompletableFuture.runAsync(
                        () -> decode(slot, pi, rdr), decodeExecutor);
                inFlightDecodes.add(f);
                f.whenComplete((v, t) -> inFlightDecodes.remove(f));
            }

            if (!done) {
                // The sentinel needs a free slot. If all MAX_INFLIGHT_PAGES slots
                // are occupied (pages submitted but not yet drained), wait for
                // the drain to advance before writing.
                while (!done && nextSeq - consumePosition >= MAX_INFLIGHT_PAGES) {
                    LockSupport.park();
                }
                if (!done) {
                    int sentinelSlot = nextSeq % MAX_INFLIGHT_PAGES;
                    reorderBuffer.set(sentinelSlot, EMPTY_SENTINEL);
                    LockSupport.unpark(drainThread);
                }
            }

            LOG.log(System.Logger.Level.DEBUG,
                    "[{0}] Retriever finished: {1} pages submitted. "
                    + "source={2,number,0.0}ms, throttle={3,number,0.0}ms ({4} parks)",
                    column.name(), totalPagesSubmitted,
                    sourceNanos / 1_000_000.0, throttleNanos / 1_000_000.0, throttleParks);
        }
        catch (Throwable t) {
            signalError(enrichWithFileName(t, pageSource.getCurrentFileName()));
        }
    }

    /// Decode task: decodes one page, stores result in reorder buffer, unparks drain.
    private void decode(int slot, PageInfo pageInfo, PageDecoder pageDecoder) {
        if (done || error.get() != null) {
            return;
        }
        try {
            Page page = pageInfo.isNullPlaceholder()
                    ? pageDecoder.nullPage(pageInfo.placeholderNumValues())
                    : pageDecoder.decodePage(pageInfo.pageData(), pageInfo.dictionary(), levelScratchBuffer[slot]);
            reorderBuffer.set(slot, new DecodedPage(page, pageInfo.mask()));
        }
        catch (Throwable t) {
            signalError(enrichWithFileName(t, fileNameBuffer[slot]));
        }
        LockSupport.unpark(drainThread);
    }

    // ==================== Drain VThread ====================

    private long assemblyNanos;
    private long decodeWaitNanos;
    private int totalPagesDrained;
    private int decodeWaitParks;

    private void runDrain() {
        try {
            filterActive = pageSource.isFilterActive();
            currentBatch = exchange.takeBatch();
            initDrainState();

            while (!done) {
                long t0 = System.nanoTime();
                boolean drained = drainReadyPages();
                assemblyNanos += System.nanoTime() - t0;

                if (!done && !drained) {
                    // No pages were ready — park until a decode task completes
                    long parkStart = System.nanoTime();
                    decodeWaitParks++;
                    LockSupport.park();
                    decodeWaitNanos += System.nanoTime() - parkStart;
                }
                // If we drained something, loop immediately to check for more
            }

            // assemblyNanos includes publishBlock; subtract to get pure assembly
            long pureAssembly = assemblyNanos - publishBlockNanos;

            LOG.log(System.Logger.Level.DEBUG,
                    "[{0}] Drain finished: {1} pages drained, {2} batches. "
                    + "assembly={3,number,0.0}ms, decodeWait={4,number,0.0}ms ({5} parks), "
                    + "publishBlock={6,number,0.0}ms",
                    column.name(), totalPagesDrained, batchesPublished,
                    pureAssembly / 1_000_000.0, decodeWaitNanos / 1_000_000.0, decodeWaitParks,
                    publishBlockNanos / 1_000_000.0);
        }
        catch (Throwable t) {
            signalError(enrichWithFileName(t, currentBatchFileName));
        }
    }

    /// Drains all consecutive ready pages from the reorder buffer.
    /// Returns true if at least one page was drained.
    private boolean drainReadyPages() {
        boolean drained = false;
        while (!done) {
            int slot = consumePosition % MAX_INFLIGHT_PAGES;
            DecodedPage decoded = reorderBuffer.getAndSet(slot, null);
            if (decoded == null) {
                break;
            }
            if (decoded == EMPTY_SENTINEL) {
                finishDrain();
                return true;
            }

            // Detect file boundary: flush the current batch when the file changes
            // so that each batch is attributed to a single file.
            String pageFileName = fileNameBuffer[slot];
            if (pageFileName != null) {
                if (currentBatchFileName != null
                        && !pageFileName.equals(currentBatchFileName)
                        && rowsInCurrentBatch > 0) {
                    publishCurrentBatch();
                }
                currentBatchFileName = pageFileName;
            }

            boolean pageAlwaysMatches = filterAlwaysMatchesBuffer[slot];

            // Statistics did not prove this page's row group matches in full, so from
            // here on the drain can no longer tell a scanned row from a matching one.
            // Give up the cap and leave it to the filtering reader downstream, which
            // counts matches.
            if (filterActive && !pageAlwaysMatches) {
                activeMaxRows = UNLIMITED;
            }

            // Detect a filter-always-matches boundary: flush so that each batch is
            // homogeneous and the per-batch filter can be skipped for batches whose
            // row groups are proven to match in full. Row groups only ever share a
            // batch within one file, so this composes with the file flush above.
            if (flushOnFilterAlwaysMatchesTransition()) {
                if (pageAlwaysMatches != currentBatchFilterAlwaysMatches && rowsInCurrentBatch > 0) {
                    publishCurrentBatch();
                }
                currentBatchFilterAlwaysMatches = pageAlwaysMatches;
            }

            assemblePage(decoded.page(), decoded.mask());
            consumePosition++;
            totalPagesDrained++;
            unparkRetriever();
            drained = true;
        }
        return drained;
    }

    void finishDrain() {
        if (rowsInCurrentBatch > 0) {
            publishCurrentBatch();
        }
        done = true;
        exchange.finish();
        // Wake the retriever so it can observe `done` and exit; otherwise it
        // could be parked on the throttle indefinitely (consumePosition never
        // advances again once drain has finished).
        unparkRetriever();
    }

    // ==================== Error Handling ====================

    void signalError(Throwable t) {
        error.compareAndSet(null, t);
        done = true;
        exchange.signalError(t);
        LockSupport.unpark(retrieverThread);
        LockSupport.unpark(drainThread);
    }

    /// Says what a throwable means, then names the file it came from.
    ///
    /// [#asReadFailure] decides the type first, so what is enriched here is already the
    /// exception a caller will see. A `RuntimeException` — including the
    /// `ParquetReadException` most decoder failures have just become — is enriched via
    /// [ExceptionContext#addFileContext], which preserves whatever type it arrived as.
    /// `IOException` is restated as a fresh `IOException` carrying the prefix: the pipeline
    /// carries a failure across its thread boundary as a `Throwable`, so it stays checked the
    /// whole way and the readers declare it rather than unwrapping anything. `Error` and other
    /// throwables propagate unchanged.
    private static Throwable enrichWithFileName(Throwable t, String fileName) {
        Throwable typed = asReadFailure(t);
        if (fileName == null || fileName.isEmpty()) {
            return typed;
        }
        if (typed instanceof RuntimeException re) {
            return ExceptionContext.addFileContext(fileName, re);
        }
        if (typed instanceof IOException ioe) {
            // Stays checked. The pipeline carries a failure across its thread
            // boundary as a `Throwable`, so nothing between here and the reader
            // needs it wrapped, and the reader's own signature can declare it.
            return new IOException(
                    ExceptionContext.filePrefix(fileName)
                            + (ioe.getMessage() != null ? ioe.getMessage() : "I/O failure"),
                    ioe);
        }
        return typed;
    }

    /// What a decoder threw, said as what it means.
    ///
    /// A corrupt dictionary index reaches here as an
    /// [ArrayIndexOutOfBoundsException] from `dict[i]`, an impossible RLE run
    /// header as an [IllegalStateException], a length that will not fit as an
    /// [ArithmeticException]. Every one of them is the file being wrong, and every
    /// one of them reads to a user as a defect in this library. They become a
    /// [ParquetReadException] keeping the original as its cause.
    ///
    /// Four things pass through. [Error] is neither the file's fault nor
    /// something to retry. An [IOException] is the transport, as is an
    /// [UncheckedIOException]: nothing under this reader raises one — every wrap
    /// made to leave a lambda is undone by the method enclosing it — but an
    /// [dev.hardwood.InputFile] is implementable from outside, and one that
    /// answers a failed `readRange` with the unchecked form is still describing
    /// the transport, so it must not be relabelled as the file being wrong. A
    /// [ParquetReadException] already says what it is — including a
    /// [dev.hardwood.reader.SchemaIncompatibleException]. And an
    /// [UnsupportedOperationException] is a codec library that is absent or an
    /// encoding not implemented, which is this library's limit rather than a
    /// fault in the file.
    ///
    /// The cost is that a defect of ours reaching a decoder is reported as a
    /// problem with the file. That is the rarer mistake: without this, every
    /// corrupt file is reported as a defect of ours.
    /// Package-private rather than private: this mapping is the judgement the reader's exception
    /// model rests on, and it is asserted directly rather than through a corrupt file for every
    /// arm of it.
    static Throwable asReadFailure(Throwable t) {
        if (t instanceof Error || t instanceof IOException
                || t instanceof UncheckedIOException
                || t instanceof ParquetReadException
                || t instanceof UnsupportedOperationException) {
            return t;
        }
        if (t instanceof RuntimeException) {
            return new ParquetReadException(
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), t);
        }
        return t;
    }

    private void unparkRetriever() {
        Thread t = retrieverThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /// Maximum number of decoded-but-undrained pages before the retriever throttles.
    /// Kept low to limit decoded page retention and GC pressure. With large pages
    /// (~4-10 MB decoded), high values cause old-gen promotion and expensive G1
    /// evacuation pauses. Overridable via the `hardwood.internal.maxOutstanding` system property.
    public static final int MAX_INFLIGHT_PAGES =
            Integer.getInteger("hardwood.internal.maxOutstanding", 8);
}
