/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.wide;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.Version;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.Util;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import shaded.parquet.org.apache.thrift.protocol.TCompactProtocol;
import shaded.parquet.org.apache.thrift.transport.TIOStreamTransport;
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

/// The parquet-java counterpart of [WideSchemaMetadataBenchmark], over the identical fixtures,
/// so the two are compared on the same machine, JVM and bytes. Run both together — the class
/// filter `WideSchemaMetadata` matches each — and read the pairs:
///
/// - `decodeFooter` — `Util.readFileMetaData`, the Thrift decode alone, stopping at the
///   generated `format.FileMetaData` structures a caller still has to convert.
/// - `decodeFooterStockThrift` — the same generated structures read straight off an Apache
///   Thrift `TCompactProtocol`, without the `InterningProtocol` that `Util` wraps around it.
///   parquet-java interns every string the footer repeats; the gap between this and
///   `decodeFooter` is what that costs or saves on a schema this wide.
/// - `buildMetadata` — `ParquetMetadataConverter.fromParquetMetadata` over an already-decoded
///   footer: the conversion into `ParquetMetadata` and its `MessageType`, which is the work
///   Hardwood's decode and `FileSchema` construction cover between them.
/// - `openFile` — `ParquetFileReader.open`, what a caller pays end to end.
///
/// This class lives beside its Hardwood twin rather than inside it because both libraries name
/// their reader `ParquetFileReader`.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = { "-Xms2g", "-Xmx8g", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WideSchemaMetadataParquetJavaBenchmark {

    @Param({ "10", "100", "1000", "10000", "100000" })
    private int columns;

    private byte[] footerBytes;
    private FileMetaData decodedFooter;
    private Configuration hadoopConf;
    private org.apache.hadoop.fs.Path hadoopPath;

    @Setup
    public void setup() throws IOException {
        Path dir = Path.of(BenchmarkData.dir());
        WideSchemaFileGenerator.ensureFile(dir, columns);
        Path path = WideSchemaFileGenerator.file(dir, columns).toAbsolutePath().normalize();
        footerBytes = Footers.read(path);
        decodedFooter = Util.readFileMetaData(new ByteArrayInputStream(footerBytes));
        hadoopConf = new Configuration();
        hadoopPath = new org.apache.hadoop.fs.Path(path.toString());
        // Stamped from the artifact on the classpath, so a published comparison names the
        // version it actually measured rather than the one whoever ran it believed was pinned.
        System.out.printf("%,d columns against parquet-java %s%n", columns, Version.VERSION_NUMBER);
    }

    @Benchmark
    public void decodeFooter(Blackhole blackhole) throws IOException {
        blackhole.consume(Util.readFileMetaData(new ByteArrayInputStream(footerBytes)));
    }

    /// The Thrift runtime parquet-java ships is shaded into its own namespace, so the stock
    /// decode has to be spelled with the shaded names — there is no unshaded `libthrift` on
    /// this classpath to reach for instead.
    @Benchmark
    public void decodeFooterStockThrift(Blackhole blackhole) throws Exception {
        FileMetaData metaData = new FileMetaData();
        metaData.read(new TCompactProtocol(new TIOStreamTransport(new ByteArrayInputStream(footerBytes))));
        blackhole.consume(metaData);
    }

    @Benchmark
    public void buildMetadata(Blackhole blackhole) throws IOException {
        blackhole.consume(new ParquetMetadataConverter().fromParquetMetadata(decodedFooter));
    }

    @Benchmark
    public void openFile(Blackhole blackhole) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(hadoopConf, hadoopPath)) {
            blackhole.consume(reader.getFileMetaData().getSchema());
        }
    }
}
