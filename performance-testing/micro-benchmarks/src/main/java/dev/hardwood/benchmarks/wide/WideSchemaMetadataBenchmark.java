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
import java.util.List;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.benchmarks.BenchmarkData;
import dev.hardwood.internal.thrift.FileMetaDataReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.SchemaElement;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;

/// Footer metadata decode as a function of schema width: files of 10 to 100,000 `FLOAT64`
/// columns over [WideSchemaFileGenerator#ROW_GROUPS] row groups, so the footer holds
/// `columns × 10` `ColumnChunk` structures. This is the shape of a machine-learning feature
/// table, and the cost is paid in full on open — before a single value is read, and
/// regardless of how few columns the read then projects.
///
/// - `decodeFooter` — Thrift decode of the footer bytes into a [FileMetaData], with the
///   bytes already in memory. No I/O, no schema building: the parser alone.
/// - `decodeFooterMapped` — the same decode over a memory-mapped, and so direct, buffer. The
///   decoder reads strings and compares cached column paths straight out of the backing array
///   where it has one, and copies byte by byte where it does not; this arm is what the second
///   case costs, and it is the buffer a local file read produces.
/// - `decodeFooterMappedCopied` — the mapped footer copied onto the heap and then decoded, so
///   the decode runs on an array. Against `decodeFooterMapped` this is what buying the array
///   for a footer read once and dropped is worth: it is worth nothing. The two decodes cost
///   the same to within a few percent at every width — a direct buffer's `get` is a bare
///   memory load where a heap buffer's is an array access the JIT bounds-checks, which offsets
///   the array-only paths — and the copy adds the footer's own size in allocation on top. The
///   reader therefore decodes off whatever buffer the file hands it.
/// - `buildSchema` — [FileSchema] construction from already-decoded schema elements, the
///   other half of what `open` does beyond reading bytes.
/// - `openFile` — what a caller actually pays for [ParquetFileReader#open]: memory-map the
///   file, read the footer off it, decode it, and build the schema.
///
/// [WideSchemaMetadataParquetJavaBenchmark] runs the same three steps through parquet-java over
/// the identical fixtures; run both (the class filter `WideSchemaMetadata` matches each) for the
/// comparison. Note what the two decodes produce: Hardwood's yields the metadata its reader uses
/// directly, while parquet-java's decode yields generated Thrift structures a caller still has
/// to convert, which is why its conversion step is measured too.
///
/// Divide a result by the column count for the per-column cost, which is what makes the
/// numbers comparable across widths and against other implementations.
///
/// The fixtures are generated on demand from `@Setup` (also runnable up front, see
/// [WideSchemaFileGenerator]) and cached in the benchmark data directory.
///
/// The fork's `-Xmx` is sized for the widest fixture, whose million column chunks decode into
/// roughly 270 MB of live metadata while allocating some 300 MB per decode. A single decode
/// fits in a fraction of that heap, but on a tight one the repeated decodes of a JMH iteration
/// turn the result into a measurement of the collector rather than the parser. Run it with
/// `-prof gc` when changing the allocation behaviour of the parser.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = { "-Xms2g", "-Xmx8g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WideSchemaMetadataBenchmark {

    @Param({ "10", "100", "1000", "10000", "100000" })
    private int columns;

    private Path path;
    /// The footer bytes, read once so `decodeFooter` measures only the parser.
    private ByteBuffer footer;
    /// The same bytes as a mapped, direct buffer, for `decodeFooterMapped`.
    private ByteBuffer mappedFooter;
    private List<SchemaElement> schemaElements;
    private HardwoodContext context;

    @Setup
    public void setup() throws IOException {
        Path dir = Path.of(BenchmarkData.dir());
        WideSchemaFileGenerator.ensureFile(dir, columns);
        path = WideSchemaFileGenerator.file(dir, columns).toAbsolutePath().normalize();
        footer = ByteBuffer.wrap(Footers.read(path));
        mappedFooter = Footers.map(path);
        schemaElements = decode().schema();
        context = HardwoodContext.create();
        System.out.printf("%,d columns: %,d footer bytes (%,.1f bytes/column)%n",
                columns, footer.remaining(), (double) footer.remaining() / columns);
    }

    @TearDown
    public void tearDown() {
        context.close();
    }

    @Benchmark
    public void decodeFooter(Blackhole blackhole) throws IOException {
        blackhole.consume(decode());
    }

    @Benchmark
    public void decodeFooterMapped(Blackhole blackhole) throws IOException {
        blackhole.consume(FileMetaDataReader.read(new ThriftCompactReader(mappedFooter.duplicate())));
    }

    @Benchmark
    public void decodeFooterMappedCopied(Blackhole blackhole) throws IOException {
        ByteBuffer copy = ByteBuffer.allocate(mappedFooter.remaining());
        copy.put(mappedFooter.duplicate());
        blackhole.consume(FileMetaDataReader.read(new ThriftCompactReader(copy.flip())));
    }

    @Benchmark
    public void buildSchema(Blackhole blackhole) {
        blackhole.consume(FileSchema.fromSchemaElements(schemaElements));
    }

    @Benchmark
    public void openFile(Blackhole blackhole) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(path), context)) {
            blackhole.consume(reader.getFileSchema());
        }
    }

    private FileMetaData decode() throws IOException {
        // A duplicate so each invocation starts at the footer's first byte.
        return FileMetaDataReader.read(new ThriftCompactReader(footer.duplicate()));
    }

}
