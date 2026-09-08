/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.internal.metadata.DataPageHeader;
import dev.hardwood.internal.metadata.DataPageHeaderV2;
import dev.hardwood.internal.metadata.DictionaryPageHeader;
import dev.hardwood.internal.metadata.PageHeader;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.PageType;
import dev.hardwood.reader.ParquetReadException;

/// Reader for PageHeader from Thrift Compact Protocol.
public class PageHeaderReader {

    public static PageHeader read(ThriftCompactReader reader) {
        int saved = reader.pushFieldIdContext(ThriftStruct.PAGE_HEADER);
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static PageHeader readInternal(ThriftCompactReader reader) {
        PageType type = null;
        int uncompressedPageSize = 0;
        int compressedPageSize = 0;
        Integer crc = null;
        DataPageHeader dataPageHeader = null;
        DataPageHeaderV2 dataPageHeaderV2 = null;
        DictionaryPageHeader dictionaryPageHeader = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // type
                    if (reader.acceptField(header, Codes.I32)) {
                        int rawType = reader.readI32();
                        type = ThriftEnumLookup.pageType(rawType);
                        // A page whose header declares a type we do not know cannot be decoded.
                        if (type == PageType.UNKNOWN) {
                            throw new ParquetReadException("PageHeader has unknown page type: " + rawType);
                        }
                    }
                    break;
                case 2: // uncompressed_page_size
                    if (reader.acceptField(header, Codes.I32)) {
                        uncompressedPageSize = reader.readNonNegativeI32();
                    }
                    break;
                case 3: // compressed_page_size
                    if (reader.acceptField(header, Codes.I32)) {
                        compressedPageSize = reader.readNonNegativeI32();
                    }
                    break;
                case 4: // crc
                    if (reader.acceptField(header, Codes.I32)) {
                        crc = reader.readI32();
                    }
                    break;
                case 5: // data_page_header
                    if (reader.acceptField(header, Codes.STRUCT)) {
                        dataPageHeader = DataPageHeaderReader.read(reader);
                    }
                    break;
                case 6: // index_page_header (optional) - skipped for now
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
                case 7: // dictionary_page_header
                    if (reader.acceptField(header, Codes.STRUCT)) {
                        dictionaryPageHeader = DictionaryPageHeaderReader.read(reader);
                    }
                    break;
                case 8: // data_page_header_v2
                    if (reader.acceptField(header, Codes.STRUCT)) {
                        dataPageHeaderV2 = DataPageHeaderV2Reader.read(reader);
                    }
                    break;
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        // Validate required fields
        if (type == null) {
            throw new ParquetReadException("PageHeader missing required field: type");
        }

        return new PageHeader(type, uncompressedPageSize, compressedPageSize,
                dataPageHeader, dataPageHeaderV2, dictionaryPageHeader, crc);
    }
}
