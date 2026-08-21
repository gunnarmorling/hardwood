/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import org.apache.parquet.column.Encoding;

import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.PhysicalType;

/// The vocabulary of the write-path coverage assertion described in
/// `_designs/WRITE_COVERAGE_ASSERTION.md`: what a cell is, and how one is spelled.
///
/// A **cell** is one point of one projection, spelled as a string so that it survives the trip
/// through the file [CoverageRegistry] writes and [WriteCoverageVerdictTest] reads back. The
/// same spelling is produced by [CoverageDomain], which says which cells must be reached, and by
/// the registry, which says which were.
///
/// The projections are pairwise rather than the full cross product of every dimension. Each
/// exists because a defect class lives in that pair and in no smaller one; together they hold
/// the writer's capabilities to a few hundred cells rather than the tens of thousands a cross
/// product would demand.
final class Coverage {

    private Coverage() {
    }

    /// One required view of the space the writer can produce.
    enum Projection {

        /// Physical type against the encoding its data pages declare. The value encoders are per
        /// type, so this is where an encoding defect lives — the #901 class, a stream only a
        /// lenient decoder accepts.
        TYPE_ENCODING,

        /// Page encoding against the codec its body is compressed with. Stage 19 widened both
        /// sides at once, and the framing of an unusual page body is what this pair holds.
        ENCODING_CODEC,

        /// Physical type against the repetition shape of the column holding it. The level streams
        /// and the value stream are written together and read together.
        TYPE_REPETITION,

        /// `FIXED_LEN_BYTE_ARRAY` length against page encoding. `BYTE_STREAM_SPLIT` scatters by
        /// byte position and `DELTA_BYTE_ARRAY` shares prefixes, so both are length-sensitive
        /// where the other encodings are not.
        FIXED_LENGTH_ENCODING,

        /// Logical-type annotation against the storage form of the chunk carrying it. An
        /// annotation's comparator governs the chunk's bounds in either form, and the dictionary
        /// path reaches those bounds through a different accumulator.
        ANNOTATION_STORAGE,

        /// Logical-type annotation against the boundary class of the values written under it.
        /// The ends of an annotation's declared range are where the statistics comparator is
        /// fragile, and where a value the annotation cannot hold must be refused.
        ANNOTATION_BOUNDARY
    }

    /// The repetition shape of a column chunk, as the footer reports it. The three `OPTIONAL`
    /// shapes differ in the definition-level stream they produce, not in the schema, so they are
    /// told apart by the chunk's null count rather than by its descriptor.
    enum RepetitionShape {

        /// No definition-level stream at all.
        REQUIRED,

        /// A levelled column whose every value is present.
        OPTIONAL_ALL_PRESENT,

        /// A levelled column with nulls among the values.
        OPTIONAL_SOME_NULL,

        /// A levelled column with no present value, which carries a null count but no bounds and
        /// no dictionary page.
        OPTIONAL_ALL_NULL,

        /// A column under a repeated field, which carries a repetition-level stream. Recorded
        /// where it occurs; the required set is the flat shapes, which the nested shapes vary
        /// rather than replace.
        REPEATED
    }

    /// How a column chunk stores its values.
    enum StorageForm {

        /// A dictionary page and `RLE_DICTIONARY` indices.
        DICTIONARY,

        /// The values themselves, under whichever encoding was chosen.
        NO_DICTIONARY,

        /// A group node, which carries an annotation but no values of its own.
        GROUP
    }

    /// Where in an annotation's declared range a written value sits, or how one outside it was
    /// refused.
    ///
    /// For an annotation that narrows its physical type, [#MIN] and [#MAX] are the ends
    /// `LogicalTypeValueRange` computes. For one that narrows nothing they are the extremes of
    /// the order the annotation puts on that type — which for an unsigned integer is not the
    /// type's own: every bit pattern is a value, and the largest is the all-ones one the signed
    /// storage spells `-1`.
    enum BoundaryClass {

        /// The smallest value the column may hold.
        MIN,

        /// The largest value the column may hold.
        MAX,

        /// A value strictly between the two.
        INTERIOR,

        /// A value below the declared minimum, refused by the columnar API.
        BELOW_MIN_BATCH,

        /// A value below the declared minimum, refused by the row API.
        BELOW_MIN_ROW,

        /// A value above the declared maximum, refused by the columnar API.
        ABOVE_MAX_BATCH,

        /// A value above the declared maximum, refused by the row API.
        ABOVE_MAX_ROW,

        /// The nulls an `UNKNOWN` column carries, that annotation admitting no value at all.
        NULLS_ONLY,

        /// A value on an `UNKNOWN` column, refused by the columnar API.
        VALUE_REJECTED_BATCH,

        /// A value on an `UNKNOWN` column, refused by the row API.
        VALUE_REJECTED_ROW
    }

    /// The cell for one physical type carrying one page encoding.
    static String typeEncoding(PhysicalType type, Encoding encoding) {
        return cell(Projection.TYPE_ENCODING, type.name(), encoding.name());
    }

    /// The cell for one page encoding compressed with one codec.
    static String encodingCodec(Encoding encoding, CompressionCodec codec) {
        return cell(Projection.ENCODING_CODEC, encoding.name(), codec.name());
    }

    /// The cell for one physical type in one repetition shape.
    static String typeRepetition(PhysicalType type, RepetitionShape shape) {
        return cell(Projection.TYPE_REPETITION, type.name(), shape.name());
    }

    /// The cell for one `FIXED_LEN_BYTE_ARRAY` length carrying one page encoding.
    static String fixedLengthEncoding(int typeLength, Encoding encoding) {
        return cell(Projection.FIXED_LENGTH_ENCODING, Integer.toString(typeLength), encoding.name());
    }

    /// The cell for one annotation on one carrier, stored one way.
    static String annotationStorage(String annotationKey, String carrier, StorageForm form) {
        return cell(Projection.ANNOTATION_STORAGE, annotationKey, carrier, form.name());
    }

    /// The cell for one annotation on one carrier, reached at one boundary class.
    ///
    /// The carrier is part of the cell because it is part of the range: a `DECIMAL(1, 0)` over an
    /// `INT32` is bounded by arithmetic on the precision, and the same annotation over a
    /// `BYTE_ARRAY` by the magnitude its bytes spell. Collapsing the two would let one stand in
    /// for the other.
    static String annotationBoundary(String annotationKey, String carrier, BoundaryClass boundary) {
        return cell(Projection.ANNOTATION_BOUNDARY, annotationKey, carrier, boundary.name());
    }

    /// How a carrier is spelled inside an [Projection#ANNOTATION_STORAGE] cell: the physical
    /// type, with the declared length where it has one, since a `DECIMAL` behaves differently
    /// over each type that can carry it.
    ///
    /// @param type the carrier's physical type
    /// @param typeLength its declared length, or `null` where the type has none
    /// @return the carrier's spelling
    static String carrier(PhysicalType type, Integer typeLength) {
        return typeLength == null ? type.name() : type.name() + "(" + typeLength + ")";
    }

    /// The projection a cell belongs to, for grouping a failure by what it says is missing.
    static Projection projectionOf(String cell) {
        return Projection.valueOf(cell.substring(0, cell.indexOf(SEPARATOR)));
    }

    private static final char SEPARATOR = '|';

    private static String cell(Projection projection, String... parts) {
        StringBuilder cell = new StringBuilder(projection.name());
        for (String part : parts) {
            cell.append(SEPARATOR).append(part);
        }
        return cell.toString();
    }
}
