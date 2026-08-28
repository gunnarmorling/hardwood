/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.SizeStatistics;

/// Reader for the Thrift SizeStatistics struct from Parquet metadata.
///
/// Parquet SizeStatistics struct fields:
///
/// - 1: unencoded_byte_array_data_bytes (i64, optional)
/// - 2: repetition_level_histogram (list<i64>, optional)
/// - 3: definition_level_histogram (list<i64>, optional)
public class SizeStatisticsReader {

    public static SizeStatistics read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static SizeStatistics readInternal(ThriftCompactReader reader) throws IOException {
        Long unencodedByteArrayDataBytes = null;
        long[] repetitionLevelHistogram = null;
        long[] definitionLevelHistogram = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // unencoded_byte_array_data_bytes (optional i64)
                    if (reader.acceptField(header, Codes.I64)) {
                        unencodedByteArrayDataBytes = reader.readI64();
                    }
                    break;
                case 2: // repetition_level_histogram (optional list<i64>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        repetitionLevelHistogram = reader.readOptionalI64Array(
                                "SizeStatistics.repetition_level_histogram");
                    }
                    break;
                case 3: // definition_level_histogram (optional list<i64>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        definitionLevelHistogram = reader.readOptionalI64Array(
                                "SizeStatistics.definition_level_histogram");
                    }
                    break;
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        return new SizeStatistics(unencodedByteArrayDataBytes, repetitionLevelHistogram, definitionLevelHistogram);
    }
}
