/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// What reading a small file to the end costs, as a function of how many columns are read.
///
/// This is the read-path counterpart of [ReaderCloseLatencyTest], and the same defect one step
/// earlier. That test covers teardown: a drain blocked inside its exchange could not see the
/// `finished` flag until its own timed queue operation expired, so `close()` inherited the wait
/// once per column. This covers the read itself: the *consumer* waiting inside
/// [dev.hardwood.internal.reader.BatchExchange#poll()] cannot see the flag either, so the last
/// `nextBatch()` — the one that reports the end — waited out the same window.
///
/// The two are mirrored. Teardown strands a producer whose consumer has stopped consuming; this
/// strands a consumer whose producer has stopped producing. Teardown was also the abnormal path,
/// reached only by abandoning a read with data outstanding. This is the ordinary one: every
/// reader that reads to completion asks once more than there is data for, and that ask is the
/// one that waited.
///
/// The property under test is that a full read costs the same whether one column is read or
/// sixteen. Each column is opened through its own [ParquetFileReader#columnReader(String)] and
/// drained before the next is opened, because that is what makes the delays add rather than
/// overlap — a projection read through one [dev.hardwood.reader.ColumnReaders] runs its columns
/// concurrently, so the later ones find their streams already ended and pay nothing.
///
/// On the bound: see [ReaderCloseLatencyTest]'s note, which applies unchanged. Sub-millisecond
/// reads are typical here, the distribution has a heavy tail under CPU oversubscription, and the
/// bound is set to keep a real gap on both sides rather than to sit just under the regression.
class ReaderEofLatencyTest {

    /// Wide enough that one stall per column is unmistakable against the bound.
    private static final int COLUMNS = 16;

    /// Few enough rows that a column is one batch, which is the state this is about: the
    /// consumer takes that batch, asks again, and arrives at the exchange before the drain has
    /// finished itself. A file large enough to need many batches gives the drain time to end the
    /// stream while the consumer is still working through them, and the last ask finds the flag
    /// already set — the same read, none of the wait.
    private static final int ROWS = 1_000;

    /// What a read that waits out the poll interval once per column settles at. The exchange
    /// polls on a 10 ms window, so that is what one column costs.
    private static final Duration PER_COLUMN_STALL_DURATION = Duration.ofMillis(10L);

    /// Passes comfortably when no read stalls, and cannot pass when they all do: stalling costs
    /// about `COLUMNS * 10 ms` = 160 ms, against measurements of a few milliseconds for the
    /// whole loop when the end of the stream is signalled.
    private static final Duration BOUND = Duration.ofMillis(60L);

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void readingToTheEndDoesNotScaleWithColumnCount() throws Exception {
        byte[] file = writeSyntheticFile();

        try (Hardwood hardwood = Hardwood.create()) {
            // A first pass pays the one-off costs — class loading, the executor's threads, the
            // footer parse — so they are not attributed to the reads being measured.
            timeFullReadOfEveryColumn(hardwood, file);

            Duration elapsed = timeFullReadOfEveryColumn(hardwood, file);

            assertThat(elapsed)
                    .as("reading %d columns of %d rows to the end, one column at a time; "
                            + "a %d ms stall per column would be about %d ms",
                            COLUMNS, ROWS, PER_COLUMN_STALL_DURATION.toMillis(),
                            stallAcrossColumns().toMillis())
                    .isLessThan(BOUND);
        }
    }

    /// Opens each column in turn, drains it to the end, and returns how long all of them took.
    ///
    /// The `nextBatch()` that returns `false` is what is being measured, so every column is read
    /// past its last batch rather than stopped at it.
    private static Duration timeFullReadOfEveryColumn(Hardwood hardwood, byte[] file)
            throws Exception {

        try (ParquetFileReader parquet = hardwood.open(InputFile.of(ByteBuffer.wrap(file)))) {
            long start = System.nanoTime();
            for (String column : columnNames()) {
                int batches = 0;
                try (ColumnReader reader = parquet.columnReader(column)) {
                    while (reader.nextBatch()) {
                        batches++;
                    }
                }
                assertThat(batches).as("batches read from %s", column).isPositive();
            }
            return Duration.ofNanos(System.nanoTime() - start);
        }
    }

    /// What a read that stalls once per column would cost in total, for the assertion message.
    /// Every column is opened and drained in turn, so none of the stalls overlap.
    private static Duration stallAcrossColumns() {
        return PER_COLUMN_STALL_DURATION.multipliedBy(COLUMNS);
    }

    private static List<String> columnNames() {
        List<String> names = new ArrayList<>(COLUMNS);
        for (int i = 0; i < COLUMNS; i++) {
            names.add("c" + i);
        }
        return names;
    }

    /// A synthetic file of `COLUMNS` required `INT64` columns and `ROWS` rows. Nothing about the
    /// values matters, only that every column has a chunk to decode.
    private static byte[] writeSyntheticFile() throws Exception {
        FileSchema.Builder schema = FileSchema.builder("eof_scaling");
        for (String name : columnNames()) {
            schema.addColumn(name, PhysicalType.INT64, RepetitionType.REQUIRED);
        }

        long[] values = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = i;
        }

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema.build())) {
            writer.columnWriter().writeBatch(batch -> {
                for (String name : columnNames()) {
                    batch.longs(name, values);
                }
            });
        }
        return out.toByteArray();
    }
}
