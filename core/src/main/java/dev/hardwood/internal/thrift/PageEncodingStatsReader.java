/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.PageEncodingStats;
import dev.hardwood.metadata.PageType;

/// Reads a Thrift-encoded `list<PageEncodingStats>` into an unmodifiable list: one entry per
/// (page type, encoding) pair written in a column chunk.
class PageEncodingStatsReader {

    /// Reads an encoding stats list from the given reader, which must be positioned right after
    /// the list field header has been consumed (i.e. ready to read the list header).
    static List<PageEncodingStats> read(ThriftCompactReader reader) throws IOException {
        ThriftCompactReader.CollectionHeader listHeader = reader.readListHeader();
        // Elements are read as structs, so a list declaring anything else would have its value
        // bytes misread as field headers.
        if (listHeader.elementType() != Codes.STRUCT) {
            throw new IOException("ColumnMetaData.encoding_stats has wrong Thrift element type 0x"
                    + Integer.toHexString(listHeader.elementType() & 0xFF) + " (expected struct)");
        }
        List<PageEncodingStats> result = new ArrayList<>(listHeader.size());
        for (int i = 0; i < listHeader.size(); i++) {
            result.add(readStats(reader));
        }
        return Collections.unmodifiableList(result);
    }

    /// Reads a single PageEncodingStats Thrift struct (field 1: page_type, field 2: encoding,
    /// field 3: count). All three are required by the format; a struct missing any of them, or
    /// carrying one at the wrong wire type, is a malformed footer rather than an entry to drop.
    private static PageEncodingStats readStats(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            PageType pageType = null;
            Encoding encoding = null;
            int count = -1;

            while (true) {
                ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
                if (header == null) {
                    break;
                }

                switch (header.fieldId()) {
                    case 1: // page_type (required PageType)
                        requireI32(header.type(), "page_type");
                        pageType = ThriftEnumLookup.pageType(reader.readI32());
                        break;
                    case 2: // encoding (required Encoding)
                        requireI32(header.type(), "encoding");
                        encoding = ThriftEnumLookup.encoding(reader.readI32());
                        break;
                    case 3: // count (required i32)
                        requireI32(header.type(), "count");
                        count = reader.readNonNegativeI32("PageEncodingStats.count");
                        break;
                    default:
                        reader.skipField(header.type());
                        break;
                }
            }

            if (pageType == null || encoding == null || count < 0) {
                throw new IOException("PageEncodingStats missing required field(s):"
                        + (pageType == null ? " page_type" : "")
                        + (encoding == null ? " encoding" : "")
                        + (count < 0 ? " count" : ""));
            }
            return new PageEncodingStats(pageType, encoding, count);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static void requireI32(byte type, String fieldName) throws IOException {
        if (type != Codes.I32) {
            throw new IOException("PageEncodingStats." + fieldName + " has wrong Thrift type 0x"
                    + Integer.toHexString(type & 0xFF) + " (expected i32)");
        }
    }
}
