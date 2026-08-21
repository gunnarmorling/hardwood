/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.text.CharWidth;

import static org.assertj.core.api.Assertions.assertThat;

class StringsTest {

    @Test
    void hardWrapChunksAsciiAtTheWidthBoundary() {
        assertThat(Strings.hardWrap("abcdefgh", 3)).containsExactly("abc", "def", "gh");
        assertThat(Strings.hardWrap("abc", 3)).containsExactly("abc");
    }

    @Test
    void hardWrapPreservesHardLineBreaks() {
        assertThat(Strings.hardWrap("ab\n\ncd", 4)).containsExactly("ab", "", "cd");
    }

    @Test
    void hardWrapCountsWideGlyphsAsTwoCells() {
        // Each CJK ideograph occupies two cells, so only two fit in five.
        List<String> lines = Strings.hardWrap("日本語テキスト", 5);
        assertThat(lines).containsExactly("日本", "語テ", "キス", "ト");
        for (String line : lines) {
            assertThat(CharWidth.of(line)).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void hardWrapCountsCombiningMarksAsZeroCells() {
        // 10 chars, 5 cells: every 'e' carries a zero-width combining accent.
        String accented = "e\u0301".repeat(5);
        assertThat(accented).hasSize(10);
        assertThat(Strings.hardWrap(accented, 5))
                .as("the string already fits five cells, so it must not be split")
                .containsExactly(accented);
    }

    @Test
    void hardWrapEmitsAGlyphWiderThanTheBudgetOnItsOwnLine() {
        assertThat(Strings.hardWrap("日本", 1))
                .as("overflowing by one cell beats looping forever making no progress")
                .containsExactly("日", "本");
    }
}
