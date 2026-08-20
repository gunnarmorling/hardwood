/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
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

import dev.hardwood.InputFile;
import dev.hardwood.OutputFile;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.RowWriter;
import dev.hardwood.writer.WriterConfig;

/// Encodes a flat, taxi-shaped fixture through the three write APIs, the write-side
/// counterpart of the read path's `FlatPerformanceTest`.
///
/// | Contender | API |
/// |-----------|-----|
/// | `hardwoodColumnar` | [ParquetFileWriter#writeBatch], 1024-row batches |
/// | `hardwoodRow` | [ParquetFileWriter#rowWriter()] → [RowWriter#writeRow] |
/// | `parquetJavaGroup` | parquet-java's [ExampleParquetWriter] over `SimpleGroup` |
///
/// **parquet-java has no columnar write API** — its `WriteSupport` is record-at-a-time by
/// construction — so the comparison is really the two record-shaped APIs head to head, with
/// Hardwood's columnar API as the ceiling neither row API can beat.
///
/// What each contender pays inside the measured region is the cost of its own API, which is
/// not the same cost in all three:
///
/// - `parquetJavaGroup` builds one `SimpleGroup` per record. That object is inherent to
///   parquet-java's design, so it belongs in the number, but the gap is not pure encoding
///   speed.
/// - `hardwoodRow` writes `pickup_ts` through [dev.hardwood.writer.StructBuilder#setTimestamp],
///   so the annotated-value conversion the other two do not perform is in its number. That is
///   what a caller holding records actually pays. The [Instant] objects themselves come from
///   the fixture, so their allocation is outside the measured region and only the conversion
///   and the pointer chase are inside it.
/// - parquet-java writes a column index and an offset index per column chunk, which Hardwood
///   does not produce yet, so its files carry a little metadata Hardwood's do not.
/// - The two Hardwood contenders write into `ByteBufferOutputFile` and parquet-java into
///   [MemoryOutputFile], which are not the same sink. Both accumulate into a
///   `ByteArrayOutputStream`; `ByteBufferOutputFile` takes a [ByteBuffer] and appends the
///   array behind it, so neither side copies the payload twice on the way to the buffer.
///
/// Everything a caller can match is matched: page target, row-group target, codec, dictionary
/// encoding, writer version, and page checksums. The dictionary page limit is parquet-java's
/// alone — Hardwood chooses a chunk's encoding by comparing sizes rather than by consulting a
/// limit, so there is nothing to match it to. The row-group target is an
/// explicit 16 MiB on both sides so a million rows produces a handful of row groups and the
/// flush path is exercised, rather than a single group at the 128 MiB default. **The size of
/// each produced file is reported from the trial setup**, because a contender that is faster
/// and writes a larger file has not won.
///
/// Both sides write to memory, so the number is encode throughput rather than the container's
/// I/O noise. Correctness is not asserted per invocation — the write path's round-trip,
/// equivalence and interop tests cover it — beyond the trial setup reading each produced file
/// back through Hardwood and checking its row count.
///
/// Run it with:
///
/// ```
/// ./mvnw -Pperformance-test -pl performance-testing/micro-benchmarks -am package -Dquick
/// java -jar performance-testing/micro-benchmarks/target/benchmarks.jar FlatWriteBenchmark -prof gc
/// ```
///
/// `-Dperf.rows` sets the record count (default one million, roughly 50 MB of source values);
/// `-Dperf.dir` writes to files in that directory instead of to memory, for the case where
/// end-to-end cost including the filesystem is the question.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
// jvmArgsAppend, not jvmArgs, as in every benchmark in this module: the latter replaces the
// inherited command line, which would drop the -Dperf.* properties the fork's setup reads.
@Fork(value = 2, jvmArgsAppend = { "-Xms2g", "-Xmx2g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FlatWriteBenchmark {

    /// Records written per invocation, `-Dperf.rows`.
    private static final int DEFAULT_ROWS = 1_000_000;

    /// Records per [ParquetFileWriter#writeBatch] call, the arrival unit of a columnar
    /// producer. It is also what [RowWriter] stages before submitting a batch, so both
    /// Hardwood contenders reach the encoder in batches of the same size.
    private static final int BATCH_ROWS = 1024;

    private static final int PAGE_TARGET_BYTES = 1 << 20;
    private static final long ROW_GROUP_TARGET_BYTES = 16L << 20;
    /// parquet-java's dictionary page limit. Hardwood has no counterpart: it decides a chunk's
    /// encoding by comparing sizes rather than by consulting a limit, so this is one setting the
    /// two sides cannot match.
    private static final int PARQUET_JAVA_DICTIONARY_PAGE_LIMIT_BYTES = 1 << 20;

    private static final String COLUMNAR_FILE = "hardwood-columnar.parquet";
    private static final String ROW_FILE = "hardwood-row.parquet";
    private static final String PARQUET_JAVA_FILE = "parquet-java-group.parquet";

    private static final String PARQUET_JAVA_SCHEMA = """
            message flat {
              required int64 id;
              required int64 pickup_ts (TIMESTAMP(MICROS,true));
              optional int32 passenger_count;
              required double fare;
              required binary payment_type (STRING);
              optional binary vendor (STRING);
            }
            """;

    /// The codec dimension, across what both writers produce. `BROTLI` is Hardwood-only —
    /// parquet-java resolves it through `org.apache.hadoop.io.compress.BrotliCodec`, which is
    /// not on its classpath — so including it would report one contender against nothing.
    @Param({ "UNCOMPRESSED", "LZ4_RAW", "SNAPPY", "ZSTD", "GZIP" })
    private String codec;

    private FlatWriteFixture fixture;
    private FileSchema hardwoodSchema;
    private WriterConfig writerConfig;
    private MessageType parquetJavaSchema;
    private Configuration hadoopConf;
    private CompressionCodecName parquetJavaCodec;

    /// Destination directory, or null when both sides write to memory.
    private Path dir;

    /// One contender's write, so the destination handling is written once for both Hardwood
    /// APIs rather than per benchmark method.
    @FunctionalInterface
    private interface HardwoodWrite {
        void writeTo(OutputFile out) throws IOException;
    }

    @Setup
    public void setUp() throws IOException {
        String configured = System.getProperty("perf.dir");
        dir = configured == null || configured.isBlank() ? null : Files.createDirectories(Path.of(configured));

        fixture = FlatWriteFixture.generate(configuredRows(), BATCH_ROWS);
        hardwoodSchema = hardwoodSchema();
        writerConfig = WriterConfig.builder()
                .pageTargetBytes(PAGE_TARGET_BYTES)
                .rowGroupTargetBytes(ROW_GROUP_TARGET_BYTES)
                .enableDictionary(true)
                .codec(CompressionCodec.valueOf(codec))
                .build();
        parquetJavaSchema = MessageTypeParser.parseMessageType(PARQUET_JAVA_SCHEMA);
        parquetJavaCodec = CompressionCodecName.valueOf(codec);
        hadoopConf = new Configuration();
        // Hadoop's LocalFileSystem writes a .crc sidecar beside every file it creates: a second
        // checksum pass over the output and a second file, neither of which Hardwood's
        // destination pays. RawLocalFileSystem writes the file alone, so -Dperf.dir measures the
        // same work on both sides.
        hadoopConf.setClass("fs.file.impl", RawLocalFileSystem.class, FileSystem.class);

        reportProducedFiles();
    }

    /// The record count from `-Dperf.rows`, rejecting a value this benchmark cannot use rather
    /// than falling back to the default and reporting a number for a row count nobody asked for.
    /// The property is shared with [BenchmarkData], which reads it as a `long`.
    private static int configuredRows() {
        String configured = System.getProperty("perf.rows");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ROWS;
        }
        try {
            return Math.toIntExact(Long.parseLong(configured.trim()));
        }
        catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(
                    "perf.rows must be an int this benchmark can hold but was '" + configured + "'", e);
        }
    }

    @Benchmark
    public long hardwoodColumnar() throws IOException {
        return writeHardwood(COLUMNAR_FILE, this::writeColumnar);
    }

    @Benchmark
    public long hardwoodRow() throws IOException {
        return writeHardwood(ROW_FILE, this::writeRows);
    }

    @Benchmark
    public long parquetJavaGroup() throws IOException {
        if (dir == null) {
            MemoryOutputFile out = new MemoryOutputFile();
            writeGroups(ExampleParquetWriter.builder(out));
            return out.size();
        }
        Path path = dir.resolve(PARQUET_JAVA_FILE);
        Files.deleteIfExists(path);
        writeGroups(ExampleParquetWriter.builder(new org.apache.hadoop.fs.Path(path.toUri())));
        return Files.size(path);
    }

    /// Writes the fixture through one of the Hardwood APIs, returning the size of the file
    /// produced.
    private long writeHardwood(String fileName, HardwoodWrite write) throws IOException {
        if (dir == null) {
            ByteBufferOutputFile out = new ByteBufferOutputFile();
            write.writeTo(out);
            return out.position();
        }
        Path path = dir.resolve(fileName);
        write.writeTo(OutputFile.of(path));
        return Files.size(path);
    }

    /// Hands each batch's column arrays to the writer as they are.
    private void writeColumnar(OutputFile out) throws IOException {
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, hardwoodSchema, writerConfig)) {
            for (int b = 0; b < fixture.batchCount(); b++) {
                int batch = b;
                writer.writeBatch(columns -> columns
                        .longs("id", fixture.id[batch])
                        .longs("pickup_ts", fixture.pickupMicros[batch])
                        .ints("passenger_count", fixture.passengerCount[batch], fixture.passengerCountNulls[batch])
                        .doubles("fare", fixture.fare[batch])
                        .bytes("payment_type", fixture.paymentTypeBytes[batch])
                        .bytes("vendor", fixture.vendorBytes[batch], fixture.vendorNulls[batch]));
            }
        }
    }

    /// Walks the same arrays record by record, writing each field by name.
    private void writeRows(OutputFile out) throws IOException {
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, hardwoodSchema, writerConfig)) {
            RowWriter rows = writer.rowWriter();
            for (int b = 0; b < fixture.batchCount(); b++) {
                long[] ids = fixture.id[b];
                Instant[] pickups = fixture.pickup[b];
                int[] passengers = fixture.passengerCount[b];
                boolean[] passengersNull = fixture.passengerCountNulls[b];
                double[] fares = fixture.fare[b];
                String[] payments = fixture.paymentType[b];
                String[] vendors = fixture.vendor[b];
                for (int r = 0; r < ids.length; r++) {
                    int row = r;
                    rows.writeRow(record -> {
                        record.setLong("id", ids[row])
                                .setTimestamp("pickup_ts", pickups[row])
                                .setDouble("fare", fares[row])
                                .setString("payment_type", payments[row])
                                .setString("vendor", vendors[row]);
                        if (passengersNull[row]) {
                            record.setNull("passenger_count");
                        }
                        else {
                            record.setInt("passenger_count", passengers[row]);
                        }
                    });
                }
            }
        }
    }

    /// Walks the same arrays record by record, constructing the `SimpleGroup` parquet-java's
    /// record-shaped API takes.
    private void writeGroups(ExampleParquetWriter.Builder builder) throws IOException {
        try (ParquetWriter<Group> writer = builder
                .withConf(hadoopConf)
                .withType(parquetJavaSchema)
                .withCompressionCodec(parquetJavaCodec)
                .withPageSize(PAGE_TARGET_BYTES)
                // Hardwood bounds a page by size alone, so parquet-java's 20k-row page cap is
                // lifted: with it in place the two would not be cutting pages on the same rule.
                .withPageRowCountLimit(Integer.MAX_VALUE)
                .withRowGroupSize(ROW_GROUP_TARGET_BYTES)
                .withDictionaryEncoding(true)
                .withDictionaryPageSize(PARQUET_JAVA_DICTIONARY_PAGE_LIMIT_BYTES)
                .withWriterVersion(WriterVersion.PARQUET_1_0)
                .withPageWriteChecksumEnabled(true)
                .withValidation(false)
                .build()) {
            SimpleGroupFactory groups = new SimpleGroupFactory(parquetJavaSchema);
            for (int b = 0; b < fixture.batchCount(); b++) {
                long[] ids = fixture.id[b];
                long[] pickups = fixture.pickupMicros[b];
                int[] passengers = fixture.passengerCount[b];
                boolean[] passengersNull = fixture.passengerCountNulls[b];
                double[] fares = fixture.fare[b];
                String[] payments = fixture.paymentType[b];
                String[] vendors = fixture.vendor[b];
                boolean[] vendorsNull = fixture.vendorNulls[b];
                for (int r = 0; r < ids.length; r++) {
                    Group group = groups.newGroup()
                            .append("id", ids[r])
                            .append("pickup_ts", pickups[r])
                            .append("fare", fares[r])
                            .append("payment_type", payments[r]);
                    if (!passengersNull[r]) {
                        group.append("passenger_count", passengers[r]);
                    }
                    if (!vendorsNull[r]) {
                        group.append("vendor", vendors[r]);
                    }
                    writer.write(group);
                }
            }
        }
    }

    private FileSchema hardwoodSchema() {
        return FileSchema.builder("flat")
                .addColumn("id", PhysicalType.INT64, RepetitionType.REQUIRED)
                .addColumn("pickup_ts", PhysicalType.INT64, RepetitionType.REQUIRED,
                        new LogicalType.TimestampType(true, LogicalType.TimeUnit.MICROS))
                .addColumn("passenger_count", PhysicalType.INT32, RepetitionType.OPTIONAL)
                .addColumn("fare", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
                .addColumn("payment_type", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                        new LogicalType.StringType())
                .addColumn("vendor", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL,
                        new LogicalType.StringType())
                .build();
    }

    /// Writes the fixture once through each contender to memory, checks that each produced
    /// file holds the records the fixture has, and prints the three sizes so the times are
    /// never read without them.
    private void reportProducedFiles() throws IOException {
        ByteBufferOutputFile columnar = new ByteBufferOutputFile();
        writeColumnar(columnar);
        ByteBufferOutputFile row = new ByteBufferOutputFile();
        writeRows(row);
        MemoryOutputFile groups = new MemoryOutputFile();
        writeGroups(ExampleParquetWriter.builder(groups));

        byte[] columnarFile = columnar.toByteArray();
        byte[] rowFile = row.toByteArray();
        // The two Hardwood APIs write the same bytes for the same records, which the writer's
        // equivalence tests hold them to. A divergence here is a writer defect that would
        // otherwise be read as one contender producing a leaner file than the other.
        if (columnarFile.length != rowFile.length) {
            throw new IllegalStateException("The two Hardwood APIs produced files of different size: "
                    + columnarFile.length + " bytes columnar against " + rowFile.length + " bytes row");
        }

        System.out.printf("%nFlatWriteBenchmark: %,d rows, codec %s, %,d-row batches%n",
                fixture.rows(), codec, BATCH_ROWS);
        report("HARDWOOD_COLUMNAR", columnarFile);
        report("HARDWOOD_ROW", rowFile);
        report("PARQUET_JAVA_GROUP", groups.toByteArray());
    }

    private void report(String contender, byte[] file) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(file)))) {
            long rows = reader.getFileMetaData().numRows();
            if (rows != fixture.rows()) {
                throw new IllegalStateException(contender + " wrote " + rows + " rows, expected " + fixture.rows());
            }
            System.out.printf("  %-20s %,15d bytes, %,d rows, %d row groups%n",
                    contender, file.length, rows, reader.getFileMetaData().rowGroups().size());
        }
    }
}
