/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.hardwood.InputFile;
import dev.hardwood.OutputFile;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The per-column encoding policy end to end: every legal (policy, physical type) pair written
/// and read back, the metadata each produces, how a policy resolves against the file-wide
/// default, and what the writer refuses.
class WriterEncodingPolicyTest {

    private static final int ROWS = 3_000;
    private static final int FIXED_WIDTH = 6;

    // ==================== Round trip ====================

    /// Every pair of the legality table, which is what the policy promises a caller can ask for.
    static List<Object[]> legalPairs() {
        List<Object[]> pairs = new ArrayList<>();
        for (ColumnEncoding encoding : ColumnEncoding.values()) {
            for (PhysicalType type : new PhysicalType[] { PhysicalType.BOOLEAN, PhysicalType.INT32,
                    PhysicalType.INT64, PhysicalType.FLOAT, PhysicalType.DOUBLE,
                    PhysicalType.BYTE_ARRAY, PhysicalType.FIXED_LEN_BYTE_ARRAY }) {
                if (supports(encoding, type)) {
                    pairs.add(new Object[] { encoding, type });
                }
            }
        }
        return pairs;
    }

    /// Mirrors `ColumnEncoding.supports` from the outside, so a change to the table has to be
    /// made deliberately in both places rather than silently shrinking this sweep.
    private static boolean supports(ColumnEncoding encoding, PhysicalType type) {
        return switch (encoding) {
            case AUTO, PLAIN -> true;
            case DELTA_BINARY_PACKED -> type == PhysicalType.INT32 || type == PhysicalType.INT64;
            case DELTA_LENGTH_BYTE_ARRAY -> type == PhysicalType.BYTE_ARRAY;
            case DELTA_BYTE_ARRAY -> type == PhysicalType.BYTE_ARRAY
                    || type == PhysicalType.FIXED_LEN_BYTE_ARRAY;
            case BYTE_STREAM_SPLIT -> type == PhysicalType.INT32 || type == PhysicalType.INT64
                    || type == PhysicalType.FLOAT || type == PhysicalType.DOUBLE
                    || type == PhysicalType.FIXED_LEN_BYTE_ARRAY;
        };
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("legalPairs")
    void roundTripsARequiredColumn(ColumnEncoding encoding, PhysicalType type) throws Exception {
        // A small page target puts several pages in the chunk, so what round-trips is a column
        // whose pages each carry the encoding's own header rather than one page that could lean
        // on state from the whole chunk.
        WriterConfig config = WriterConfig.builder()
                .encoding(encoding)
                .pageTargetBytes(1024)
                .build();

        byte[] file = write(type, RepetitionType.REQUIRED, config, null);

        assertRoundTrip(file, type, null);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("legalPairs")
    void roundTripsAnOptionalColumnWithNulls(ColumnEncoding encoding, PhysicalType type) throws Exception {
        // Nulls put a definition-level stream ahead of the values, and the value section holds
        // only the present ones — which is what the encodings have to count, not the rows.
        boolean[] nulls = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            nulls[i] = i % 5 == 0;
        }
        WriterConfig config = WriterConfig.builder()
                .encoding(encoding)
                .pageTargetBytes(1024)
                .build();

        byte[] file = write(type, RepetitionType.OPTIONAL, config, nulls);

        assertRoundTrip(file, type, nulls);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("legalPairs")
    void roundTripsAcrossRowGroups(ColumnEncoding encoding, PhysicalType type) throws Exception {
        // Several row groups, so each chunk starts the encoding afresh and a policy applies to
        // every one of them rather than only the first.
        // BOOLEAN is the narrowest type — one bit per buffered value — so the row count is what
        // it takes for that column to cross the target, and every wider type crosses it sooner.
        int rows = 40_000;
        WriterConfig config = WriterConfig.builder()
                .encoding(encoding)
                .pageTargetBytes(512)
                .rowGroupTargetBytes(1024)
                .build();

        byte[] file = write(type, RepetitionType.REQUIRED, config, null, rows);

        try (ParquetFileReader reader = open(file)) {
            assertThat(reader.getFileMetaData().rowGroups().size()).as("row groups").isGreaterThan(1);
        }
        assertRoundTrip(file, type, null, rows);
    }

    // ==================== Metadata ====================

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("legalPairs")
    void declaresTheEncodingItActuallyWrote(ColumnEncoding encoding, PhysicalType type) throws Exception {
        WriterConfig config = WriterConfig.builder().encoding(encoding).build();

        byte[] file = write(type, RepetitionType.REQUIRED, config, null);

        try (ParquetFileReader reader = open(file)) {
            ColumnMetaData meta = columnMeta(reader);
            if (encoding == ColumnEncoding.AUTO) {
                // AUTO is the pre-existing behaviour and is covered elsewhere; here it only has
                // to not claim one of the optional encodings.
                assertThat(meta.encodings())
                        .doesNotContain(Encoding.DELTA_BINARY_PACKED, Encoding.DELTA_BYTE_ARRAY,
                                Encoding.DELTA_LENGTH_BYTE_ARRAY, Encoding.BYTE_STREAM_SPLIT);
                return;
            }
            assertThat(meta.encodings()).as("declared encodings").containsExactly(expected(encoding));
            assertThat(meta.dictionaryPageOffset()).as("a named policy builds no dictionary").isNull();
        }
    }

    @Test
    void aNamedPolicyListsNoPlainWhereNothingIsPlain() throws Exception {
        // The chunk encodings list is what the chunk uses, not a superset. PLAIN used to be
        // listed unconditionally, which was true only while a chunk was either PLAIN or a
        // dictionary whose body is.
        WriterConfig config = WriterConfig.builder().encoding(ColumnEncoding.DELTA_BINARY_PACKED).build();

        byte[] file = write(PhysicalType.INT32, RepetitionType.REQUIRED, config, null);

        try (ParquetFileReader reader = open(file)) {
            assertThat(columnMeta(reader).encodings()).containsExactly(Encoding.DELTA_BINARY_PACKED);
        }
    }

    @Test
    void aLevelledChunkStillDeclaresRleForItsLevels() throws Exception {
        WriterConfig config = WriterConfig.builder().encoding(ColumnEncoding.DELTA_BYTE_ARRAY).build();
        boolean[] nulls = new boolean[ROWS];
        nulls[0] = true;

        byte[] file = write(PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, config, nulls);

        try (ParquetFileReader reader = open(file)) {
            assertThat(columnMeta(reader).encodings())
                    .containsExactlyInAnyOrder(Encoding.RLE, Encoding.DELTA_BYTE_ARRAY);
        }
    }

    @Test
    void aNamedPolicyStatesNoDistinctCount() throws Exception {
        // The count comes from the dictionary the chunk interned into, and a named policy builds
        // none — so the field is left out rather than guessed at.
        WriterConfig config = WriterConfig.builder().encoding(ColumnEncoding.DELTA_BINARY_PACKED).build();

        byte[] file = write(PhysicalType.INT64, RepetitionType.REQUIRED, config, null);

        try (ParquetFileReader reader = open(file)) {
            assertThat(columnMeta(reader).statistics().distinctCount()).isNull();
        }
    }

    @Test
    void statisticsSurviveANamedPolicy() throws Exception {
        // Bounds and null counts are accumulated from the values, independently of how they are
        // then encoded, so an encoding change must not disturb them.
        WriterConfig plain = WriterConfig.builder().encoding(ColumnEncoding.PLAIN).build();
        WriterConfig delta = WriterConfig.builder().encoding(ColumnEncoding.DELTA_BINARY_PACKED).build();
        boolean[] nulls = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            nulls[i] = i % 5 == 0;
        }

        byte[] plainFile = write(PhysicalType.INT32, RepetitionType.OPTIONAL, plain, nulls);
        byte[] deltaFile = write(PhysicalType.INT32, RepetitionType.OPTIONAL, delta, nulls);

        try (ParquetFileReader plainReader = open(plainFile); ParquetFileReader deltaReader = open(deltaFile)) {
            assertThat(columnMeta(deltaReader).statistics().minValue())
                    .isEqualTo(columnMeta(plainReader).statistics().minValue());
            assertThat(columnMeta(deltaReader).statistics().maxValue())
                    .isEqualTo(columnMeta(plainReader).statistics().maxValue());
            assertThat(columnMeta(deltaReader).statistics().nullCount())
                    .isEqualTo(columnMeta(plainReader).statistics().nullCount());
        }
    }

    // ==================== What it buys ====================

    @Test
    void deltaBeatsPlainOnASortedColumn() throws Exception {
        // The reason the policy exists: a sorted column costs its deltas rather than its values.
        // Uncompressed, so what is compared is the encoding rather than the codec.
        long[] timestamps = new long[ROWS];
        long now = 1_700_000_000_000L;
        for (int i = 0; i < ROWS; i++) {
            timestamps[i] = now + i * 1_000L;
        }
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.INT64, RepetitionType.REQUIRED)
                .build();

        int plain = writeLongs(schema, timestamps, ColumnEncoding.PLAIN).length;
        int delta = writeLongs(schema, timestamps, ColumnEncoding.DELTA_BINARY_PACKED).length;

        assertThat(delta).as("DELTA_BINARY_PACKED %d bytes against PLAIN's %d", delta, plain)
                .isLessThan(plain / 4);
    }

    @Test
    void byteStreamSplitChangesNoSizeButHelpsTheCodec() throws Exception {
        // Byte-stream-split reorders bytes rather than removing any, so uncompressed it is
        // exactly PLAIN's size — and its whole payoff shows up only once a codec follows it.
        double[] values = new double[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = 100.0 + i * 0.015625;
        }
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
                .build();

        int plain = writeDoubles(schema, values, ColumnEncoding.PLAIN, CompressionCodec.UNCOMPRESSED).length;
        int split = writeDoubles(schema, values, ColumnEncoding.BYTE_STREAM_SPLIT,
                CompressionCodec.UNCOMPRESSED).length;
        int plainZstd = writeDoubles(schema, values, ColumnEncoding.PLAIN, CompressionCodec.ZSTD).length;
        int splitZstd = writeDoubles(schema, values, ColumnEncoding.BYTE_STREAM_SPLIT,
                CompressionCodec.ZSTD).length;

        assertThat(split).as("uncompressed, the split is the same size as PLAIN").isEqualTo(plain);
        assertThat(splitZstd).as("ZSTD over split %d against ZSTD over plain %d", splitZstd, plainZstd)
                .isLessThan(plainZstd);
    }

    // ==================== Resolution ====================

    @Test
    void aColumnOverrideBeatsTheFileWideDefault() throws Exception {
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("a", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder()
                .encoding(ColumnEncoding.PLAIN)
                .encoding("b", ColumnEncoding.DELTA_BINARY_PACKED)
                .build();

        byte[] file = writeTwoIntColumns(schema, config);

        try (ParquetFileReader reader = open(file)) {
            assertThat(meta(reader, 0).encodings()).as("a takes the default")
                    .containsExactly(Encoding.PLAIN);
            assertThat(meta(reader, 1).encodings()).as("b takes its override")
                    .containsExactly(Encoding.DELTA_BINARY_PACKED);
        }
    }

    @Test
    void anOverriddenColumnLeavesItsNeighboursOnAuto() throws Exception {
        // The default default is AUTO, so a column named here must not drag the rest of the
        // schema off the dictionary they would otherwise get.
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("a", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder()
                .encoding("b", ColumnEncoding.DELTA_BINARY_PACKED)
                .build();

        byte[] file = writeTwoIntColumns(schema, config);

        try (ParquetFileReader reader = open(file)) {
            assertThat(meta(reader, 0).dictionaryPageOffset()).as("a keeps its dictionary").isNotNull();
            assertThat(meta(reader, 1).dictionaryPageOffset()).as("b has none").isNull();
            assertThat(meta(reader, 1).encodings()).containsExactly(Encoding.DELTA_BINARY_PACKED);
        }
    }

    @Test
    void addressesANestedColumnByItsDottedLeafPath() throws Exception {
        // A leaf is named by its path, synthetic list segments included — the leaf name alone
        // would be ambiguous in a schema that repeats it at several depths.
        FileSchema schema = FileSchema.builder("schema")
                .list("readings", RepetitionType.OPTIONAL, element -> element
                        .primitive(PhysicalType.INT64, RepetitionType.REQUIRED))
                .build();
        WriterConfig config = WriterConfig.builder()
                .encoding("readings.list.element", ColumnEncoding.DELTA_BINARY_PACKED)
                .build();

        int[] offsets = new int[ROWS + 1];
        long[] values = new long[ROWS * 2];
        for (int i = 0; i < ROWS; i++) {
            offsets[i + 1] = offsets[i] + 2;
            values[i * 2] = i;
            values[i * 2 + 1] = i + 1;
        }
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch
                    .list("readings", offsets)
                    .longs("readings.list.element", values));
        }

        try (ParquetFileReader reader = open(out.toByteArray())) {
            assertThat(meta(reader, 0).encodings()).contains(Encoding.DELTA_BINARY_PACKED);
        }
    }

    @Test
    void defaultsToAuto() {
        assertThat(WriterConfig.DEFAULT_ENCODING).isEqualTo(ColumnEncoding.AUTO);
        assertThat(WriterConfig.defaults().defaultEncoding()).isEqualTo(ColumnEncoding.AUTO);
        assertThat(WriterConfig.defaults().columnEncodings()).isEmpty();
        assertThat(WriterConfig.defaults().encodingFor("anything")).isEqualTo(ColumnEncoding.AUTO);
    }

    @Test
    void exposesItsColumnEncodingsUnmodifiably() {
        WriterConfig config = WriterConfig.builder().encoding("v", ColumnEncoding.PLAIN).build();

        assertThatThrownBy(() -> config.columnEncodings().put("other", ColumnEncoding.PLAIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== Refusals ====================

    @Test
    void rejectsAnEncodingForAColumnTheSchemaDoesNotHave() {
        // A path matching nothing is a typo, and its only other effect would be to write the
        // file in an encoding the caller did not ask for. The message lists what it could match.
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder()
                .encoding("typo", ColumnEncoding.PLAIN)
                .build();

        assertThatThrownBy(() -> ParquetFileWriter.create(new ByteBufferOutputFile(), schema, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typo")
                .hasMessageContaining("[v]");
    }

    @Test
    void rejectsAnEncodingTheColumnsTypeCannotCarry() {
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder()
                .encoding("v", ColumnEncoding.DELTA_BINARY_PACKED)
                .build();

        assertThatThrownBy(() -> ParquetFileWriter.create(new ByteBufferOutputFile(), schema, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELTA_BINARY_PACKED")
                .hasMessageContaining("BYTE_ARRAY");
    }

    @Test
    void rejectsAFileWideDefaultOneColumnCannotCarry() {
        // The alternative — quietly resolving that one column to something else — is the silent
        // divergence the check exists to prevent, so a mixed schema has to state its intent per
        // column.
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("n", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
                .addColumn("s", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder().encoding(ColumnEncoding.BYTE_STREAM_SPLIT).build();

        assertThatThrownBy(() -> ParquetFileWriter.create(new ByteBufferOutputFile(), schema, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'s'")
                .hasMessageContaining("file-wide default");
    }

    @Test
    void rejectsAFileThatWouldBeLeftHalfWritten(@TempDir Path dir) {
        // Validation runs before the output is touched, so a configuration the writer cannot
        // honour leaves no file behind at all — the same guarantee codec resolution gives.
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.BOOLEAN, RepetitionType.REQUIRED)
                .build();
        WriterConfig config = WriterConfig.builder().encoding(ColumnEncoding.BYTE_STREAM_SPLIT).build();
        Path file = dir.resolve("rejected.parquet");

        assertThatThrownBy(() -> ParquetFileWriter.create(OutputFile.of(file), schema, config))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.exists(file)).as("nothing was written").isFalse();
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> WriterConfig.builder().encoding((ColumnEncoding) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriterConfig.builder().encoding(null, ColumnEncoding.PLAIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriterConfig.builder().encoding("v", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== Helpers ====================

    private static Encoding expected(ColumnEncoding encoding) {
        return switch (encoding) {
            case PLAIN -> Encoding.PLAIN;
            case DELTA_BINARY_PACKED -> Encoding.DELTA_BINARY_PACKED;
            case DELTA_LENGTH_BYTE_ARRAY -> Encoding.DELTA_LENGTH_BYTE_ARRAY;
            case DELTA_BYTE_ARRAY -> Encoding.DELTA_BYTE_ARRAY;
            case BYTE_STREAM_SPLIT -> Encoding.BYTE_STREAM_SPLIT;
            case AUTO -> throw new IllegalArgumentException("AUTO names no single encoding");
        };
    }

    /// The value of row `r`, chosen per type so the deltas are small and the byte arrays share
    /// prefixes — the shapes the optional encodings target.
    private static Object value(PhysicalType type, int r) {
        return switch (type) {
            case BOOLEAN -> r % 3 == 0;
            case INT32 -> 1_000_000 + r * 3;
            case INT64 -> 1_700_000_000_000L + r * 1_000L;
            case FLOAT -> 100.0f + r * 0.25f;
            case DOUBLE -> 100.0 + r * 0.015625;
            case BYTE_ARRAY -> ("/data/part-" + String.format("%05d", r)).getBytes(StandardCharsets.UTF_8);
            case FIXED_LEN_BYTE_ARRAY -> String.format("%06d", r % 1000).getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException(type.toString());
        };
    }

    private static byte[] write(PhysicalType type, RepetitionType repetition, WriterConfig config,
            boolean[] nulls) throws Exception {
        return write(type, repetition, config, nulls, ROWS);
    }

    private static byte[] write(PhysicalType type, RepetitionType repetition, WriterConfig config,
            boolean[] nulls, int rows) throws Exception {
        FileSchema.Builder builder = FileSchema.builder("schema");
        if (type == PhysicalType.FIXED_LEN_BYTE_ARRAY) {
            builder.addColumn("v", type, repetition, FIXED_WIDTH);
        }
        else {
            builder.addColumn("v", type, repetition);
        }
        FileSchema schema = builder.build();

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> fill(batch, type, rows, nulls));
        }
        return out.toByteArray();
    }

    private static void fill(ColumnBatch batch, PhysicalType type, int rows, boolean[] nulls) {
        switch (type) {
            case BOOLEAN -> {
                boolean[] values = new boolean[rows];
                for (int r = 0; r < rows; r++) {
                    values[r] = (boolean) value(type, r);
                }
                if (nulls == null) {
                    batch.booleans("v", values);
                }
                else {
                    batch.booleans("v", values, nulls);
                }
            }
            case INT32 -> {
                int[] values = new int[rows];
                for (int r = 0; r < rows; r++) {
                    values[r] = (int) value(type, r);
                }
                if (nulls == null) {
                    batch.ints("v", values);
                }
                else {
                    batch.ints("v", values, nulls);
                }
            }
            case INT64 -> {
                long[] values = new long[rows];
                for (int r = 0; r < rows; r++) {
                    values[r] = (long) value(type, r);
                }
                if (nulls == null) {
                    batch.longs("v", values);
                }
                else {
                    batch.longs("v", values, nulls);
                }
            }
            case FLOAT -> {
                float[] values = new float[rows];
                for (int r = 0; r < rows; r++) {
                    values[r] = (float) value(type, r);
                }
                if (nulls == null) {
                    batch.floats("v", values);
                }
                else {
                    batch.floats("v", values, nulls);
                }
            }
            case DOUBLE -> {
                double[] values = new double[rows];
                for (int r = 0; r < rows; r++) {
                    values[r] = (double) value(type, r);
                }
                if (nulls == null) {
                    batch.doubles("v", values);
                }
                else {
                    batch.doubles("v", values, nulls);
                }
            }
            case BYTE_ARRAY -> {
                byte[][] values = new byte[rows][];
                for (int r = 0; r < rows; r++) {
                    values[r] = (byte[]) value(type, r);
                }
                if (nulls == null) {
                    batch.bytes("v", values);
                }
                else {
                    batch.bytes("v", values, nulls);
                }
            }
            case FIXED_LEN_BYTE_ARRAY -> {
                byte[][] values = new byte[rows][];
                for (int r = 0; r < rows; r++) {
                    values[r] = (byte[]) value(type, r);
                }
                if (nulls == null) {
                    batch.fixed("v", values);
                }
                else {
                    batch.fixed("v", values, nulls);
                }
            }
            default -> throw new IllegalArgumentException(type.toString());
        }
    }

    /// Reads the file back and asserts every row, present or null, matches what was written.
    private static void assertRoundTrip(byte[] file, PhysicalType type, boolean[] nulls) throws Exception {
        assertRoundTrip(file, type, nulls, ROWS);
    }

    private static void assertRoundTrip(byte[] file, PhysicalType type, boolean[] nulls, int rows)
            throws Exception {
        try (ParquetFileReader reader = open(file)) {
            assertThat(reader.getFileMetaData().numRows()).as("row count").isEqualTo(rows);
            List<Object> read = new ArrayList<>(rows);
            try (ColumnReader column = reader.columnReader(0)) {
                while (column.nextBatch()) {
                    readBatch(column, type, read);
                }
            }
            assertThat(read).as("value count").hasSize(rows);
            for (int r = 0; r < rows; r++) {
                if (nulls != null && nulls[r]) {
                    assertThat(read.get(r)).as("row %d is null", r).isNull();
                }
                else if (type == PhysicalType.BYTE_ARRAY || type == PhysicalType.FIXED_LEN_BYTE_ARRAY) {
                    assertThat((byte[]) read.get(r)).as("row %d", r).isEqualTo((byte[]) value(type, r));
                }
                else {
                    assertThat(read.get(r)).as("row %d", r).isEqualTo(value(type, r));
                }
            }
        }
    }

    private static void readBatch(ColumnReader column, PhysicalType type, List<Object> into) {
        int count = column.getRecordCount();
        dev.hardwood.Validity validity = column.getLeafValidity();
        switch (type) {
            case BOOLEAN -> {
                boolean[] batch = column.getBooleans();
                for (int i = 0; i < count; i++) {
                    into.add(validity.isNull(i) ? null : batch[i]);
                }
            }
            case INT32 -> {
                int[] batch = column.getInts();
                for (int i = 0; i < count; i++) {
                    into.add(validity.isNull(i) ? null : batch[i]);
                }
            }
            case INT64 -> {
                long[] batch = column.getLongs();
                for (int i = 0; i < count; i++) {
                    into.add(validity.isNull(i) ? null : batch[i]);
                }
            }
            case FLOAT -> {
                float[] batch = column.getFloats();
                for (int i = 0; i < count; i++) {
                    into.add(validity.isNull(i) ? null : batch[i]);
                }
            }
            case DOUBLE -> {
                double[] batch = column.getDoubles();
                for (int i = 0; i < count; i++) {
                    into.add(validity.isNull(i) ? null : batch[i]);
                }
            }
            case BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> {
                byte[][] batch = column.getBinaries();
                for (int i = 0; i < count; i++) {
                    into.add(validity.isNull(i) ? null : batch[i]);
                }
            }
            default -> throw new IllegalArgumentException(type.toString());
        }
    }

    private static byte[] writeLongs(FileSchema schema, long[] values, ColumnEncoding encoding) throws Exception {
        WriterConfig config = WriterConfig.builder()
                .encoding(encoding)
                .codec(CompressionCodec.UNCOMPRESSED)
                .build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch.longs("v", values));
        }
        return out.toByteArray();
    }

    private static byte[] writeDoubles(FileSchema schema, double[] values, ColumnEncoding encoding,
            CompressionCodec codec) throws Exception {
        WriterConfig config = WriterConfig.builder().encoding(encoding).codec(codec).build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch.doubles("v", values));
        }
        return out.toByteArray();
    }

    private static byte[] writeTwoIntColumns(FileSchema schema, WriterConfig config) throws Exception {
        int[] values = new int[ROWS];
        for (int r = 0; r < ROWS; r++) {
            values[r] = r % 50;
        }
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch.ints("a", values).ints("b", values));
        }
        return out.toByteArray();
    }

    private static ParquetFileReader open(byte[] file) throws Exception {
        return ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(file)));
    }

    private static ColumnMetaData columnMeta(ParquetFileReader reader) {
        return meta(reader, 0);
    }

    private static ColumnMetaData meta(ParquetFileReader reader, int column) {
        return reader.getFileMetaData().rowGroups().get(0).columns().get(column).metaData();
    }
}
