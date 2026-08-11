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
import dev.hardwood.metadata.OffsetIndex;
import dev.hardwood.metadata.PageLocation;

/// Reader for OffsetIndex from Thrift Compact Protocol.
///
/// Parquet OffsetIndex struct fields:
///
/// - 1: page_locations (list<PageLocation>)
/// - 2: unencoded_byte_array_data_bytes (list<i64>, optional)
public class OffsetIndexReader {

    public static OffsetIndex read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static OffsetIndex readInternal(ThriftCompactReader reader) throws IOException {
        List<PageLocation> pageLocations = Collections.emptyList();
        long[] unencodedByteArrayDataBytes = null;

        while (true) {
            ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
            if (header == null) {
                break;
            }

            switch (header.fieldId()) {
                case 1: // page_locations (required list<PageLocation>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        pageLocations = reader.readStructList(
                                "OffsetIndex.page_locations", PageLocationReader::read);
                    }
                    break;
                case 2: // unencoded_byte_array_data_bytes (list<i64>, optional)
                    if (reader.acceptField(header, Codes.LIST)) {
                        unencodedByteArrayDataBytes = reader.readOptionalI64Array(
                                "OffsetIndex.unencoded_byte_array_data_bytes");
                    }
                    break;
                default:
                    reader.skipField(header.type());
                    break;
            }
        }

        return new OffsetIndex(pageLocations, unencodedByteArrayDataBytes);
    }
}
