/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluralsTest {

    @Test
    void formatPicksSingularForOneAndPluralOtherwise() {
        assertThat(Plurals.format(0, "row", "rows")).isEqualTo("0 rows");
        assertThat(Plurals.format(1, "row", "rows")).isEqualTo("1 row");
        assertThat(Plurals.format(2, "row", "rows")).isEqualTo("2 rows");
    }

    @Test
    void formatGroupsLargeNumbersWithComma() {
        assertThat(Plurals.format(12_400_000L, "row", "rows")).isEqualTo("12,400,000 rows");
    }

    @Test
    void rangeOfHandlesZeroTotal() {
        assertThat(Plurals.rangeOf(RowWindow.from(0, 0, 0, 10), 0)).isEqualTo("0");
    }

    @Test
    void rangeOfShowsSingleElementWhenTotalIsOne() {
        assertThat(Plurals.rangeOf(RowWindow.from(0, 0, 1, 10), 1)).isEqualTo("1 of 1");
    }

    @Test
    void rangeOfShowsFullRangeWhenTotalFitsViewport() {
        assertThat(Plurals.rangeOf(RowWindow.from(0, 2, 5, 10), 5)).isEqualTo("1-5 of 5");
        assertThat(Plurals.rangeOf(RowWindow.from(0, 0, 10, 10), 10)).isEqualTo("1-10 of 10");
    }

    @Test
    void rangeOfReportsTheWindowTheBodyIsShowing() {
        // Whatever slice the renderer took, the title says the same one —
        // scrolled up, scrolled down, or freshly entered.
        assertThat(Plurals.rangeOf(RowWindow.from(0, 0, 100, 10), 100)).isEqualTo("1-10 of 100");
        assertThat(Plurals.rangeOf(RowWindow.from(0, 10, 100, 10), 100)).isEqualTo("2-11 of 100");
        assertThat(Plurals.rangeOf(RowWindow.from(50, 55, 100, 10), 100)).isEqualTo("51-60 of 100");
        assertThat(Plurals.rangeOf(RowWindow.from(90, 99, 100, 10), 100)).isEqualTo("91-100 of 100");
    }

    @Test
    void rangeOfShowsASingleRowWithoutARange() {
        assertThat(Plurals.rangeOf(RowWindow.from(5, 5, 100, 1), 100)).isEqualTo("6 of 100");
    }

    @Test
    void rangeOfFormatsLargeTotalsWithComma() {
        assertThat(Plurals.rangeOf(RowWindow.from(0, 0, 12_400_000, 20), 12_400_000))
                .isEqualTo("1-20 of 12,400,000");
    }
}
