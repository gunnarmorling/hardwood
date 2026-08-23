/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.Util;
import org.junit.jupiter.api.Test;

import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/// Oracle proving `nan_count` (field 9) is forward-compatible with a reader that predates it.
///
/// `parquet-format-structures` 1.17.1, the pinned test dependency, generates its `Statistics`
/// struct from a `parquet.thrift` version without `nan_count` — the same position a pre-#1017
/// reader is in. Thrift's compact protocol carries each field's id and wire type inline, so an
/// unknown field is skipped rather than rejected. This test writes a `NaN`-bearing chunk with
/// Hardwood, then decodes the footer with that older struct: the read must not fail, and the
/// fields the old reader does know about must still be intact.
class StatisticsNanCountParquetJavaOracleTest {

    private static final int MAGIC_SIZE = 4;
    private static final int FOOTER_LENGTH_SIZE = 4;

    @Test
    void oldReaderSkipsUnknownNanCountAndStillReadsBounds() throws Exception {
        double[] values = { 1.0, Double.NaN, -2.5, Double.NaN, 3.5 };
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("v", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
                .build();

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema)) {
            writer.writeBatch(batch -> batch.doubles(0, values));
        }

        FileMetaData decoded = assertDoesNotThrow(() -> readFooter(out.toByteArray()));

        org.apache.parquet.format.Statistics statistics =
                decoded.getRow_groups().get(0).getColumns().get(0).getMeta_data().getStatistics();
        assertThat(statistics.getMin_value()).isEqualTo(encode(-2.5));
        assertThat(statistics.getMax_value()).isEqualTo(encode(3.5));
        assertThat(statistics.getNull_count()).isEqualTo(0L);
    }

    private static FileMetaData readFooter(byte[] fileBytes) throws Exception {
        int footerInfoPos = fileBytes.length - MAGIC_SIZE - FOOTER_LENGTH_SIZE;
        int footerLength = ByteBuffer.wrap(fileBytes, footerInfoPos, FOOTER_LENGTH_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        int footerStart = footerInfoPos - footerLength;
        return Util.readFileMetaData(
                new ByteArrayInputStream(fileBytes, footerStart, footerLength));
    }

    private static byte[] encode(double value) {
        return ByteBuffer.allocate(Double.BYTES).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
    }
}
