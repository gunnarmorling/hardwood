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
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnMetaData;

/// Reader for ColumnChunk from Thrift Compact Protocol.
public class ColumnChunkReader {

    public static ColumnChunk read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static ColumnChunk readInternal(ThriftCompactReader reader) throws IOException {
        ColumnMetaData metaData = null;
        Long offsetIndexOffset = null;
        Integer offsetIndexLength = null;
        Long columnIndexOffset = null;
        Integer columnIndexLength = null;
        // Absent means this file, and so does the empty string the spec allows for it.
        String filePath = "";

        while (true) {
            ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
            if (header == null) {
                break;
            }

            switch (header.fieldId()) {
                case 1: // file_path (optional string - deprecated)
                    if (reader.acceptField(header, Codes.BINARY)) {
                        filePath = reader.readString();
                    }
                    break;
                case 2: // file_offset (required i64)
                    reader.skipField(header.type());
                    break;
                case 3: // meta_data (required)
                    if (reader.acceptField(header, Codes.STRUCT)) {
                        metaData = ColumnMetaDataReader.read(reader);
                    }
                    break;
                case 4: // offset_index_offset (optional i64)
                    if (reader.acceptField(header, Codes.I64)) {
                        offsetIndexOffset = reader.readNonNegativeI64("ColumnChunk.offset_index_offset");
                    }
                    break;
                case 5: // offset_index_length (optional i32)
                    if (reader.acceptField(header, Codes.I32)) {
                        offsetIndexLength = reader.readNonNegativeI32("ColumnChunk.offset_index_length");
                    }
                    break;
                case 6: // column_index_offset (optional i64)
                    if (reader.acceptField(header, Codes.I64)) {
                        columnIndexOffset = reader.readNonNegativeI64("ColumnChunk.column_index_offset");
                    }
                    break;
                case 7: // column_index_length (optional i32)
                    if (reader.acceptField(header, Codes.I32)) {
                        columnIndexLength = reader.readNonNegativeI32("ColumnChunk.column_index_length");
                    }
                    break;
                default:
                    reader.skipField(header.type());
                    break;
            }
        }

        return new ColumnChunk(metaData, offsetIndexOffset, offsetIndexLength, columnIndexOffset,
                columnIndexLength, filePath);
    }
}
