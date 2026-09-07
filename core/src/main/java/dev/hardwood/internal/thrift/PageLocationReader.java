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
import dev.hardwood.metadata.PageLocation;

/// Reader for PageLocation from Thrift Compact Protocol.
public class PageLocationReader {

    public static PageLocation read(ThriftCompactReader reader) throws IOException {
        int depth = reader.structDepth();
        try {
            return readFields(reader);
        }
        catch (IOException e) {
            throw ThriftParseException.at("PageLocation", depth, e);
        }
    }

    private static PageLocation readFields(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static PageLocation readInternal(ThriftCompactReader reader) throws IOException {
        long offset = 0;
        int compressedPageSize = 0;
        long firstRowIndex = 0;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // offset (i64)
                    if (reader.acceptField(header, Codes.I64)) {
                        offset = reader.readNonNegativeI64("PageLocation.offset");
                    }
                    break;
                case 2: // compressed_page_size (i32)
                    if (reader.acceptField(header, Codes.I32)) {
                        compressedPageSize = reader.readNonNegativeI32("PageLocation.compressed_page_size");
                    }
                    break;
                case 3: // first_row_index (i64)
                    if (reader.acceptField(header, Codes.I64)) {
                        firstRowIndex = reader.readNonNegativeI64("PageLocation.first_row_index");
                    }
                    break;
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        return new PageLocation(offset, compressedPageSize, firstRowIndex);
    }
}
