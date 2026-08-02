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
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;

/// Filtered reads over a dictionary-encoded low-cardinality column, measuring both sides of
/// dictionary predicate push-down (see `_designs/DICTIONARY_PUSHDOWN.md`).
///
/// Push-down reads a surviving row group's dictionary page before any data is read. That is a win
/// when the dictionary proves the value absent — the whole row group is skipped, statistics having
/// been unable to decide — and a cost when it does not, since the read then proceeds as it would
/// have anyway. This benchmark bounds both.
///
/// Fixtures: `dict_pushdown.parquet` (10M rows, 10 row groups, 512 distinct `cat_<even>` values)
/// and `dict_pushdown_no_stats.parquet` — run
/// `python performance-testing/generate_dict_pushdown_data.py` first.
///
/// The `probe` parameter selects a predicate and a fixture. Each filtered probe comes as a pair —
/// the same predicate with push-down eligible and ineligible — because a `_no_dictionary` variant
/// is the only honest control for what push-down changed:
///
/// - `absent` — `cat_1`, lexicographically inside every row group's min/max but in no dictionary.
///   Statistics cannot drop it; the dictionary drops every row group, so only dictionary pages are
///   read and no data page is touched. This is the win, and it is the number the feature exists for.
/// - `absent_no_dictionary` — the same `cat_1` probe with push-down ineligible, so nothing prunes
///   and the whole file is filtered to find the zero rows that match. The control for `absent`, and
///   the work `absent` avoids.
/// - `present` — `cat_2`, in every dictionary, so every row group survives and the dictionary read
///   pruned nothing.
/// - `present_no_dictionary` — the same `cat_2` probe with push-down ineligible. The control for
///   `present`.
///
/// A `_no_dictionary` probe reads `dict_pushdown_no_stats.parquet`, which is
/// `dict_pushdown.parquet` with `category`'s `encoding_stats` dropped from the footer. Without that
/// field a reader cannot establish that the dictionary covers the whole chunk, so push-down is
/// ineligible. The two files are otherwise byte-identical — same pages, same offsets, same
/// statistics, same dictionary pages, all still read the same way — so a pair differs in push-down
/// eligibility and nothing else.
///
/// The pairs read as:
///
/// - `absent_no_dictionary` minus `absent` is what push-down saves: a whole filtered scan against a
///   dictionary probe.
/// - `present` minus `present_no_dictionary` is what push-down costs when it prunes nothing.
///
/// `absent` bounds the cost from the other direction: it is a *complete* run — open, footer, then
/// locate, decompress, parse and probe the dictionary of all ten row groups, none of which
/// statistics or a bloom filter drop first — so its whole score is an upper bound on what push-down
/// can cost when it prunes nothing.
///
/// That cost is one request per filtered column per surviving row group; on a memory-mapped local
/// file it is a page-cache hit, so only a remote backend pays it as a round trip.
/// `DictionaryPushDownIoTest` in `hardwood-core` pins that request *count* deterministically, which
/// is the assertable form of the same fact.
///
/// Reference numbers (10M rows, 10 row groups, single fork, JDK 21, Apple M-series, page cache
/// warm) — for spotting movement, not for comparing machines:
///
/// ```
/// probe                    avgt ms/op
/// absent                         0.16
/// absent_no_dictionary         161.3
/// present                      172.6
/// present_no_dictionary        171.5
/// ```
///
/// `absent` against `absent_no_dictionary` is the feature in one line: 0.16 ms versus 162 ms, a
/// scan avoided in full. `present` against `present_no_dictionary` is the price of being wrong —
/// the two sit within each other's run-to-run spread, so on this fixture push-down costs no more
/// than the ~0.16 ms `absent` bounds it at.
///
/// Run:
/// ```shell
/// ./mvnw -pl core install -DskipTests
/// ./mvnw -pl performance-testing/micro-benchmarks package -Pperformance-test
/// java -jar performance-testing/micro-benchmarks/target/benchmarks.jar DictionaryPushDownBenchmark -p dataDir=performance-testing/test-data-setup/target/benchmark-data
/// ```
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = { "-Xms1g", "-Xmx1g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class DictionaryPushDownBenchmark {

    @Param({})
    private String dataDir;

    @Param({ "dict_pushdown.parquet" })
    private String fileName;

    /// Same file with `category`'s `encoding_stats` dropped from the footer, which makes the chunk
    /// ineligible for push-down. Pages, offsets, statistics and the dictionary pages themselves are
    /// byte-identical to `fileName`.
    @Param({ "dict_pushdown_no_stats.parquet" })
    private String noStatsFileName;

    @Param({ "absent", "absent_no_dictionary", "present", "present_no_dictionary" })
    private String probe;

    private Path path;
    private FilterPredicate filter;

    @Setup
    public void setup() {
        String probedFile = probe.endsWith("_no_dictionary") ? noStatsFileName : fileName;
        path = Path.of(dataDir).resolve(probedFile).toAbsolutePath().normalize();
        if (!path.toFile().exists()) {
            throw new IllegalStateException("Parquet file not found: " + path
                    + ". Run 'python performance-testing/generate_dict_pushdown_data.py' first.");
        }
        filter = switch (probe) {
            // Odd suffix: within every row group's min/max, in no dictionary.
            case "absent", "absent_no_dictionary" -> FilterPredicate.eq("category", "cat_1");
            // Even suffix: in every dictionary, so no row group is dropped.
            case "present", "present_no_dictionary" -> FilterPredicate.eq("category", "cat_2");
            default -> throw new IllegalStateException("Unknown probe: " + probe);
        };
    }

    /// Filtered read of `payload` alone. `category` is decoded to evaluate the predicate but is not
    /// projected, which is the shape a real filtered scan takes.
    @Benchmark
    public void columnReaderPayload(Blackhole blackhole) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(path));
             ColumnReader values = reader.buildColumnReader("payload").filter(filter).build()) {
            long sum = 0;
            while (values.nextBatch()) {
                long[] batch = values.getLongs();
                int count = values.getRecordCount();
                for (int i = 0; i < count; i++) {
                    sum += batch[i];
                }
            }
            blackhole.consume(sum);
        }
    }
}
