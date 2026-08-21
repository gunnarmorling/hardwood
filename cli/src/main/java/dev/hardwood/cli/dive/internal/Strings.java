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

import dev.tamboui.text.CharWidth;

/// Small string helpers shared by `dive` screens. Kept here so the
/// truncation/padding behavior — including the ellipsis character — stays
/// consistent across every screen that draws columnar content.
public final class Strings {

    /// The character used to mark visually-truncated content. Centralised
    /// here so changes propagate to every screen at once.
    public static final char ELLIPSIS = '…';

    private Strings() {
    }

    /// Pads `s` on the right with spaces to at least `width` columns. Strings
    /// already at or above `width` are returned unchanged (no truncation).
    public static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }

    /// Truncates `s` from the left so the suffix stays visible (e.g. for
    /// long column paths, where the trailing leaf name is the distinctive
    /// part). Strings within `maxWidth` are returned unchanged.
    public static String truncateLeft(String s, int maxWidth) {
        if (s.length() <= maxWidth) {
            return s;
        }
        return ELLIPSIS + s.substring(s.length() - maxWidth + 1);
    }

    /// Word-wraps `value` so each returned line fits within `width` cells.
    /// Hard line breaks in the source are preserved. Words longer than `width`
    /// are character-chunked so they don't overflow the boundary.
    public static List<String> wordWrap(String value, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            out.add(value);
            return out;
        }
        for (String line : value.split("\n", -1)) {
            if (line.isEmpty()) {
                out.add("");
                continue;
            }
            String[] words = line.split(" ", -1);
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                while (word.length() > width) {
                    if (!currentLine.isEmpty()) {
                        out.add(currentLine.toString());
                        currentLine.setLength(0);
                    }
                    out.add(word.substring(0, width));
                    word = word.substring(width);
                }
                if (currentLine.isEmpty()) {
                    currentLine.append(word);
                } else if (currentLine.length() + 1 + word.length() <= width) {
                    currentLine.append(" ").append(word);
                } else {
                    out.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentLine.append(word);
                }
            }
            if (!currentLine.isEmpty()) {
                out.add(currentLine.toString());
            }
        }
        return out;
    }

    /// Splits `value` into display lines of at most `width` cells without
    /// respecting word boundaries. Hard line breaks in the source are
    /// preserved; each segment is then chunked at `width` if it's longer.
    ///
    /// Chunking measures display cells, not `char`s, so that a line's rendered
    /// width matches what callers budgeted for: wide glyphs (CJK, emoji) count
    /// double and combining marks count zero.
    public static List<String> hardWrap(String value, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            out.add(value);
            return out;
        }
        for (String line : value.split("\n", -1)) {
            if (line.isEmpty()) {
                out.add("");
                continue;
            }
            String rest = line;
            while (!rest.isEmpty()) {
                String chunk = CharWidth.substringByWidth(rest, width);
                if (chunk.isEmpty()) {
                    // A single glyph wider than the whole budget: emit it on
                    // its own line and overflow by one cell rather than loop
                    // forever making no progress.
                    chunk = rest.substring(0, rest.offsetByCodePoints(0, 1));
                }
                out.add(chunk);
                rest = rest.substring(chunk.length());
            }
        }
        return out;
    }
}
