/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import java.util.ArrayList;
import java.util.List;

/// The offset / bytes / ASCII layout, in one place.
///
/// How many bytes to a row is the caller's, because it follows the width of the
/// box the rows go in. A sixteen-byte row needs about 78 columns: the key/value
/// modal grows to 120 and takes them comfortably, while the read-failure
/// overlay is capped at 60 and would clip the ASCII gutter off the right edge,
/// so it asks for [#NARROW_ROW] instead.
public final class HexDump {

    /// Hex digits the offset gutter is printed to.
    private static final int GUTTER_DIGITS = 6;

    /// Bytes to a row for a box too narrow for [#WIDE_ROW].
    public static final int NARROW_ROW = 8;

    /// Bytes to a row where there is room, which is what a hex editor shows.
    public static final int WIDE_ROW = 16;

    private HexDump() {
    }

    /// One row of the dump: the offset it starts at, and its rendered text.
    ///
    /// The offset comes back alongside the text so a caller can style the row
    /// holding a byte it cares about without re-deriving which row that is.
    ///
    /// @param offset the file offset of the row's first byte
    /// @param text   `offset  xx xx ..  ascii`
    public record Row(long offset, String text) {
    }

    /// Rows covering `bytes`, whose first byte sits at `baseOffset` in the file.
    ///
    /// Offsets in the gutter are absolute, so they read the same as the offset
    /// in the message beside them and as what a hex editor would show — a
    /// dump numbered from its own start would be a second coordinate system
    /// for the reader to reconcile.
    public static List<Row> rows(byte[] bytes, long baseOffset, int perRow) {
        List<Row> rows = new ArrayList<>();
        for (int start = 0; start < bytes.length; start += perRow) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < perRow; i++) {
                if (start + i < bytes.length) {
                    int b = bytes[start + i] & 0xff;
                    hex.append(Fmt.fmt("%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                }
                else {
                    hex.append("   ");
                }
            }
            // Trim only the space after the last byte position, never the
            // padding standing in for bytes a short final row does not have:
            // stripping that shifts the ASCII column left and the grid stops
            // lining up on the one row a reader is most likely to be looking at.
            rows.add(new Row(baseOffset + start,
                    Fmt.fmt("%0" + GUTTER_DIGITS + "x  %s  %s", baseOffset + start,
                            hex.substring(0, perRow * 3 - 1), ascii)));
        }
        return rows;
    }

    /// Index within a row's text where the offset gutter ends, so a caller can
    /// style the gutter of the row it cares about.
    public static int gutterWidth() {
        return GUTTER_DIGITS;
    }

    /// Index within a row's text where the `indexInRow`th byte's two hex digits
    /// begin, so a caller can style one byte rather than the line holding it.
    public static int byteColumn(int indexInRow) {
        return GUTTER_DIGITS + 2 + indexInRow * 3;
    }

    /// Where to start reading so that `offset` is shown with `window` bytes of
    /// context around it, aligned to a row so the gutter runs in whole rows.
    ///
    /// Bounded and positional: an offset at the end of a large region reads and
    /// renders exactly what one at its start does.
    ///
    /// Nothing is pulled back from the end of the file. Doing that and then
    /// aligning down can land the window entirely before the byte it was asked
    /// about — an offset in the file's last few bytes fell outside its own
    /// window. Near the end the caller simply reads fewer bytes, which is what
    /// it already does when the file is shorter than the window.
    public static long windowStart(long offset, int window, int perRow) {
        long start = Math.max(0, offset - window / 2);
        return start - start % perRow;
    }
}
