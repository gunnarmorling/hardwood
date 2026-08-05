/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal.table;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamedTableTest {

    @Test
    void rendersWideCharsAligned() {
        String out = render(new String[]{"city", "n"},
                List.of(new String[]{"Montevideo", "1"},
                        new String[]{"말도나도주", "2"}),
                40, true, false);

        assertThat(out).isEqualTo("""
                +------------+---+
                | city       | n |
                +------------+---+
                | Montevideo | 1 |
                | 말도나도주 | 2 |
                +------------+---+
                """);
    }

    @Test
    void widensColumnForWideHeader() {
        String out = render(new String[]{"漢字水", "n"},
                List.<String[]>of(new String[]{"ab", "1"}),
                40, true, false);

        assertLinesAligned(out);
    }

    @Test
    void keepsMixedScriptRowsAligned() {
        String out = render(new String[]{"A", "B"},
                List.of(new String[]{"buenos aires", "12"},
                        new String[]{"말도나도주", "3"},
                        new String[]{"漢字水", "4"},
                        new String[]{"コキンボ", "5"}),
                40, true, false);

        assertLinesAligned(out);
    }

    @Test
    void truncatesByDisplayWidth() {
        String out = render(new String[]{"city", "n"},
                List.<String[]>of(new String[]{"말도나도주", "1"}),
                6, true, false);

        assertLinesAligned(out);
        // 10 cells of Hangul clipped to a 6-cell column. A third syllable would
        // overflow, so the row stops at two and leaves one cell of slack.
        assertThat(out).contains("말도…").doesNotContain("나");
    }

    /// A one-cell column has no room for content beside the ellipsis, so the cell
    /// collapses to the ellipsis alone rather than overflowing the border.
    @Test
    void truncatesToEllipsisAloneInASingleCellColumn() {
        String out = render(new String[]{"n"},
                List.<String[]>of(new String[]{"abcdef"}),
                1, true, false);

        assertLinesAligned(out);
        assertThat(out).contains("| … |");
    }

    /// A column narrower than a single wide glyph must still produce square output:
    /// the odd cell is padded rather than filled with an overflowing glyph.
    @Test
    void wrapsByDisplayWidth() {
        String out = render(new String[]{"city", "n"},
                List.<String[]>of(new String[]{"말도나도주", "1"}),
                5, false, false);

        assertLinesAligned(out);
    }

    @Test
    void wrappingPreservesCellContent() {
        String out = render(new String[]{"c"},
                List.<String[]>of(new String[]{"漢字水"}),
                5, false, false);

        assertLinesAligned(out);
        assertThat(out.replaceAll("[^漢字水]", "")).isEqualTo("漢字水");
    }

    /// An empty cell wraps to zero lines; a row of nothing but empty cells must
    /// still occupy one line rather than vanishing from the table.
    @Test
    void rendersRowOfEmptyCellsAsBlankRow() {
        String out = render(new String[]{"a", "b"},
                List.of(new String[]{"x", "y"},
                        new String[]{"", ""}),
                40, false, false);

        assertLinesAligned(out);
        assertThat(out.split("\n")).hasSize(6);
    }

    /// Column widths come from the first `sampleSize` rows only, so a wide-character
    /// row arriving later is wider than its column and has to be clipped to fit.
    @Test
    void truncatesRowsArrivingAfterTheWidthSample() {
        String out = render(new String[]{"city", "n"},
                List.of(new String[]{"ab", "1"},
                        new String[]{"말도나도주", "2"}),
                1, 40, true, false);

        assertLinesAligned(out);
        assertThat(out).contains("말…");
    }

    @Test
    void wrapsRowsArrivingAfterTheWidthSample() {
        String out = render(new String[]{"city", "n"},
                List.of(new String[]{"ab", "1"},
                        new String[]{"말도나도주", "2"}),
                1, 40, false, false);

        assertLinesAligned(out);
        assertThat(out.replaceAll("[^말도나주]", "")).isEqualTo("말도나도주");
    }

    private static void assertLinesAligned(String rendered) {
        String[] lines = rendered.split("\n");
        int expected = RowTable.displayWidth(lines[0]);
        for (String line : lines) {
            assertThat(RowTable.displayWidth(line))
                    .as("line width: %s", line)
                    .isEqualTo(expected);
        }
    }

    private static String render(String[] headers, List<String[]> rows,
            int maxWidth, boolean truncate, boolean rowDelimiter) {
        return render(headers, rows, rows.size(), maxWidth, truncate, rowDelimiter);
    }

    private static String render(String[] headers, List<String[]> rows, int sampleSize,
            int maxWidth, boolean truncate, boolean rowDelimiter) {
        Iterator<IntFunction<String>> iterator = rows.stream()
                .map(row -> (IntFunction<String>) i -> row[i])
                .iterator();

        StringWriter sink = new StringWriter();
        PrintWriter out = new PrintWriter(sink);
        new StreamedTable().print(out, headers, iterator, sampleSize, maxWidth, truncate, rowDelimiter);
        out.flush();
        return sink.toString();
    }
}
