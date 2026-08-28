/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.schema;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaPathResolverTest {

    private static final FileSchema SCHEMA = FileSchema.builder("root")
            .addColumn("id", PhysicalType.INT32, RepetitionType.REQUIRED)
            .struct("address", RepetitionType.OPTIONAL, address -> address
                    .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL))
            .list("scores", RepetitionType.OPTIONAL,
                    element -> element.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
            .build();

    @Test
    void resolvesTopLevelLeaf() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "id");

        assertThat(resolution.node()).isInstanceOf(SchemaNode.PrimitiveNode.class);
        assertThat(resolution.node().name()).isEqualTo("id");
        assertThat(resolution.topLevelChildIndex()).isZero();
        assertThat(resolution.blockedByPrimitive()).isFalse();
    }

    @Test
    void resolvesGroup() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "address");

        assertThat(resolution.node()).isInstanceOf(SchemaNode.GroupNode.class);
        assertThat(resolution.topLevelChildIndex()).isEqualTo(1);
    }

    @Test
    void resolvesNestedLeaf() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "address.city");

        assertThat(resolution.node()).isInstanceOf(SchemaNode.PrimitiveNode.class);
        assertThat(resolution.node().name()).isEqualTo("city");
        assertThat(resolution.topLevelChildIndex()).isEqualTo(1);
    }

    @Test
    void resolvesThroughTheSyntheticListLevels() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "scores.list.element");

        assertThat(resolution.node()).isInstanceOf(SchemaNode.PrimitiveNode.class);
        assertThat(resolution.topLevelChildIndex()).isEqualTo(2);
    }

    @Test
    void unknownTopLevelNameResolvesToNothing() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "nope");

        assertThat(resolution.node()).isNull();
        assertThat(resolution.topLevelChildIndex()).isEqualTo(-1);
        assertThat(resolution.blockedByPrimitive()).isFalse();
    }

    @Test
    void unknownNestedNameKeepsTheTopLevelIndex() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "address.nope");

        assertThat(resolution.node()).isNull();
        assertThat(resolution.topLevelChildIndex()).isEqualTo(1);
        assertThat(resolution.blockedByPrimitive()).isFalse();
    }

    @Test
    void descendingIntoALeafIsReportedSeparately() {
        SchemaPathResolver.Resolution resolution = SchemaPathResolver.resolve(SCHEMA, "id.city");

        assertThat(resolution.node()).isNull();
        assertThat(resolution.topLevelChildIndex()).isZero();
        assertThat(resolution.blockedByPrimitive()).isTrue();
    }

    @Test
    void emptyAndTrailingDotPathsResolveToNothing() {
        assertThat(SchemaPathResolver.resolve(SCHEMA, "").node()).isNull();
        assertThat(SchemaPathResolver.resolve(SCHEMA, "address.").node()).isNull();
    }
}
