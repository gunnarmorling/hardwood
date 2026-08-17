/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.nio.file.Path;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCommandTest implements SchemaCommandContract {

    private final String NESTED_FILE = this.getClass().getResource("/nested_struct_test.parquet").getPath();

    private final String VARIANT_FILE = this.getClass().getResource("/variant_test.parquet").getPath();

    private final String VARIANT_SHREDDED_FILE = this.getClass().getResource("/variant_shredded_test.parquet").getPath();

    @Override
    public String plainFile() {
        return getClass().getResource("/plain_uncompressed.parquet").getPath();
    }

    @Override
    public String nonexistentFile() {
        return "nonexistent.parquet";
    }

    @Test
    void displaysAvroSchemaForNestedFile() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("\"type\": \"record\"");
    }

    @Test
    void sanitizesNamesInAvroSchema(@TempDir Path tempDir) throws Exception {
        Path parquetFile = tempDir.resolve("escaped-names.parquet");
        FileSchema schema = FileSchema.builder("root \"schema\"\\path")
                .addColumn("say \"hi\"\\field", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("bell\u0007field", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        try (ParquetFileWriter ignored = ParquetFileWriter.create(OutputFile.of(parquetFile), schema)) {
        }

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        Schema parsed = new Schema.Parser().parse(result.output());
        assertThat(parsed.getName()).isEqualTo("Root__schema__path");
        assertThat(parsed.getDoc()).isEqualTo("Parquet name: root \"schema\"\\path");
        assertThat(parsed.getFields()).extracting(Schema.Field::name)
                .containsExactly("say__hi__field", "bell_field");
        assertThat(parsed.getFields()).extracting(Schema.Field::doc)
                .containsExactly("Parquet name: say \"hi\"\\field", "Parquet name: bell\u0007field");
    }

    @Test
    void disambiguatesCollidingFieldNamesInAvroSchema(@TempDir Path tempDir) throws Exception {
        Path parquetFile = tempDir.resolve("colliding-names.parquet");
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("a b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("a-b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("a.b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        try (ParquetFileWriter ignored = ParquetFileWriter.create(OutputFile.of(parquetFile), schema)) {
        }

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        Schema parsed = new Schema.Parser().parse(result.output());
        assertThat(parsed.getFields()).extracting(Schema.Field::name).containsExactly("a_b", "a_b_2", "a_b_3");
    }

    @Test
    void leavesLegalNamesUndocumentedInAvroSchema() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).doesNotContain("\"doc\"");
        Schema parsed = new Schema.Parser().parse(result.output());
        assertThat(parsed.getType()).isEqualTo(Schema.Type.RECORD);
    }

    @Test
    void displaysProtoSchemaForNestedFile() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "PROTO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("syntax = \"proto3\"")
                .contains("message");
    }

    @Test
    void rejectsRemoteUri() {
        Cli.Result result = Cli.launch("schema", "-f", "gs://bucket/data.parquet");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("not implemented yet");
    }

    @Test
    void displaysVariantAnnotation() {
        Cli.Result result = Cli.launch("schema", "-f", VARIANT_FILE);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("""
                message schema {
                  required int32 id;
                  optional group var (VARIANT(1)) {
                    required byte_array metadata;
                    required byte_array value;
                  }
                }""");
    }

    @Test
    void displaysShreddedVariantAnnotationWithTypedValueChild() {
        Cli.Result result = Cli.launch("schema", "-f", VARIANT_SHREDDED_FILE);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("""
                message schema {
                  required int32 id;
                  optional group var (VARIANT(1)) {
                    required byte_array metadata;
                    optional byte_array value;
                    optional int64 typed_value;
                  }
                }""");
    }
}
