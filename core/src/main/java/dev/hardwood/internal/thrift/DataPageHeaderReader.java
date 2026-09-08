/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.internal.metadata.DataPageHeader;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.reader.ParquetReadException;

/// Reader for DataPageHeader from Thrift Compact Protocol.
public class DataPageHeaderReader {

    public static DataPageHeader read(ThriftCompactReader reader) {
        int saved = reader.pushFieldIdContext(ThriftStruct.DATA_PAGE_HEADER);
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static DataPageHeader readInternal(ThriftCompactReader reader) {
        int numValues = 0;
        Encoding encoding = null;
        int encodingValue = -1;
        Encoding definitionLevelEncoding = null;
        Encoding repetitionLevelEncoding = null;
        Statistics statistics = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // num_values
                    if (reader.acceptField(header, Codes.I32)) {
                        numValues = reader.readNonNegativeI32();
                    }
                    break;
                case 2: // encoding
                    if (reader.acceptField(header, Codes.I32)) {
                        encodingValue = reader.readI32();
                        encoding = ThriftEnumLookup.encoding(encodingValue);
                    }
                    break;
                case 3: // definition_level_encoding
                    if (reader.acceptField(header, Codes.I32)) {
                        definitionLevelEncoding = ThriftEnumLookup.encoding(reader.readI32());
                    }
                    break;
                case 4: // repetition_level_encoding
                    if (reader.acceptField(header, Codes.I32)) {
                        repetitionLevelEncoding = ThriftEnumLookup.encoding(reader.readI32());
                    }
                    break;
                case 5: // statistics
                    if (reader.acceptField(header, Codes.STRUCT)) {
                        statistics = StatisticsReader.read(reader);
                    }
                    break;
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        if (encoding == null) {
            throw new ParquetReadException("DataPageHeader missing required field: encoding");
        }

        return new DataPageHeader(numValues, encoding, encodingValue,
                definitionLevelEncoding, repetitionLevelEncoding, statistics);
    }
}
