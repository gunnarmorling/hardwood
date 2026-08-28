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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import static org.assertj.core.api.Assertions.assertThat;

/// The navigation every list pane in dive shares. These pin the rules the
/// screens are no longer each free to reinterpret.
class CursorPaneTest {

    private static final int COUNT = 100;

    @BeforeEach
    @AfterEach
    void clearObservedViewport() {
        Keys.resetObservedGeometry();
    }

    @Test
    void arrowsStepOneRowAndPageKeysStepAViewport() {
        assertThat(CursorPane.select(key(KeyCode.DOWN), 50, COUNT)).isEqualTo(51);
        assertThat(CursorPane.select(key(KeyCode.UP), 50, COUNT)).isEqualTo(49);
        int stride = Keys.viewportStride();
        assertThat(CursorPane.select(key(KeyCode.PAGE_DOWN), 50, COUNT)).isEqualTo(50 + stride);
        assertThat(CursorPane.select(key(KeyCode.PAGE_UP), 50, COUNT)).isEqualTo(50 - stride);
    }

    @Test
    void shiftedArrowsArePageKeys() {
        int stride = Keys.viewportStride();
        assertThat(CursorPane.select(shift(KeyCode.DOWN), 50, COUNT)).isEqualTo(50 + stride);
        assertThat(CursorPane.select(shift(KeyCode.UP), 50, COUNT)).isEqualTo(50 - stride);
    }

    @Test
    void jumpKeysGoToTheEnds() {
        assertThat(CursorPane.select(chr('g'), 50, COUNT)).isZero();
        assertThat(CursorPane.select(chr('G'), 50, COUNT)).isEqualTo(COUNT - 1);
    }

    @Test
    void movesAreClampedToTheRowCount() {
        assertThat(CursorPane.select(key(KeyCode.UP), 0, COUNT)).isZero();
        assertThat(CursorPane.select(key(KeyCode.DOWN), COUNT - 1, COUNT)).isEqualTo(COUNT - 1);
        assertThat(CursorPane.select(key(KeyCode.PAGE_UP), 3, COUNT)).isZero();
        assertThat(CursorPane.select(key(KeyCode.PAGE_DOWN), COUNT - 2, COUNT)).isEqualTo(COUNT - 1);
    }

    @Test
    void aStaleSelectionIsClampedBeforeItIsAdjusted() {
        // The row count can shrink underneath a selection — a filter being
        // typed, say — without the screen having reset it first.
        assertThat(CursorPane.select(key(KeyCode.DOWN), 900, COUNT)).isEqualTo(COUNT - 1);
        assertThat(CursorPane.select(key(KeyCode.UP), 900, COUNT)).isEqualTo(COUNT - 2);
    }

    @Test
    void anEmptyListHasNothingToSelect() {
        assertThat(CursorPane.select(key(KeyCode.DOWN), 0, 0)).isEqualTo(CursorPane.UNHANDLED);
        assertThat(CursorPane.select(chr('G'), 0, 0)).isEqualTo(CursorPane.UNHANDLED);
    }

    @Test
    void keysThatDoNotMoveTheCursorAreLeftToTheCaller() {
        assertThat(CursorPane.select(key(KeyCode.ENTER), 50, COUNT)).isEqualTo(CursorPane.UNHANDLED);
        assertThat(CursorPane.select(chr('t'), 50, COUNT)).isEqualTo(CursorPane.UNHANDLED);
        assertThat(CursorPane.select(key(KeyCode.LEFT), 50, COUNT)).isEqualTo(CursorPane.UNHANDLED);
    }

    @Test
    void hintsListOnlyTheKeysWithSomewhereToGo() {
        assertThat(CursorPane.hints(0)).isEmpty();
        assertThat(CursorPane.hints(1)).isEmpty();
        assertThat(CursorPane.hints(2))
                .contains("[↑↓] move", "[g/G] first/last")
                .doesNotContain("page");
        assertThat(CursorPane.hints(Keys.viewportStride() + 1))
                .contains("[↑↓] move", "[PgDn/PgUp or Shift+↓↑] page", "[g/G] first/last");
    }

    @Test
    void theMarkerSaysWhatEnterCanActOnRatherThanWhereTheCursorIs() {
        // Mixed pane: every actionable row is marked, so a reader can see
        // which rows go somewhere without arrowing onto each one.
        assertThat(CursorPane.marker(true, false, true)).isEqualTo(CursorPane.MARKER);
        assertThat(CursorPane.marker(true, true, true)).isEqualTo(CursorPane.MARKER);
        assertThat(CursorPane.marker(false, false, true)).isEqualTo(CursorPane.NO_MARKER);
        // The cursor row is not exempt: a row Enter cannot act on says so
        // even while selected.
        assertThat(CursorPane.marker(false, true, true)).isEqualTo(CursorPane.NO_MARKER);
    }

    @Test
    void aUniformPaneMarksOnlyTheCursorRow() {
        // Nothing to distinguish, so a caret against every row would be a
        // column of identical glyphs.
        assertThat(CursorPane.marker(true, true, false)).isEqualTo(CursorPane.MARKER);
        assertThat(CursorPane.marker(true, false, false)).isEqualTo(CursorPane.NO_MARKER);
    }

    @Test
    void markedAndUnmarkedRowsAlignWithOneAnother() {
        assertThat(CursorPane.MARKER).hasSameSizeAs(CursorPane.NO_MARKER);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.NONE, '\0');
    }

    private static KeyEvent shift(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.SHIFT, '\0');
    }

    private static KeyEvent chr(char c) {
        return new KeyEvent(KeyCode.CHAR, KeyModifiers.NONE, c);
    }
}
