/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import dev.hardwood.internal.conversion.LogicalTypeConverter;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.ParquetReadException;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Float16LogicalTypeTest {

    private static final Path FILE = Paths.get("src/test/resources/float16_logical_type_test.parquet");

    /// Offset of `duration`'s `LogicalType` union variant in `interval_logical_type_test.parquet`.
    private static final int LOGICAL_TYPE_VARIANT_BYTE = 187;

    // Row 0: 0.0
    // Row 1: 1.0
    // Row 2: -1.5
    // Row 3: 65504.0  (max finite binary16)
    // Row 4: +Infinity
    // Row 5: NaN
    // Row 6: null

    private ColumnSchema halfColumn;
    private int halfIdx;
    private final List<Float> values = new ArrayList<>();

    @BeforeAll
    void readAll() throws IOException {
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rowReader = fileReader.rowReader()) {
            halfColumn = fileReader.getFileSchema().getColumn("half");
            halfIdx = halfColumn.columnIndex();
            while (rowReader.hasNext()) {
                rowReader.next();
                values.add(rowReader.isNull("half") ? null : rowReader.getFloat("half"));
            }
        }
    }

    @Test
    void testSchemaReportsFloat16LogicalTypeOnFixedLenByteArray() {
        assertThat(halfColumn.type()).isEqualTo(PhysicalType.FIXED_LEN_BYTE_ARRAY);
        assertThat(halfColumn.typeLength()).isEqualTo(2);
        assertThat(halfColumn.logicalType()).isInstanceOf(LogicalType.Float16Type.class);
    }

    @Test
    void testGetFloatReturnsDecodedValuesForFloat16Column() {
        assertThat(values).hasSize(7);
        assertThat(values.get(0)).isEqualTo(0.0f);
        assertThat(values.get(1)).isEqualTo(1.0f);
        assertThat(values.get(2)).isEqualTo(-1.5f);
        assertThat(values.get(3)).isEqualTo(65504.0f);
        assertThat(values.get(4)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(values.get(5)).isNotNull();
        assertThat(Float.isNaN(values.get(5))).isTrue();
        assertThat(values.get(6)).isNull();
    }

    @Test
    void testGetFloatByIndexReturnsSameValueForFloat16Column() throws IOException {
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rowReader = fileReader.rowReader()) {
            rowReader.next();
            assertThat(rowReader.getFloat(halfIdx)).isEqualTo(0.0f);
        }
    }

    /// Primitive accessor convention: NPE when the field is null, just like
    /// `getInt`/`getLong`/`getDouble`/`getBoolean`.
    @Test
    void testGetFloatThrowsNpeOnNullFloat16Value() throws IOException {
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rowReader = fileReader.rowReader()) {
            for (int i = 0; i < 7; i++) {
                rowReader.next();
            }
            assertThatThrownBy(() -> rowReader.getFloat("half"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void testConvertToFloat16RejectsWrongPhysicalType() {
        assertThatThrownBy(() ->
                LogicalTypeConverter.convertToFloat16(0L, PhysicalType.INT64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FIXED_LEN_BYTE_ARRAY");
    }

    @Test
    void testConvertToFloat16RejectsWrongByteLength() {
        assertThatThrownBy(() ->
                LogicalTypeConverter.convertToFloat16(new byte[4], PhysicalType.FIXED_LEN_BYTE_ARRAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 bytes");
    }

    /// `getFloat` on a non-FLOAT column whose physical type is FLBA but is not
    /// a half-precision payload (here: FLBA(12) annotated INTERVAL) is the caller
    /// asking a column for a type it does not hold, so it keeps
    /// `IllegalArgumentException` and names no file — the file is not at fault — and
    /// says what the column actually is rather than the width FLOAT16 would need.
    @Test
    void testGetFloatOnNonFloat16FlbaColumnRaisesIllegalArgumentException() throws IOException {
        Path intervalFile = Paths.get("src/test/resources/interval_logical_type_test.parquet");
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(intervalFile));
             RowReader rowReader = fileReader.rowReader()) {
            rowReader.next();
            assertThatThrownBy(() -> rowReader.getFloat("duration"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Column 'duration' is FIXED_LEN_BYTE_ARRAY annotated INTERVAL,"
                            + " which cannot be read as a float");
        }
    }

    /// `FilterPredicate.gt(col, 0.5f)` against a FLOAT16 column dispatches in the
    /// resolver to a `Float16Predicate`, which decodes the 2-byte payload before
    /// comparing. From the caller's perspective the call is identical to filtering
    /// a physical FLOAT column.
    @Test
    void testFloatPredicateOnFloat16ColumnFiltersByDecodedValue() throws IOException {
        // half values: 0.0, 1.0, -1.5, 65504.0, +Inf, NaN, null.
        // Float.compare orders NaN after all finite values and +Inf, and treats
        // +0.0 > -0.0; with `gt 0.5f` we keep 1.0, 65504.0, +Inf, NaN — null drops.
        List<Float> kept = new ArrayList<>();
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rowReader = fileReader.buildRowReader()
                     .filter(FilterPredicate.gt("half", 0.5f))
                     .build()) {
            while (rowReader.hasNext()) {
                rowReader.next();
                kept.add(rowReader.getFloat("half"));
            }
        }
        assertThat(kept).hasSize(4);
        assertThat(kept.get(0)).isEqualTo(1.0f);
        assertThat(kept.get(1)).isEqualTo(65504.0f);
        assertThat(kept.get(2)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(Float.isNaN(kept.get(3))).isTrue();
    }

    /// `getFloat` must work through the record-matcher
    /// delegating wrapper installed by `buildRowReader().filter(...)`.
    @Test
    void testGetFloatThroughRecordMatcher() throws IOException {
        // id=1..7 maps to half=0.0, 1.0, -1.5, 65504.0, +Inf, NaN, null. With id>3
        // the filtered reader yields rows id=4..7.
        List<Float> filtered = new ArrayList<>();
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rowReader = fileReader.buildRowReader()
                     .filter(FilterPredicate.gt("id", 3))
                     .build()) {
            while (rowReader.hasNext()) {
                rowReader.next();
                filtered.add(rowReader.isNull("half") ? null : rowReader.getFloat("half"));
            }
        }
        assertThat(filtered).hasSize(4);
        assertThat(filtered.get(0)).isEqualTo(65504.0f);
        assertThat(filtered.get(1)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(filtered.get(2)).isNotNull();
        assertThat(Float.isNaN(filtered.get(2))).isTrue();
        assertThat(filtered.get(3)).isNull();
    }

    /// Byte 187 is the `LogicalType` union variant on `duration`, a `FIXED_LEN_BYTE_ARRAY(12)`
    /// column the file annotates `INTERVAL` (variant 9). Relabelling it `FLOAT16` (variant 15)
    /// leaves the values untouched and the annotation contradicting the width.
    ///
    /// The offsets are properties of the checked-in bytes, so regenerating the fixture moves
    /// them; the test then fails with what it did produce, which is what a new offset is
    /// derived from.
    ///
    /// The file contradicts itself and it is the file that is wrong, not the caller — who
    /// asked a `FLOAT16` column for a float, exactly what it claims to be. So the failure is
    /// a [ParquetReadException] naming the file and the column rather than an
    /// `IllegalArgumentException` about a byte count.
    ///
    /// And only the accessor that converts raises it. The file opens, the schema reads, and
    /// the column still yields its raw bytes: a reader that rejected the file outright would
    /// refuse one that another reader can serve.
    @Test
    void testAnAnnotationTheWidthContradictsFailsOnlyTheConvertingAccessor() throws IOException {
        byte[] relabelled = Files.readAllBytes(
                Paths.get("src/test/resources/interval_logical_type_test.parquet"));
        relabelled[LOGICAL_TYPE_VARIANT_BYTE] = (byte) 0xFC;

        try (ParquetFileReader fileReader = ParquetFileReader.open(
                     InputFile.of(ByteBuffer.wrap(relabelled)));
             RowReader rowReader = fileReader.rowReader()) {
            ColumnSchema duration = fileReader.getFileSchema().getColumn("duration");
            assertThat(duration.logicalType()).isInstanceOf(LogicalType.Float16Type.class);
            assertThat(duration.typeLength()).isEqualTo(12);

            rowReader.next();
            assertThat(rowReader.getBinary("duration")).hasSize(12);

            assertThatThrownBy(() -> rowReader.getFloat("duration"))
                    .isInstanceOf(ParquetReadException.class)
                    .hasMessage("[<memory>] Column 'duration': FLOAT16 is exactly 2 bytes,"
                            + " but the column declares 12");

            // getValue converts too, and says the same thing — there it is the column's
            // leaf kind rather than a check of its own.
            assertThatThrownBy(() -> rowReader.getValue("duration"))
                    .isInstanceOf(ParquetReadException.class)
                    .hasMessage("[<memory>] Column 'duration': FLOAT16 is exactly 2 bytes,"
                            + " but the column declares 12");
        }
    }

    /// The nested reader answers a caller the same way the flat one does. The two decide it
    /// from the same facts by different routes — the flat reader per projected column, the
    /// nested view per primitive — so a test that only exercised one would not notice them
    /// drifting apart.
    @Test
    void testNestedReaderRejectsAFloatReadTheSameWay() throws IOException {
        Path nested = Paths.get("src/test/resources/nested_struct_test.parquet");
        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(nested));
             RowReader rowReader = fileReader.rowReader()) {
            rowReader.next();
            assertThatThrownBy(() -> rowReader.getFloat("id"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Column 'id' is INT32, which cannot be read as a float");
        }
    }
}
