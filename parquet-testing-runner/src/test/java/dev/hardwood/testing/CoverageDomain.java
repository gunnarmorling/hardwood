/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.parquet.column.Encoding;

import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.internal.writer.EncodingSupport;
import dev.hardwood.internal.writer.LogicalTypeValueRange;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnEncoding;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.WriterConfig;

import static dev.hardwood.testing.Coverage.BoundaryClass;
import static dev.hardwood.testing.Coverage.RepetitionShape;
import static dev.hardwood.testing.Coverage.StorageForm;

/// The space the writer can produce, derived from the writer rather than listed beside it.
///
/// Every dimension comes from something the writer itself decides: the physical types and codecs
/// from what it accepts when asked to write one, the encodings from
/// [EncodingSupport], the annotations from the sealed [LogicalType] hierarchy, and the value
/// boundaries from the [LogicalTypeValueRange] the writer applies to them. A capability added to
/// the writer therefore adds a requirement here in the same commit, rather than waiting to be
/// noticed.
///
/// The required cells are pairwise projections of that space rather than its cross product. See
/// `_designs/WRITE_COVERAGE_ASSERTION.md` for why each projection is admitted.
final class CoverageDomain {

    private CoverageDomain() {
    }

    /// The `FIXED_LEN_BYTE_ARRAY` lengths the writer has a reason to see: the two annotations
    /// that fix a length of their own, the length `UUID` fixes, and the length the flat matrix
    /// writes. `BYTE_STREAM_SPLIT` scatters by byte position and `DELTA_BYTE_ARRAY` shares
    /// prefixes, so the length is part of what those encoders do rather than a detail above them.
    private static final List<Integer> FIXED_LENGTHS = List.of(2, 8, 12, 16);

    /// The repetition shapes required of a flat column. [RepetitionShape#REPEATED] is not among
    /// them: the nested shapes vary the level streams rather than the value encoders, and are
    /// enumerated by `WriterNestedInteropTest` on the types it declares rather than swept across
    /// all of them.
    private static final List<RepetitionShape> FLAT_SHAPES = List.of(
            RepetitionShape.REQUIRED,
            RepetitionShape.OPTIONAL_ALL_PRESENT,
            RepetitionShape.OPTIONAL_SOME_NULL,
            RepetitionShape.OPTIONAL_ALL_NULL);

    /// The name of the single column of the schemas this domain declares, and so of the column a
    /// test driven by it writes.
    static final String COLUMN = "c";

    /// The largest `DECIMAL` precision the domain asks any carrier for. A `BYTE_ARRAY` carrier
    /// accepts any precision at all, so a representative has to be chosen; this is the one a
    /// sixteen-byte fixed carrier tops out at, which makes the two agree.
    private static final int MAX_PROBED_PRECISION = 38;

    /// One annotation the writer can emit, on one carrier.
    ///
    /// @param logicalType the annotation
    /// @param carrier the physical type carrying it
    /// @param typeLength the carrier's declared length, or `null` where the type has none
    record Annotation(LogicalType logicalType, PhysicalType carrier, Integer typeLength) {

        /// This annotation's canonical key.
        String key() {
            return LogicalTypeKey.of(logicalType);
        }

        /// The spelling of the carrier inside a cell.
        String carrierKey() {
            return Coverage.carrier(carrier, typeLength);
        }

        @Override
        public String toString() {
            return key() + " on " + carrierKey();
        }
    }

    /// Every cell some test must produce.
    static Set<String> required() {
        Set<String> cells = new TreeSet<>();
        Set<Encoding> producible = EnumSet.noneOf(Encoding.class);

        for (PhysicalType type : writableTypes()) {
            for (Encoding encoding : pageEncodings(type)) {
                cells.add(Coverage.typeEncoding(type, encoding));
                producible.add(encoding);
            }
            for (RepetitionShape shape : FLAT_SHAPES) {
                cells.add(Coverage.typeRepetition(type, shape));
            }
        }

        for (Encoding encoding : producible) {
            for (CompressionCodec codec : producedCodecs()) {
                cells.add(Coverage.encodingCodec(encoding, codec));
            }
        }

        for (Encoding encoding : pageEncodings(PhysicalType.FIXED_LEN_BYTE_ARRAY)) {
            for (int typeLength : FIXED_LENGTHS) {
                cells.add(Coverage.fixedLengthEncoding(typeLength, encoding));
            }
        }

        for (Annotation annotation : annotations()) {
            for (StorageForm form : storageForms(annotation)) {
                cells.add(Coverage.annotationStorage(annotation.key(), annotation.carrierKey(), form));
            }
            for (BoundaryClass boundary : boundaryClasses(annotation)) {
                cells.add(Coverage.annotationBoundary(
                        annotation.key(), annotation.carrierKey(), boundary));
            }
        }

        for (LogicalType group : groupAnnotations()) {
            cells.add(Coverage.annotationStorage(
                    LogicalTypeKey.of(group), StorageForm.GROUP.name(), StorageForm.GROUP));
        }

        return cells;
    }

    /// The physical types the writer produces, found by asking it to write each one. `INT96` is
    /// the one it refuses; a release that gains it gains its requirements here with no edit.
    static List<PhysicalType> writableTypes() {
        List<PhysicalType> types = new ArrayList<>();
        for (PhysicalType type : PhysicalType.values()) {
            if (accepts(type, defaultLength(type), null, WriterConfig.defaults())) {
                types.add(type);
            }
        }
        return types;
    }

    /// The codecs the writer produces, found by asking it to compress with each one. `LZO` and
    /// the Hadoop-framed `LZ4` are the two it refuses, each for a reason of its own.
    static List<CompressionCodec> producedCodecs() {
        List<CompressionCodec> codecs = new ArrayList<>();
        for (CompressionCodec codec : CompressionCodec.values()) {
            if (accepts(PhysicalType.INT32, null, null,
                    WriterConfig.builder().codec(codec).build())) {
                codecs.add(codec);
            }
        }
        return codecs;
    }

    /// Every annotation point the writer must be shown, on the carrier it belongs to.
    ///
    /// The kinds come from the sealed hierarchy through [LogicalTypeKey], which does not compile
    /// until a new member is spelled. The parameters of the kinds that have them are enumerated
    /// here, each varying independently of the others: an `INT`'s width against its signedness, a
    /// `TIME` or `TIMESTAMP`'s unit against its UTC adjustment, and a `DECIMAL`'s precision
    /// against its scale on every carrier that can hold one.
    static List<Annotation> annotations() {
        List<Annotation> annotations = new ArrayList<>();

        binary(annotations, new LogicalType.StringType());
        binary(annotations, new LogicalType.EnumType());
        binary(annotations, new LogicalType.JsonType());
        binary(annotations, new LogicalType.BsonType());
        binary(annotations, new LogicalType.GeometryType("EPSG:4326"));
        binary(annotations, new LogicalType.GeographyType("EPSG:4326",
                LogicalType.EdgeInterpolationAlgorithm.KARNEY));

        annotations.add(new Annotation(new LogicalType.DateType(), PhysicalType.INT32, null));
        annotations.add(new Annotation(new LogicalType.NullType(), PhysicalType.INT32, null));
        fixed(annotations, new LogicalType.UuidType(), 16);
        fixed(annotations, new LogicalType.Float16Type(), 2);
        fixed(annotations, new LogicalType.IntervalType(), 12);

        for (int bitWidth : List.of(8, 16, 32, 64)) {
            for (boolean signed : List.of(true, false)) {
                annotations.add(new Annotation(new LogicalType.IntType(bitWidth, signed),
                        bitWidth == Long.SIZE ? PhysicalType.INT64 : PhysicalType.INT32, null));
            }
        }

        for (LogicalType.TimeUnit unit : LogicalType.TimeUnit.values()) {
            for (boolean utc : List.of(true, false)) {
                annotations.add(new Annotation(new LogicalType.TimeType(utc, unit),
                        unit == LogicalType.TimeUnit.MILLIS ? PhysicalType.INT32 : PhysicalType.INT64,
                        null));
                annotations.add(new Annotation(new LogicalType.TimestampType(utc, unit),
                        PhysicalType.INT64, null));
            }
        }

        decimals(annotations, PhysicalType.INT32, null);
        decimals(annotations, PhysicalType.INT64, null);
        decimals(annotations, PhysicalType.BYTE_ARRAY, null);
        decimals(annotations, PhysicalType.FIXED_LEN_BYTE_ARRAY, 16);

        return annotations;
    }

    /// The annotations that sit on a group node rather than on a column.
    static List<LogicalType> groupAnnotations() {
        return List.of(new LogicalType.ListType(), new LogicalType.MapType());
    }

    /// The range `annotation` declares, as the writer resolves it.
    static LogicalTypeValueRange rangeOf(Annotation annotation) {
        return LogicalTypeValueRange.of(columnOf(annotation));
    }

    /// The storage forms a column under `annotation` must be written in. An `UNKNOWN` column
    /// holds no value to intern, so it has no dictionary form.
    static Set<StorageForm> storageForms(Annotation annotation) {
        if (rangeOf(annotation).holdsNoValue()) {
            return EnumSet.of(StorageForm.NO_DICTIONARY);
        }
        return EnumSet.of(StorageForm.DICTIONARY, StorageForm.NO_DICTIONARY);
    }

    /// The boundary classes `annotation` must be exercised at.
    ///
    /// An annotation that narrows its physical type has ends to reach and values to refuse either
    /// side of them, through both write APIs. One that narrows nothing has only the physical
    /// type's own extremes, and nothing to refuse. `UNKNOWN` admits no value at all, so it has
    /// its nulls and the refusal of anything else.
    static Set<BoundaryClass> boundaryClasses(Annotation annotation) {
        LogicalTypeValueRange range = rangeOf(annotation);
        if (range.holdsNoValue()) {
            return EnumSet.of(BoundaryClass.NULLS_ONLY,
                    BoundaryClass.VALUE_REJECTED_BATCH, BoundaryClass.VALUE_REJECTED_ROW);
        }
        if (range.isBounded()) {
            return EnumSet.of(BoundaryClass.MIN, BoundaryClass.MAX, BoundaryClass.INTERIOR,
                    BoundaryClass.BELOW_MIN_BATCH, BoundaryClass.BELOW_MIN_ROW,
                    BoundaryClass.ABOVE_MAX_BATCH, BoundaryClass.ABOVE_MAX_ROW);
        }
        return EnumSet.of(BoundaryClass.MIN, BoundaryClass.MAX, BoundaryClass.INTERIOR);
    }

    /// The encodings a column of `type` can have on its data pages, over every policy legal for
    /// it. Every policy but [ColumnEncoding#AUTO] names one outright; `AUTO` reaches `PLAIN`
    /// always and `RLE_DICTIONARY` wherever a dictionary is possible at all.
    static Set<Encoding> pageEncodings(PhysicalType type) {
        Set<Encoding> encodings = EnumSet.noneOf(Encoding.class);
        for (ColumnEncoding policy : ColumnEncoding.values()) {
            if (!EncodingSupport.supports(policy, type)) {
                continue;
            }
            switch (policy) {
                case AUTO -> {
                    encodings.add(Encoding.PLAIN);
                    if (EncodingSupport.dictionaryCapable(type)) {
                        encodings.add(Encoding.RLE_DICTIONARY);
                    }
                }
                case PLAIN -> encodings.add(Encoding.PLAIN);
                case DELTA_BINARY_PACKED -> encodings.add(Encoding.DELTA_BINARY_PACKED);
                case DELTA_LENGTH_BYTE_ARRAY -> encodings.add(Encoding.DELTA_LENGTH_BYTE_ARRAY);
                case DELTA_BYTE_ARRAY -> encodings.add(Encoding.DELTA_BYTE_ARRAY);
                case BYTE_STREAM_SPLIT -> encodings.add(Encoding.BYTE_STREAM_SPLIT);
            }
        }
        return encodings;
    }

    /// A one-column schema carrying `annotation`, declared `OPTIONAL` so that every annotation —
    /// `UNKNOWN` among them, which admits nothing but nulls — can be declared the same way.
    static FileSchema schemaOf(Annotation annotation) {
        return declare(annotation.carrier(), annotation.typeLength(), annotation.logicalType());
    }

    private static ColumnSchema columnOf(Annotation annotation) {
        return schemaOf(annotation).getColumn(0);
    }

    /// The `DECIMAL` points of one carrier: the smallest precision and the largest that carrier
    /// holds, each with no scale at all and with a scale as large as the precision — the point at
    /// which a bound derived by arithmetic on the precision overflows.
    private static void decimals(List<Annotation> annotations, PhysicalType carrier, Integer typeLength) {
        for (int precision : List.of(1, maxDecimalPrecision(carrier, typeLength))) {
            for (int scale : List.of(0, precision)) {
                annotations.add(new Annotation(new LogicalType.DecimalType(scale, precision),
                        carrier, typeLength));
            }
        }
    }

    /// The largest `DECIMAL` precision `carrier` accepts, found by asking it, and capped at
    /// [#MAX_PROBED_PRECISION] for the carrier that accepts any precision at all.
    private static int maxDecimalPrecision(PhysicalType carrier, Integer typeLength) {
        int largest = 1;
        for (int precision = 1; precision <= MAX_PROBED_PRECISION; precision++) {
            if (!accepts(carrier, typeLength, new LogicalType.DecimalType(0, precision),
                    WriterConfig.defaults())) {
                break;
            }
            largest = precision;
        }
        return largest;
    }

    private static void binary(List<Annotation> annotations, LogicalType logicalType) {
        annotations.add(new Annotation(logicalType, PhysicalType.BYTE_ARRAY, null));
    }

    private static void fixed(List<Annotation> annotations, LogicalType logicalType, int typeLength) {
        annotations.add(new Annotation(logicalType, PhysicalType.FIXED_LEN_BYTE_ARRAY, typeLength));
    }

    /// Whether the writer accepts a one-column file of this shape. Both halves count: a schema
    /// the builder refuses and a configuration the writer refuses are equally "not produced".
    private static boolean accepts(PhysicalType type, Integer typeLength, LogicalType logicalType,
            WriterConfig config) {

        FileSchema schema;
        try {
            schema = declare(type, typeLength, logicalType);
        }
        catch (RuntimeException e) {
            return false;
        }
        try {
            ParquetFileWriter.create(new ByteBufferOutputFile(), schema, config).close();
            return true;
        }
        catch (RuntimeException e) {
            return false;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Probing what the writer accepts must not fail on I/O", e);
        }
    }

    private static FileSchema declare(PhysicalType type, Integer typeLength, LogicalType logicalType) {
        FileSchema.Builder schema = FileSchema.builder("coverage");
        if (typeLength == null) {
            return logicalType == null
                    ? schema.addColumn(COLUMN, type, RepetitionType.OPTIONAL).build()
                    : schema.addColumn(COLUMN, type, RepetitionType.OPTIONAL, logicalType).build();
        }
        return logicalType == null
                ? schema.addColumn(COLUMN, type, RepetitionType.OPTIONAL, typeLength.intValue()).build()
                : schema.addColumn(COLUMN, type, RepetitionType.OPTIONAL, typeLength.intValue(), logicalType)
                        .build();
    }

    /// The length a probe declares for a type that needs one.
    private static Integer defaultLength(PhysicalType type) {
        return type == PhysicalType.FIXED_LEN_BYTE_ARRAY ? 8 : null;
    }
}
