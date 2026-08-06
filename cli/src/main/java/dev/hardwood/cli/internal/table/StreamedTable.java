/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal.table;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

// note: align text left since it is how people do read in english
public class StreamedTable {

    public void print(PrintWriter out, String[] headers,
               Iterator<IntFunction<String>> iterator,
               int sampleSize, int maxWidth, boolean truncate, boolean rowDelimiter) {
        int n = headers.length;

        // sample a few rows so we can better adjust the widths
        List<String[]> sampleRows = new ArrayList<>();
        for (int count = 0; count < sampleSize && iterator.hasNext(); count++) {
            IntFunction<String> next = iterator.next();
            sampleRows.add(IntStream.range(0, headers.length)
                    .mapToObj(next)
                    .toArray(String[]::new));
        }

        // compute column widths based on headers + sample rows
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            widths[i] = RowTable.displayWidth(headers[i]);
        }
        for (String[] row : sampleRows) {
            for (int i = 0; i < n; i++) {
                String cell = row[i];
                if (cell != null) {
                    widths[i] = Math.max(widths[i], RowTable.displayWidth(cell));
                }
            }
        }

        for (int i = 0; i < n; i++) {
            widths[i] = Math.min(widths[i], maxWidth);
        }

        String sep = makeSeparator(widths);

        out.println(sep);
        printRow(out, i -> headers[i], widths, truncate);
        out.println(sep);

        // catch up the sampled rows
        for (String[] row : sampleRows) {
            printRow(out, i -> row[i], widths, truncate);
            if (rowDelimiter) {
                out.println(sep);
            }
        }

        // finish the dataset content
        while (iterator.hasNext()) {
            IntFunction<String> rowFunc = iterator.next();
            printRow(out, rowFunc, widths, truncate);
            if (rowDelimiter) {
                out.println(sep);
            }
        }

        if (!rowDelimiter && !sampleRows.isEmpty()) {
            out.println(sep);
        }

        out.flush();
    }

    private String makeSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.repeat("-", w + 2).append("+");
        }
        return sb.toString();
    }

    private void printRow(PrintWriter out, IntFunction<String> rowFunc, int[] widths, boolean truncate) {
        int n = widths.length;
        if (truncate) {
            out.print("|");
            for (int i = 0; i < n; i++) {
                String cell = cellAt(rowFunc, i);
                if (RowTable.displayWidth(cell) > widths[i]) {
                    // The ellipsis itself occupies the last cell of the column.
                    cell = widths[i] == 0 ?
                            "" :
                            cell.substring(0, prefixEnd(cell, 0, widths[i] - 1)) + "…";
                }
                printPadded(out, cell, widths[i]);
            }
            out.println();
            return;
        }

        List<String[]> wrappedCells = new ArrayList<>();
        // An empty cell wraps to no lines at all, so a row whose cells are all empty
        // would print nothing and disappear from the table. Every row occupies at
        // least one line.
        int maxLines = 1;

        for (int i = 0; i < n; i++) {
            List<String> lines = wrap(cellAt(rowFunc, i), widths[i]);
            maxLines = Math.max(maxLines, lines.size());
            wrappedCells.add(lines.toArray(new String[0]));
        }

        for (int line = 0; line < maxLines; line++) {
            out.print("|");
            for (int i = 0; i < n; i++) {
                String[] lines = wrappedCells.get(i);
                String content = (line < lines.length) ? lines[line] : "";
                printPadded(out, content, widths[i]);
            }
            out.println();
        }
    }

    private static String cellAt(IntFunction<String> rowFunc, int i) {
        String cell = rowFunc.apply(i);
        return cell == null ? "" : cell;
    }

    /// Writes the cell followed by enough spaces to fill `width` terminal cells.
    /// `printf("%-Ns")` cannot be used because its padding counts UTF-16 code
    /// units, which under-pads East Asian wide characters.
    private static void printPadded(PrintWriter out, String cell, int width) {
        out.print(' ');
        out.print(cell);
        int padding = width - RowTable.displayWidth(cell);
        for (int i = 0; i < padding; i++) {
            out.print(' ');
        }
        out.print(" |");
    }

    /// Splits the cell into chunks of at most `width` terminal cells each.
    private static List<String> wrap(String cell, int width) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < cell.length()) {
            int end = prefixEnd(cell, start, width);
            if (end == start) {
                // The column is narrower than the next code point. Emit it on its own
                // so wrapping terminates; the cell overflows the column by one cell.
                end = start + Character.charCount(cell.codePointAt(start));
            }
            lines.add(cell.substring(start, end));
            start = end;
        }
        return lines;
    }

    /// Index just past the longest run starting at `from` whose display width fits
    /// `width`, never splitting a code point. Returns `from` when even the first
    /// code point does not fit.
    private static int prefixEnd(String s, int from, int width) {
        int used = 0;
        int i = from;
        int len = s.length();
        while (i < len) {
            int cp = s.codePointAt(i);
            int cellsUsed = RowTable.isWideCodePoint(cp) ? 2 : 1;
            if (used + cellsUsed > width) {
                break;
            }
            used += cellsUsed;
            i += Character.charCount(cp);
        }
        return i;
    }
}
