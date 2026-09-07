/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import dev.hardwood.InputFile;
import dev.hardwood.cli.internal.HexDump;
import dev.hardwood.cli.internal.Strings;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

/// What a screen shows instead of itself when the file will not give up what it
/// was asked for.
///
/// The message is the reader's, which by the time it reaches here names the
/// file, the column, the region and the byte the read gave up on. This adds no
/// wording of its own beyond the hint: a sentence invented here would be a
/// second, vaguer account of something already described precisely.
public final class ReadFailureOverlay {

    private static final int WIDTH = 60;

    /// Bytes of file shown around the offset. Bounded and read positionally, so
    /// a failure at the end of a large region costs exactly what one at its
    /// start does.
    private static final int WINDOW = 48;

    private ReadFailureOverlay() {
    }

    /// The text for `e`, or its simple class name when it has none so the box
    /// is never blank.
    ///
    /// `new UncheckedIOException(cause)` reports the cause's `toString()` as its
    /// own message, which would put a fully qualified class name in front of the
    /// reader; that bare form is unwrapped. A wrapper built with a message of
    /// its own is kept — that is the one carrying the read context.
    public static String messageOf(Throwable e) {
        Throwable reported = e;
        if (e instanceof UncheckedIOException && e.getCause() != null
                && e.getCause().toString().equals(e.getMessage())) {
            reported = e.getCause();
        }
        String message = reported.getMessage();
        return message == null || message.isBlank()
                ? reported.getClass().getSimpleName()
                : message;
    }

    /// Lines the overlay would show at the width the last frame wrapped to, so
    /// the key handler and the renderer agree on how far it can scroll.
    public static int lineCount(String message, List<HexDump.Row> window, long offset) {
        return lines(message, Keys.modalWidth(), window, offset).size();
    }

    public static void render(Buffer buffer, Rect screenArea, String message,
                              List<HexDump.Row> window, long offset, int scroll) {
        Rect widthProbe = ScrollPane.modalArea(screenArea, WIDTH, screenArea.height());
        List<Line> lines = lines(message, ScrollPane.modalWidth(widthProbe), window, offset);
        Rect area = ScrollPane.modalArea(screenArea, WIDTH, lines.size() + 4);
        ScrollPane.renderModal(buffer, area, "Read failed", lines, scroll, "[Esc] back");
    }

    /// The bytes at `offset`, or an empty list when there are none to show.
    ///
    /// A parse that failed over bytes that read cleanly will read them cleanly
    /// again, which is what makes this safe; a read that failed outright has
    /// nothing to show and fails here too, so the overlay falls back to its
    /// message rather than putting its own rendering back on the path that just
    /// broke.
    public static List<HexDump.Row> windowAt(InputFile inputFile, long offset) {
        try {
            long length = inputFile.length();
            long start = HexDump.windowStart(offset, WINDOW, HexDump.NARROW_ROW);
            int size = Math.toIntExact(Math.min(WINDOW, Math.max(0, length - start)));
            if (size <= 0) {
                return List.of();
            }
            ByteBuffer buffer = inputFile.readRange(start, size);
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            return HexDump.rows(bytes, start, HexDump.NARROW_ROW);
        }
        catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /// One row of the dump, with the byte the read gave up on picked out in the
    /// error tone along with its row's offset.
    ///
    /// The byte, not the line holding it: a whole red row says "everything here
    /// is wrong", which for eight bytes of which one is the subject is a
    /// stronger claim than the reader has. Nothing is marked in the gutter
    /// either — the marker glyph means "Enter acts on this" everywhere else in
    /// dive, and borrowing it to mean "this byte is wrong" would be a lie.
    private static Line hexLine(HexDump.Row row, long offset) {
        String text = row.text();
        int index = (int) (offset - row.offset());
        if (index < 0 || index >= HexDump.NARROW_ROW) {
            return Line.from(Span.raw(" " + text));
        }
        int column = HexDump.byteColumn(index);
        if (column + 2 > text.length()) {
            return Line.from(Span.raw(" " + text));
        }
        return Line.from(
                Span.raw(" "),
                new Span(text.substring(0, HexDump.gutterWidth()), Theme.error()),
                Span.raw(text.substring(HexDump.gutterWidth(), column)),
                new Span(text.substring(column, column + 2), Theme.error()),
                Span.raw(text.substring(column + 2)));
    }

    private static List<Line> lines(String message, int width, List<HexDump.Row> window,
                                    long offset) {
        List<Line> lines = new ArrayList<>();
        // One column narrower than the box allows, so the inset matches on both
        // sides — wrapping to the full width insets the left and lets the text
        // touch the right border.
        for (String chunk : Strings.wordWrap(message, Math.max(1, width - 1))) {
            lines.add(Line.from(Span.raw(" " + chunk)));
        }
        if (window.isEmpty()) {
            return lines;
        }
        lines.add(Line.empty());
        for (HexDump.Row row : window) {
            lines.add(hexLine(row, offset));
        }
        return lines;
    }
}
