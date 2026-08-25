/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// What tearing a reader down costs, as a function of how many columns are projected —
/// asked of both consumer shapes, [ColumnReaders#close()] and [RowReader#close()].
///
/// Teardown is not on the hot path of a long scan, so it is easy for it to acquire a cost
/// nobody notices. It is on the hot path of a *short* read — one batch, a `head(n)`, a point
/// lookup — where the read itself is single-digit milliseconds and anything teardown adds is
/// the dominant term.
///
/// Two things made it the dominant term. Each column's worker signalled its drain thread to
/// stop by setting a flag the drain only observed after a 10 ms timed queue operation
/// expired, so every close waited out that window; and the closes ran one after another, so
/// the windows added instead of overlapping. The cost was therefore linear in the projection
/// width, at about one poll interval per column.
///
/// Both consumer shapes have to be covered, because the two `BatchExchange` modes park their
/// drain thread at *different* blocking points and so are separately breakable.
/// [ColumnReaders] uses the detaching mode, whose drain parks in `publish()`'s timed offer on
/// a full ready queue; [RowReader] uses the recycling mode, whose drain parks in
/// `takeBatch()`'s timed poll on an empty free pool. A teardown that releases only one of the
/// two looks fixed from one shape and is still broken from the other.
///
/// On the bound: the passing path is not three orders of magnitude clear of it, as an earlier
/// version of this comment claimed. Measured margin is closer to 5x — sub-millisecond closes
/// are typical, but the distribution has a heavy tail, and under 8x CPU oversubscription
/// maxima of 27 / 61 / 67 / 101 ms have been observed across four runs, i.e. a badly loaded
/// machine can fail this test. The bound is deliberately *not* raised to paper that over: the
/// defect's floor at this width is about 150 ms, and every millimetre the bound moves toward
/// it costs discriminating power. 60 ms keeps a real gap on both sides; a failure here on a
/// saturated CI box is a false positive worth re-running rather than a reason to widen it.
class ReaderCloseLatencyTest {

    /// Wide enough that one poll interval per column is unmistakable against the bound, and
    /// still a projection a caller might plausibly ask for.
    ///
    /// Also wide enough for the row-reader case to reproduce at all. Batch size there is
    /// derived from the projection (see [dev.hardwood.internal.reader.BatchSizing]) rather
    /// than set by the caller, so a narrow projection gets batches big enough to swallow the
    /// whole file, every drain reaches EOF and finishes itself, and nothing is left parked for
    /// close() to release. The effect appears from about 8 columns upward; 16 is comfortably
    /// inside that.
    private static final int COLUMNS = 16;

    /// Many more rows than the one batch the test consumes. This is the state the defect needs:
    /// with data still to come, each column's drain thread has filled the two-deep ready queue
    /// and is parked — trying to publish a third batch on the column path, or waiting for a
    /// recycled holder on the row path. A file consumed entirely by the first batch does not
    /// reproduce it — the drain reaches EOF and exits on its own before close() is ever called.
    private static final int ROWS = 200_000;

    /// Forces many batches per column, so one batch is a small fraction of the file.
    /// Applies to the column path only; the row path has no caller-set batch size.
    private static final int BATCH_SIZE = 1_024;

    /// How many rows the row-reader case consumes before closing. Enough to have started the
    /// pipeline, far short of the file.
    private static final int ROWS_READ = 10;

    /// The timed queue operations in `BatchExchange` used a 10 ms window, and a sequential
    /// close waited out one per column. Anything at or above this is the old behaviour.
    private static final long POLL_INTERVAL_MILLIS = 10;

    /// Passes comfortably when no close waits out a window, and cannot pass when they all do:
    /// the old path needs about `(COLUMNS - 1) * 10 ms` = 150 ms. See the class comment for
    /// the actual margin and its tail.
    private static final long BOUND_MILLIS = 60;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void closeDoesNotScaleWithProjectionWidth() throws Exception {
        byte[] file = writeSyntheticFile();
        String[] columns = columnNames().toArray(new String[0]);

        try (Hardwood hardwood = Hardwood.create()) {
            // A first read pays the one-off costs — class loading, the executor's threads, the
            // footer parse — so they are not attributed to the close being measured.
            timeCloseAfterOneBatch(hardwood, file, columns);

            long elapsedNanos = timeCloseAfterOneBatch(hardwood, file, columns);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

            assertThat(elapsedMillis)
                    .as("close() of a %d-column projection; one %d ms poll interval per column "
                            + "would be about %d ms",
                            COLUMNS, POLL_INTERVAL_MILLIS, (COLUMNS - 1) * POLL_INTERVAL_MILLIS)
                    .isLessThan(BOUND_MILLIS);
        }
    }

    /// The same question asked of the filtered path, which tears down through
    /// [dev.hardwood.reader.FilterCoordinator] rather than through [ColumnReaders#close()]'s
    /// own loop, and so needs the fix applied in its own right.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void filteredCloseDoesNotScaleWithProjectionWidth() throws Exception {
        byte[] file = writeSyntheticFile();
        String[] columns = columnNames().toArray(new String[0]);

        try (Hardwood hardwood = Hardwood.create()) {
            timeFilteredCloseAfterOneBatch(hardwood, file, columns);

            long elapsedNanos = timeFilteredCloseAfterOneBatch(hardwood, file, columns);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

            assertThat(elapsedMillis)
                    .as("close() of a filtered %d-column projection", COLUMNS)
                    .isLessThan(BOUND_MILLIS);
        }
    }

    /// And of the row-reader path, which is the *other* exchange mode.
    ///
    /// [RowReader] recycles its batch holders, so its drain parks in `takeBatch()` waiting for
    /// the consumer to hand a holder back, not in `publish()` waiting for ready-queue room.
    /// Releasing only the publish side leaves this path paying a full poll interval per column,
    /// which is exactly the regression the two cases above cannot see.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rowReaderCloseDoesNotScaleWithProjectionWidth() throws Exception {
        byte[] file = writeSyntheticFile();
        String[] columns = columnNames().toArray(new String[0]);

        try (Hardwood hardwood = Hardwood.create()) {
            timeRowReaderCloseAfterFewRows(hardwood, file, columns);

            long elapsedNanos = timeRowReaderCloseAfterFewRows(hardwood, file, columns);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

            assertThat(elapsedMillis)
                    .as("RowReader.close() over a %d-column projection after reading %d rows; "
                            + "one %d ms poll interval per column would be about %d ms",
                            COLUMNS, ROWS_READ, POLL_INTERVAL_MILLIS,
                            (COLUMNS - 1) * POLL_INTERVAL_MILLIS)
                    .isLessThan(BOUND_MILLIS);
        }
    }

    /// Opens a row reader over the full projection, reads a handful of rows, and returns how
    /// long the close took.
    ///
    /// The rows matter for the same reason the single batch does on the column path: they are
    /// what gets the drain threads running and their exchanges occupied. Reading only a
    /// handful — rather than draining — is what leaves them parked mid-file when close()
    /// arrives.
    private static long timeRowReaderCloseAfterFewRows(
            Hardwood hardwood, byte[] file, String[] columns) throws Exception {
        try (ParquetFileReader parquet = hardwood.open(InputFile.of(ByteBuffer.wrap(file)))) {
            RowReader rows = parquet.buildRowReader()
                    .projection(ColumnProjection.columns(columns))
                    .build();
            for (int i = 0; i < ROWS_READ; i++) {
                assertThat(rows.hasNext()).as("row %d of %d", i, ROWS_READ).isTrue();
                rows.next();
            }

            long start = System.nanoTime();
            rows.close();
            return System.nanoTime() - start;
        }
    }

    /// A predicate that keeps essentially every row, so the readers still have work outstanding
    /// when close() arrives — the same precondition the unfiltered case relies on.
    private static long timeFilteredCloseAfterOneBatch(
            Hardwood hardwood, byte[] file, String[] columns) throws Exception {
        try (ParquetFileReader parquet = hardwood.open(InputFile.of(ByteBuffer.wrap(file)))) {
            ColumnReaders readers = parquet.buildColumnReaders(ColumnProjection.columns(columns))
                    .filter(FilterPredicate.gt("c0", -1L))
                    .batchSize(BATCH_SIZE)
                    .build();
            assertThat(readers.nextBatch()).as("first filtered batch").isTrue();

            long start = System.nanoTime();
            readers.close();
            return System.nanoTime() - start;
        }
    }

    /// Opens the projection, consumes exactly one batch, and returns how long the close took.
    ///
    /// The batch matters: it is what gets the drain threads running and the ready queues
    /// occupied, which is the state a close has to unwind. Closing a reader that never read
    /// does not reach the path this is about.
    private static long timeCloseAfterOneBatch(Hardwood hardwood, byte[] file, String[] columns)
            throws Exception {
        try (ParquetFileReader parquet = hardwood.open(InputFile.of(ByteBuffer.wrap(file)))) {
            ColumnReaders readers = parquet.buildColumnReaders(ColumnProjection.columns(columns))
                    .batchSize(BATCH_SIZE)
                    .build();
            for (String column : columns) {
                ColumnReader reader = readers.getColumnReader(column);
                assertThat(reader.nextBatch()).as("first batch of %s", column).isTrue();
            }

            long start = System.nanoTime();
            readers.close();
            return System.nanoTime() - start;
        }
    }

    private static List<String> columnNames() {
        List<String> names = new ArrayList<>(COLUMNS);
        for (int i = 0; i < COLUMNS; i++) {
            names.add("c" + i);
        }
        return names;
    }

    /// A synthetic file of `COLUMNS` required `INT64` columns and `ROWS` rows. Nothing about
    /// the values matters, only that every projected column has a chunk to decode.
    private static byte[] writeSyntheticFile() throws Exception {
        FileSchema.Builder schema = FileSchema.builder("close_scaling");
        for (String name : columnNames()) {
            schema.addColumn(name, PhysicalType.INT64, RepetitionType.REQUIRED);
        }

        long[] values = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = i;
        }

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema.build())) {
            writer.writeBatch(batch -> {
                for (String name : columnNames()) {
                    batch.longs(name, values);
                }
            });
        }
        return out.toByteArray();
    }
}
