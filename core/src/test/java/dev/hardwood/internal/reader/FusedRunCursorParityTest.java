/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.Validity;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.ReaderConfig;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThat;

/// Oracle: fused run-cursor path (`hardwood.cursor-decode=true`) matches the
/// materializing path (`false`) on flat dictionary-encoded columns.
///
/// Fixtures are dictionary-encoded (the fused gate requires
/// `RLE_DICTIONARY` / `PLAIN_DICTIONARY`). PLAIN-only columns are out of scope
/// for this oracle — both configs would take the materializing path.
///
/// Compares full columns (values, string interning, and validity), not batch
/// boundaries, so batch policy differences cannot mask or invent failures.
/// Exercises both [ColumnReader] and [FlatRowReader], filters (producing multi-interval
/// [PageRowMask]s for cursor skipping), and maxRows mid-page clamping.
class FusedRunCursorParityTest {

    private static final Path YELLOW_TRIPDATA = Path.of("src/test/resources/yellow_tripdata_sample.parquet");
    private static final Path TINY_PAGES = Path.of("src/test/resources/run_cursor_tiny_pages.parquet");
    private static final Path REQUIRED_DICT = Path.of("src/test/resources/run_cursor_required_dict.parquet");
    /// DataPageV2 mirror of {@link #TINY_PAGES}: exercises the def+index fused V2 branch
    /// ({@code readValueRegion} extracts the value region from the page body).
    private static final Path TINY_PAGES_V2 = Path.of("src/test/resources/run_cursor_tiny_pages_v2.parquet");
    /// DataPageV2 mirror of {@link #REQUIRED_DICT}: exercises the index-only fused V2 branch
    /// ({@code readValueRegion} + {@code valuesData[0]} as the bit-width byte).
    private static final Path REQUIRED_DICT_V2 = Path.of("src/test/resources/run_cursor_required_dict_v2.parquet");

    private static final ReaderConfig CURSOR_OFF = ReaderConfig.builder()
            .option("hardwood.cursor-decode", "false")
            .build();
    private static final ReaderConfig CURSOR_ON = ReaderConfig.builder()
            .option("hardwood.cursor-decode", "true")
            .build();

    static Stream<Arguments> oracleFixtures() {
        return Stream.of(
                // Multi-type optional dict smoke (real-world sample).
                Arguments.of(YELLOW_TRIPDATA, 1024),
                // Tiny uncompressed dict pages: cursor byte ownership + bit-width 0.
                Arguments.of(TINY_PAGES, 64),
                Arguments.of(TINY_PAGES, 1024),
                // Required dict, all physical types, low/high card, single-entry.
                Arguments.of(REQUIRED_DICT, 64),
                Arguments.of(REQUIRED_DICT, 1024),
                // DataPageV2 mirrors: cover readValueRegion + the V2 fused branches.
                Arguments.of(TINY_PAGES_V2, 64),
                Arguments.of(TINY_PAGES_V2, 1024),
                Arguments.of(REQUIRED_DICT_V2, 64),
                Arguments.of(REQUIRED_DICT_V2, 1024));
    }

    static Stream<Arguments> filteredFixtures() {
        return Stream.of(
                Arguments.of(TINY_PAGES, 64, FilterPredicate.gtEq("lowcard_int", 2)),
                Arguments.of(TINY_PAGES_V2, 64, FilterPredicate.gtEq("lowcard_int", 2)),
                Arguments.of(REQUIRED_DICT, 128, FilterPredicate.and(
                        FilterPredicate.gtEq("int32_low_card", 15),
                        FilterPredicate.ltEq("int32_low_card", 25))),
                Arguments.of(REQUIRED_DICT_V2, 128, FilterPredicate.and(
                        FilterPredicate.gtEq("int32_low_card", 15),
                        FilterPredicate.ltEq("int32_low_card", 25)))
        );
    }

    static Stream<Arguments> maxRowsFixtures() {
        return Stream.of(
                Arguments.of(TINY_PAGES, 64, 37L),
                Arguments.of(TINY_PAGES, 64, 500L),
                Arguments.of(REQUIRED_DICT, 128, 1234L),
                Arguments.of(REQUIRED_DICT_V2, 128, 1234L)
        );
    }

    @ParameterizedTest(name = "{0} batchSize={1}")
    @MethodSource("oracleFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fusedMatchesMaterializing(Path file, int batchSize) throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            FileSchema schema;
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, CURSOR_OFF)) {
                schema = reader.getFileSchema();
            }

            for (int colIdx = 0; colIdx < schema.getColumnCount(); colIdx++) {
                ColumnSchema column = schema.getColumn(colIdx);
                if (column.maxRepetitionLevel() > 0) {
                    continue;
                }

                ColumnSnapshot materializing = readColumn(file, colIdx, batchSize, context, CURSOR_OFF);
                ColumnSnapshot fused = readColumn(file, colIdx, batchSize, context, CURSOR_ON);
                assertSnapshotsEqual(column, materializing, fused);
            }
        }
    }

    @ParameterizedTest(name = "RowReader {0} batchSize={1}")
    @MethodSource("oracleFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fusedMatchesMaterializingRowReader(Path file, int batchSize) throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            FileSchema schema;
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, CURSOR_OFF)) {
                schema = reader.getFileSchema();
            }

            for (int colIdx = 0; colIdx < schema.getColumnCount(); colIdx++) {
                ColumnSchema column = schema.getColumn(colIdx);
                if (column.maxRepetitionLevel() > 0) {
                    continue;
                }

                RowSnapshot materializing = readRowReader(file, colIdx, context, CURSOR_OFF);
                RowSnapshot fused = readRowReader(file, colIdx, context, CURSOR_ON);
                assertRowSnapshotsEqual(column, materializing, fused);
            }
        }
    }

    @ParameterizedTest(name = "Filter {0} batchSize={1}")
    @MethodSource("filteredFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fusedMatchesMaterializingWithFilter(Path file, int batchSize, FilterPredicate filter) throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            FileSchema schema;
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, CURSOR_OFF)) {
                schema = reader.getFileSchema();
            }

            for (int colIdx = 0; colIdx < schema.getColumnCount(); colIdx++) {
                ColumnSchema column = schema.getColumn(colIdx);
                if (column.maxRepetitionLevel() > 0) {
                    continue;
                }

                ColumnSnapshot materializing = readColumnWithFilter(file, colIdx, batchSize, context, CURSOR_OFF, filter);
                ColumnSnapshot fused = readColumnWithFilter(file, colIdx, batchSize, context, CURSOR_ON, filter);
                assertSnapshotsEqual(column, materializing, fused);
            }
        }
    }

    @ParameterizedTest(name = "MaxRows {0} batchSize={1} maxRows={2}")
    @MethodSource("maxRowsFixtures")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fusedMatchesMaterializingWithMaxRows(Path file, int batchSize, long maxRows) throws Exception {
        try (HardwoodContext context = HardwoodContext.create()) {
            FileSchema schema;
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, CURSOR_OFF)) {
                schema = reader.getFileSchema();
            }

            for (int colIdx = 0; colIdx < schema.getColumnCount(); colIdx++) {
                ColumnSchema column = schema.getColumn(colIdx);
                if (column.maxRepetitionLevel() > 0) {
                    continue;
                }

                RowSnapshot materializing = readRowReaderWithMaxRows(file, colIdx, context, CURSOR_OFF, maxRows);
                RowSnapshot fused = readRowReaderWithMaxRows(file, colIdx, context, CURSOR_ON, maxRows);
                assertRowSnapshotsEqual(column, materializing, fused);
            }
        }
    }

    private static ColumnSnapshot readColumn(Path file, int colIdx, int batchSize,
                                              HardwoodContext context, ReaderConfig config) throws Exception {
        List<BatchSlice> batches = new ArrayList<>();
        int totalRecords = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, config);
             ColumnReader col = reader.buildColumnReader(colIdx).batchSize(batchSize).build()) {
            ColumnSchema column = col.getColumnSchema();
            while (col.nextBatch()) {
                int n = col.getValueCount();
                totalRecords += n;
                batches.add(captureBatch(col, column, n));
            }
        }
        return new ColumnSnapshot(totalRecords, batches);
    }

    private static ColumnSnapshot readColumnWithFilter(Path file, int colIdx, int batchSize,
                                                       HardwoodContext context, ReaderConfig config,
                                                       FilterPredicate filter) throws Exception {
        List<BatchSlice> batches = new ArrayList<>();
        int totalRecords = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, config);
             ColumnReader col = reader.buildColumnReader(colIdx).filter(filter).batchSize(batchSize).build()) {
            ColumnSchema column = col.getColumnSchema();
            while (col.nextBatch()) {
                int n = col.getValueCount();
                totalRecords += n;
                batches.add(captureBatch(col, column, n));
            }
        }
        return new ColumnSnapshot(totalRecords, batches);
    }

    private static RowSnapshot readRowReader(Path file, int colIdx, HardwoodContext context, ReaderConfig config) throws Exception {
        return readRowReaderWithMaxRows(file, colIdx, context, config, 0);
    }

    private static RowSnapshot readRowReaderWithMaxRows(Path file, int colIdx, HardwoodContext context, ReaderConfig config, long maxRows) throws Exception {
        List<Boolean> nulls = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file), context, config)) {
            ColumnSchema column = reader.getFileSchema().getColumn(colIdx);
            ParquetFileReader.RowReaderBuilder builder = reader.buildRowReader()
                    .projection(ColumnProjection.columns(column.name()));
            if (maxRows > 0) {
                builder.head(maxRows);
            }
            try (RowReader rowReader = builder.build()) {
                while (rowReader.hasNext()) {
                    rowReader.next();
                    boolean isNull = rowReader.isNull(0);
                    nulls.add(isNull);
                    if (!isNull) {
                        Object val = switch (column.type()) {
                            case INT32 -> rowReader.getInt(0);
                            case INT64 -> rowReader.getLong(0);
                            case FLOAT -> rowReader.getFloat(0);
                            case DOUBLE -> rowReader.getDouble(0);
                            case BOOLEAN -> rowReader.getBoolean(0);
                            case BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY, INT96 -> rowReader.getString(0);
                            default -> throw new UnsupportedOperationException("Unsupported type: " + column.type());
                        };
                        values.add(val);
                    } else {
                        values.add(null);
                    }
                }
            }
        }
        return new RowSnapshot(nulls, values);
    }

    private static BatchSlice captureBatch(ColumnReader col, ColumnSchema column, int n) {
        Validity validity = col.getLeafValidity();
        boolean[] nulls = null;
        if (validity.hasNulls()) {
            nulls = new boolean[n];
            for (int i = 0; i < n; i++) {
                nulls[i] = validity.isNull(i);
            }
        }

        Object values = switch (column.type()) {
            case INT32 -> Arrays.copyOf(col.getInts(), n);
            case INT64 -> Arrays.copyOf(col.getLongs(), n);
            case FLOAT -> Arrays.copyOf(col.getFloats(), n);
            case DOUBLE -> Arrays.copyOf(col.getDoubles(), n);
            case BOOLEAN -> Arrays.copyOf(col.getBooleans(), n);
            case BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY, INT96 -> {
                byte[] bytes = col.getBinaryValues();
                int[] offsets = col.getBinaryOffsets();
                int end = offsets[n];
                String[] strings = Arrays.copyOf(col.getStrings(), n);
                yield new BinarySlice(Arrays.copyOf(bytes, end), Arrays.copyOf(offsets, n + 1), strings);
            }
            default -> throw new UnsupportedOperationException("Unsupported type: " + column.type());
        };
        return new BatchSlice(n, nulls, values);
    }

    private static void assertSnapshotsEqual(ColumnSchema column, ColumnSnapshot mat, ColumnSnapshot fused) {
        assertThat(fused.totalRecords)
                .as("record count for %s", column.name())
                .isEqualTo(mat.totalRecords);

        int matPos = 0;
        int fusedPos = 0;
        int matBatch = 0;
        int fusedBatch = 0;
        int matOffset = 0;
        int fusedOffset = 0;

        for (int global = 0; global < mat.totalRecords; global++) {
            while (matOffset >= mat.batches.get(matBatch).recordCount) {
                matOffset = 0;
                matBatch++;
            }
            while (fusedOffset >= fused.batches.get(fusedBatch).recordCount) {
                fusedOffset = 0;
                fusedBatch++;
            }

            BatchSlice m = mat.batches.get(matBatch);
            BatchSlice f = fused.batches.get(fusedBatch);

            boolean matNull = m.nulls != null && m.nulls[matOffset];
            boolean fusedNull = f.nulls != null && f.nulls[fusedOffset];
            assertThat(fusedNull)
                    .as("nullability at global %d for %s (mat batch %d/%d, fused batch %d/%d)",
                            global, column.name(), matBatch, matOffset, fusedBatch, fusedOffset)
                    .isEqualTo(matNull);

            if (!matNull) {
                assertValueEqual(column, m.values, matOffset, f.values, fusedOffset, global);
            }

            matOffset++;
            fusedOffset++;
            matPos++;
            fusedPos++;
        }
        assertThat(matPos).isEqualTo(mat.totalRecords);
        assertThat(fusedPos).isEqualTo(fused.totalRecords);
    }

    private static void assertRowSnapshotsEqual(ColumnSchema column, RowSnapshot mat, RowSnapshot fused) {
        assertThat(fused.nulls.size())
                .as("row count for %s", column.name())
                .isEqualTo(mat.nulls.size());

        for (int i = 0; i < mat.nulls.size(); i++) {
            assertThat(fused.nulls.get(i))
                    .as("nullability at row %d for %s", i, column.name())
                    .isEqualTo(mat.nulls.get(i));

            if (!mat.nulls.get(i)) {
                if (column.type() == dev.hardwood.metadata.PhysicalType.FLOAT) {
                    assertThat(Float.floatToRawIntBits((Float) fused.values.get(i)))
                            .as("FLOAT bits at row %d for %s", i, column.name())
                            .isEqualTo(Float.floatToRawIntBits((Float) mat.values.get(i)));
                } else if (column.type() == dev.hardwood.metadata.PhysicalType.DOUBLE) {
                    assertThat(Double.doubleToRawLongBits((Double) fused.values.get(i)))
                            .as("DOUBLE bits at row %d for %s", i, column.name())
                            .isEqualTo(Double.doubleToRawLongBits((Double) mat.values.get(i)));
                } else {
                    assertThat(fused.values.get(i))
                            .as("value at row %d for %s", i, column.name())
                            .isEqualTo(mat.values.get(i));
                }
            }
        }
    }

    private static void assertValueEqual(ColumnSchema column, Object matVals, int matI,
                                         Object fusedVals, int fusedI, int global) {
        switch (column.type()) {
            case INT32 -> assertThat(((int[]) fusedVals)[fusedI])
                    .as("INT32 at %d for %s", global, column.name())
                    .isEqualTo(((int[]) matVals)[matI]);
            case INT64 -> assertThat(((long[]) fusedVals)[fusedI])
                    .as("INT64 at %d for %s", global, column.name())
                    .isEqualTo(((long[]) matVals)[matI]);
            case FLOAT -> assertThat(Float.floatToRawIntBits(((float[]) fusedVals)[fusedI]))
                    .as("FLOAT bits at %d for %s", global, column.name())
                    .isEqualTo(Float.floatToRawIntBits(((float[]) matVals)[matI]));
            case DOUBLE -> assertThat(Double.doubleToRawLongBits(((double[]) fusedVals)[fusedI]))
                    .as("DOUBLE bits at %d for %s", global, column.name())
                    .isEqualTo(Double.doubleToRawLongBits(((double[]) matVals)[matI]));
            case BOOLEAN -> assertThat(((boolean[]) fusedVals)[fusedI])
                    .as("BOOLEAN at %d for %s", global, column.name())
                    .isEqualTo(((boolean[]) matVals)[matI]);
            case BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY, INT96 -> {
                BinarySlice m = (BinarySlice) matVals;
                BinarySlice f = (BinarySlice) fusedVals;
                int mStart = m.offsets[matI];
                int mLen = m.offsets[matI + 1] - mStart;
                int fStart = f.offsets[fusedI];
                int fLen = f.offsets[fusedI + 1] - fStart;
                assertThat(fLen)
                        .as("binary length at %d for %s", global, column.name())
                        .isEqualTo(mLen);
                assertThat(Arrays.copyOfRange(f.bytes, fStart, fStart + fLen))
                        .as("binary bytes at %d for %s", global, column.name())
                        .containsExactly(Arrays.copyOfRange(m.bytes, mStart, mStart + mLen));
                assertThat(f.strings[fusedI])
                        .as("stringAt/getStrings at %d for %s", global, column.name())
                        .isEqualTo(m.strings[matI]);
            }
            default -> throw new UnsupportedOperationException("Unsupported type: " + column.type());
        }
    }

    private record BinarySlice(byte[] bytes, int[] offsets, String[] strings) {
    }

    private record BatchSlice(int recordCount, boolean[] nulls, Object values) {
    }

    private record ColumnSnapshot(int totalRecords, List<BatchSlice> batches) {
    }

    private record RowSnapshot(List<Boolean> nulls, List<Object> values) {
    }
}
