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
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamedTableTest {

    @Test
    void truncatesWideCharactersWithoutMisaligningTheTable() {
        String output = render(List.<String[]>of(new String[]{"말도나도주"}), 6, true);

        assertThat(output).isEqualTo("""
                +--------+
                | name   |
                +--------+
                | 말도…  |
                +--------+""");
        assertEqualDisplayWidths(output);
    }

    @Test
    void wrapsWideCharactersAtDisplayWidthBoundaries() {
        String output = render(List.<String[]>of(new String[]{"말도나"}), 4, false);

        assertThat(output).isEqualTo("""
                +------+
                | name |
                +------+
                | 말도 |
                | 나   |
                +------+""");
        assertEqualDisplayWidths(output);
    }

    @Test
    void doesNotTruncateSurrogatePairsThatFitTheDisplayWidth() {
        String output = render(List.<String[]>of(new String[]{"😀abcd"}), 5, true);

        assertThat(output).isEqualTo("""
                +-------+
                | name  |
                +-------+
                | 😀abcd |
                +-------+""");
        assertEqualDisplayWidths(output);
    }

    @Test
    void truncatesSurrogatePairsOnCodePointBoundaries() {
        // The truncation boundary falls mid-cell where an emoji sits: it must be dropped
        // whole, never split into a lone surrogate. "😀" occupies two char units, so a
        // code-unit-based cut would land inside the pair.
        String output = render(List.<String[]>of(new String[]{"😀😀😀"}), "x", 2, true);

        assertThat(output).isEqualTo("""
                +----+
                | x  |
                +----+
                | 😀… |
                +----+""");
        assertEqualDisplayWidths(output);
    }

    @Test
    void emitsWideCharacterInColumnNarrowerThanOneWideCell() {
        // Guards the wrap force-progress branch: a wide char whose width (2) exceeds the
        // column width (1) must still advance one code point per line rather than loop
        // forever. It necessarily overflows the border — nothing narrower is possible.
        String output = render(List.<String[]>of(new String[]{"가나"}), "x", 1, false);

        assertThat(output).isEqualTo("""
                +---+
                | x |
                +---+
                | 가 |
                | 나 |
                +---+""");
    }

    private static String render(List<String[]> rows, int maxWidth, boolean truncate) {
        return render(rows, "name", maxWidth, truncate);
    }

    private static String render(List<String[]> rows, String header, int maxWidth, boolean truncate) {
        StringWriter output = new StringWriter();
        new StreamedTable().print(
                new PrintWriter(output),
                new String[]{header},
                rows.stream()
                        .<IntFunction<String>>map(row -> column -> row[column])
                        .iterator(),
                rows.size(),
                maxWidth,
                truncate,
                false);
        return output.toString().stripTrailing();
    }

    private static void assertEqualDisplayWidths(String output) {
        int expectedWidth = RowTable.displayWidth(output.lines().findFirst().orElseThrow());
        assertThat(output.lines()).allSatisfy(line ->
                assertThat(RowTable.displayWidth(line)).isEqualTo(expectedWidth));
    }
}
