/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.ColumnMetaData;

import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I32;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I64;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_LIST;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_STRUCT;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnMetaDataReaderTest {

    @Test
    void readsSizeStatisticsAtFieldSixteen() throws IOException {
        byte[] sizeStatistics = struct()
                .field(1, TYPE_I64).i64(2048)
                .field(2, TYPE_LIST).i64List(6, 3)
                .field(3, TYPE_LIST).i64List(1, 8)
                .stop().build();

        ColumnMetaData metaData = read(baseColumn()
                .field(16, TYPE_STRUCT).nested(sizeStatistics)
                .stop().build());

        assertThat(metaData.sizeStatistics()).isNotNull();
        assertThat(metaData.sizeStatistics().unencodedByteArrayDataBytes()).isEqualTo(2048L);
        assertThat(metaData.sizeStatistics().repetitionLevelHistogram()).containsExactly(6L, 3L);
        assertThat(metaData.sizeStatistics().definitionLevelHistogram()).containsExactly(1L, 8L);
    }

    @Test
    void keepsParsingGeospatialStatisticsAfterSizeStatistics() throws IOException {
        // Field ids restart inside a nested struct. If the size-statistics decode leaked its
        // field-id context, the following field 17 would be misread.
        byte[] sizeStatistics = struct()
                .field(1, TYPE_I64).i64(512)
                .stop().build();
        byte[] geospatialStatistics = struct()
                .field(2, TYPE_LIST).i32List(3)
                .stop().build();

        ColumnMetaData metaData = read(baseColumn()
                .field(16, TYPE_STRUCT).nested(sizeStatistics)
                .field(17, TYPE_STRUCT).nested(geospatialStatistics)
                .stop().build());

        assertThat(metaData.sizeStatistics().unencodedByteArrayDataBytes()).isEqualTo(512L);
        assertThat(metaData.geospatialStatistics()).isNotNull();
        assertThat(metaData.geospatialStatistics().geospatialTypes()).containsExactly(3);
        assertThat(metaData.dataPageOffset()).isEqualTo(4L);
    }

    @Test
    void reportsAbsentSizeStatisticsAsNull() throws IOException {
        ColumnMetaData metaData = read(baseColumn().stop().build());

        assertThat(metaData.sizeStatistics()).isNull();
    }

    @Test
    void skipsWrongTypedSizeStatisticsField() throws IOException {
        // A malformed file types field 16 as an i64 rather than a struct.
        ColumnMetaData metaData = read(baseColumn()
                .field(16, TYPE_I64).i64(7)
                .field(17, TYPE_STRUCT).nested(struct().field(2, TYPE_LIST).i32List(1).stop().build())
                .stop().build());

        assertThat(metaData.sizeStatistics()).isNull();
        assertThat(metaData.geospatialStatistics().geospatialTypes()).containsExactly(1);
    }

    /// The required prefix of a `ColumnMetaData`: an uncompressed, `PLAIN`-encoded
    /// `INT32` column named `col`. Callers append the optional fields under test and
    /// terminate the struct themselves.
    private static ThriftStructBuilder baseColumn() {
        return struct()
                .field(1, TYPE_I32).i32(1) // INT32
                .field(2, TYPE_LIST).i32List(0) // PLAIN
                .field(3, TYPE_LIST).binaryList("col".getBytes(StandardCharsets.UTF_8))
                .field(4, TYPE_I32).i32(0) // UNCOMPRESSED
                .field(5, TYPE_I64).i64(100)
                .field(6, TYPE_I64).i64(1000)
                .field(7, TYPE_I64).i64(900)
                .field(9, TYPE_I64).i64(4);
    }

    private static ColumnMetaData read(byte[] thrift) throws IOException {
        return ColumnMetaDataReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));
    }

    private static ThriftStructBuilder struct() {
        return new ThriftStructBuilder();
    }
}
