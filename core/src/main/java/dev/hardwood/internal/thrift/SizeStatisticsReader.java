/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.util.List;

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
        List<Long> repetitionLevelHistogram = null;
        List<Long> definitionLevelHistogram = null;

        while (true) {
            ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
            if (header == null) {
                break;
            }

            switch (header.fieldId()) {
                case 1: // unencoded_byte_array_data_bytes (optional i64)
                    if (header.type() == 0x06) {
                        unencodedByteArrayDataBytes = reader.readI64();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 2: // repetition_level_histogram (optional list<i64>)
                    if (header.type() == 0x09) { // LIST
                        repetitionLevelHistogram = reader.readI64List();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 3: // definition_level_histogram (optional list<i64>)
                    if (header.type() == 0x09) { // LIST
                        definitionLevelHistogram = reader.readI64List();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                default:
                    reader.skipField(header.type());
                    break;
            }
        }

        return new SizeStatistics(unencodedByteArrayDataBytes, repetitionLevelHistogram, definitionLevelHistogram);
    }
}
