/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.metadata.SchemaElement;

/// Writer for SchemaElement to Thrift Compact Protocol, the inverse of
/// [SchemaElementReader].
public class SchemaElementWriter {

    public static void write(ThriftCompactWriter writer, SchemaElement element) {
        short saved = writer.pushFieldIdContext();
        try {
            if (element.type() != null) {
                writer.writeFieldBegin(1, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(ThriftEnumLookup.thriftValue(element.type()));
            }
            if (element.typeLength() != null) {
                writer.writeFieldBegin(2, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(element.typeLength());
            }
            if (element.repetitionType() != null) {
                writer.writeFieldBegin(3, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(ThriftEnumLookup.thriftValue(element.repetitionType()));
            }
            // name is required
            writer.writeFieldBegin(4, ThriftCompactConstants.FieldType.BINARY);
            writer.writeString(element.name());
            if (element.numChildren() != null) {
                writer.writeFieldBegin(5, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(element.numChildren());
            }
            if (element.convertedType() != null) {
                writer.writeFieldBegin(6, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(ThriftEnumLookup.thriftValue(element.convertedType()));
            }
            if (element.scale() != null) {
                writer.writeFieldBegin(7, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(element.scale());
            }
            if (element.precision() != null) {
                writer.writeFieldBegin(8, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(element.precision());
            }
            if (element.fieldId() != null) {
                writer.writeFieldBegin(9, ThriftCompactConstants.FieldType.I32);
                writer.writeI32(element.fieldId());
            }
            if (element.logicalType() != null) {
                writer.writeFieldBegin(10, ThriftCompactConstants.FieldType.STRUCT);
                LogicalTypeWriter.write(writer, element.logicalType());
            }
            writer.writeFieldStop();
        }
        finally {
            writer.popFieldIdContext(saved);
        }
    }
}
