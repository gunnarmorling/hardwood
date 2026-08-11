/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.schema;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.ConvertedType;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.LogicalType.EdgeInterpolationAlgorithm;
import dev.hardwood.metadata.LogicalType.TimeUnit;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.metadata.SchemaElement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests for declaring logical type annotations through [FileSchema.Builder] and lowering them
/// back to the [SchemaElement] list written to the footer.
///
/// parquet-format requires a writer to emit the modern `LogicalType` union *and* the legacy
/// `converted_type` wherever one exists, so both representations are asserted on every
/// annotation that has both.
class FileSchemaLogicalTypeTest {

    private static SchemaElement element(FileSchema schema, String name) {
        return schema.toSchemaElements().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No schema element named " + name));
    }

    private static FileSchema withColumn(PhysicalType type, LogicalType logicalType) {
        return FileSchema.builder("schema")
                .addColumn("annotated", type, RepetitionType.OPTIONAL, logicalType)
                .build();
    }

    private static SchemaElement lowered(PhysicalType type, LogicalType logicalType) {
        return element(withColumn(type, logicalType), "annotated");
    }

    @Test
    void stringCarriesBothRepresentations() {
        SchemaElement string = lowered(PhysicalType.BYTE_ARRAY, new LogicalType.StringType());

        assertThat(string.logicalType()).isEqualTo(new LogicalType.StringType());
        assertThat(string.convertedType()).isEqualTo(ConvertedType.UTF8);
    }

    @Test
    void decimalCarriesScaleAndPrecisionAlongsideTheUnion() {
        SchemaElement decimal = lowered(PhysicalType.INT64, new LogicalType.DecimalType(2, 18));

        assertThat(decimal.logicalType()).isEqualTo(new LogicalType.DecimalType(2, 18));
        assertThat(decimal.convertedType()).isEqualTo(ConvertedType.DECIMAL);
        assertThat(decimal.scale()).isEqualTo(2);
        assertThat(decimal.precision()).isEqualTo(18);
    }

    @Test
    void signedAndUnsignedIntegersMapToTheirLegacyEnum() {
        assertThat(lowered(PhysicalType.INT32, new LogicalType.IntType(16, true)).convertedType())
                .isEqualTo(ConvertedType.INT_16);
        assertThat(lowered(PhysicalType.INT32, new LogicalType.IntType(16, false)).convertedType())
                .isEqualTo(ConvertedType.UINT_16);
        assertThat(lowered(PhysicalType.INT64, new LogicalType.IntType(64, false)).convertedType())
                .isEqualTo(ConvertedType.UINT_64);
    }

    /// The legacy annotations denoted UTC-normalized values, but parquet-format requires a
    /// writer to annotate local times with them too, so libraries predating the union still see
    /// an annotation. The union carries the exact semantics.
    @Test
    void localTimestampStillCarriesTheLegacyAnnotation() {
        SchemaElement local = lowered(PhysicalType.INT64, new LogicalType.TimestampType(false, TimeUnit.MILLIS));

        assertThat(local.logicalType()).isEqualTo(new LogicalType.TimestampType(false, TimeUnit.MILLIS));
        assertThat(local.convertedType()).isEqualTo(ConvertedType.TIMESTAMP_MILLIS);
    }

    @Test
    void nanosecondUnitsAreUnionOnly() {
        assertThat(lowered(PhysicalType.INT64, new LogicalType.TimestampType(true, TimeUnit.NANOS)).convertedType())
                .isNull();
        assertThat(lowered(PhysicalType.INT64, new LogicalType.TimeType(true, TimeUnit.NANOS)).convertedType())
                .isNull();
    }

    @Test
    void typesWithoutALegacyEquivalentAreUnionOnly() {
        SchemaElement uuid = element(FileSchema.builder("schema")
                .addColumn("id", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16,
                        new LogicalType.UuidType())
                .build(), "id");

        assertThat(uuid.logicalType()).isEqualTo(new LogicalType.UuidType());
        assertThat(uuid.convertedType()).isNull();
    }

    /// parquet.thrift reserves the INTERVAL union member without defining it, so an interval
    /// column is annotated by the legacy `converted_type` alone — and still reads back as the
    /// `IntervalType` the caller declared.
    @Test
    void intervalIsLegacyOnly() {
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("duration", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 12,
                        new LogicalType.IntervalType())
                .build();
        SchemaElement interval = element(schema, "duration");

        assertThat(interval.logicalType()).isNull();
        assertThat(interval.convertedType()).isEqualTo(ConvertedType.INTERVAL);
        assertThat(schema.getColumn("duration").logicalType()).isEqualTo(new LogicalType.IntervalType());
    }

    @Test
    void listAndMapGroupsCarryBothRepresentations() {
        FileSchema schema = FileSchema.builder("schema")
                .list("tags", RepetitionType.OPTIONAL, element -> element.primitive(
                        PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, new LogicalType.StringType()))
                .map("counts", RepetitionType.OPTIONAL, PhysicalType.BYTE_ARRAY,
                        value -> value.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
                .build();

        assertThat(element(schema, "tags").logicalType()).isEqualTo(new LogicalType.ListType());
        assertThat(element(schema, "tags").convertedType()).isEqualTo(ConvertedType.LIST);
        assertThat(element(schema, "counts").logicalType()).isEqualTo(new LogicalType.MapType());
        assertThat(element(schema, "counts").convertedType()).isEqualTo(ConvertedType.MAP);
        assertThat(element(schema, "element").logicalType()).isEqualTo(new LogicalType.StringType());
    }

    @Test
    void nestedFieldsCarryTheirAnnotation() {
        FileSchema schema = FileSchema.builder("schema")
                .struct("person", RepetitionType.OPTIONAL, person -> person
                        .addColumn("name", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL,
                                new LogicalType.StringType())
                        .addColumn("born", PhysicalType.INT32, RepetitionType.OPTIONAL,
                                new LogicalType.DateType()))
                .build();

        assertThat(schema.getColumn("person.name").logicalType()).isEqualTo(new LogicalType.StringType());
        assertThat(element(schema, "born").convertedType()).isEqualTo(ConvertedType.DATE);
    }

    /// A schema read from a file that predates the union carries only the legacy annotation.
    /// Writing it back out must emit both representations — and must not lose the decimal's
    /// scale and precision, which the legacy form keeps in sibling fields.
    @Test
    void legacyOnlySchemaIsRewrittenWithBothRepresentations() {
        List<SchemaElement> legacy = List.of(
                new SchemaElement("schema", null, null, RepetitionType.REQUIRED, 2, null, null, null, null, null),
                new SchemaElement("name", PhysicalType.BYTE_ARRAY, null, RepetitionType.OPTIONAL, null,
                        ConvertedType.UTF8, null, null, null, null),
                new SchemaElement("amount", PhysicalType.INT64, null, RepetitionType.REQUIRED, null,
                        ConvertedType.DECIMAL, 4, 15, null, null));

        List<SchemaElement> rewritten = FileSchema.fromSchemaElements(legacy).toSchemaElements();

        assertThat(rewritten.get(1).logicalType()).isEqualTo(new LogicalType.StringType());
        assertThat(rewritten.get(1).convertedType()).isEqualTo(ConvertedType.UTF8);
        assertThat(rewritten.get(2).logicalType()).isEqualTo(new LogicalType.DecimalType(4, 15));
        assertThat(rewritten.get(2).convertedType()).isEqualTo(ConvertedType.DECIMAL);
        assertThat(rewritten.get(2).scale()).isEqualTo(4);
        assertThat(rewritten.get(2).precision()).isEqualTo(15);
    }

    @Test
    void legacyOnlyListGroupIsRewrittenWithBothRepresentations() {
        List<SchemaElement> legacy = List.of(
                new SchemaElement("schema", null, null, RepetitionType.REQUIRED, 1, null, null, null, null, null),
                new SchemaElement("tags", null, null, RepetitionType.OPTIONAL, 1,
                        ConvertedType.LIST, null, null, null, null),
                new SchemaElement("list", null, null, RepetitionType.REPEATED, 1, null, null, null, null, null),
                new SchemaElement("element", PhysicalType.INT32, null, RepetitionType.OPTIONAL, null,
                        null, null, null, null, null));

        List<SchemaElement> rewritten = FileSchema.fromSchemaElements(legacy).toSchemaElements();

        assertThat(rewritten.get(1).logicalType()).isEqualTo(new LogicalType.ListType());
        assertThat(rewritten.get(1).convertedType()).isEqualTo(ConvertedType.LIST);
    }

    @Test
    void annotationMustMatchThePhysicalType() {
        assertThatThrownBy(() -> withColumn(PhysicalType.INT32, new LogicalType.StringType()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BYTE_ARRAY")
                .hasMessageContaining("annotated");
        assertThatThrownBy(() -> withColumn(PhysicalType.INT64, new LogicalType.DateType()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withColumn(PhysicalType.INT32, new LogicalType.IntType(64, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withColumn(PhysicalType.INT64, new LogicalType.TimeType(true, TimeUnit.MILLIS)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> withColumn(PhysicalType.BYTE_ARRAY,
                new LogicalType.GeographyType("OGC:CRS84", EdgeInterpolationAlgorithm.SPHERICAL)))
                .doesNotThrowAnyException();
    }

    @Test
    void fixedWidthAnnotationsRequireTheirExactLength() {
        assertThatThrownBy(() -> FileSchema.builder("schema")
                .addColumn("id", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 8,
                        new LogicalType.UuidType())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
        assertThatThrownBy(() -> FileSchema.builder("schema")
                .addColumn("half", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4,
                        new LogicalType.Float16Type())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }

    @Test
    void decimalPrecisionMustFitThePhysicalType() {
        assertThatThrownBy(() -> withColumn(PhysicalType.INT32, new LogicalType.DecimalType(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9");
        assertThatThrownBy(() -> withColumn(PhysicalType.INT64, new LogicalType.DecimalType(0, 19)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("18");
        // Four bytes of two's complement span 9 digits, the same as an INT32.
        assertThatThrownBy(() -> FileSchema.builder("schema")
                .addColumn("amount", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4,
                        new LogicalType.DecimalType(0, 10))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9");
        assertThatThrownBy(() -> withColumn(PhysicalType.BYTE_ARRAY, new LogicalType.DecimalType(5, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
        assertThatThrownBy(() -> withColumn(PhysicalType.BOOLEAN, new LogicalType.DecimalType(0, 4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupAnnotationsAreRejectedOnAPrimitive() {
        assertThatThrownBy(() -> withColumn(PhysicalType.BYTE_ARRAY, new LogicalType.ListType()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list");
        assertThatThrownBy(() -> withColumn(PhysicalType.BYTE_ARRAY, new LogicalType.MapType()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withColumn(PhysicalType.BYTE_ARRAY, new LogicalType.VariantType(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VARIANT");
    }

    /// UNKNOWN describes a column that holds only nulls, so it annotates any physical type.
    @Test
    void unknownAnnotatesAnyPhysicalType() {
        for (PhysicalType type : List.of(PhysicalType.BOOLEAN, PhysicalType.INT32, PhysicalType.DOUBLE,
                PhysicalType.BYTE_ARRAY)) {
            assertThat(lowered(type, new LogicalType.NullType()).logicalType())
                    .isEqualTo(new LogicalType.NullType());
        }
    }
}
