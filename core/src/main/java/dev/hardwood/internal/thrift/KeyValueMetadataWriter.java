/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.util.Map;

/// Writes a `Map<String, String>` as a Thrift `list<KeyValue>`, the inverse of
/// [KeyValueMetadataReader].
///
/// `KeyValue.value` is optional in the format, so a `null` value writes a struct carrying only
/// its key rather than an empty string: that is what a key read from a file that carried no
/// value must be written back as for the two to agree.
public class KeyValueMetadataWriter {

    /// Thrift field id of `KeyValue.key` (required string).
    private static final int KEY = 1;
    /// Thrift field id of `KeyValue.value` (optional string).
    private static final int VALUE = 2;

    /// Writes the list body. The caller has written the field header the list belongs to.
    public static void write(ThriftCompactWriter writer, Map<String, String> metadata) {
        writer.writeListBegin(metadata.size(), ThriftCompactConstants.ElementType.STRUCT);
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            writeKeyValue(writer, entry.getKey(), entry.getValue());
        }
    }

    private static void writeKeyValue(ThriftCompactWriter writer, String key, String value) {
        short saved = writer.pushFieldIdContext();
        try {
            writer.writeFieldBegin(KEY, ThriftCompactConstants.FieldType.BINARY);
            writer.writeString(key);
            if (value != null) {
                writer.writeFieldBegin(VALUE, ThriftCompactConstants.FieldType.BINARY);
                writer.writeString(value);
            }
            writer.writeFieldStop();
        }
        finally {
            writer.popFieldIdContext(saved);
        }
    }
}
