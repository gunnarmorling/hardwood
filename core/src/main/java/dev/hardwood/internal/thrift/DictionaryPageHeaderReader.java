/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;

import dev.hardwood.internal.metadata.DictionaryPageHeader;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.Encoding;

/// Reader for DictionaryPageHeader from Thrift Compact Protocol.
public class DictionaryPageHeaderReader {

    public static DictionaryPageHeader read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static DictionaryPageHeader readInternal(ThriftCompactReader reader) throws IOException {
        int numValues = 0;
        Encoding encoding = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // num_values
                    if (reader.acceptField(header, Codes.I32)) {
                        numValues = reader.readNonNegativeI32("DictionaryPageHeader.num_values");
                    }
                    break;
                case 2: // encoding
                    if (reader.acceptField(header, Codes.I32)) {
                        encoding = ThriftEnumLookup.encoding(reader.readI32());
                    }
                    break;
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        return new DictionaryPageHeader(numValues, encoding);
    }
}
