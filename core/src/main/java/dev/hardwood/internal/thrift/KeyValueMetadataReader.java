/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;

/// Reads a Thrift-encoded `list<KeyValue>` into an unmodifiable `Map<String, String>`.
class KeyValueMetadataReader {

    /// Reads a key-value metadata list from the given reader, which must be positioned
    /// right after the list field header has been consumed (i.e. ready to read the list header).
    ///
    /// The field is optional wherever it appears, so a list declaring anything but struct
    /// elements is skipped and reported as an empty map.
    ///
    /// @param fieldName fully-qualified name of the field being read, for the log message
    static Map<String, String> read(ThriftCompactReader reader, String fieldName) {
        long listHeader = reader.acceptListHeader(Codes.STRUCT, fieldName);
        if (listHeader == ThriftCompactReader.ABSENT_LIST) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>(ThriftCompactReader.listSize(listHeader));
        for (int i = 0; i < ThriftCompactReader.listSize(listHeader); i++) {
            readKeyValue(reader, result);
        }
        return Collections.unmodifiableMap(result);
    }

    /// Reads a single KeyValue Thrift struct (field 1: key, field 2: value) and puts it into the map.
    private static void readKeyValue(ThriftCompactReader reader, Map<String, String> target) {
        short saved = reader.pushFieldIdContext();
        try {
            String key = null;
            String value = null;

            while (true) {
                int header = reader.readFieldHeader();
                if (header == ThriftCompactReader.STOP_FIELD) {
                    break;
                }

                switch (ThriftCompactReader.fieldId(header)) {
                    case 1: // key (required string)
                        if (reader.acceptField(header, Codes.BINARY)) {
                            key = reader.readString();
                        }
                        break;
                    case 2: // value (optional string)
                        if (reader.acceptField(header, Codes.BINARY)) {
                            value = reader.readString();
                        }
                        break;
                    default:
                        reader.skipField(ThriftCompactReader.fieldType(header));
                        break;
                }
            }

            if (key != null) {
                target.put(key, value);
            }
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }
}
