/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;

import dev.hardwood.internal.metadata.DataPageHeaderV2;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.Statistics;

/// Reader for DataPageHeaderV2 from Thrift Compact Protocol.
public class DataPageHeaderV2Reader {

    public static DataPageHeaderV2 read(ThriftCompactReader reader) throws IOException {
        int depth = reader.structDepth();
        try {
            return readFields(reader);
        }
        catch (IOException e) {
            throw ThriftParseException.at("DataPageHeaderV2", depth, e);
        }
    }

    private static DataPageHeaderV2 readFields(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static DataPageHeaderV2 readInternal(ThriftCompactReader reader) throws IOException {
        int numValues = 0;
        int numNulls = 0;
        int numRows = 0;
        Encoding encoding = null;
        int encodingValue = -1;
        int definitionLevelsByteLength = 0;
        int repetitionLevelsByteLength = 0;
        boolean isCompressed = true; // Default value per Parquet spec
        Statistics statistics = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // num_values
                    if (reader.acceptField(header, Codes.I32)) {
                        numValues = reader.readNonNegativeI32("DataPageHeaderV2.num_values");
                    }
                    break;
                case 2: // num_nulls
                    if (reader.acceptField(header, Codes.I32)) {
                        numNulls = reader.readNonNegativeI32("DataPageHeaderV2.num_nulls");
                    }
                    break;
                case 3: // num_rows
                    if (reader.acceptField(header, Codes.I32)) {
                        numRows = reader.readNonNegativeI32("DataPageHeaderV2.num_rows");
                    }
                    break;
                case 4: // encoding
                    if (reader.acceptField(header, Codes.I32)) {
                        encodingValue = reader.readI32();
                        encoding = ThriftEnumLookup.encoding(encodingValue);
                    }
                    break;
                case 5: // definition_levels_byte_length
                    if (reader.acceptField(header, Codes.I32)) {
                        definitionLevelsByteLength = reader.readNonNegativeI32("DataPageHeaderV2.definition_levels_byte_length");
                    }
                    break;
                case 6: // repetition_levels_byte_length
                    if (reader.acceptField(header, Codes.I32)) {
                        repetitionLevelsByteLength = reader.readNonNegativeI32("DataPageHeaderV2.repetition_levels_byte_length");
                    }
                    break;
                case 7: // is_compressed
                    isCompressed = reader.readBooleanField(header, isCompressed);
                    break;
                case 8: // statistics
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
            throw new IOException("DataPageHeaderV2 missing required field: encoding");
        }

        return new DataPageHeaderV2(numValues, numNulls, numRows, encoding, encodingValue,
                definitionLevelsByteLength, repetitionLevelsByteLength, isCompressed, statistics);
    }
}
