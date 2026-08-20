/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.wide;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import dev.hardwood.benchmarks.BenchmarkData;
import dev.hardwood.internal.thrift.FileMetaDataReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;

/// What a lazily-materializing footer decode would cost, measured as the floor it converges to.
///
/// A lazy design cannot avoid walking the footer — Thrift carries no index, so the byte range of
/// each column chunk is only found by parsing forward. What it *can* avoid is building the
/// records: on a file of 100,000 columns and ten row groups, a projected read of three columns
/// materializes a million `ColumnMetaData` (and their statistics, paths and encoding lists) to
/// use thirty of them.
///
/// - `fullDecode` — today's decode, every structure materialized. The baseline.
/// - `skipToChunkIndex` — the structural walk a lazy decode would do eagerly: descend into the
///   row groups, record each column chunk's `(offset, length)` in the footer, and skip its body.
///   Materializing a chunk later is then a decode of that byte range alone.
/// - `skipRowGroups` — schema only, the row-group list skipped whole. The absolute floor, and
///   what a schema-only caller (`hardwood schema`, a projection planner reading names) could pay.
///
/// The gap between `fullDecode` and `skipToChunkIndex` is the prize a lazy design plays for; the
/// gap between `skipToChunkIndex` and `skipRowGroups` is what the chunk index itself costs.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgsAppend = { "-Xms2g", "-Xmx8g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 2)
public class WideSchemaSkipFloorBenchmark {

    /// `FileMetaData.row_groups`.
    private static final int FIELD_ROW_GROUPS = 4;
    /// `RowGroup.columns`.
    private static final int FIELD_COLUMNS = 1;

    @Param({ "1000", "10000", "100000" })
    private int columns;

    private ByteBuffer footer;

    @Setup
    public void setup() throws IOException {
        Path dir = Path.of(BenchmarkData.dir());
        WideSchemaFileGenerator.ensureFile(dir, columns);
        footer = ByteBuffer.wrap(Footers.read(WideSchemaFileGenerator.file(dir, columns)));
    }

    @Benchmark
    public void fullDecode(Blackhole blackhole) throws IOException {
        blackhole.consume(FileMetaDataReader.read(new ThriftCompactReader(footer.duplicate())));
    }

    @Benchmark
    public void skipToChunkIndex(Blackhole blackhole) throws IOException {
        ThriftCompactReader reader = new ThriftCompactReader(footer.duplicate());
        // The index a lazy FileMetaData would hold: where each chunk's bytes start and end.
        int[] chunkOffsets = new int[columns * WideSchemaFileGenerator.ROW_GROUPS + 1];
        int chunkCount = 0;
        int header;
        while ((header = reader.readFieldHeader()) != ThriftCompactReader.STOP_FIELD) {
            if (ThriftCompactReader.fieldId(header) != FIELD_ROW_GROUPS) {
                reader.skipField(ThriftCompactReader.fieldType(header));
                continue;
            }
            long rowGroups = reader.readListHeader();
            for (int g = 0, n = ThriftCompactReader.listSize(rowGroups); g < n; g++) {
                chunkCount = skipRowGroup(reader, chunkOffsets, chunkCount);
            }
        }
        blackhole.consume(chunkOffsets);
        blackhole.consume(chunkCount);
    }

    /// Walks one row group, recording where each of its column chunks begins and skipping the
    /// chunk itself.
    private static int skipRowGroup(ThriftCompactReader reader, int[] chunkOffsets, int chunkCount)
            throws IOException {
        int count = chunkCount;
        int header;
        while ((header = reader.readFieldHeader()) != ThriftCompactReader.STOP_FIELD) {
            if (ThriftCompactReader.fieldId(header) != FIELD_COLUMNS) {
                reader.skipField(ThriftCompactReader.fieldType(header));
                continue;
            }
            long chunks = reader.readListHeader();
            for (int c = 0, n = ThriftCompactReader.listSize(chunks); c < n; c++) {
                chunkOffsets[count++] = reader.getBytesRead();
                reader.skipStruct();
            }
        }
        return count;
    }

    @Benchmark
    public void skipRowGroups(Blackhole blackhole) throws IOException {
        ThriftCompactReader reader = new ThriftCompactReader(footer.duplicate());
        int fields = 0;
        int header;
        while ((header = reader.readFieldHeader()) != ThriftCompactReader.STOP_FIELD) {
            reader.skipField(ThriftCompactReader.fieldType(header));
            fields++;
        }
        blackhole.consume(fields);
    }
}
