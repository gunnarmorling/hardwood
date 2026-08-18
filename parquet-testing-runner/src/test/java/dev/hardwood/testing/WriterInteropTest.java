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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.testing.InteropCase.Nullability;
import dev.hardwood.testing.ParquetJavaReader.Pages;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.WriterConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// The flat half of the write-path interop gate (`_designs/WRITER_INTEROP_GATE.md`): Hardwood
/// writes a single-column file, parquet-java reads it back through its Group record model, and
/// the values, the null counts and the column-chunk statistics are asserted against the data
/// that was written.
///
/// The matrix varies one axis at a time — repetition, encoding, codec, layout — against a
/// representative base, and sweeps every axis across all seven writable physical types, since
/// the value encoders are per type and that is where an encoding defect lives. Its floor is a
/// single-entry dictionary per physical type, the shape that produced #901: an `RLE_DICTIONARY`
/// index stream with no run header, which Hardwood's own reader and DuckDB both accept and
/// parquet-java rejects.
///
/// The nested shapes are in [WriterNestedInteropTest] and the logical-type annotations in
/// [WriterLogicalTypeInteropTest].
class WriterInteropTest {

    private static final String COLUMN = "v";

    /// Enough rows that a case crosses a page and a row-group boundary at the small targets the
    /// layout axis configures, and small enough that the whole matrix stays quick.
    private static final int MANY_ROWS = 20_000;

    private static final int FEW_ROWS = 2_000;

    // ==================== Axes ====================

    /// The floor of the matrix: a column chunk whose dictionary holds exactly one entry, in every
    /// repetition shape, for every physical type.
    static Stream<InteropCase> singleEntryDictionary() {
        return sweep(List.of(Nullability.values()), (type, nullability) -> InteropCase.of(
                "single-entry dictionary", type, nullability, 1, 200, WriterConfig.defaults()));
    }

    /// The encoding axis: the dictionary in its ordinary multi-entry form, overflowing to a
    /// mid-chunk `PLAIN` fallback, and disabled outright.
    static Stream<InteropCase> encodings() {
        return sweep(List.of(
                new EncodingAxis("multi-entry dictionary", 64, WriterConfig.defaults(), false),
                new EncodingAxis("dictionary overflowing to PLAIN", FEW_ROWS,
                        WriterConfig.builder().dictionaryPageLimitBytes(512).build(), true),
                new EncodingAxis("dictionary disabled", 64,
                        WriterConfig.builder().enableDictionary(false).build(), false)),
                (type, axis) -> new InteropCase(axis.name(), type, Nullability.OPTIONAL_SOME_NULL,
                        axis.distinct(), FEW_ROWS, axis.config(), axis.plainFallback()));
    }

    /// The codec axis, over every codec the writer can produce today. Stage 19 adds the rest and
    /// extends this axis with them.
    static Stream<InteropCase> codecs() {
        return sweep(List.of(CompressionCodec.UNCOMPRESSED, CompressionCodec.ZSTD),
                (type, codec) -> InteropCase.of("codec " + codec, type, Nullability.OPTIONAL_SOME_NULL,
                        64, FEW_ROWS, WriterConfig.builder().codec(codec).build()));
    }

    /// The layout axis: one page, several pages within one row group, and several row groups.
    ///
    /// The single-page value is pinned exactly — 200 rows at the default 1 MiB targets is one
    /// page in one row group for every type — so it establishes the "one page" end of the axis
    /// instead of merely passing a bound any file satisfies. The other two are lower bounds: their
    /// small page and row-group targets are crossed many times over, and how many times is an
    /// artifact of the per-type value width rather than something worth pinning.
    static Stream<LayoutCase> layouts() {
        return sweep(List.of(
                new LayoutAxis("single page", 200, WriterConfig.defaults(), 1, 1, LayoutBound.EXACTLY),
                new LayoutAxis("multiple pages", MANY_ROWS,
                        WriterConfig.builder().pageTargetBytes(1024).build(), 1, 2, LayoutBound.AT_LEAST),
                // The target is well under a BOOLEAN column's buffered bits at this row count,
                // which is the narrowest of the seven types and so sets the bar for all of them.
                new LayoutAxis("multiple row groups", MANY_ROWS, WriterConfig.builder()
                        .pageTargetBytes(1024).rowGroupTargetBytes(1024).build(), 2, 2, LayoutBound.AT_LEAST)),
                (type, axis) -> new LayoutCase(
                        InteropCase.of(axis.name(), type, Nullability.OPTIONAL_SOME_NULL, 64,
                                axis.rows(), axis.config()),
                        axis.rowGroups(), axis.dataPages(), axis.bound()));
    }

    // ==================== Tests ====================

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void singleEntryDictionary(InteropCase testCase, @TempDir Path dir) throws IOException {
        verify(testCase, dir);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void encodings(InteropCase testCase, @TempDir Path dir) throws IOException {
        verify(testCase, dir);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void codecs(InteropCase testCase, @TempDir Path dir) throws IOException {
        verify(testCase, dir);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void layouts(LayoutCase layoutCase, @TempDir Path dir) throws IOException {
        Verified verified = verify(layoutCase.base(), dir);

        int rowGroups = verified.footer().getBlocks().size();
        int dataPages = verified.pages().dataPageCount();
        if (layoutCase.bound() == LayoutBound.EXACTLY) {
            assertThat(rowGroups).as("row groups").isEqualTo(layoutCase.rowGroups());
            assertThat(dataPages).as("data pages").isEqualTo(layoutCase.dataPages());
        }
        else {
            assertThat(rowGroups).as("row groups").isGreaterThanOrEqualTo(layoutCase.rowGroups());
            assertThat(dataPages).as("data pages").isGreaterThanOrEqualTo(layoutCase.dataPages());
        }
    }

    // ==================== The gate ====================

    /// Writes the case's file with Hardwood and asserts everything parquet-java can see about it:
    /// that it reads at all, that every value and null matches what was written, and that the
    /// footer's row count, statistics and encodings do too.
    private Verified verify(InteropCase testCase, Path dir) throws IOException {
        Path file = dir.resolve("written.parquet");
        write(testCase, file);

        assertValues(testCase, ParquetJavaReader.readGroups(file));

        ParquetMetadata footer = ParquetJavaReader.readFooter(file);
        Pages pages = ParquetJavaReader.readPages(file);
        assertRowCount(testCase, footer);
        assertStatistics(testCase, footer);
        assertEncodings(testCase, footer, pages);
        return new Verified(footer, pages);
    }

    private void write(InteropCase testCase, Path file) throws IOException {
        FileSchema schema = testCase.type()
                .declare(FileSchema.builder("interop"), COLUMN, testCase.nullability().repetitionType())
                .build();

        try (ParquetFileWriter writer = ParquetFileWriter.create(OutputFile.of(file), schema, testCase.config())) {
            writer.writeBatch(batch -> testCase.type().set(batch, COLUMN, testCase));
        }
    }

    /// Every row parquet-java produces carries the value that was written, and a null row is
    /// absent from its group rather than carrying a stand-in value.
    private void assertValues(InteropCase testCase, List<Group> rows) {
        assertThat(rows).as("row count").hasSize(testCase.rows());
        for (int r = 0; r < rows.size(); r++) {
            Group row = rows.get(r);
            int field = row.getType().getFieldIndex(COLUMN);
            if (testCase.isNull(r)) {
                assertThat(row.getFieldRepetitionCount(field)).as("row %d is null", r).isZero();
            }
            else {
                assertThat(row.getFieldRepetitionCount(field)).as("row %d is present", r).isOne();
                assertThat(testCase.type().read(row, field)).as("row %d value", r)
                        .isEqualTo(testCase.type().value(testCase.ordinal(r)));
            }
        }
    }

    private void assertRowCount(InteropCase testCase, ParquetMetadata footer) {
        long rows = footer.getBlocks().stream().mapToLong(BlockMetaData::getRowCount).sum();
        assertThat(rows).as("footer row count").isEqualTo(testCase.rows());
    }

    /// The column-chunk statistics parquet-java exposes agree with the written data. It builds
    /// them with its own comparator, derived from the column's type and the footer's
    /// `column_orders`, so agreement here is a cross-implementation check on the sort order the
    /// bounds were computed in — not merely on the bytes they were stored as.
    ///
    /// Bounds are folded over the row groups, so the assertion holds whatever layout the case
    /// produced.
    private void assertStatistics(InteropCase testCase, ParquetMetadata footer) {
        long nulls = 0;
        Comparable<Object> min = null;
        Comparable<Object> max = null;
        for (ColumnChunkMetaData chunk : chunks(footer)) {
            Statistics<?> statistics = chunk.getStatistics();
            nulls += statistics.getNumNulls();
            if (!statistics.hasNonNullValue()) {
                continue;
            }
            min = extreme(min, statistics.genericGetMin(), true);
            max = extreme(max, statistics.genericGetMax(), false);
        }

        assertThat(nulls).as("null count").isEqualTo(testCase.nullCount());
        assertThat(min).as("min").isEqualTo(testCase.type().expectedMin(testCase));
        assertThat(max).as("max").isEqualTo(testCase.type().expectedMax(testCase));
    }

    /// The case actually produced the encoding it exists to cover, so a change in the writer's
    /// encoding choice becomes a failure rather than a silent loss of coverage.
    ///
    /// The discriminating assertion is on the *page* value encodings, not the chunk's `encodings`
    /// list: that list always contains `PLAIN`, because a dictionary page body is itself `PLAIN`,
    /// so it cannot tell a dictionary-only chunk from one that overflowed and fell back mid-chunk.
    /// Each page declares its own encoding, and those separate the two.
    private void assertEncodings(InteropCase testCase, ParquetMetadata footer, Pages pages) {
        for (ColumnChunkMetaData chunk : chunks(footer)) {
            assertThat(chunk.getEncodings()).as("declared chunk encodings")
                    .matches(e -> e.contains(Encoding.RLE_DICTIONARY) == testCase.expectsDictionary(),
                            testCase.expectsDictionary() ? "contains RLE_DICTIONARY" : "has no RLE_DICTIONARY");
        }
        assertThat(pages.valueEncodings()).as("page value encodings")
                .containsExactlyInAnyOrderElementsOf(expectedPageEncodings(testCase));
    }

    /// The value encodings the case's data pages must declare. A dictionary-encoded chunk emits
    /// `RLE_DICTIONARY` pages throughout; one that overflows its dictionary emits `PLAIN` pages
    /// from the fallback on, so both appear; and a chunk with no dictionary at all — dictionary
    /// disabled, a `BOOLEAN` column, or one with no present value to intern — is `PLAIN` only.
    private static List<Encoding> expectedPageEncodings(InteropCase testCase) {
        if (!testCase.expectsDictionary()) {
            return List.of(Encoding.PLAIN);
        }
        return testCase.expectsPlainFallback()
                ? List.of(Encoding.RLE_DICTIONARY, Encoding.PLAIN)
                : List.of(Encoding.RLE_DICTIONARY);
    }

    private static List<ColumnChunkMetaData> chunks(ParquetMetadata footer) {
        List<ColumnChunkMetaData> chunks = new ArrayList<>();
        for (BlockMetaData block : footer.getBlocks()) {
            chunks.addAll(block.getColumns());
        }
        return chunks;
    }

    /// Folds one row group's bound into the running one, in parquet-java's own comparison order
    /// for the type.
    @SuppressWarnings("unchecked")
    private static Comparable<Object> extreme(Comparable<Object> current, Comparable<?> candidate, boolean min) {
        Comparable<Object> other = (Comparable<Object>) candidate;
        if (current == null) {
            return other;
        }
        int order = other.compareTo(current);
        return (min ? order < 0 : order > 0) ? other : current;
    }

    // ==================== Matrix plumbing ====================

    /// The cross product of every physical type with one axis's values.
    private static <A, C> Stream<C> sweep(List<A> axis, BiFunction<TypeFixture, A, C> factory) {
        return Stream.of(TypeFixture.values())
                .flatMap(type -> axis.stream().map(value -> factory.apply(type, value)));
    }

    private record EncodingAxis(String name, int distinct, WriterConfig config, boolean plainFallback) {
    }

    private record LayoutAxis(String name, int rows, WriterConfig config, int rowGroups, int dataPages,
            LayoutBound bound) {
    }

    /// Whether a layout case's row-group and page counts are the exact numbers the configuration
    /// produces, or the floor it must clear.
    enum LayoutBound {
        EXACTLY,
        AT_LEAST
    }

    /// A flat case plus the layout the writer configuration was sized to produce.
    record LayoutCase(InteropCase base, int rowGroups, int dataPages, LayoutBound bound) {
        @Override
        public String toString() {
            return base.toString();
        }
    }

    private record Verified(ParquetMetadata footer, Pages pages) {
    }
}
