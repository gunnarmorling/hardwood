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
///
/// The field is optional and purely informational, so no shape of it fails the footer read: a
/// list that cannot be decoded as written is reported as absent (an empty list) and logged at
/// WARNING. An empty list carries no such claim, since callers already handle writers that omit
/// the field entirely.
class PageEncodingStatsReader {

    private static final System.Logger LOG = System.getLogger(PageEncodingStatsReader.class.getName());

    /// Reads an encoding stats list from the given reader, which must be positioned right after
    /// the list field header has been consumed (i.e. ready to read the list header).
    ///
    /// The reader is always left positioned on the byte after the list, so the rest of the
    /// footer parses regardless of what this field contained.
    static List<PageEncodingStats> read(ThriftCompactReader reader) throws IOException {
        ThriftCompactReader.CollectionHeader listHeader = reader.readListHeader();
        // Elements are read as structs, so a list declaring anything else would have its value
        // bytes misread as field headers. Skipping by the declared element type consumes it
        // without that risk.
        if (listHeader.elementType() != Codes.STRUCT) {
            for (int i = 0; i < listHeader.size(); i++) {
                reader.skipField(listHeader.elementType());
            }
            LOG.log(System.Logger.Level.WARNING,
                    "Ignoring ColumnMetaData.encoding_stats: wrong Thrift element type 0x"
                            + Integer.toHexString(listHeader.elementType() & 0xFF) + " (expected struct)");
            return List.of();
        }
        List<PageEncodingStats> result = new ArrayList<>(listHeader.size());
        for (int i = 0; i < listHeader.size(); i++) {
            PageEncodingStats stats = readStats(reader);
            if (stats != null) {
                result.add(stats);
            }
        }
        if (result.size() != listHeader.size()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Ignoring ColumnMetaData.encoding_stats: " + (listHeader.size() - result.size())
                            + " of " + listHeader.size() + " entries are missing a required field");
            return List.of();
        }
        return Collections.unmodifiableList(result);
    }

    /// Reads a single PageEncodingStats Thrift struct (field 1: page_type, field 2: encoding,
    /// field 3: count), all three required by the format and all three `i32`.
    ///
    /// Returns `null` for a struct that does not carry all three at that wire type, or whose
    /// count is negative. The struct is consumed either way, leaving the reader on the next
    /// element.
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
                // Every field defined on this struct is an i32, so one wire-type gate covers
                // all three: anything else is a field this version cannot use.
                if (header.type() != Codes.I32) {
                    reader.skipField(header.type());
                    continue;
                }
                switch (header.fieldId()) {
                    case 1 -> pageType = ThriftEnumLookup.pageType(reader.readI32());
                    case 2 -> encoding = ThriftEnumLookup.encoding(reader.readI32());
                    case 3 -> count = reader.readI32();
                    default -> reader.skipField(Codes.I32);
                }
            }

            if (pageType == null || encoding == null || count < 0) {
                return null;
            }
            return new PageEncodingStats(pageType, encoding, count);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }
}
