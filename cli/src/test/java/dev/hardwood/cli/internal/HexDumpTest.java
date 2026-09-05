/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The window has to contain the byte it was asked about and start on a row
/// boundary, at both ends of a file and everywhere between.
///
/// Both properties were broken at some point by a change that preserved the
/// other: aligning before clamping to the end of the file produced unaligned
/// rows, and clamping before aligning put the window entirely before the byte.
class HexDumpTest {

    private static final int WINDOW = 48;

    private static final long LENGTH = 161_813;

    @ParameterizedTest
    @ValueSource(longs = { 0, 1, 4, 7, 8, 100, 41_104, 161_752, 161_753, 161_805, 161_812 })
    void theWindowStartsOnARowAndContainsTheByte(long offset) {
        long start = HexDump.windowStart(offset, WINDOW, HexDump.NARROW_ROW);
        long end = Math.min(LENGTH, start + WINDOW);

        assertThat(start % HexDump.NARROW_ROW).as("row-aligned start for %d", offset).isZero();
        assertThat(start).as("never before the file for %d", offset).isNotNegative();
        assertThat(offset).as("byte %d inside [%d, %d)", offset, start, end)
                .isGreaterThanOrEqualTo(start).isLessThan(end);
    }

    /// Bounded and positional: the window costs the same wherever in a region
    /// the failure is, which is what stops a failure at the end of a large page
    /// reading or rendering more than one at its start.
    @Test
    void theWindowIsTheSameSizeWhereverTheFailureIs() {
        long atStart = HexDump.windowStart(8, WINDOW, HexDump.NARROW_ROW);
        long atEnd = HexDump.windowStart(1_000_000, WINDOW, HexDump.NARROW_ROW);

        assertThat(HexDump.rows(new byte[WINDOW], atStart, HexDump.NARROW_ROW))
                .hasSameSizeAs(HexDump.rows(new byte[WINDOW], atEnd, HexDump.NARROW_ROW));
    }

    /// Absolute offsets, so the gutter reads the same as the offset in the
    /// message beside it and as what a hex editor shows.
    @Test
    void theGutterCarriesAbsoluteOffsets() {
        List<HexDump.Row> rows = HexDump.rows(new byte[16], 0xa090, HexDump.NARROW_ROW);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).offset()).isEqualTo(0xa090);
        assertThat(rows.get(0).text()).startsWith("00a090  ");
        assertThat(rows.get(1).text()).startsWith("00a098  ");
    }

    /// Printable ASCII shows as itself and everything else as a dot, so the
    /// gutter is readable without being mistaken for data.
    @Test
    void theAsciiGutterShowsOnlyPrintableBytes() {
        byte[] bytes = { 'P', 'A', 'R', '1', 0x00, 0x1f, 0x7f, (byte) 0xff };

        assertThat(HexDump.rows(bytes, 0, HexDump.NARROW_ROW).get(0).text())
                .isEqualTo("000000  50 41 52 31 00 1f 7f ff  PAR1....");
    }

    /// A row shorter than the full width still lines its ASCII up, so a partial
    /// last row does not shift the column.
    @Test
    void aShortFinalRowKeepsItsColumns() {
        List<HexDump.Row> rows = HexDump.rows(new byte[] { 1, 2, 3 }, 0, HexDump.NARROW_ROW);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).text()).isEqualTo("000000  01 02 03                 ...");
    }

    /// The byte column is what lets a caller style one byte rather than the
    /// line holding it.
    @Test
    void theByteColumnPointsAtThatBytesDigits() {
        String text = HexDump.rows(new byte[] { 0, 0, (byte) 0xab, 0 }, 0, HexDump.NARROW_ROW)
                .get(0).text();
        int column = HexDump.byteColumn(2);

        assertThat(text.substring(column, column + 2)).isEqualTo("ab");
    }
}
