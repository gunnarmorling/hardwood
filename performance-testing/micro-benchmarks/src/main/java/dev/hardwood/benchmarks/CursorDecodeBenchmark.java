/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.ReaderConfig;

/// Single-core JMH A/B of [ColumnReader] with the fused cursor decode path on
/// vs off (`hardwood.cursor-decode`).
///
/// Matches the #726 measurement guidance: multi-core end-to-end wall-clock is
/// bound by decompression and pipeline coordination, so this benchmark uses
/// [HardwoodContext#create(int) HardwoodContext.create(1)], `@Threads(1)`, and
/// an **UNCOMPRESSED** dictionary-encoded corpus so the timed work is decode +
/// assembly of definition levels and dictionary indices.
///
/// Scenarios (corpus from `generate_cursor_data.py`) map to the task cases:
///
/// | `scenario`     | File                         | What it exercises |
/// |----------------|------------------------------|-------------------|
/// | `all_present`  | `cursor_{type}_all_present`  | All-present def-level RLE (generalizes #721) |
/// | `null_heavy`   | `cursor_{type}_null_heavy`   | All-null RLE runs + present stretches on null-heavy pages |
/// | `low_card`     | `cursor_{type}_low_card`     | RLE-rich dictionary index stream (low cardinality) |
///
/// `requiredFloor` reads a required column (no def levels, dict size 256,
/// 8-bit indices). `requiredLowCard` reads a required column with dict size 4
/// and long index runs to measure `Arrays.fill` exploitation. `requiredHighCard`
/// reads a required column with dict size 4096 (12-bit indices) to stress heavy
/// bit-packing on the fused path.
///
/// Generate the corpus first:
/// ```
/// python performance-testing/generate_cursor_data.py <dataDir>
/// ```
/// then run with `-p dataDir=<dataDir>`.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Threads(1)
@Fork(value = 2, jvmArgs = { "-Xms2g", "-Xmx2g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CursorDecodeBenchmark {

    private static final String COLUMN = "value";

    @Param({})
    private String dataDir;

    @Param({ "int32", "int64", "float", "double" })
    private String type;

    /// Task scenarios: all-present def RLE, null-heavy def runs, low-card index RLE.
    @Param({ "all_present", "null_heavy", "low_card" })
    private String scenario;

    @Param({ "true", "false" })
    private boolean cursorDecode;

    private Path scenarioPath;
    private Path requiredPath;
    private Path requiredLowCardPath;
    private Path requiredHighCardPath;
    private HardwoodContext context;
    private ReaderConfig config;

    @Setup(Level.Trial)
    public void setup() {
        scenarioPath = resolve("cursor_" + type + "_" + scenario + ".parquet");
        requiredPath = resolve("cursor_" + type + "_required.parquet");
        requiredLowCardPath = resolve("cursor_" + type + "_required_low_card.parquet");
        requiredHighCardPath = resolve("cursor_" + type + "_required_high_card.parquet");
        context = HardwoodContext.create(1);
        config = ReaderConfig.builder()
                .option("hardwood.cursor-decode", String.valueOf(cursorDecode))
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        context.close();
    }

    /// Optional dictionary-encoded column for the selected scenario, cursor path
    /// on or off via `@Param`.
    @Benchmark
    public double decode() throws IOException {
        return sumColumn(scenarioPath);
    }

    /// Required column (no def levels) — decode floor (dict size 256, 8-bit
    /// indices). Realistic cardinality for production columns; mostly bit-packed
    /// index stream.
    @Benchmark
    public double requiredFloor() throws IOException {
        return sumColumn(requiredPath);
    }

    /// Required low-cardinality column (dict size 4, long index RLE runs).
    /// Measures the index-only fused path's `Arrays.fill` exploitation on
    /// required columns.
    @Benchmark
    public double requiredLowCard() throws IOException {
        return sumColumn(requiredLowCardPath);
    }

    /// Required high-cardinality column (dict size 4096, 12-bit indices).
    /// Stresses the fused path's bit-unpack decode side with wide index values
    /// and no RLE benefit.
    @Benchmark
    public double requiredHighCard() throws IOException {
        return sumColumn(requiredHighCardPath);
    }

    private double sumColumn(Path path) throws IOException {
        double sum = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(path), context, config);
                ColumnReader col = reader.columnReader(COLUMN)) {
            while (col.nextBatch()) {
                int n = col.getValueCount();
                sum += switch (type) {
                    case "int32" -> sumInts(col.getInts(), n);
                    case "int64" -> sumLongs(col.getLongs(), n);
                    case "float" -> sumFloats(col.getFloats(), n);
                    case "double" -> sumDoubles(col.getDoubles(), n);
                    default -> throw new IllegalStateException("Unknown type: " + type);
                };
            }
        }
        return sum;
    }

    private static double sumInts(int[] values, int n) {
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += values[i];
        }
        return s;
    }

    private static double sumLongs(long[] values, int n) {
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += values[i];
        }
        return s;
    }

    private static double sumFloats(float[] values, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            s += values[i];
        }
        return s;
    }

    private static double sumDoubles(double[] values, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            s += values[i];
        }
        return s;
    }

    private Path resolve(String fileName) {
        Path path = Path.of(dataDir).resolve(fileName).toAbsolutePath().normalize();
        if (!path.toFile().exists()) {
            throw new IllegalStateException("Benchmark file not found: " + path
                    + ". Run 'python performance-testing/generate_cursor_data.py " + dataDir + "' first.");
        }
        return path;
    }
}
