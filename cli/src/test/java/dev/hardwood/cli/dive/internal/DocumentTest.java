/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import static org.assertj.core.api.Assertions.assertThat;

/// A pane whose rows are not uniform: the cursor moves over the rows, the
/// window over the lines, and the two are not the same count.
class DocumentTest {

    /// Three sections of two facts each: a heading and a blank before every
    /// pair, so six rows across sixteen lines.
    private static Document sectioned() {
        Document.Builder b = Document.builder();
        for (int section = 0; section < 3; section++) {
            if (section > 0) {
                b.blank();
            }
            b.decoration(Line.from(Span.raw("section " + section)));
            b.row(Line.from(Span.raw("fact " + section + "a")));
            b.row(Line.from(Span.raw("fact " + section + "b")));
        }
        return b.build();
    }

    @BeforeEach
    @AfterEach
    void clearObservedViewport() {
        Keys.resetObservedGeometry();
    }

    @Test
    void rowsAreTheLinesTheCursorStopsOn() {
        Document doc = sectioned();
        assertThat(doc.rowCount()).isEqualTo(6);
        assertThat(doc.lineCount()).isEqualTo(11);
        assertThat(doc.lineOfRow(0)).isEqualTo(1);
        assertThat(doc.rowAtLine(0))
                .as("a heading is not a row")
                .isEqualTo(-1);
        assertThat(doc.rowAtLine(1)).isZero();
    }

    @Test
    void steppingMovesOneRowNotOneLine() {
        Document doc = sectioned();
        assertThat(doc.select(key(KeyCode.DOWN), 1, 8))
                .as("over the blank and the heading between the sections")
                .isEqualTo(2);
        assertThat(doc.select(key(KeyCode.UP), 2, 8)).isEqualTo(1);
    }

    @Test
    void pagingMovesAViewportOfLinesAndLandsOnARow() {
        Document doc = sectioned();
        // Row 0 is line 1; four lines on takes us to line 5, whose nearest
        // row is row 2 at line 5 — not four rows on, which would overshoot
        // the pane by the headings in between.
        assertThat(doc.select(key(KeyCode.PAGE_DOWN), 0, 4)).isEqualTo(2);
        assertThat(doc.select(key(KeyCode.PAGE_UP), 5, 4)).isEqualTo(3);
    }

    @Test
    void hintsOfferPagingOnLinesAndSteppingOnRows() {
        Document doc = sectioned();
        assertThat(doc.hints(40))
                .as("everything fits, so there is nothing to page to")
                .doesNotContain("page");
        assertThat(doc.hints(40)).contains("[↑↓] move", "[g/G] first/last");
        assertThat(doc.hints(4))
                .as("eleven lines do not fit in four, whatever the row count")
                .contains("[PgDn/PgUp or Shift+↓↑] page");
        assertThat(Document.builder().row(Line.empty()).build().hints(4))
                .as("a single row has nowhere to move")
                .isEmpty();
    }

    @Test
    void theWindowSlidesTheLeastItCan() {
        Document doc = sectioned();
        // Cursor at the last row with a four-line window: bottom-pinned.
        int bottom = doc.windowTop(0, 5, 4);
        assertThat(bottom).isEqualTo(doc.lineOfRow(5) - 3);
        // Stepping up one row stays inside that window, so it does not move.
        assertThat(doc.windowTop(bottom, 4, 4)).isEqualTo(bottom);
    }

    @Test
    void rowZeroPinsTheWindowToTheFirstLine() {
        Document doc = sectioned();
        assertThat(doc.windowTop(5, 0, 4))
                .as("so a leading heading is not stranded above a cursor that cannot reach it")
                .isZero();
    }

    @Test
    void aMoveReconcilesAStaleWindowBeforeSliding() {
        Document doc = sectioned();
        // A state built before this pane rendered carries a window computed
        // from another pane's viewport. The frame the reader sees was drawn
        // from the reconciled value, so the move must start there too.
        int stale = 0;
        int rendered = doc.windowTop(stale, 5, 4);
        assertThat(doc.windowTopAfterMove(stale, 5, 4, 4))
                .isEqualTo(doc.windowTop(rendered, 4, 4))
                .isEqualTo(rendered);
    }

    @Test
    void aDocumentOfPureDecorationHasNoCursor() {
        Document doc = Document.builder().decoration(Line.empty()).blank().build();
        assertThat(doc.rowCount()).isZero();
        assertThat(doc.lineOfRow(0)).isEqualTo(-1);
        assertThat(doc.select(key(KeyCode.DOWN), 0, 4)).isEqualTo(CursorPane.UNHANDLED);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.NONE, '\0');
    }
}
