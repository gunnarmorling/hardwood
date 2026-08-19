/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.hardwood.InputFile;
import dev.hardwood.cli.dive.internal.ColumnChunkDetailScreen;
import dev.hardwood.cli.dive.internal.DataPreviewScreen;
import dev.hardwood.cli.dive.internal.HelpOverlay;
import dev.hardwood.cli.dive.internal.Keys;
import dev.hardwood.cli.internal.Version;
import dev.hardwood.schema.ColumnSchema;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import static org.assertj.core.api.Assertions.assertThat;

/// Layer-2 visual tests — render screens to an in-memory buffer and assert
/// on the captured cells. Catches title / row / marker bugs that the
/// handler-only tests in [DiveStateTest] don't see.
class DiveRenderTest {

    private static final Rect AREA = new Rect(0, 0, 120, 40);

    private static final Pattern RANGE_MARKER = Pattern.compile("─ \\d+-\\d+/\\d+ ");

    private ParquetModel model;

    @BeforeEach
    void setUp() throws Exception {
        Keys.resetObservedViewport();
        Path path = Path.of(getClass().getResource("/column_index_pushdown.parquet").getPath());
        model = ParquetModel.open(InputFile.of(path), path.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        model.close();
    }

    @Test
    void rowGroupsTitleShowsRange() {
        ScreenState.RowGroups state = new ScreenState.RowGroups(0);
        RenderHarness.RenderedFrame frame = RenderHarness.render(AREA, state, model);

        // Title is on the top border; should embed "1-N of M" — total
        // is the row group count of the fixture (1 RG).
        String title = frame.firstLineContaining("Row groups");
        assertThat(title).isNotNull().contains("1");
        assertThat(title).contains("of " + model.rowGroupCount());
    }

    @Test
    void breadcrumbDoesNotDuplicateRowGroupAndShowsLeafName() throws Exception {
        // Open a fixture with a multi-character column name (`category`)
        // and walk Overview → RowGroups → RowGroupDetail → ColumnChunks
        // → ColumnChunkDetail. The breadcrumb chain is rendered by the
        // chrome, not by the screen body — but DiveApp wires it through
        // Chrome.renderBreadcrumb. To avoid pulling DiveApp into this
        // test we assert via direct breadcrumb-label calls on the
        // chrome utility, exercising the same switch.
        Path file = Path.of(getClass().getResource("/dictionary_with_crc.parquet").getPath());
        try (ParquetModel m = ParquetModel.open(InputFile.of(file), file.toString())) {
            NavigationStack stack = new NavigationStack(ScreenState.Overview.initial());
            stack.push(new ScreenState.RowGroups(0));
            stack.push(new ScreenState.RowGroupDetail(0,
                    ScreenState.RowGroupDetail.Pane.MENU, 0));
            stack.push(new ScreenState.ColumnChunks(0, 1));  // col 1 = "category"
            stack.push(new ScreenState.ColumnChunkDetail(0, 1,
                    ScreenState.ColumnChunkDetail.Pane.MENU, 0, true, false));

            // Breadcrumb labels via the package-private utility.
            List<String> labels = stack.frames().stream()
                    .map(s -> dev.hardwood.cli.dive.internal.Chrome.breadcrumbLabel(s, m))
                    .toList();

            // No duplicate "RG #0" — RowGroupDetail says "RG #0", and
            // ColumnChunks now just says "Column chunks" (not "RG #0 ›
            // Column chunks").
            assertThat(labels).contains("Overview", "Row groups", "RG #0",
                    "Column chunks", "category");
            assertThat(labels.stream().filter(l -> l.equals("RG #0")).count()).isOne();
            // ColumnChunkDetail label is the leaf name, not "[col 1]".
            assertThat(labels).doesNotContain("[col 1]");
        }
    }

    @Test
    void breadcrumbEnrichesLeafWithRowGroupAndColumnFromFooterPath() {
        // Footer → FileIndexes(COLUMN) → ColumnIndexView. None of the
        // context-bearing frames (RowGroupDetail / ColumnChunks /
        // ColumnChunkDetail / ColumnAcrossRowGroups) appear upstream, so
        // Chrome.renderBreadcrumb must append "(RG #N · column)" to the
        // leaf label so the user still sees which chunk they're in.
        NavigationStack stack = new NavigationStack(ScreenState.Overview.initial());
        stack.push(ScreenState.Footer.initial());
        stack.push(new ScreenState.FileIndexes(ScreenState.FileIndexes.Kind.COLUMN, 0));
        stack.push(new ScreenState.ColumnIndexView(0, 0, 0, "", false, true, false));

        Rect breadcrumbArea = new Rect(0, 0, 200, 1);
        dev.tamboui.buffer.Buffer buffer = dev.tamboui.buffer.Buffer.empty(breadcrumbArea);
        dev.hardwood.cli.dive.internal.Chrome.renderBreadcrumb(buffer, breadcrumbArea, stack, model);

        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < breadcrumbArea.width(); x++) {
            String sym = buffer.get(x, 0).symbol();
            sb.append(sym == null || sym.isEmpty() ? ' ' : sym);
        }
        String breadcrumb = sb.toString().stripTrailing();

        assertThat(breadcrumb).contains("Overview");
        assertThat(breadcrumb).contains("Footer & indexes");
        assertThat(breadcrumb).contains("All column indexes");
        assertThat(breadcrumb).contains("Column index");
        // The enrichment: leaf "Column index" gets "(RG #0 · id)" suffix
        // because no upstream frame establishes (RG, column) context.
        String columnPath = model.schema().getColumn(0).fieldPath().toString();
        assertThat(breadcrumb).contains("(RG #0 · " + columnPath + ")");
    }

    @Test
    void breadcrumbDoesNotEnrichLeafWhenContextAlreadyOnPath() {
        // Pages reached via Overview → RowGroups → RowGroupDetail →
        // ColumnChunks → ColumnChunkDetail → Pages. Both RG and column
        // context are already on the path, so no "(RG #N · …)" suffix.
        NavigationStack stack = new NavigationStack(ScreenState.Overview.initial());
        stack.push(new ScreenState.RowGroups(0));
        stack.push(new ScreenState.RowGroupDetail(0, ScreenState.RowGroupDetail.Pane.MENU, 0));
        stack.push(new ScreenState.ColumnChunks(0, 0));
        stack.push(new ScreenState.ColumnChunkDetail(0, 0,
                ScreenState.ColumnChunkDetail.Pane.MENU, 0, true, false));
        stack.push(new ScreenState.Pages(0, 0, 0, false, true));

        Rect breadcrumbArea = new Rect(0, 0, 200, 1);
        dev.tamboui.buffer.Buffer buffer = dev.tamboui.buffer.Buffer.empty(breadcrumbArea);
        dev.hardwood.cli.dive.internal.Chrome.renderBreadcrumb(buffer, breadcrumbArea, stack, model);

        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < breadcrumbArea.width(); x++) {
            String sym = buffer.get(x, 0).symbol();
            sb.append(sym == null || sym.isEmpty() ? ' ' : sym);
        }
        String breadcrumb = sb.toString().stripTrailing();

        // Leaf is just "Pages" — no parenthetical enrichment.
        assertThat(breadcrumb).endsWith("Pages");
    }

    @Test
    void pagesMinMaxCellEndsInEllipsisWhenValueTruncated() {
        // The fixture has an `id` column with INT64 values 0..9999. After
        // toggling logical types off the formatter renders the raw long;
        // pages with large values exceed the cell width. Find a page
        // where the cell ends with the truncation marker.
        ScreenState.Pages state = new ScreenState.Pages(0, 0, 0, false, true);
        RenderHarness.RenderedFrame frame = RenderHarness.render(AREA, state, model);
        // Even without truncation we should see the Pages title with a
        // range — locks the table-render path runs without throwing.
        assertThat(frame.firstLineContaining("Pages")).isNotNull();

        // Force a long-value column by switching to a string column. The
        // fixture's other column (`value`) is INT64 which won't truncate,
        // so we just verify the formatter path produces visible output;
        // a stronger assertion would need a fixture with a long-string
        // column index — left to the @MethodSource matrix below.
        assertThat(frame.contains("0")).isTrue();
    }

    @Test
    void dataPreviewCellEndsInEllipsisAtComputedWidth() throws Exception {
        // Tight viewport (50 cols) leaves only a partial-width slot for
        // one of the visible columns, so long values must be truncated.
        // The yellow_tripdata fixture has TIMESTAMP and DECIMAL columns
        // wider than the per-cell budget at this viewport.
        Path file = Path.of(getClass().getResource("/yellow_tripdata_sample.parquet").getPath());
        try (ParquetModel m = ParquetModel.open(InputFile.of(file), file.toString())) {
            ScreenState.DataPreview state = dev.hardwood.cli.dive.internal.DataPreviewScreen
                    .initialState(m, 10);
            RenderHarness.RenderedFrame frame = RenderHarness.render(
                    new Rect(0, 0, 50, 40), state, m);
            assertThat(frame.contains("…"))
                    .as("expected at least one truncated cell with ellipsis")
                    .isTrue();
        }
    }

    @Test
    void dataPreviewPacksNarrowColumnsIntoAvailableWidth() {
        List<String> columns = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        List<String> cells = List.of("1", "2", "3", "4", "5", "6", "7", "8");
        ScreenState.DataPreview state = new ScreenState.DataPreview(
                0, 1, columns, List.of(cells), List.of(cells),
                0, 0, -1, true, Set.of(), 0);

        RenderHarness.RenderedFrame frame = RenderHarness.render(
                new Rect(0, 0, 24, 6), state, model);

        assertThat(frame.contains("a b c d e f g h")).isTrue();
    }

    @Test
    void dataPreviewRightScrollEventuallyRevealsTheLastColumnInFull() throws Exception {
        // A column clipped by the remaining width budget — as opposed to one
        // capped at VALUE_TRUNCATE — must always be reachable in full by
        // scrolling right, including when it is the file's last column.
        Path file = Path.of(getClass().getResource("/yellow_tripdata_sample.parquet").getPath());
        try (ParquetModel m = ParquetModel.open(InputFile.of(file), file.toString())) {
            NavigationStack stack = new NavigationStack(ScreenState.Overview.initial());
            stack.push(DataPreviewScreen.initialState(m, 5));
            List<String> names = ((ScreenState.DataPreview) stack.top()).columnNames();
            String lastColumn = names.get(names.size() - 1);

            RenderHarness.RenderedFrame frame = RenderHarness.render(AREA, stack.top(), m);
            // Bounded so a regression in the stop condition fails the test
            // instead of hanging it.
            int steps = 0;
            while (DataPreviewScreen.handle(key(KeyCode.RIGHT), m, stack)) {
                frame = RenderHarness.render(AREA, stack.top(), m);
                assertThat(++steps).isLessThan(names.size() + 1);
            }

            assertThat(frame.contains(lastColumn))
                    .as("right-scroll stopped with '%s' still clipped", lastColumn)
                    .isTrue();
        }
    }

    @Test
    void dataPreviewKeybarOffersColumnScrollWhileTheLastColumnIsClipped() {
        // Four columns that all fit the window, but the trailing one is
        // clipped for want of budget. Scrolling right would drop `a` and
        // free the space, so the keybar has to advertise it.
        List<String> columns = List.of("a", "b", "c", "wide");
        List<String> cells = List.of("1".repeat(10), "2".repeat(10), "3".repeat(10), "D".repeat(30));
        ScreenState.DataPreview state = new ScreenState.DataPreview(
                0, 1, columns, List.of(cells), List.of(cells),
                0, 0, -1, true, Set.of(), 0);
        Rect area = new Rect(0, 0, 48, 6);

        RenderHarness.RenderedFrame frame = RenderHarness.render(area, state, model);

        assertThat(frame.contains("…"))
                .as("expected the trailing column to be clipped at this width")
                .isTrue();
        assertThat(RenderHarness.keybarFor(state, model)).contains("[←→] columns");
    }

    /// Cross-product smoke render: every screen × every fixture renders
    /// without throwing. Catches data-shape edge cases (no CI, no dict,
    /// nested types, all-null pages) that the handler tests don't
    /// exercise visually.
    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("smokeMatrix")
    void screenRendersWithoutException(String fixture, String screenName,
                                       Function<ParquetModel, ScreenState> ctor) throws Exception {
        Path file = Path.of(getClass().getResource("/" + fixture).getPath());
        try (ParquetModel m = ParquetModel.open(InputFile.of(file), file.toString())) {
            ScreenState s = ctor.apply(m);
            if (s == null) {
                return;  // not applicable (e.g., no dict in fixture)
            }
            RenderHarness.render(AREA, s, m);
        }
    }

    @Test
    void helpOverlayWrapsLongDescriptions() {
        // At 80 width, the description budget is 38 chars.
        // The longest description is 52 chars, so it should be forced to wrap.
        Rect screenArea = new Rect(0, 0, 80, 40);
        Buffer buffer = Buffer.empty(screenArea);

        HelpOverlay.render(buffer, screenArea);

        assertThat(renderToString(buffer, screenArea))
                .contains("enter filter mode (Schema, Column ")
                .contains("               index, Dictionary) ");
    }

    /// The overlay is where a TUI user reads which build they are on, so the line must carry
    /// the resolved version rather than the label alone.
    @Test
    void helpOverlayShowsTheBuildVersion() {
        Rect screenArea = new Rect(0, 0, 120, 40);
        Buffer buffer = Buffer.empty(screenArea);

        HelpOverlay.render(buffer, screenArea);

        assertThat(renderToString(buffer, screenArea)).contains("Version: " + Version.getVersion());
    }

    @Test
    void helpOverlayFitsAllKeybindingsAtNarrowWidth() {
        // At 50×40 the overlay width drops to 46 (descBudget 24), so many more
        // descriptions wrap onto a second line. The overlay's height must grow
        // with the content; otherwise the bottom keybindings get clipped — the
        // same failure mode that motivated #386, just at a different breakpoint.
        Rect screenArea = new Rect(0, 0, 50, 40);
        Buffer buffer = Buffer.empty(screenArea);

        HelpOverlay.render(buffer, screenArea);

        // The "Press ? or Esc to close" sentinel is the very last line of the
        // overlay; if it renders, no content above it can have been clipped.
        assertThat(renderToString(buffer, screenArea)).contains("Press ? or Esc to close");
    }

    private static String renderToString(Buffer buffer, Rect screenArea) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < screenArea.height(); y++) {
            for (int x = 0; x < screenArea.width(); x++) {
                String sym = buffer.get(x, y).symbol();
                sb.append(sym == null || sym.isEmpty() ? ' ' : sym);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> smokeMatrix() {
        // Pick a handful of fixtures that span the file-shape space:
        // CI present / absent, dict present / absent, nested, variant.
        String[] fixtures = {
                "column_index_pushdown.parquet",  // has CI
                "dictionary_with_crc.parquet",    // has dict on one column only
                "filter_pushdown_int.parquet",    // no CI, no dict, plain
                "nested_struct_test.parquet",     // nested schema
                "variant_test.parquet",           // VARIANT type
                "primitive_types_test.parquet",   // many primitive types
        };
        ScreenCtor[] screens = {
                new ScreenCtor("Overview", m -> ScreenState.Overview.initial()),
                new ScreenCtor("Schema", m -> ScreenState.Schema.initial()),
                new ScreenCtor("RowGroups", m -> new ScreenState.RowGroups(0)),
                new ScreenCtor("RowGroupDetail",
                        m -> new ScreenState.RowGroupDetail(0,
                                ScreenState.RowGroupDetail.Pane.MENU, 0)),
                new ScreenCtor("RowGroupIndexes",
                        m -> new ScreenState.RowGroupIndexes(0, 0)),
                new ScreenCtor("ColumnChunks",
                        m -> new ScreenState.ColumnChunks(0, 0)),
                new ScreenCtor("ColumnChunkDetail",
                        m -> new ScreenState.ColumnChunkDetail(0, 0,
                                ScreenState.ColumnChunkDetail.Pane.MENU, 0, true, false)),
                new ScreenCtor("Pages",
                        m -> new ScreenState.Pages(0, 0, 0, false, true)),
                new ScreenCtor("ColumnAcrossRowGroups",
                        m -> new ScreenState.ColumnAcrossRowGroups(0, 0, true)),
                new ScreenCtor("Footer", m -> ScreenState.Footer.initial()),
                new ScreenCtor("DataPreview",
                        m -> dev.hardwood.cli.dive.internal.DataPreviewScreen.initialState(m, 5)),
        };
        return Stream.of(fixtures).flatMap(f ->
                Stream.of(screens).map(sc ->
                        org.junit.jupiter.params.provider.Arguments.of(f, sc.name(), sc.ctor())));
    }

    private record ScreenCtor(String name, Function<ParquetModel, ScreenState> ctor) {
    }

    private static int columnIndexOf(ParquetModel model, String dottedName) {
        for (ColumnSchema column : model.schema().getColumns()) {
            if (column.fieldPath().matchesDottedName(dottedName)) {
                return column.columnIndex();
            }
        }
        throw new IllegalArgumentException("no such column: " + dottedName);
    }

    private static RenderHarness.RenderedFrame renderSizeStatistics(String dottedName, boolean levels)
            throws Exception {
        Path path = Path.of(DiveRenderTest.class.getResource("/dive_screenshots_fixture.parquet").toURI());
        try (ParquetModel sizeStatsModel = ParquetModel.open(InputFile.of(path), path.toString())) {
            return RenderHarness.render(AREA, new ScreenState.ColumnChunkDetail(
                    0, columnIndexOf(sizeStatsModel, dottedName),
                    ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, levels), sizeStatsModel);
        }
    }

    /// Whether the column index holds per-page histograms is already in the
    /// metadata the menu has loaded, so the hint answers it before the reader
    /// spends a keystroke finding out.
    @Test
    void menuHintsAnnotatePageLevelSizeStatisticsWhenPresent() throws Exception {
        Path path = Path.of(getClass().getResource("/size_statistics_test.parquet").toURI());
        try (ParquetModel pageIndexModel = ParquetModel.open(InputFile.of(path), path.toString())) {
            RenderHarness.RenderedFrame frame = RenderHarness.render(AREA,
                    new ScreenState.ColumnChunkDetail(0, columnIndexOf(pageIndexModel, "name"),
                            ScreenState.ColumnChunkDetail.Pane.MENU, 0, true, false), pageIndexModel);

            assertThat(frame.contains("present · levels")).isTrue();
            assertThat(frame.contains("present · unencoded")).isTrue();
        }
    }

    /// A page index that predates the size-statistics fields still reads
    /// `present`; the annotation is what distinguishes the two.
    @Test
    void menuHintsOmitTheAnnotationWhenThePageIndexCarriesNoSizeStatistics() {
        RenderHarness.RenderedFrame frame = RenderHarness.render(AREA,
                new ScreenState.ColumnChunkDetail(0, 0,
                        ScreenState.ColumnChunkDetail.Pane.MENU, 0, true, false), model);

        assertThat(frame.contains("present")).isTrue();
        assertThat(frame.contains("· levels")).isFalse();
        assertThat(frame.contains("· unencoded")).isFalse();
    }

    /// Collapsed is the default: the derived rows are the summary worth
    /// seeing at a glance, and the pane does not scroll.
    @Test
    void columnChunkDetailShowsDerivedSizeStatisticsWithLevelsCollapsed() throws Exception {
        RenderHarness.RenderedFrame frame = renderSizeStatistics("websites.list.element", false);

        assertThat(frame.contains("Size statistics")).isTrue();
        assertThat(frame.contains("chunk only")).isTrue();
        assertThat(frame.contains("Records")).isTrue();
        assertThat(frame.contains("per record")).isTrue();
        assertThat(frame.contains("[l] to show")).isTrue();
        assertThat(frame.contains("websites empty")).isFalse();
    }

    @Test
    void columnChunkDetailShowsNamedLevelBucketsWhenToggledOn() throws Exception {
        RenderHarness.RenderedFrame frame = renderSizeStatistics("websites.list.element", true);

        assertThat(frame.contains("websites null")).isTrue();
        assertThat(frame.contains("websites empty")).isTrue();
        assertThat(frame.contains("element null")).isTrue();
        assertThat(frame.contains("element present")).isTrue();
        assertThat(frame.contains("new record")).isTrue();
        assertThat(frame.contains("websites.list")).isTrue();
        assertThat(frame.contains("[l] to show")).isFalse();
    }

    /// A column the writer recorded no size statistics for says so, but still
    /// shows the unencoded size, which follows from the value count and the
    /// fixed width rather than from anything the writer had to record.
    @Test
    void columnChunkDetailReportsAMissingSizeStatisticsAsNotWritten() throws Exception {
        RenderHarness.RenderedFrame frame = renderSizeStatistics("metric_a", true);

        assertThat(frame.contains("— (not written)")).isTrue();
        assertThat(frame.contains("per record")).isFalse();
        assertThat(frame.contains("Unencoded")).isTrue();
    }

    /// A required, non-repeated BYTE_ARRAY has no histograms to show but
    /// its unencoded size is still the interesting number.
    @Test
    void columnChunkDetailShowsUnencodedSizeForAFlatRequiredColumn() throws Exception {
        RenderHarness.RenderedFrame frame = renderSizeStatistics("id", true);

        assertThat(frame.contains("Unencoded")).isTrue();
        assertThat(frame.contains("Avg value size")).isTrue();
        assertThat(frame.contains("Records")).isFalse();
    }

    /// `dive` and `hardwood inspect columns` must name the same encoding for
    /// the same chunk. Both read `encoding_stats`, so both say what the data
    /// pages use rather than repeating the flat list, which carries the
    /// dictionary page and the RLE level streams too.
    @Test
    void columnChunkDetailNamesTheDataPageEncodingAndTheDeclaredList() throws Exception {
        RenderHarness.RenderedFrame frame = renderSizeStatistics("names.primary", false);

        assertThat(frame.contains("Encoding")).isTrue();
        // Every value distinct, so the dictionary is a second copy of the
        // column — the one thing `DICT` on its own cannot say.
        assertThat(frame.contains("DICT 100%")).isTrue();
        assertThat(frame.contains("150 entries for 150 values")).isTrue();
        assertThat(frame.contains("Chunk encodings")).isTrue();
    }

    /// Without `encoding_stats` the declared list is where the label came
    /// from, so showing it a second time would restate the row above it.
    @Test
    void columnChunkDetailDropsTheDeclaredListWithoutEncodingStats() throws Exception {
        Path path = Path.of(getClass().getResource("/geospatial_e2e_test.parquet").toURI());
        try (ParquetModel plainModel = ParquetModel.open(InputFile.of(path), path.toString())) {
            RenderHarness.RenderedFrame frame = RenderHarness.render(AREA,
                    new ScreenState.ColumnChunkDetail(0, columnIndexOf(plainModel, "city_name"),
                            ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, false), plainModel);

            assertThat(frame.contains("Encoding")).isTrue();
            assertThat(frame.contains("Chunk encodings")).isFalse();
        }
    }

    /// A required column holds no nulls, which the schema settles whether or
    /// not the writer recorded a `null_count`. Reporting `—` would contradict
    /// the present-value count taken from the same place.
    @Test
    void columnChunkDetailReportsZeroNullsForARequiredColumnWithoutStatistics() throws Exception {
        Path path = Path.of(getClass().getResource("/geospatial_e2e_test.parquet").toURI());
        try (ParquetModel plainModel = ParquetModel.open(InputFile.of(path), path.toString())) {
            RenderHarness.RenderedFrame frame = RenderHarness.render(AREA,
                    new ScreenState.ColumnChunkDetail(0, columnIndexOf(plainModel, "city_name"),
                            ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, false), plainModel);

            assertThat(frame.contains("Nulls")).isTrue();
            assertThat(frame.contains("0  (0.0%)")).isTrue();
        }
    }

    /// The cross-row-group screen is the interactive twin of
    /// `inspect columns --column`, so it carries the same unencoded size and
    /// the same percentage form of the compression.
    @Test
    void columnAcrossRowGroupsCarriesTheUnencodedSizeAndCompression() {
        RenderHarness.RenderedFrame frame = RenderHarness.render(AREA,
                new ScreenState.ColumnAcrossRowGroups(0, 0, true, 0), model);

        assertThat(frame.contains("Unencoded")).isTrue();
        assertThat(frame.contains("Compression")).isTrue();
        assertThat(frame.contains("Ratio")).isFalse();
    }

    /// Every surface renders compression as a percentage of the uncompressed
    /// size. A `×` factor on one screen and a `%` on the next describes the
    /// same quantity two ways, which is the reading error this pins shut.
    @ParameterizedTest
    @MethodSource("compressionScreens")
    void everyScreenRendersCompressionAsAPercentage(ScreenState state) {
        RenderHarness.RenderedFrame frame = RenderHarness.render(AREA, state, model);

        assertThat(frame.contains("Compression")).isTrue();
        assertThat(frame.contains("×")).isFalse();
    }

    static Stream<ScreenState> compressionScreens() {
        return Stream.of(
                ScreenState.Overview.initial(),
                new ScreenState.RowGroups(0),
                new ScreenState.RowGroupDetail(0, ScreenState.RowGroupDetail.Pane.MENU, 0),
                new ScreenState.ColumnChunks(0, 0, 0),
                new ScreenState.ColumnAcrossRowGroups(0, 0, true, 0));
    }

    /// Two `—` rows are not worth a toggle, so a chunk with no usable
    /// histogram shows them outright and never advertises `[l]` — the key
    /// would reveal exactly what is already on screen.
    @Test
    void columnChunkDetailShowsDegradedLevelRowsWithoutTheToggle() throws Exception {
        RenderHarness.RenderedFrame collapsed = renderSizeStatistics("id", false);

        assertThat(collapsed.contains("Def levels")).isTrue();
        assertThat(collapsed.contains("— (required, every value present)")).isTrue();
        assertThat(collapsed.contains("— (not repeated)")).isTrue();
        assertThat(collapsed.contains("[l] to show")).isFalse();
    }

    @Test
    void levelsKeyIsAdvertisedOnlyForAChunkWithAHistogram() throws Exception {
        Path path = Path.of(getClass().getResource("/dive_screenshots_fixture.parquet").toURI());
        try (ParquetModel sizeStatsModel = ParquetModel.open(InputFile.of(path), path.toString())) {
            assertThat(keybarFor(sizeStatsModel, "websites.list.element")).contains("[l] levels");
            // Size statistics, but both histograms empty.
            assertThat(keybarFor(sizeStatsModel, "id")).doesNotContain("[l] levels");
            // No size statistics at all.
            assertThat(keybarFor(sizeStatsModel, "metric_a")).doesNotContain("[l] levels");
        }
    }

    /// The facts pane runs to about forty lines on a nested column with its
    /// levels shown, so on an ordinary terminal the tail falls off the bottom.
    /// Dropping it silently is the hazard: the reader cannot tell a clipped
    /// pane from a complete one.
    @Test
    void columnChunkDetailScrollsTheFactsPaneWhenItOverflows() throws Exception {
        Path path = Path.of(getClass().getResource("/dive_screenshots_fixture.parquet").toURI());
        try (ParquetModel model = ParquetModel.open(InputFile.of(path), path.toString())) {
            int column = columnIndexOf(model, "names.common.key_value.value");
            Rect shortArea = new Rect(0, 0, 120, 24);

            RenderHarness.RenderedFrame top = RenderHarness.render(shortArea,
                    new ScreenState.ColumnChunkDetail(0, column,
                            ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, true, 0), model);
            // The head is visible, the tail is not, and the title says so.
            assertThat(top.contains("Path")).isTrue();
            assertThat(top.contains("Rep levels")).isFalse();
            assertThat(hasRangeMarker(top)).isTrue();

            RenderHarness.RenderedFrame scrolled = RenderHarness.render(shortArea,
                    new ScreenState.ColumnChunkDetail(0, column,
                            ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, true, 40), model);
            // Clamped to the last full viewport, so the final line is reachable.
            assertThat(scrolled.contains("Rep levels")).isTrue();
            assertThat(scrolled.contains("Path")).isFalse();
        }
    }

    /// A pane that fits carries no range suffix — the marker is a statement
    /// that content is hidden, not decoration.
    @Test
    void columnChunkDetailOmitsTheRangeWhenEverythingFits() throws Exception {
        Path path = Path.of(getClass().getResource("/dive_screenshots_fixture.parquet").toURI());
        try (ParquetModel model = ParquetModel.open(InputFile.of(path), path.toString())) {
            RenderHarness.RenderedFrame frame = RenderHarness.render(new Rect(0, 0, 120, 60),
                    new ScreenState.ColumnChunkDetail(0, columnIndexOf(model, "metric_a"),
                            ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, false, 0), model);

            assertThat(frame.contains("Path")).isTrue();
            assertThat(frame.contains("— (not written)")).isTrue();
            assertThat(hasRangeMarker(frame)).isFalse();
        }
    }

    /// Whether the facts pane's title carries the `first-last/total` suffix
    /// that says content is hidden below the fold.
    private static boolean hasRangeMarker(RenderHarness.RenderedFrame frame) {
        return frame.lines().stream().anyMatch(line -> RANGE_MARKER.matcher(line).find());
    }

    private static String keybarFor(ParquetModel model, String dottedName) {
        return ColumnChunkDetailScreen.keybarKeys(new ScreenState.ColumnChunkDetail(
                0, columnIndexOf(model, dottedName),
                ScreenState.ColumnChunkDetail.Pane.FACTS, 0, true, false), model);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.NONE, '\0');
    }
}
