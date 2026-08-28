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

import org.junit.jupiter.api.Test;

import dev.tamboui.layout.Rect;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import static org.assertj.core.api.Assertions.assertThat;

/// The navigation every document pane in dive shares. These pin the rules
/// the panes are no longer each free to reinterpret.
class ScrollPaneTest {

    private static final int VIEWPORT = 10;
    private static final int LINES = 100;

    @Test
    void arrowsStepOneLineAndPageKeysStepAViewport() {
        assertThat(ScrollPane.scroll(key(KeyCode.DOWN), 50, LINES, VIEWPORT)).isEqualTo(51);
        assertThat(ScrollPane.scroll(key(KeyCode.UP), 50, LINES, VIEWPORT)).isEqualTo(49);
        assertThat(ScrollPane.scroll(key(KeyCode.PAGE_DOWN), 50, LINES, VIEWPORT)).isEqualTo(60);
        assertThat(ScrollPane.scroll(key(KeyCode.PAGE_UP), 50, LINES, VIEWPORT)).isEqualTo(40);
    }

    @Test
    void shiftedArrowsArePageKeys() {
        assertThat(ScrollPane.scroll(shift(KeyCode.DOWN), 50, LINES, VIEWPORT)).isEqualTo(60);
        assertThat(ScrollPane.scroll(shift(KeyCode.UP), 50, LINES, VIEWPORT)).isEqualTo(40);
    }

    @Test
    void jumpKeysGoToTheEnds() {
        assertThat(ScrollPane.scroll(chr('g'), 50, LINES, VIEWPORT)).isZero();
        assertThat(ScrollPane.scroll(chr('G'), 50, LINES, VIEWPORT)).isEqualTo(LINES - VIEWPORT);
    }

    @Test
    void movesAreClampedToTheContent() {
        assertThat(ScrollPane.scroll(key(KeyCode.PAGE_UP), 3, LINES, VIEWPORT)).isZero();
        assertThat(ScrollPane.scroll(key(KeyCode.PAGE_DOWN), 95, LINES, VIEWPORT))
                .isEqualTo(LINES - VIEWPORT);
    }

    @Test
    void aStaleOffsetIsClampedBeforeItIsAdjusted() {
        // Content that shrank underneath a scrolled pane — collapsing a
        // section, say — must not leave an offset that swallows keypresses.
        assertThat(ScrollPane.scroll(key(KeyCode.UP), 900, LINES, VIEWPORT))
                .isEqualTo(LINES - VIEWPORT - 1);
    }

    @Test
    void contentThatFitsIsHandledButDoesNotMove() {
        assertThat(ScrollPane.scroll(key(KeyCode.PAGE_DOWN), 0, 5, VIEWPORT)).isZero();
        assertThat(ScrollPane.overflows(5, VIEWPORT)).isFalse();
        assertThat(ScrollPane.maxScroll(5, VIEWPORT)).isZero();
    }

    @Test
    void keysThatDoNotScrollAreLeftToTheCaller() {
        assertThat(ScrollPane.scroll(key(KeyCode.ENTER), 50, LINES, VIEWPORT))
                .isEqualTo(ScrollPane.UNHANDLED);
        assertThat(ScrollPane.scroll(chr('t'), 50, LINES, VIEWPORT))
                .isEqualTo(ScrollPane.UNHANDLED);
    }

    @Test
    void theWindowIsTheVisibleSliceAndSurvivesAStaleOffset() {
        List<Line> lines = lines(LINES);
        assertThat(ScrollPane.window(lines, 20, VIEWPORT)).isEqualTo(lines.subList(20, 30));
        assertThat(ScrollPane.window(lines, 900, VIEWPORT)).isEqualTo(lines.subList(90, 100));
        assertThat(ScrollPane.window(lines(4), 0, VIEWPORT)).hasSize(4);
    }

    @Test
    void hintsAreEmptyWhileTheContentFits() {
        assertThat(ScrollPane.hints(5, VIEWPORT)).isEmpty();
        assertThat(ScrollPane.hints(LINES, VIEWPORT))
                .contains("[↑↓] scroll", "[PgDn/PgUp or Shift+↓↑] page", "[g/G] top/bottom");
    }

    @Test
    void modalGeometryLeavesRoomForItsChrome() {
        Rect screen = new Rect(0, 0, 120, 40);
        Rect modal = ScrollPane.modalArea(screen, 80, 16);
        assertThat(modal.width()).isEqualTo(80);
        assertThat(modal.height()).isEqualTo(16);
        // Borders, blank separator and hint row come off the height; borders
        // and the row inset come off the width.
        assertThat(ScrollPane.modalViewport(modal)).isEqualTo(12);
        assertThat(ScrollPane.modalWidth(modal)).isEqualTo(77);
    }

    @Test
    void modalGeometryShrinksToASmallTerminal() {
        Rect modal = ScrollPane.modalArea(new Rect(0, 0, 30, 10), 80, 16);
        assertThat(modal.width()).isEqualTo(26);
        assertThat(modal.height()).isEqualTo(8);
        assertThat(ScrollPane.modalViewport(modal)).isPositive();
        assertThat(ScrollPane.modalWidth(modal)).isPositive();
    }

    private static List<Line> lines(int count) {
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(Line.from(Span.raw("line " + i)));
        }
        return lines;
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
