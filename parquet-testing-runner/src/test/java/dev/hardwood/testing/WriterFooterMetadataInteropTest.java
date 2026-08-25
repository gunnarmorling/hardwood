/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// The interop gate over the footer's `key_value_metadata`: what Hardwood stamps on a file is
/// what an independent implementation reads back out of it.
///
/// The field is the one Hardwood's own round trip cannot vouch for on its own. It carries
/// `ARROW:schema`, the pandas descriptor and the table-format stamps, which means the consumer
/// that has to find them is never Hardwood — a reader that agrees with the writer about a
/// malformed `list<KeyValue>` would hide the break from both halves of a Hardwood round trip.
class WriterFooterMetadataInteropTest {

    private static final String COLUMN = "v";

    @Test
    void parquetJavaReadsTheMetadataHardwoodWrote(@TempDir Path dir) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ARROW:schema", "AAAAgAAAABAAAAAAAAoADgAGAAUACAAKAAAAAAEEABAAAAAA");
        metadata.put("org.apache.spark.sql.parquet.row.metadata", "{\"type\":\"struct\",\"fields\":[]}");
        metadata.put("unicode.value", "grüße — 🌲");

        Path file = write(dir, metadata);

        ParquetMetadata footer = ParquetJavaReader.readFooter(file);
        assertThat(footer.getFileMetaData().getKeyValueMetaData())
                .containsAllEntriesOf(metadata);
    }

    /// `KeyValue.value` is optional, and a key carrying no value is what a reader reports as a
    /// null value. parquet-java must see the same thing, or a file round-tripped through
    /// Hardwood would gain an empty string where the original had nothing.
    @Test
    void parquetJavaSeesAKeyWithNoValue(@TempDir Path dir) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("valueless", null);
        metadata.put("empty", "");

        Path file = write(dir, metadata);

        Map<String, String> readBack = ParquetJavaReader.readFooter(file)
                .getFileMetaData().getKeyValueMetaData();
        assertThat(readBack).containsEntry("valueless", null);
        assertThat(readBack).containsEntry("empty", "");
    }

    /// A file given no metadata carries none, rather than an empty `list<KeyValue>` that a
    /// stricter consumer could balk at.
    @Test
    void aFileGivenNoMetadataCarriesNone(@TempDir Path dir) throws IOException {
        Path file = write(dir, Map.of());

        assertThat(ParquetJavaReader.readFooter(file).getFileMetaData().getKeyValueMetaData())
                .isEmpty();
    }

    /// The identifier a caller sets must stay parseable by parquet-java's `VersionParser`, on
    /// which its writer-specific correctness workarounds turn.
    @Test
    void parquetJavaParsesACallerSuppliedCreatedBy(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("created-by.parquet");
        try (ParquetFileWriter writer = ParquetFileWriter.create(OutputFile.of(file), schema())) {
            writer.createdBy("myapp version 2.1.0 (build deadbeef)");
            writer.columnWriter().writeBatch(batch -> batch.ints(0, new int[]{ 1, 2, 3 }));
        }

        assertThat(ParquetJavaReader.readFooter(file).getFileMetaData().getCreatedBy())
                .isEqualTo("myapp version 2.1.0 (build deadbeef)");
    }

    private static Path write(Path dir, Map<String, String> metadata) throws IOException {
        Path file = dir.resolve("footer-metadata.parquet");
        try (ParquetFileWriter writer = ParquetFileWriter.create(OutputFile.of(file), schema())) {
            writer.keyValueMetadata(metadata);
            writer.columnWriter().writeBatch(batch -> batch.ints(0, new int[]{ 1, 2, 3 }));
        }
        return file;
    }

    private static FileSchema schema() {
        return FileSchema.builder("footer-metadata")
                .addColumn(COLUMN, PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
    }
}
