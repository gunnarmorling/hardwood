/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.RowGroup;

/// Reader for RowGroup from Thrift Compact Protocol.
public class RowGroupReader {

    public static RowGroup read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static RowGroup readInternal(ThriftCompactReader reader) throws IOException {
        List<ColumnChunk> columns = Collections.emptyList();
        long totalByteSize = 0;
        long numRows = 0;

        while (true) {
            ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
            if (header == null) {
                break;
            }

            switch (header.fieldId()) {
                case 1: // columns (required list<ColumnChunk>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        columns = reader.readStructList("RowGroup.columns", ColumnChunkReader::read);
                    }
                    break;
                case 2: // total_byte_size
                    if (reader.acceptField(header, Codes.I64)) {
                        totalByteSize = reader.readNonNegativeI64("RowGroup.total_byte_size");
                    }
                    break;
                case 3: // num_rows
                    if (reader.acceptField(header, Codes.I64)) {
                        numRows = reader.readNonNegativeI64("RowGroup.num_rows");
                    }
                    break;
                default:
                    reader.skipField(header.type());
                    break;
            }
        }

        return new RowGroup(columns, totalByteSize, numRows);
    }
}
