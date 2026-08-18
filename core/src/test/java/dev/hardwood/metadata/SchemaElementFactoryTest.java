/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.schema.FileSchema;

import static dev.hardwood.metadata.SchemaElement.fixedLengthPrimitive;
import static dev.hardwood.metadata.SchemaElement.group;
import static dev.hardwood.metadata.SchemaElement.primitive;
import static dev.hardwood.metadata.SchemaElement.root;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaElementFactoryTest {

    @Test
    void groupEqualsCanonicalConstruction() {
        assertThat(group("address", RepetitionType.OPTIONAL, 3))
                .isEqualTo(new SchemaElement("address", null, null, RepetitionType.OPTIONAL, 3, null, null, null, null,
                        null));
    }

    @Test
    void annotatedGroupEqualsCanonicalConstruction() {
        LogicalType listType = new LogicalType.ListType();
        assertThat(group("items", RepetitionType.OPTIONAL, 1, listType))
                .isEqualTo(new SchemaElement("items", null, null, RepetitionType.OPTIONAL, 1, null, null, null, null,
                        listType));
    }

    @Test
    void primitiveEqualsCanonicalConstruction() {
        assertThat(primitive("zip", PhysicalType.INT32, RepetitionType.OPTIONAL))
                .isEqualTo(new SchemaElement("zip", PhysicalType.INT32, null, RepetitionType.OPTIONAL, null, null, null,
                        null, null, null));
    }

    @Test
    void annotatedPrimitiveEqualsCanonicalConstruction() {
        LogicalType stringType = new LogicalType.StringType();
        assertThat(primitive("city", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, stringType))
                .isEqualTo(new SchemaElement("city", PhysicalType.BYTE_ARRAY, null, RepetitionType.OPTIONAL, null, null,
                        null, null, null, stringType));
    }

    @Test
    void fixedLengthPrimitiveEqualsCanonicalConstruction() {
        assertThat(fixedLengthPrimitive("token", 4, RepetitionType.OPTIONAL))
                .isEqualTo(new SchemaElement("token", PhysicalType.FIXED_LEN_BYTE_ARRAY, 4, RepetitionType.OPTIONAL,
                        null, null, null, null, null, null));
    }

    @Test
    void annotatedFixedLengthPrimitiveEqualsCanonicalConstruction() {
        LogicalType uuidType = new LogicalType.UuidType();
        assertThat(fixedLengthPrimitive("id", 16, RepetitionType.REQUIRED, uuidType))
                .isEqualTo(new SchemaElement("id", PhysicalType.FIXED_LEN_BYTE_ARRAY, 16, RepetitionType.REQUIRED, null,
                        null, null, null, null, uuidType));
    }

    @Test
    void groupIsAGroupAndPrimitivesArePrimitive() {
        assertThat(group("g", RepetitionType.OPTIONAL, 0).isGroup()).isTrue();
        assertThat(group("g", RepetitionType.OPTIONAL, 0).isPrimitive()).isFalse();
        assertThat(primitive("p", PhysicalType.INT32, RepetitionType.OPTIONAL).isPrimitive()).isTrue();
        assertThat(primitive("p", PhysicalType.INT32, RepetitionType.OPTIONAL).isGroup()).isFalse();
        assertThat(fixedLengthPrimitive("f", 4, RepetitionType.OPTIONAL).isPrimitive()).isTrue();
        assertThat(fixedLengthPrimitive("f", 4, RepetitionType.OPTIONAL).isGroup()).isFalse();
    }

    /// A root element carries no repetition. `RecordFilterMicroBenchmark` and many fixtures
    /// build one that way, and `FileSchema.fromSchemaElements` defaults it to `REQUIRED`.
    @Test
    void groupKeepsANullRepetition() {
        SchemaElement root = root("root", 4);

        assertThat(root.repetitionType()).isNull();
        assertThat(root).isEqualTo(new SchemaElement("root", null, null, null, 4, null, null, null, null, null));
    }

    /// `type_length` on a non-FLBA column is the maximum bit length of a value, which the
    /// primitive factories cannot express. `withTypeLength` is the only way to set it.
    @Test
    void withTypeLengthSetsTheMaximumBitLength() {
        assertThat(primitive("tag", PhysicalType.INT32, RepetitionType.REQUIRED).withTypeLength(3))
                .isEqualTo(new SchemaElement("tag", PhysicalType.INT32, 3, RepetitionType.REQUIRED, null, null, null,
                        null, null, null));
    }

    @Test
    void withTypeLengthKeepsEveryOtherComponent() {
        LogicalType stringType = new LogicalType.StringType();
        SchemaElement annotated = primitive("city", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, stringType);

        assertThat(annotated.withTypeLength(12))
                .isEqualTo(new SchemaElement("city", PhysicalType.BYTE_ARRAY, 12, RepetitionType.OPTIONAL, null, null,
                        null, null, null, stringType));
    }

    @Test
    void withTypeLengthReplacesAFixedWidth() {
        assertThat(fixedLengthPrimitive("token", 4, RepetitionType.OPTIONAL).withTypeLength(8))
                .isEqualTo(fixedLengthPrimitive("token", 8, RepetitionType.OPTIONAL));
    }

    @Test
    void withTypeLengthRejectsAGroupAndANonPositiveLength() {
        assertThatThrownBy(() -> group("g", RepetitionType.OPTIONAL, 1).withTypeLength(4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry a type length");
        assertThatThrownBy(() -> primitive("p", PhysicalType.INT32, RepetitionType.REQUIRED).withTypeLength(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive type length");
    }

    @Test
    void aNullLogicalTypeMatchesTheShorterOverload() {
        assertThat(group("g", RepetitionType.OPTIONAL, 2, null)).isEqualTo(group("g", RepetitionType.OPTIONAL, 2));
        assertThat(primitive("p", PhysicalType.INT64, RepetitionType.REQUIRED, null))
                .isEqualTo(primitive("p", PhysicalType.INT64, RepetitionType.REQUIRED));
        assertThat(fixedLengthPrimitive("f", 8, RepetitionType.REQUIRED, null))
                .isEqualTo(fixedLengthPrimitive("f", 8, RepetitionType.REQUIRED));
    }

    /// The record accepts a null name, and so does the read path: a footer missing Thrift
    /// field 4 decodes to a null-named element that `FileSchema.toSchemaElements()` writes
    /// back out. A factory that rejected null would stop that round trip.
    @Test
    void everyFactoryAcceptsANullName() {
        assertThat(group(null, RepetitionType.REQUIRED, 1))
                .isEqualTo(new SchemaElement(null, null, null, RepetitionType.REQUIRED, 1, null, null, null, null,
                        null));
        assertThat(primitive(null, PhysicalType.INT32, RepetitionType.REQUIRED))
                .isEqualTo(new SchemaElement(null, PhysicalType.INT32, null, RepetitionType.REQUIRED, null, null, null,
                        null, null, null));
        assertThat(fixedLengthPrimitive(null, 4, RepetitionType.REQUIRED))
                .isEqualTo(new SchemaElement(null, PhysicalType.FIXED_LEN_BYTE_ARRAY, 4, RepetitionType.REQUIRED, null,
                        null, null, null, null, null));
    }

    @Test
    void primitiveRejectsANullType() {
        assertThatThrownBy(() -> primitive("value", null, RepetitionType.OPTIONAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value")
                .hasMessageContaining("null type denotes a group");
    }

    @Test
    void primitiveRejectsFixedLenByteArray() {
        assertThatThrownBy(() -> primitive("token", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.OPTIONAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token")
                .hasMessageContaining("fixedLengthPrimitive");
    }

    @Test
    void groupRejectsANegativeChildCount() {
        assertThatThrownBy(() -> group("address", RepetitionType.OPTIONAL, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");
    }

    @Test
    void groupAcceptsNoChildren() {
        assertThat(group("empty", RepetitionType.OPTIONAL, 0).numChildren()).isZero();
    }

    @Test
    void fixedLengthPrimitiveRejectsANonPositiveWidth() {
        assertThatThrownBy(() -> fixedLengthPrimitive("token", 0, RepetitionType.OPTIONAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
        assertThatThrownBy(() -> fixedLengthPrimitive("token", -4, RepetitionType.OPTIONAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    /// Record equality proves the factories match the constructor. This proves the rest of
    /// the system accepts what they build.
    @Test
    void factoryBuiltElementsRoundTripThroughFileSchema() {
        List<SchemaElement> elements = List.of(
                root("root", 3),
                group("items", RepetitionType.OPTIONAL, 1, new LogicalType.ListType()),
                primitive("element", PhysicalType.INT32, RepetitionType.REQUIRED),
                primitive("name", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, new LogicalType.StringType()),
                fixedLengthPrimitive("id", 16, RepetitionType.REQUIRED, new LogicalType.UuidType()));

        FileSchema schema = FileSchema.fromSchemaElements(elements);

        assertThat(schema.getName()).isEqualTo("root");
        // The root comes back with the REQUIRED that fromSchemaElements reads into an absent
        // repetition; every other element survives the trip unchanged.
        assertThat(schema.toSchemaElements()).containsExactly(
                new SchemaElement("root", null, null, RepetitionType.REQUIRED, 3, null, null, null, null, null),
                elements.get(1),
                elements.get(2),
                elements.get(3),
                elements.get(4));
    }
}
