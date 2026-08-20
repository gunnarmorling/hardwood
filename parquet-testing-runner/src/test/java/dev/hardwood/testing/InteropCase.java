/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.writer.WriterConfig;

/// One point of the write-path interop gate's flat matrix: a single-column file described by its
/// physical type, its repetition shape, how many distinct values it holds, how many rows, and the
/// writer configuration that produced it.
///
/// The values themselves are a pure function of the row index — [#ordinal] maps a row onto a
/// value ordinal, which [TypeFixture#value] turns into the value — so the case is both what the
/// writer is fed and what parquet-java's output is asserted against.
///
/// @param name what this point of the matrix covers, for the test display name
/// @param type the physical type under test
/// @param nullability the repetition shape
/// @param distinct how many distinct values the column cycles through
/// @param rows how many rows to write
/// @param config the writer configuration
/// @param plainOnly whether the case's values are expected to argue against a dictionary, so the
///        chunk is written `PLAIN` even though dictionary encoding is enabled
record InteropCase(String name, TypeFixture type, Nullability nullability, int distinct, int rows,
        WriterConfig config, boolean plainOnly) {

    /// A case whose values argue for a dictionary.
    static InteropCase of(String name, TypeFixture type, Nullability nullability, int distinct, int rows,
            WriterConfig config) {
        return new InteropCase(name, type, nullability, distinct, rows, config, false);
    }

    /// The repetition shapes a flat column can be written in.
    enum Nullability {

        /// No definition-level stream at all.
        REQUIRED(RepetitionType.REQUIRED),
        /// A levelled column whose every row happens to be present.
        OPTIONAL_ALL_PRESENT(RepetitionType.OPTIONAL),
        /// A levelled column with nulls interleaved among the values.
        OPTIONAL_SOME_NULL(RepetitionType.OPTIONAL),
        /// A levelled column with no present value at all, which carries a null count but no
        /// bounds and no dictionary page.
        OPTIONAL_ALL_NULL(RepetitionType.OPTIONAL);

        private final RepetitionType repetitionType;

        Nullability(RepetitionType repetitionType) {
            this.repetitionType = repetitionType;
        }

        RepetitionType repetitionType() {
            return repetitionType;
        }
    }

    /// Every third row is null in the interleaved shape, which puts nulls both inside and across
    /// the page and row-group boundaries the layout axis produces.
    private static final int NULL_EVERY = 3;

    /// Whether row `row` is written as null.
    boolean isNull(int row) {
        return switch (nullability) {
            case REQUIRED, OPTIONAL_ALL_PRESENT -> false;
            case OPTIONAL_SOME_NULL -> row % NULL_EVERY == NULL_EVERY - 1;
            case OPTIONAL_ALL_NULL -> true;
        };
    }

    /// The value ordinal of row `row`.
    int ordinal(int row) {
        return row % distinct;
    }

    /// The per-row null mask to pass to the batch setter, or `null` to use the mask-less setter.
    /// An `OPTIONAL` column with no nulls deliberately goes through the mask-less setter, which
    /// is the shape that writes an all-present definition-level stream.
    boolean[] nulls() {
        if (nullability == Nullability.REQUIRED || nullability == Nullability.OPTIONAL_ALL_PRESENT) {
            return null;
        }
        boolean[] nulls = new boolean[rows];
        for (int r = 0; r < rows; r++) {
            nulls[r] = isNull(r);
        }
        return nulls;
    }

    /// How many rows are written as null.
    long nullCount() {
        long nulls = 0;
        for (int r = 0; r < rows; r++) {
            if (isNull(r)) {
                nulls++;
            }
        }
        return nulls;
    }

    /// Whether the produced column chunks should declare `RLE_DICTIONARY`. An all-null chunk has
    /// no value to intern, so it writes no dictionary page.
    boolean expectsDictionary() {
        return config.enableDictionary() && type.dictionaryCapable()
                && nullability != Nullability.OPTIONAL_ALL_NULL
                && !plainOnly;
    }

    @Override
    public String toString() {
        return type + " / " + name + " / " + nullability;
    }
}
