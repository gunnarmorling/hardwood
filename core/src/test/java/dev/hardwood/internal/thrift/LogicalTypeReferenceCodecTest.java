/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.apache.parquet.format.EdgeInterpolationAlgorithm;
import org.apache.parquet.format.FieldRepetitionType;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.GeographyType;
import org.apache.parquet.format.LogicalType;
import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.format.Type;
import org.apache.parquet.format.Util;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/// Checks Hardwood's hand-written readers against footers produced by parquet-java, an
/// independent implementation of the same spec.
///
/// A fixture built by this project's own datagen only proves the reader agrees with the writer
/// that produced it: both can encode a field the same wrong way and the round trip still passes.
/// These cases encode with `org.apache.parquet.format`, so a field whose wire type Hardwood has
/// wrong fails here even when every in-house fixture agrees with itself.
///
/// The reference types share their simple names with Hardwood's metadata records, so the
/// Hardwood side is qualified: `org.apache.parquet.format` is imported, since it dominates the
/// encoding side.
class LogicalTypeReferenceCodecTest {

    /// `GeographyType.algorithm` is `optional EdgeInterpolationAlgorithm`, a Thrift **enum**, so
    /// the reference writes it as an `i32` — not as a struct holding a union whose set variant
    /// names the algorithm. Reading it as the latter skips the field and reports every geography
    /// column as the `SPHERICAL` default (#909).
    @Test
    void geographyAlgorithmMatchesTheReferenceEncoding() throws IOException {
        dev.hardwood.metadata.LogicalType parsed =
                readLogicalType(referenceFooter("EPSG:4326", EdgeInterpolationAlgorithm.THOMAS));

        assertThat(parsed).isInstanceOf(dev.hardwood.metadata.LogicalType.GeographyType.class);
        dev.hardwood.metadata.LogicalType.GeographyType geography =
                (dev.hardwood.metadata.LogicalType.GeographyType) parsed;
        assertThat(geography.crs()).isEqualTo("EPSG:4326");
        assertThat(geography.edgeInterpolation())
                .isEqualTo(dev.hardwood.metadata.LogicalType.EdgeInterpolationAlgorithm.THOMAS);
    }

    /// Every algorithm the reference knows decodes to the constant of the same name, which pins
    /// the numbering and not just the wire type — the mapping was off by one as well.
    @Test
    void everyReferenceAlgorithmDecodesToItsOwnConstant() throws IOException {
        for (EdgeInterpolationAlgorithm algorithm : EdgeInterpolationAlgorithm.values()) {
            dev.hardwood.metadata.LogicalType parsed =
                    readLogicalType(referenceFooter("OGC:CRS84", algorithm));
            assertThat(((dev.hardwood.metadata.LogicalType.GeographyType) parsed).edgeInterpolation().name())
                    .as("algorithm %s (thrift value %d)", algorithm.name(), algorithm.getValue())
                    .isEqualTo(algorithm.name());
        }
    }

    /// A footer holding one `GEOGRAPHY` column, serialized by the reference implementation.
    private static byte[] referenceFooter(String crs, EdgeInterpolationAlgorithm algorithm)
            throws IOException {
        GeographyType geography = new GeographyType();
        geography.setCrs(crs);
        geography.setAlgorithm(algorithm);

        SchemaElement root = new SchemaElement("schema");
        root.setNum_children(1);
        SchemaElement column = new SchemaElement("city_geom");
        column.setType(Type.BYTE_ARRAY);
        column.setRepetition_type(FieldRepetitionType.OPTIONAL);
        column.setLogicalType(LogicalType.GEOGRAPHY(geography));

        FileMetaData metaData = new FileMetaData(1, List.of(root, column), 0, List.of());
        metaData.setCreated_by("parquet-format-structures (reference)");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Util.writeFileMetaData(metaData, out);
        return out.toByteArray();
    }

    /// Parses the footer with Hardwood and returns the geography column's logical type.
    private static dev.hardwood.metadata.LogicalType readLogicalType(byte[] footer) {
        ThriftCompactReader reader = new ThriftCompactReader(
                ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN));
        dev.hardwood.metadata.FileMetaData metaData =
                assertDoesNotThrow(() -> FileMetaDataReader.read(reader));
        return metaData.schema().get(1).logicalType();
    }
}
