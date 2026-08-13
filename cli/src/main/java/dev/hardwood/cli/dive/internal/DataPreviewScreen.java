/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.hardwood.cli.dive.ParquetModel;
import dev.hardwood.cli.dive.ScreenState;
import dev.hardwood.cli.internal.Fmt;
import dev.hardwood.schema.SchemaNode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;

/// Projected-row preview. `firstRow` / `pageSize` define which rows are currently
/// loaded; `←/→` scrolls the visible column window for wide schemas; `PgDn`/`PgUp`
/// (or `Shift+↓/↑`) flip pages. [ParquetModel#readPreviewPage] maintains a
/// forward-only cursor across calls, so stepping forward never re-iterates from
/// row 0 — only backward moves (`PgUp`, `g` jump-to-top) recreate the reader.
public final class DataPreviewScreen {

    private static final int COLUMN_SPACING = 1;
    private static final int MIN_PARTIAL_COLUMN_WIDTH = 8;
    private static final int VALUE_TRUNCATE = 32;

    /// A sliding ±10×viewport row window of pre-formatted Data preview
    /// rows. Within-window navigation (PgUp/PgDn that stays inside the
    /// horizon) is served from the buffer with no I/O. See [PreviewWindow].
    private static final PreviewWindow WINDOW = new PreviewWindow();

    private DataPreviewScreen() {
    }

    /// Loads the first page; the page size starts at the most recently
    /// observed viewport stride (or `Keys.PAGE_STRIDE` as a pre-render
    /// fallback) and gets re-loaded to the actual viewport on the first
    /// event after the screen renders.
    public static ScreenState.DataPreview initialState(ParquetModel model) {
        return initialState(model, Keys.viewportStride());
    }

    /// Test entry point — caller picks an explicit page size for
    /// deterministic page-boundary assertions. Production code uses the
    /// viewport-derived overload.
    public static ScreenState.DataPreview initialState(ParquetModel model, int pageSize) {
        return loadPage(model, 0, pageSize, 0, true);
    }

    public static boolean handle(KeyEvent event, ParquetModel model, dev.hardwood.cli.dive.NavigationStack stack) {
        ScreenState.DataPreview state = (ScreenState.DataPreview) stack.top();
        // Auto-resize the page to match the current viewport. The first
        // render after initialState observes the available rows; on the
        // first subsequent event we re-load to fill the actual viewport.
        // Skip while a modal is open — the modal owns the screen.
        if (state.modalRow() < 0 && Keys.hasObservedViewport()
                && state.pageSize() != Keys.viewportStride()) {
            state = loadPage(model, state.firstRow(), Keys.viewportStride(),
                    state.columnScroll(), state.logicalTypes());
            stack.replaceTop(state);
        }
        long total = model.facts().totalRows();
        if (state.modalRow() >= 0) {
            return handleModal(event, state, stack, model);
        }
        // Plain ↑/↓ moves the selected-row cursor inside the current page; Shift+↑/↓
        // pages (handled by the PgDn/PgUp branches below). Enter opens the
        // full-record modal at the cursor. When the cursor would move past
        // the loaded slice, auto-page so the cursor walks through the whole
        // dataset like any other list screen.
        // The visible window slides freely: `firstRow` is the absolute index
        // of the first visible row, not a page-aligned offset. Step / page
        // navigation moves the absolute selection (`firstRow + selectedRow`)
        // and slides the window minimally, mirroring the dictionary and
        // other list screens — so PgUp lands the cursor at the top, PgDn at
        // the bottom, and step-down past the bottom row keeps the cursor at
        // the bottom while the rows underneath shift up.
        if (Keys.isStepUp(event)) {
            if (state.rows().isEmpty()) {
                return false;
            }
            stack.replaceTop(moveBy(state, -1, model, total, ScrollBias.KEEP));
            return state.firstRow() != 0 || state.selectedRow() != 0;
        }
        if (Keys.isStepDown(event)) {
            if (state.rows().isEmpty()) {
                return false;
            }
            long abs = state.firstRow() + state.selectedRow();
            if (abs >= total - 1) {
                return false;
            }
            stack.replaceTop(moveBy(state, 1, model, total, ScrollBias.KEEP));
            return true;
        }
        if (event.isConfirm() && !state.rows().isEmpty()) {
            stack.replaceTop(withModalRow(state, state.selectedRow()));
            return true;
        }
        if (Keys.isPageDown(event)) {
            long abs = state.firstRow() + state.selectedRow();
            if (abs >= total - 1) {
                return false;
            }
            stack.replaceTop(moveBy(state, state.pageSize(), model, total, ScrollBias.BOTTOM));
            return true;
        }
        if (Keys.isPageUp(event)) {
            long abs = state.firstRow() + state.selectedRow();
            if (abs == 0) {
                return false;
            }
            stack.replaceTop(moveBy(state, -state.pageSize(), model, total, ScrollBias.TOP));
            return true;
        }
        if (event.isLeft()) {
            if (state.columnScroll() == 0) {
                return false;
            }
            stack.replaceTop(withColumnScroll(state, Math.max(0, state.columnScroll() - 1)));
            return true;
        }
        if (event.isRight()) {
            if (!canScrollRight(state, columnWindow(state, Keys.viewportWidth()))) {
                return false;
            }
            stack.replaceTop(withColumnScroll(state, state.columnScroll() + 1));
            return true;
        }
        if (Keys.isJumpTop(event)) {
            long abs = state.firstRow() + state.selectedRow();
            if (abs == 0) {
                return false;
            }
            stack.replaceTop(moveTo(state, 0, model, total, ScrollBias.TOP));
            return true;
        }
        if (Keys.isJumpBottom(event)) {
            long abs = state.firstRow() + state.selectedRow();
            if (abs == total - 1) {
                return false;
            }
            stack.replaceTop(moveTo(state, total - 1, model, total, ScrollBias.BOTTOM));
            return true;
        }
        // Toggle logical-type rendering. Modifier-free `t` (avoid clobbering
        // typed text in any future search-mode here).
        if (event.code() == KeyCode.CHAR && event.character() == 't'
                && !event.hasCtrl() && !event.hasAlt()) {
            stack.replaceTop(loadPage(model, state.firstRow(), state.pageSize(),
                    state.columnScroll(), !state.logicalTypes()));
            return true;
        }
        return false;
    }

    public static void render(Buffer buffer, Rect area, ParquetModel model, ScreenState.DataPreview state) {
        Keys.observeArea(area.width(), area.height());
        // Block borders (top + bottom) + header row = 3 cells of chrome
        // around the data rows.
        Keys.observeViewport(area.height() - 3);
        Keys.observeViewportWidth(area.width());
        int columnCount = state.columnNames().size();
        ColumnWindow window = columnWindow(state, area.width());

        List<Row> rows = new ArrayList<>();
        for (List<String> row : state.rows()) {
            String[] truncated = new String[window.widths().size()];
            for (int i = 0; i < truncated.length; i++) {
                truncated[i] = truncate(row.get(state.columnScroll() + i), window.widths().get(i));
            }
            rows.add(Row.from(truncated));
        }
        Row header = Row.from(window.headers().toArray(new String[0])).style(Theme.accent().bold());

        long total = model.facts().totalRows();
        long lastRow = state.firstRow() + state.rows().size();
        String typeMode = state.logicalTypes() ? "" : " · physical";
        // A clipped trailing column is only partly on screen — mark the range
        // with the same ellipsis the cells use rather than claiming it whole.
        String clipMark = window.clipped() ? "…" : "";
        String title = Fmt.fmt(" Data preview (rows %,d–%,d of %,d · cols %d–%d%s of %d%s) ",
                state.firstRow() + 1, lastRow, total,
                state.columnScroll() + 1, window.end(), clipMark, columnCount, typeMode);

        Block block = Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .build();
        List<Constraint> widths = new ArrayList<>();
        for (int width : window.widths()) {
            widths.add(new Constraint.Length(width));
        }
        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(widths)
                .columnSpacing(COLUMN_SPACING)
                .block(block)
                .highlightSymbol("▶ ")
                .highlightStyle(Theme.selection())
                .build();
        TableState tableState = new TableState();
        if (!state.rows().isEmpty()) {
            tableState.select(Math.min(state.selectedRow(), state.rows().size() - 1));
        }
        table.render(area, buffer, tableState);
        if (state.modalRow() >= 0 && state.modalRow() < state.rows().size()) {
            buffer.setStyle(area, Theme.dim());
            renderRecordModal(buffer, area, model, state);
        }
    }

    private static void renderRecordModal(Buffer buffer, Rect screenArea, ParquetModel model,
                                          ScreenState.DataPreview state) {
        List<String> values = state.rows().get(state.modalRow());
        List<String> names = state.columnNames();
        ModalGeometry geometry = modalGeometry(
                screenArea.width(), screenArea.height(), names);
        int width = geometry.width();
        int height = geometry.height();
        int x = screenArea.left() + (screenArea.width() - width) / 2;
        int y = screenArea.top() + (screenArea.height() - height) / 2;
        Rect area = new Rect(x, y, width, height);
        Clear.INSTANCE.render(area, buffer);

        int maxKeyWidth = geometry.maxKeyWidth();
        int valueBudget = geometry.valueBudget();
        int viewport = geometry.viewportLines();
        String continuationIndent = " ".repeat(1 + maxKeyWidth + 3);

        // Build the full body as a flat line list. ownership[i] = the line
        // index where field i's key line starts; continuation lines for an
        // expanded field belong to that same field.
        int[] ownership = new int[names.size()];
        List<Line> all = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String pad = " ".repeat(maxKeyWidth - name.length());
            boolean isExpanded = state.expandedColumns().contains(i);
            String value = i < values.size() ? values.get(i) : "";
            ownership[i] = all.size();
            if (isExpanded) {
                List<String> wrapped = expandedValueLines(state, i, geometry);
                all.add(Line.from(
                        new Span(" " + name + pad + " : ", Theme.primary()),
                        Span.raw(wrapped.get(0))));
                for (int k = 1; k < wrapped.size(); k++) {
                    all.add(Line.from(Span.raw(continuationIndent + wrapped.get(k))));
                }
            }
            else {
                all.add(Line.from(
                        new Span(" " + name + pad + " : ", Theme.primary()),
                        Span.raw(truncate(value, valueBudget))));
            }
        }

        int totalLines = all.size();
        int cursorLine = Math.max(0, Math.min(state.modalCursorLine(), totalLines - 1));
        // Cursor is purely decorative when there's nothing to do with it:
        // no field can expand AND content fits the viewport. In that case
        // the modal becomes a static info display.
        boolean canExpandAny = hasExpandableField(state, geometry);
        boolean overflows = totalLines > viewport;
        boolean showCursor = canExpandAny || overflows;
        if (showCursor && cursorLine < all.size()) {
            int fieldIdx = fieldForLine(state, cursorLine, geometry);
            int fieldFirstLine = ownership[fieldIdx];
            String name = names.get(fieldIdx);
            String pad = " ".repeat(maxKeyWidth - name.length());
            boolean isExpanded = state.expandedColumns().contains(fieldIdx);
            String value = fieldIdx < values.size() ? values.get(fieldIdx) : "";
            Style selectionStyle = Theme.selection();
            if (cursorLine == fieldFirstLine) {
                String shown;
                if (isExpanded) {
                    List<String> wrapped = expandedValueLines(state, fieldIdx, geometry);
                    shown = wrapped.isEmpty() ? "" : wrapped.get(0);
                }
                else {
                    shown = truncate(value, valueBudget);
                }
                all.set(cursorLine, Line.from(
                        new Span("▶" + name + pad + " : ", selectionStyle),
                        new Span(shown, selectionStyle)));
            }
            else if (isExpanded) {
                List<String> wrapped = expandedValueLines(state, fieldIdx, geometry);
                int contIdx = cursorLine - fieldFirstLine;
                String text = contIdx < wrapped.size() ? wrapped.get(contIdx) : "";
                all.set(cursorLine, Line.from(new Span(continuationIndent + text, selectionStyle)));
            }
        }

        int scroll = Math.max(0, Math.min(totalLines - viewport,
                Math.max(0, cursorLine - viewport / 2)));
        int end = Math.min(totalLines, scroll + viewport);

        List<Line> lines = new ArrayList<>(all.subList(scroll, end));
        lines.add(Line.empty());
        // Hint is tiered: drop "↑↓ navigate" when neither direction can move;
        // drop "Enter expand" +
        // "e/c all" when no field has a multi-line expanded form;
        // include "t logical types" only when at least one column has a
        // logical type.
        boolean canNavigate = overflows
                || previousNavigableLine(state, cursorLine, totalLines, geometry) >= 0
                || nextNavigableLine(state, cursorLine, totalLines, geometry) >= 0;
        boolean canExpand = canExpandAny;
        boolean anyLogical = false;
        for (SchemaNode child : model.schema().getRootNode().children()) {
            if (child instanceof SchemaNode.PrimitiveNode p && p.logicalType() != null) {
                anyLogical = true;
                break;
            }
        }
        List<String> segments = new ArrayList<>();
        if (scroll + viewport < totalLines) {
            segments.add(" ↓ " + (totalLines - end) + " more lines");
        }
        else if (scroll > 0) {
            segments.add(" ↑ " + scroll + " lines above");
        }
        if (canNavigate) {
            segments.add("↑↓ navigate");
        }
        if (canExpand) {
            segments.add("Enter expand");
            segments.add("e/c all");
        }
        if (anyLogical) {
            segments.add("t logical types");
        }
        segments.add("Esc close");
        String hint = String.join(" · ", segments);
        if (!hint.startsWith(" ")) {
            hint = " " + hint;
        }
        lines.add(Line.from(new Span(hint, Theme.dim())));

        long absRow = state.firstRow() + state.modalRow();
        Block block = Block.builder()
                .title(Fmt.fmt(" Row %,d ", absRow + 1))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .build();
        Paragraph.builder()
                .block(block)
                .text(Text.from(lines))
                .left()
                .build()
                .render(area, buffer);
    }

    private static ModalGeometry modalGeometry(
            int screenWidth, int screenHeight, List<String> names) {
        int width = Math.max(40, screenWidth - 4);
        int height = Math.max(8, screenHeight - 2);
        int maxKeyWidth = 0;
        for (String name : names) {
            maxKeyWidth = Math.max(maxKeyWidth, name.length());
        }
        int valueBudget = Math.max(8, width - 2 - 1 - maxKeyWidth - 3 - 1);
        int viewportLines = Math.max(1, height - 4);
        return new ModalGeometry(width, height, maxKeyWidth, valueBudget, viewportLines);
    }

    private static ModalGeometry observedModalGeometry(ScreenState.DataPreview state) {
        if (!Keys.hasObservedArea()) {
            return null;
        }
        return modalGeometry(
                Keys.observedAreaWidth(), Keys.observedAreaHeight(), state.columnNames());
    }

    private static List<String> expandedValueLines(
            ScreenState.DataPreview state, int field, ModalGeometry geometry) {
        return expandedValueLines(state, state.modalRow(), field, geometry);
    }

    private static List<String> expandedValueLines(
            ScreenState.DataPreview state, int modalRow, int field, ModalGeometry geometry) {
        List<String> values = state.rows().get(modalRow);
        List<String> expanded = state.expandedRows().get(modalRow);
        String value = field < values.size() ? values.get(field) : "";
        String fullValue = field < expanded.size() ? expanded.get(field) : value;
        List<String> lines = geometry == null
                ? List.of(fullValue.split("\n", -1))
                : Strings.hardWrap(fullValue, geometry.valueBudget());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private record ModalGeometry(
            int width,
            int height,
            int maxKeyWidth,
            int valueBudget,
            int viewportLines) {
    }

    public static String keybarKeys(ScreenState.DataPreview state, ParquetModel model) {
        if (state.modalRow() >= 0) {
            return "";
        }
        long total = model.facts().totalRows();
        int loaded = state.rows().size();
        boolean canPage = total > state.pageSize();
        ColumnWindow window = columnWindow(state, Keys.viewportWidth());
        boolean canColumnScroll = state.columnScroll() > 0 || canScrollRight(state, window);
        boolean anyLogical = false;
        for (SchemaNode child : model.schema().getRootNode().children()) {
            if (child instanceof SchemaNode.PrimitiveNode p && p.logicalType() != null) {
                anyLogical = true;
                break;
            }
        }
        return new Keys.Hints()
                .add(loaded > 1, "[↑↓] row")
                .add(loaded > 0, "[Enter] view record")
                .add(canColumnScroll, "[←→] columns")
                .add(canPage, "[PgDn/PgUp or Shift+↓↑] page")
                .add(canPage, "[g/G] start/end")
                .add(anyLogical, "[t] logical types")
                .add(true, "[Esc] back")
                .build();
    }

    private static boolean handleModal(KeyEvent event, ScreenState.DataPreview state,
                                       dev.hardwood.cli.dive.NavigationStack stack,
                                       ParquetModel model) {
        // Inside an overflowing modal, ↑/↓ move one rendered line at a time
        // so every field remains reachable. When the body fits, they skip to
        // fields whose expanded rendering reveals something new. Enter
        // toggles that field; e / c expand / collapse all fields. Esc closes
        // the modal. Row stepping is intentionally absent — the user picks
        // another row from the table after closing.
        if (event.isCancel()) {
            stack.replaceTop(withModalRow(state, -1));
            return true;
        }
        ModalGeometry geometry = observedModalGeometry(state);
        int totalLines = totalModalLines(state, geometry);
        if (event.isConfirm()) {
            int field = fieldForLine(state, state.modalCursorLine(), geometry);
            if (geometry != null && !isExpandableField(state, field, geometry)) {
                return false;
            }
            Set<Integer> next = new HashSet<>(state.expandedColumns());
            if (!next.remove(field)) {
                next.add(field);
            }
            // Keep the cursor on the same field after toggling so the user
            // doesn't lose their place.
            int newCursor = firstLineForField(state, next, field, geometry);
            stack.replaceTop(withExpansion(state, next, newCursor));
            return true;
        }
        if (event.code() == KeyCode.CHAR && event.character() == 'e'
                && !event.hasCtrl() && !event.hasAlt()) {
            int field = fieldForLine(state, state.modalCursorLine(), geometry);
            Set<Integer> all = new HashSet<>();
            for (int i = 0; i < state.columnNames().size(); i++) {
                all.add(i);
            }
            int newCursor = firstLineForField(state, all, field, geometry);
            stack.replaceTop(withExpansion(state, all, newCursor));
            return true;
        }
        if (event.code() == KeyCode.CHAR && event.character() == 'c'
                && !event.hasCtrl() && !event.hasAlt()) {
            int field = fieldForLine(state, state.modalCursorLine(), geometry);
            int newCursor = firstLineForField(state, Set.of(), field, geometry);
            stack.replaceTop(withExpansion(state, Set.of(), newCursor));
            return true;
        }
        // `t` toggles logical-type rendering. Re-loads the current page
        // with the new flag and preserves the modal-state fields
        // (selectedRow, modalRow, expandedColumns, cursorLine) so the
        // user stays put.
        if (event.code() == KeyCode.CHAR && event.character() == 't'
                && !event.hasCtrl() && !event.hasAlt()) {
            boolean nextLogical = !state.logicalTypes();
            ScreenState.DataPreview reloaded = loadPage(model, state.firstRow(),
                    state.pageSize(), state.columnScroll(), nextLogical);
            stack.replaceTop(new ScreenState.DataPreview(
                    reloaded.firstRow(), reloaded.pageSize(), reloaded.columnNames(),
                    reloaded.rows(), reloaded.expandedRows(), reloaded.columnScroll(),
                    state.selectedRow(), state.modalRow(), nextLogical,
                    state.expandedColumns(), state.modalCursorLine()));
            return true;
        }
        if (event.isUp()) {
            if (state.modalCursorLine() == 0) {
                return false;
            }
            int previous = previousNavigableLine(
                    state, state.modalCursorLine(), totalLines, geometry);
            if (previous < 0) {
                return false;
            }
            stack.replaceTop(withCursorLine(state, previous));
            return true;
        }
        if (event.isDown()) {
            if (state.modalCursorLine() >= totalLines - 1) {
                return false;
            }
            int next = nextNavigableLine(
                    state, state.modalCursorLine(), totalLines, geometry);
            if (next < 0) {
                return false;
            }
            stack.replaceTop(withCursorLine(state, next));
            return true;
        }
        return false;
    }

    /// Total displayable lines in the modal body — one per field for
    /// collapsed fields, plus extra continuation lines for each expanded
    /// field's pretty-printed value.
    private static int totalModalLines(ScreenState.DataPreview state, ModalGeometry geometry) {
        int total = state.columnNames().size();
        for (int i : state.expandedColumns()) {
            if (i < 0 || i >= state.columnNames().size()) {
                continue;
            }
            int continuationLines = expandedValueLines(state, i, geometry).size();
            total += Math.max(0, continuationLines - 1);
        }
        return total;
    }

    /// Field index that owns the given cursor line in the flattened modal
    /// body. Continuation lines of an expanded field map to that field.
    private static int fieldForLine(
            ScreenState.DataPreview state, int line, ModalGeometry geometry) {
        int names = state.columnNames().size();
        if (names == 0) {
            return 0;
        }
        int cursor = 0;
        for (int field = 0; field < names; field++) {
            int linesForField = 1;
            if (state.expandedColumns().contains(field)) {
                linesForField = expandedValueLines(state, field, geometry).size();
            }
            if (line < cursor + linesForField) {
                return field;
            }
            cursor += linesForField;
        }
        return names - 1;
    }

    /// Line index of the key line for `field` given the new expanded set.
    private static int firstLineForField(ScreenState.DataPreview state,
                                          Set<Integer> expandedColumns, int field,
                                          ModalGeometry geometry) {
        return firstLineForField(
                state, state.modalRow(), expandedColumns, field, geometry);
    }

    private static int firstLineForField(ScreenState.DataPreview state, int modalRow,
                                          Set<Integer> expandedColumns, int field,
                                          ModalGeometry geometry) {
        int line = 0;
        for (int i = 0; i < field; i++) {
            int linesForField = 1;
            if (expandedColumns.contains(i)) {
                linesForField = expandedValueLines(state, modalRow, i, geometry).size();
            }
            line += linesForField;
        }
        return line;
    }

    private static boolean hasExpandableField(
            ScreenState.DataPreview state, ModalGeometry geometry) {
        for (int field = 0; field < state.columnNames().size(); field++) {
            if (isExpandableField(state, field, geometry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExpandableField(
            ScreenState.DataPreview state, int field, ModalGeometry geometry) {
        return isExpandableField(state, state.modalRow(), field, geometry);
    }

    private static boolean isExpandableField(
            ScreenState.DataPreview state, int modalRow, int field, ModalGeometry geometry) {
        List<String> expanded = state.expandedRows().get(modalRow);
        if (field < 0 || field >= expanded.size()) {
            return false;
        }
        if (geometry == null) {
            return expanded.get(field).indexOf('\n') >= 0;
        }
        List<String> wrapped = expandedValueLines(state, modalRow, field, geometry);
        List<String> values = state.rows().get(modalRow);
        String collapsed = truncate(field < values.size() ? values.get(field) : "",
                geometry.valueBudget());
        return wrapped.size() > 1 || !wrapped.get(0).equals(collapsed);
    }

    /// Finds the previous reachable line: one physical line when scrolling is
    /// required, otherwise the first line of the previous actionable field.
    private static int previousNavigableLine(
            ScreenState.DataPreview state, int from, int totalLines,
            ModalGeometry geometry) {
        if (usesLineNavigation(state, totalLines, geometry)) {
            return from - 1;
        }
        int currentField = fieldForLine(state, from, geometry);
        for (int field = currentField - 1; field >= 0; field--) {
            if (isExpandableField(state, field, geometry)) {
                return firstLineForField(state, state.expandedColumns(), field, geometry);
            }
        }
        return -1;
    }

    /// Finds the next reachable line: one physical line when scrolling is
    /// required, otherwise the first line of the next actionable field.
    private static int nextNavigableLine(
            ScreenState.DataPreview state, int from, int totalLines,
            ModalGeometry geometry) {
        if (usesLineNavigation(state, totalLines, geometry)) {
            return from + 1;
        }
        int currentField = fieldForLine(state, from, geometry);
        for (int field = currentField + 1; field < state.columnNames().size(); field++) {
            if (isExpandableField(state, field, geometry)) {
                return firstLineForField(state, state.expandedColumns(), field, geometry);
            }
        }
        return -1;
    }

    private static boolean usesLineNavigation(
            ScreenState.DataPreview state, int totalLines, ModalGeometry geometry) {
        if (geometry != null) {
            return totalLines > geometry.viewportLines();
        }
        // Handler-only tests and the first event before a render retain the
        // previous scalar fallback because no viewport is available yet.
        return !hasExpandableField(state, null);
    }

    private static ScreenState.DataPreview withSelectedRow(ScreenState.DataPreview s, int sel) {
        return new ScreenState.DataPreview(s.firstRow(), s.pageSize(), s.columnNames(), s.rows(),
                s.expandedRows(), s.columnScroll(), sel, s.modalRow(), s.logicalTypes(),
                s.expandedColumns(), s.modalCursorLine());
    }

    private static ScreenState.DataPreview withModalRow(ScreenState.DataPreview s, int modalRow) {
        ModalGeometry geometry = observedModalGeometry(s);
        int cursorLine = 0;
        if (modalRow >= 0
                && (geometry == null || s.columnNames().size() <= geometry.viewportLines())) {
            int field = firstExpandableField(s, modalRow, geometry);
            cursorLine = firstLineForField(
                    s, modalRow, s.expandedColumns(), field, geometry);
        }
        return new ScreenState.DataPreview(s.firstRow(), s.pageSize(), s.columnNames(), s.rows(),
                s.expandedRows(), s.columnScroll(), s.selectedRow(), modalRow, s.logicalTypes(),
                modalRow < 0 ? Set.of() : s.expandedColumns(),
                cursorLine);
    }

    /// Returns the first expandable field, or field 0 when the record has none.
    private static int firstExpandableField(
            ScreenState.DataPreview state, int modalRow, ModalGeometry geometry) {
        for (int field = 0; field < state.columnNames().size(); field++) {
            if (isExpandableField(state, modalRow, field, geometry)) {
                return field;
            }
        }
        return 0;
    }

    private static ScreenState.DataPreview withColumnScroll(ScreenState.DataPreview s, int scroll) {
        return new ScreenState.DataPreview(s.firstRow(), s.pageSize(), s.columnNames(), s.rows(),
                s.expandedRows(), scroll, s.selectedRow(), s.modalRow(), s.logicalTypes(),
                s.expandedColumns(), s.modalCursorLine());
    }

    private static ScreenState.DataPreview withCursorLine(ScreenState.DataPreview s, int line) {
        return new ScreenState.DataPreview(s.firstRow(), s.pageSize(), s.columnNames(), s.rows(),
                s.expandedRows(), s.columnScroll(), s.selectedRow(), s.modalRow(), s.logicalTypes(),
                s.expandedColumns(), line);
    }

    private static ScreenState.DataPreview withExpansion(ScreenState.DataPreview s,
                                                          Set<Integer> expanded, int cursorLine) {
        return new ScreenState.DataPreview(s.firstRow(), s.pageSize(), s.columnNames(), s.rows(),
                s.expandedRows(), s.columnScroll(), s.selectedRow(), s.modalRow(), s.logicalTypes(),
                expanded, cursorLine);
    }

    private static ScreenState.DataPreview loadPage(ParquetModel model, long firstRow, int pageSize,
                                                    int columnScroll, boolean logicalTypes) {
        PreviewWindow.Slice slice = WINDOW.slice(model, firstRow, pageSize, logicalTypes);
        return new ScreenState.DataPreview(firstRow, pageSize, slice.columnNames(),
                slice.rows(), slice.expandedRows(), columnScroll, 0, -1,
                logicalTypes, Set.of(), 0);
    }

    /// How to position the cursor within the viewport after a navigation move.
    /// `KEEP` slides the window minimally (cursor stays in its current row),
    /// `TOP` aligns the new selection at row 0, `BOTTOM` aligns it at the
    /// last visible row — matching dictionary-screen / dive list scrolling.
    private enum ScrollBias { KEEP, TOP, BOTTOM }

    private static ScreenState.DataPreview moveBy(ScreenState.DataPreview state, long delta,
                                                   ParquetModel model, long total, ScrollBias bias) {
        long abs = state.firstRow() + state.selectedRow();
        long newAbs = Math.max(0, Math.min(total - 1, abs + delta));
        return moveTo(state, newAbs, model, total, bias);
    }

    private static ScreenState.DataPreview moveTo(ScreenState.DataPreview state, long newAbs,
                                                   ParquetModel model, long total, ScrollBias bias) {
        int viewport = state.pageSize();
        long clampedAbs = Math.max(0, Math.min(total - 1, newAbs));
        long newFirst = switch (bias) {
            case KEEP -> {
                long top = state.firstRow();
                if (clampedAbs < top) {
                    yield clampedAbs;
                }
                if (clampedAbs >= top + viewport) {
                    yield clampedAbs - viewport + 1;
                }
                yield top;
            }
            case TOP -> clampedAbs;
            case BOTTOM -> Math.max(0, clampedAbs - viewport + 1);
        };
        if (newFirst + viewport > total) {
            newFirst = Math.max(0, total - viewport);
        }
        ScreenState.DataPreview loaded = newFirst == state.firstRow() && state.pageSize() == viewport
                ? state
                : loadPage(model, newFirst, viewport, state.columnScroll(), state.logicalTypes());
        int rowsLoaded = loaded.rows().size();
        int newSel = (int) Math.max(0, Math.min(rowsLoaded - 1, clampedAbs - loaded.firstRow()));
        return withSelectedRow(loaded, newSel);
    }

    private static ColumnWindow columnWindow(ScreenState.DataPreview state, int viewportWidth) {
        int availableWidth = viewportWidth - 2;
        if (!state.rows().isEmpty()) {
            availableWidth -= 2;
        }
        availableWidth = Math.max(1, availableWidth);

        List<String> headers = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        boolean clipped = false;
        int used = 0;
        int column = state.columnScroll();
        while (column < state.columnNames().size()) {
            int spacing = widths.isEmpty() ? 0 : COLUMN_SPACING;
            int remaining = availableWidth - used - spacing;
            if (remaining <= 0) {
                break;
            }

            int naturalWidth = columnContentWidth(state, column);
            if (!widths.isEmpty() && naturalWidth > remaining
                    && remaining < MIN_PARTIAL_COLUMN_WIDTH) {
                break;
            }

            int width = Math.min(naturalWidth, remaining);
            headers.add(truncate(state.columnNames().get(column), width));
            widths.add(width);
            used += spacing + width;
            column++;
            if (width < naturalWidth) {
                clipped = true;
                break;
            }
        }
        return new ColumnWindow(column, clipped, headers, widths);
    }

    /// Whether `→` has anything left to reveal. Beyond the obvious case of
    /// columns past the window, a trailing column clipped for want of budget
    /// also counts: dropping the column on the left frees the space it needs.
    /// A column capped at [#VALUE_TRUNCATE] deliberately does not — no amount
    /// of scrolling widens it, and the record modal is where full values live.
    /// The `columnScroll` bound terminates the walk when a single column is
    /// wider than the whole terminal.
    private static boolean canScrollRight(ScreenState.DataPreview state, ColumnWindow window) {
        // columnNames already carries the top-level-field count (see loadPage —
        // the reader indexes into fields, not leaves, so leaf count would overshoot).
        int columnCount = state.columnNames().size();
        return window.end() < columnCount
                || (window.clipped() && state.columnScroll() < columnCount - 1);
    }

    private static int columnContentWidth(ScreenState.DataPreview state, int column) {
        int width = CharWidth.of(state.columnNames().get(column));
        for (List<String> row : state.rows()) {
            width = Math.max(width, CharWidth.of(row.get(column)));
        }
        return Math.max(1, Math.min(VALUE_TRUNCATE, width));
    }

    private static String truncate(String s, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("Maximum width must be positive");
        }
        return CharWidth.truncateWithEllipsis(s, max, "…", CharWidth.TruncatePosition.END);
    }

    /// The columns the current viewport can show, starting at `columnScroll`.
    /// `end` is exclusive and counts a clipped trailing column; `clipped` says
    /// whether that last column had to give up width to fit the budget.
    private record ColumnWindow(int end, boolean clipped, List<String> headers, List<Integer> widths) {
        private ColumnWindow {
            headers = List.copyOf(headers);
            widths = List.copyOf(widths);
        }
    }
}
