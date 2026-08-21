/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// What the writer holds while a row group is open, against the target that is supposed to bound
/// it.
///
/// `rowGroupTargetBytes` is the writer's only memory bound: a row group's column chunks have to
/// be encoded before any of their metadata is known, so the values stay resident until the group
/// flushes. What stays resident is more than the values themselves — a chunk being analyzed for
/// a dictionary holds the value store, an `int` index per value, and the dictionary's own array
/// and lookup table, none of which the target counts — so the true peak is a multiple of it.
///
/// That multiple is what this pins. It is deliberately loose: the number is a tripwire for a
/// structure being retained that was not before, not a specification of the writer's footprint,
/// and a bound tight enough to be exact would fail on GC timing rather than on a regression.
class WriterRetentionTest {

    /// Small enough to keep the test quick and to fit any CI heap, large enough that the measured
    /// retention is well above the noise of a settled JVM.
    private static final long ROW_GROUP_TARGET = 8L << 20;

    /// How many times the target the writer may retain before this fails.
    ///
    /// The measurement was 1.25× when this was written — about 10 MiB held against an 8 MiB
    /// target — so three leaves room for GC timing and for the allocator's own slack while still
    /// failing on a regression that doubles what a chunk keeps.
    private static final int ALLOWED_MULTIPLE = 3;

    /// Enough distinct values to be worth a dictionary and few enough to keep the dictionary
    /// itself negligible, so what is measured is the per-value cost rather than the table.
    private static final int DISTINCT = 1_000;

    private static final int VALUES_PER_BATCH = 8_192;

    @Test
    void anOpenRowGroupRetainsABoundedMultipleOfItsTarget() throws Exception {
        long ceiling = ALLOWED_MULTIPLE * ROW_GROUP_TARGET;
        assumeTrue(Runtime.getRuntime().maxMemory() > 2 * ceiling,
                "needs a heap that can hold the measurement with room to spare");

        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder()
                .rowGroupTargetBytes(ROW_GROUP_TARGET)
                .build();

        // One batch short of the target, so nothing has flushed and what is resident at the end
        // is the peak rather than whatever survived a flush.
        int batches = (int) (ROW_GROUP_TARGET / Integer.BYTES / VALUES_PER_BATCH) - 1;
        int[] values = new int[VALUES_PER_BATCH];

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        long retained;
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            long baseline = usedHeap();
            for (int b = 0; b < batches; b++) {
                for (int i = 0; i < VALUES_PER_BATCH; i++) {
                    values[i] = (b * VALUES_PER_BATCH + i) % DISTINCT;
                }
                writer.writeBatch(batch -> batch.ints("v", values));
            }
            retained = usedHeap() - baseline;
        }

        try (ParquetFileReader reader = ParquetFileReader.open(
                InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().rowGroups())
                    .as("the run stayed inside one row group, so the sample was its peak")
                    .hasSize(1);
        }

        assertThat(retained)
                .as("bytes retained by an open row group against a %d MiB target", ROW_GROUP_TARGET >> 20)
                .isLessThan(ceiling);
    }

    /// Live bytes after a collection. Three passes because the first frees the garbage of the
    /// last batch and the ones after it settle what that freeing itself produced; without them
    /// the reading carries whatever the allocator had not yet reclaimed.
    private static long usedHeap() {
        for (int pass = 0; pass < 3; pass++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
