/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.util.Collections;
import java.util.List;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.RowGroup;

/// Reader for RowGroup from Thrift Compact Protocol.
public class RowGroupReader {

    public static RowGroup read(ThriftCompactReader reader) {
        int saved = reader.pushFieldIdContext(ThriftStruct.ROW_GROUP);
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static RowGroup readInternal(ThriftCompactReader reader) {
        List<ColumnChunk> columns = Collections.emptyList();
        long totalByteSize = 0;
        long numRows = 0;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // columns (required list<ColumnChunk>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        // Chunks come in schema order, so each column's path repeats the one the
                        // previous row group held at the same position.
                        reader.pathCache().startRowGroup();
                        columns = reader.readStructList(ColumnChunkReader::read);
                    }
                    break;
                case 2: // total_byte_size
                    if (reader.acceptField(header, Codes.I64)) {
                        totalByteSize = reader.readNonNegativeI64();
                    }
                    break;
                case 3: // num_rows
                    if (reader.acceptField(header, Codes.I64)) {
                        numRows = reader.readNonNegativeI64();
                    }
                    break;
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        return new RowGroup(columns, totalByteSize, numRows);
    }
}
