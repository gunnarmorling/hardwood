/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.parquet.column.schema.EdgeInterpolationAlgorithm;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveComparator;
import org.apache.parquet.schema.PrimitiveType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.LogicalType.TimeUnit;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnBatch;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// The logical-type half of the write-path interop gate (`_designs/WRITER_INTEROP_GATE.md`):
/// every annotation the writer emits is written onto a column, and parquet-java parses it out of
/// the schema and reads the annotated values back.
///
/// An annotation is not decoration — it redefines the column's sort order, so it changes what
/// `min` and `max` mean. The parameterized case pins that parquet-java sees the annotation the
/// writer declared and that its own comparator finds the bounds consistent; the order-specific
/// cases below pin the two orders that differ from the physical type's own, where a disagreement
/// would prune away live rows rather than merely mis-sort.
///
/// Statistics are what this class is for, and are what separates it from
/// [WriterAnnotationCoverageTest], which writes the same annotations at more points but asserts
/// only the values that come back. The ends of each annotation's declared range, and the refusal
/// of a value outside it, live there rather than here.
///
/// The flat matrix is in [WriterInteropTest] and the nested shapes in [WriterNestedInteropTest].
class WriterLogicalTypeInteropTest {

    private static final String COLUMN = "v";

    private static final int[] SMALL_INTS = { 0, 1, 2, 3 };
    private static final long[] SMALL_LONGS = { 0L, 1L, 2L, 3L };

    /// A well-formed little-endian WKB point, so a `GEOMETRY` / `GEOGRAPHY` column holds
    /// something a geospatial consumer could actually decode.
    private static final byte[] WKB_POINT = ByteBuffer.allocate(21)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1).putInt(1).putDouble(1.5).putDouble(2.5)
            .array();

    /// Every annotation the writer emits, paired with the parquet-java annotation it must read
    /// back as and with whether the format defines a sort order for it — `INTERVAL`, `GEOMETRY`
    /// and `GEOGRAPHY` have none, so those columns carry a null count and no bounds.
    static Stream<Annotated> annotations() {
        return Stream.of(
                binary(new LogicalType.StringType(), LogicalTypeAnnotation.stringType(), utf8()),
                binary(new LogicalType.EnumType(), LogicalTypeAnnotation.enumType(), utf8()),
                binary(new LogicalType.JsonType(), LogicalTypeAnnotation.jsonType(),
                        new byte[][] { json("1"), json("2"), json("3"), json("4") }),
                binary(new LogicalType.BsonType(), LogicalTypeAnnotation.bsonType(), utf8()),
                binary(new LogicalType.DecimalType(2, 20), LogicalTypeAnnotation.decimalType(2, 20),
                        new byte[][] { { 0x01 }, { 0x02 }, { 0x00, 0x03 }, { (byte) 0xff } }),
                unordered(binary(new LogicalType.GeometryType("EPSG:4326"),
                        LogicalTypeAnnotation.geometryType("EPSG:4326"), wkb())),
                unordered(binary(new LogicalType.GeographyType("EPSG:4326",
                        LogicalType.EdgeInterpolationAlgorithm.KARNEY),
                        LogicalTypeAnnotation.geographyType("EPSG:4326", EdgeInterpolationAlgorithm.KARNEY),
                        wkb())),

                ints(new LogicalType.DateType(), LogicalTypeAnnotation.dateType()),
                ints(new LogicalType.IntType(8, true), LogicalTypeAnnotation.intType(8, true)),
                ints(new LogicalType.IntType(16, false), LogicalTypeAnnotation.intType(16, false)),
                ints(new LogicalType.IntType(32, false), LogicalTypeAnnotation.intType(32, false)),
                ints(new LogicalType.DecimalType(2, 9), LogicalTypeAnnotation.decimalType(2, 9)),
                ints(new LogicalType.TimeType(true, TimeUnit.MILLIS),
                        LogicalTypeAnnotation.timeType(true, LogicalTypeAnnotation.TimeUnit.MILLIS)),

                longs(new LogicalType.IntType(64, false), LogicalTypeAnnotation.intType(64, false)),
                longs(new LogicalType.DecimalType(4, 18), LogicalTypeAnnotation.decimalType(4, 18)),
                longs(new LogicalType.TimeType(false, TimeUnit.MICROS),
                        LogicalTypeAnnotation.timeType(false, LogicalTypeAnnotation.TimeUnit.MICROS)),
                longs(new LogicalType.TimeType(true, TimeUnit.NANOS),
                        LogicalTypeAnnotation.timeType(true, LogicalTypeAnnotation.TimeUnit.NANOS)),
                longs(new LogicalType.TimestampType(true, TimeUnit.MILLIS),
                        LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS)),
                longs(new LogicalType.TimestampType(false, TimeUnit.MICROS),
                        LogicalTypeAnnotation.timestampType(false, LogicalTypeAnnotation.TimeUnit.MICROS)),
                longs(new LogicalType.TimestampType(true, TimeUnit.NANOS),
                        LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS)),

                fixed(16, new LogicalType.UuidType(), LogicalTypeAnnotation.uuidType(), fill(16)),
                fixed(2, new LogicalType.Float16Type(), LogicalTypeAnnotation.float16Type(), float16()),
                unordered(fixed(12, new LogicalType.IntervalType(), LogicalTypeAnnotation.intervalType(),
                        fill(12))),
                fixed(8, new LogicalType.DecimalType(3, 18), LogicalTypeAnnotation.decimalType(3, 18), fill(8)),

                nulls());
    }

    // ==================== Tests ====================

    @ParameterizedTest(name = "{0}")
    @MethodSource("annotations")
    void annotationAndValuesSurviveTheFile(Annotated annotated, @TempDir Path dir) throws IOException {
        Path file = write(dir, annotated);

        ParquetMetadata footer = ParquetJavaReader.readFooter(file);
        PrimitiveType column = footer.getFileMetaData().getSchema().getType(COLUMN).asPrimitiveType();
        assertThat(column.getLogicalTypeAnnotation()).as("annotation").isEqualTo(annotated.parquetJava());
        if (annotated.typeLength() != null) {
            assertThat(column.getTypeLength()).as("type length").isEqualTo(annotated.typeLength());
        }

        List<Group> rows = ParquetJavaReader.readGroups(file);
        assertThat(rows).hasSize(annotated.rowCount());
        for (int r = 0; r < rows.size(); r++) {
            if (annotated.allNull()) {
                assertThat(rows.get(r).getFieldRepetitionCount(COLUMN)).as("row %d is null", r).isZero();
            }
            else {
                assertThat(annotated.read(rows.get(r))).as("row %d", r).isEqualTo(annotated.expected(r));
            }
        }

        Statistics<?> statistics = footer.getBlocks().get(0).getColumns().get(0).getStatistics();
        assertThat(statistics.getNumNulls()).as("null count")
                .isEqualTo(annotated.allNull() ? annotated.rowCount() : 0);
        if (annotated.ordered()) {
            assertBounds(annotated, statistics);
        }
        else {
            assertThat(statistics.hasNonNullValue()).as("no bounds for an undefined order").isFalse();
        }
    }

    /// The bounds parquet-java reads are the true extremes of the written values *in the order the
    /// annotation defines*, reduced out of those values with parquet-java's own comparator for the
    /// column. Asserting the extremes rather than merely that `min <= max` is what makes every row
    /// of the table able to catch a bound computed in the wrong order: most of the annotations
    /// carry values that sort identically under every candidate order, so a consistency check
    /// passes for them no matter which order the writer used.
    private static void assertBounds(Annotated annotated, Statistics<?> statistics) {
        assertThat(statistics.hasNonNullValue()).as("bounds are written").isTrue();

        List<Comparable<?>> values = annotated.statisticsValues();
        assertThat(statistics.genericGetMin()).as("min")
                .isEqualTo(extreme(values, statistics.comparator(), true));
        assertThat(statistics.genericGetMax()).as("max")
                .isEqualTo(extreme(values, statistics.comparator(), false));
    }

    /// The smallest or largest of `values` under `comparator`.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Comparable<?> extreme(List<Comparable<?>> values, PrimitiveComparator comparator, boolean min) {
        Comparable<?> best = values.get(0);
        for (Comparable<?> value : values) {
            int order = comparator.compare(value, best);
            if (min ? order < 0 : order > 0) {
                best = value;
            }
        }
        return best;
    }

    /// An unsigned annotation makes the bounds unsigned: `0xffffffff` is the largest value, not
    /// the smallest, and a reader taking the signed order would prune every row group whose
    /// values straddle the sign bit.
    @Test
    void unsignedIntegerBoundsUseUnsignedOrder(@TempDir Path dir) throws IOException {
        FileSchema schema = FileSchema.builder("annotated")
                .addColumn("i", PhysicalType.INT32, RepetitionType.REQUIRED, new LogicalType.IntType(32, false))
                .addColumn("l", PhysicalType.INT64, RepetitionType.REQUIRED, new LogicalType.IntType(64, false))
                .build();

        // -1 is 4294967295 / 18446744073709551615 read unsigned, so it is the maximum of each.
        Path file = write(dir, schema, batch -> batch
                .ints("i", new int[] { 1, -1, 2 })
                .longs("l", new long[] { 1L, -1L, 2L }));

        List<ColumnChunkMetaData> columns =
                ParquetJavaReader.readFooter(file).getBlocks().get(0).getColumns();
        assertThat(columns.get(0).getStatistics().genericGetMin()).as("uint32 min").isEqualTo(1);
        assertThat(columns.get(0).getStatistics().genericGetMax()).as("uint32 max").isEqualTo(-1);
        assertThat(columns.get(1).getStatistics().genericGetMin()).as("uint64 min").isEqualTo(1L);
        assertThat(columns.get(1).getStatistics().genericGetMax()).as("uint64 max").isEqualTo(-1L);
    }

    /// A `DECIMAL` over a binary type is compared as a signed big-endian integer, not as the
    /// unsigned byte string an unannotated binary column uses, so a negative value is the
    /// minimum even though its leading byte is the largest.
    @Test
    void binaryDecimalBoundsUseSignedOrder(@TempDir Path dir) throws IOException {
        FileSchema schema = FileSchema.builder("annotated")
                .addColumn(COLUMN, PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 2,
                        new LogicalType.DecimalType(0, 4))
                .build();

        byte[] one = { 0x00, 0x01 };
        byte[] minusOne = { (byte) 0xff, (byte) 0xff };
        byte[] two = { 0x00, 0x02 };
        Path file = write(dir, schema, batch -> batch.fixed(COLUMN, new byte[][] { one, minusOne, two }));

        Statistics<?> statistics = ParquetJavaReader.readFooter(file).getBlocks().get(0)
                .getColumns().get(0).getStatistics();
        assertThat(statistics.genericGetMin()).as("min").isEqualTo(Binary.fromConstantByteArray(minusOne));
        assertThat(statistics.genericGetMax()).as("max").isEqualTo(Binary.fromConstantByteArray(two));
    }

    // ==================== Helpers ====================

    private Path write(Path dir, Annotated annotated) throws IOException {
        FileSchema schema = annotated.typeLength() == null
                ? FileSchema.builder("annotated")
                        .addColumn(COLUMN, annotated.physicalType(), annotated.repetition(), annotated.hardwood())
                        .build()
                : FileSchema.builder("annotated")
                        .addColumn(COLUMN, annotated.physicalType(), annotated.repetition(),
                                annotated.typeLength(), annotated.hardwood())
                        .build();

        return write(dir, schema, annotated::fill);
    }

    private Path write(Path dir, FileSchema schema, Consumer<ColumnBatch> filler) throws IOException {
        Path file = dir.resolve("annotated.parquet");
        try (ParquetFileWriter writer = ParquetFileWriter.create(OutputFile.of(file), schema)) {
            writer.columnWriter().writeBatch(filler);
        }
        return file;
    }

    // ==================== The annotation table ====================

    /// One annotation under test: how Hardwood declares it, the parquet-java annotation it must
    /// read back as, the values written beneath it, and whether the format defines a sort order
    /// for it.
    record Annotated(PhysicalType physicalType, Integer typeLength, LogicalType hardwood,
            LogicalTypeAnnotation parquetJava, byte[][] binaryValues, boolean ordered, boolean allNull) {

        int rowCount() {
            return binaryValues == null ? SMALL_INTS.length : binaryValues.length;
        }

        /// `UNKNOWN` annotates a column whose every value is null, so it is the one row that is
        /// `OPTIONAL` and the one whose values are written through the null-mask setter.
        RepetitionType repetition() {
            return allNull ? RepetitionType.OPTIONAL : RepetitionType.REQUIRED;
        }

        void fill(ColumnBatch batch) {
            if (allNull) {
                fillAllNull(batch);
                return;
            }
            switch (physicalType) {
                case INT32 -> batch.ints(COLUMN, SMALL_INTS);
                case INT64 -> batch.longs(COLUMN, SMALL_LONGS);
                case BYTE_ARRAY -> batch.bytes(COLUMN, binaryValues);
                case FIXED_LEN_BYTE_ARRAY -> batch.fixed(COLUMN, binaryValues);
                default -> throw new IllegalStateException("No annotation case for " + physicalType);
            }
        }

        /// Writes the all-null row's values. `UNKNOWN` is the only annotation with such a row and
        /// it sits on `INT32`, so this writes ints; a row added on another physical type has to
        /// extend this rather than silently get the wrong value width.
        private void fillAllNull(ColumnBatch batch) {
            if (physicalType != PhysicalType.INT32) {
                throw new IllegalStateException("No all-null case for " + physicalType);
            }
            boolean[] nulls = new boolean[rowCount()];
            Arrays.fill(nulls, true);
            batch.ints(COLUMN, new int[rowCount()], nulls);
        }

        Object expected(int row) {
            return switch (physicalType) {
                case INT32 -> SMALL_INTS[row];
                case INT64 -> SMALL_LONGS[row];
                default -> binaryValues[row];
            };
        }

        Object read(Group group) {
            return switch (physicalType) {
                case INT32 -> group.getInteger(COLUMN, 0);
                case INT64 -> group.getLong(COLUMN, 0);
                default -> group.getBinary(COLUMN, 0).getBytes();
            };
        }

        /// The written values in the representation parquet-java's statistics compare in, so the
        /// expected bounds can be reduced out of them with parquet-java's own comparator.
        List<Comparable<?>> statisticsValues() {
            List<Comparable<?>> values = new ArrayList<>(rowCount());
            for (int row = 0; row < rowCount(); row++) {
                Object value = expected(row);
                values.add(value instanceof byte[] bytes
                        ? Binary.fromConstantByteArray(bytes)
                        : (Comparable<?>) value);
            }
            return values;
        }

        @Override
        public String toString() {
            return hardwood + " on " + physicalType + (typeLength == null ? "" : "(" + typeLength + ")");
        }
    }

    private static Annotated ints(LogicalType hardwood, LogicalTypeAnnotation parquetJava) {
        return new Annotated(PhysicalType.INT32, null, hardwood, parquetJava, null, true, false);
    }

    private static Annotated longs(LogicalType hardwood, LogicalTypeAnnotation parquetJava) {
        return new Annotated(PhysicalType.INT64, null, hardwood, parquetJava, null, true, false);
    }

    /// The `UNKNOWN` row: an `OPTIONAL INT32` column whose every value is null, which is the only
    /// shape the annotation is legal on and the only one whose meaning it can describe.
    private static Annotated nulls() {
        return new Annotated(PhysicalType.INT32, null, new LogicalType.NullType(),
                LogicalTypeAnnotation.unknownType(), null, false, true);
    }

    private static Annotated binary(LogicalType hardwood, LogicalTypeAnnotation parquetJava, byte[][] values) {
        return new Annotated(PhysicalType.BYTE_ARRAY, null, hardwood, parquetJava, values, true, false);
    }

    private static Annotated fixed(int typeLength, LogicalType hardwood, LogicalTypeAnnotation parquetJava,
            byte[][] values) {
        return new Annotated(PhysicalType.FIXED_LEN_BYTE_ARRAY, typeLength, hardwood, parquetJava, values, true, false);
    }

    /// Marks an annotation whose sort order the format leaves undefined, so no bounds are written.
    private static Annotated unordered(Annotated annotated) {
        return new Annotated(annotated.physicalType(), annotated.typeLength(), annotated.hardwood(),
                annotated.parquetJava(), annotated.binaryValues(), false, annotated.allNull());
    }

    private static byte[][] utf8() {
        return new byte[][] { bytes("ada"), bytes("alan"), bytes(""), bytes("grace") };
    }

    private static byte[][] wkb() {
        return new byte[][] { WKB_POINT, WKB_POINT };
    }

    /// Four distinct fixed-length values, each a run of one byte value, so the same generator
    /// serves `UUID`, `INTERVAL` and the fixed `DECIMAL` alike.
    private static byte[][] fill(int length) {
        byte[][] values = new byte[4][];
        for (int i = 0; i < values.length; i++) {
            values[i] = new byte[length];
            Arrays.fill(values[i], (byte) (i + 1));
        }
        return values;
    }

    /// Four `FLOAT16` values — `+0.0`, `1.0`, `2.0`, `-1.0`.
    ///
    /// None is `NaN`, and the extremes are `-1.0` and `2.0` rather than a zero, so neither the
    /// `NaN` exclusion nor the signed-zero normalization of a zero bound enters here; both are
    /// covered on the `FLOAT` and `DOUBLE` columns of [WriterInteropTest].
    private static byte[][] float16() {
        return new byte[][] { half(0.0f), half(1.0f), half(2.0f), half(-1.0f) };
    }

    /// One half-precision value as the **little-endian** 2-byte pattern the format stores, so
    /// `1.0` (`0x3c00`) is the bytes `00 3c` and not `3c 00`. Derived rather than written as a
    /// literal: big-endian patterns all decode to near-zero subnormals of the same sign, which
    /// leaves the `FLOAT16` row unable to tell the float16 order from an unsigned byte comparison.
    private static byte[] half(float value) {
        short bits = Float.floatToFloat16(value);
        return new byte[] { (byte) bits, (byte) (bits >>> 8) };
    }

    private static byte[] json(String value) {
        return bytes("{\"n\":" + value + "}");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
