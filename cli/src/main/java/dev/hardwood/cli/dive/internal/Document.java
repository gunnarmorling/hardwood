/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;

/// The content of a pane whose rows are not uniform — a facts pane, the
/// footer body — as the lines it paints plus which of them are rows.
///
/// A section heading and the blank above it are decoration: they are painted,
/// they are read, and the cursor does not stop on them, because there is
/// nothing there to be at. Modelling that here rather than in each pane's key
/// handler keeps [CursorPane] free of any notion of which lines are inert —
/// the pane says so as it builds them, where it knows.
///
/// The cursor is an index into [#rowCount()], not into the lines, so a pane
/// holding one navigates with [CursorPane#select(dev.tamboui.tui.event.KeyEvent,int,int)]
/// exactly as a list screen does.
public final class Document {

    private final List<Line> lines;

    /// Line index of each row, ascending. The cursor indexes into this.
    private final int[] rowLines;

    private Document(List<Line> lines, int[] rowLines) {
        this.lines = List.copyOf(lines);
        this.rowLines = rowLines;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Line> lines() {
        return lines;
    }

    public int lineCount() {
        return lines.size();
    }

    /// How many rows the cursor can occupy. Zero for a document that is all
    /// decoration, which navigates as nothing rather than as a single row.
    public int rowCount() {
        return rowLines.length;
    }

    /// The line row `index` occupies, clamped, or `-1` when there are no rows.
    public int lineOfRow(int index) {
        if (rowLines.length == 0) {
            return -1;
        }
        return rowLines[Math.max(0, Math.min(index, rowLines.length - 1))];
    }

    /// The row to select after `event`, or [CursorPane#UNHANDLED] if `event`
    /// does not move the cursor.
    ///
    /// The three strides are the same ones every pane uses, but a document's
    /// rows and its lines are not the same count: paging by rows would carry
    /// the cursor much further than a screenful whenever headings and blanks
    /// sit between them. `PgDn` therefore moves a viewport of *lines* and
    /// lands on the row nearest where that puts it.
    public int select(KeyEvent event, int cursorRow, int viewportLines) {
        if (rowLines.length == 0) {
            return CursorPane.UNHANDLED;
        }
        int current = Math.max(0, Math.min(cursorRow, rowLines.length - 1));
        if (Keys.isPageUp(event)) {
            return rowNear(lineOfRow(current) - Math.max(1, viewportLines));
        }
        if (Keys.isPageDown(event)) {
            return rowNear(lineOfRow(current) + Math.max(1, viewportLines));
        }
        return CursorPane.select(event, current, rowLines.length);
    }

    /// The row whose line is nearest `line`, clamped to the document.
    private int rowNear(int line) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < rowLines.length; i++) {
            int distance = Math.abs(rowLines[i] - line);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /// The keybar fragment for this document at `viewportLines` high.
    ///
    /// Stated here rather than by each pane so that every document screen
    /// advertises the same keys on the same terms: paging is offered when the
    /// content is taller than the pane, which is a fact about lines, while
    /// stepping and jumping are offered when there is more than one row.
    public String hints(int viewportLines) {
        return new Keys.Hints()
                .add(rowCount() > 1, "[↑↓] move")
                .add(lineCount() > Math.max(1, viewportLines), "[PgDn/PgUp or Shift+↓↑] page")
                .add(rowCount() > 1, "[g/G] first/last")
                .build();
    }

    /// Where the window should start to show row `cursorRow`, given where it
    /// started last frame.
    ///
    /// The window slides the least it can, as it does on the list screens:
    /// moving the cursor up walks it to the top of the window before the
    /// content underneath moves. Recomputing the top from the cursor instead
    /// pins the cursor to the bottom row, so every step up drags the whole
    /// pane with it.
    ///
    /// Row zero pins the window to the first line so that any decoration
    /// above it — a leading section heading — is not stranded off the top,
    /// which the cursor could not reach to bring back.
    public int windowTop(int prevTop, int cursorRow, int viewport) {
        if (cursorRow <= 0) {
            return 0;
        }
        return RowWindow.adjustTop(prevTop, lineOfRow(cursorRow), viewport);
    }

    /// Where the window should start after the cursor moves from `fromRow` to
    /// `toRow`.
    ///
    /// The stored top is reconciled against `fromRow` before the move,
    /// because a state built before this pane first rendered — entering the
    /// screen — carries a top computed from whatever viewport the previous
    /// pane had. The frame the reader is looking at was drawn from the
    /// reconciled value, so moving from the stored one would step the body to
    /// catch up.
    public int windowTopAfterMove(int prevTop, int fromRow, int toRow, int viewport) {
        return windowTop(windowTop(prevTop, fromRow, viewport), toRow, viewport);
    }

    /// The row at `line`, or `-1` when that line is decoration. Panes use this
    /// to decide what `Enter` acts on.
    public int rowAtLine(int line) {
        for (int i = 0; i < rowLines.length; i++) {
            if (rowLines[i] == line) {
                return i;
            }
        }
        return -1;
    }

    /// Accumulates lines, recording which are rows. Callers add a line as a
    /// row when the cursor should stop on it and as decoration otherwise.
    public static final class Builder {
        private final List<Line> lines = new ArrayList<>();
        private final List<Integer> rows = new ArrayList<>();

        /// A line the cursor stops on.
        public Builder row(Line line) {
            rows.add(lines.size());
            lines.add(line);
            return this;
        }

        /// A line the cursor passes over: a heading, a blank, a continuation.
        public Builder decoration(Line line) {
            lines.add(line);
            return this;
        }

        public Builder blank() {
            return decoration(Line.empty());
        }

        public Document build() {
            int[] rowLines = new int[rows.size()];
            for (int i = 0; i < rowLines.length; i++) {
                rowLines[i] = rows.get(i);
            }
            return new Document(lines, rowLines);
        }
    }
}
