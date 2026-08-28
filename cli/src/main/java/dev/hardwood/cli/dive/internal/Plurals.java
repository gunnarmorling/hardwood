/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.dive.internal;

import dev.hardwood.cli.internal.Fmt;

/// Renders `count + noun` strings consistently across the `dive` TUI: picks
/// singular vs plural form based on the count, and formats the number with
/// the locale-independent grouping separator (comma). Handles irregular
/// plurals ("entry / entries", "leaf / leaves") by requiring both forms from
/// the caller. Zero takes the plural form (standard English convention).
public final class Plurals {

    private Plurals() {
    }

    public static String format(long count, String singular, String plural) {
        return Fmt.fmt("%,d", count) + " " + (count == 1 ? singular : plural);
    }

    /// "12-31 of 4,096" for the rows a window actually shows, or "0" when
    /// there are none. `window` is the same slice the body renders, so the
    /// title cannot report a range the reader is not looking at.
    public static String rangeOf(RowWindow window, int total) {
        if (total <= 0) {
            return "0";
        }
        int start = window.start() + 1;
        int end = Math.min(total, window.end());
        if (start == end) {
            return Fmt.fmt("%,d of %,d", start, total);
        }
        return Fmt.fmt("%,d-%,d of %,d", start, end, total);
    }
}
