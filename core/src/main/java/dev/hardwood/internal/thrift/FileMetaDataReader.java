/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.hardwood.internal.EncryptedFileException;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.ColumnOrder;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.metadata.SchemaElement;

/// Reader for FileMetaData from Thrift Compact Protocol.
public class FileMetaDataReader {

    public static FileMetaData read(ThriftCompactReader reader) {
        int saved = reader.pushFieldIdContext(ThriftStruct.FILE_META_DATA);
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static FileMetaData readInternal(ThriftCompactReader reader) {
        int version = 0;
        List<SchemaElement> schema = Collections.emptyList();
        long numRows = 0;
        List<RowGroup> rowGroups = Collections.emptyList();
        Map<String, String> keyValueMetadata = Collections.emptyMap();
        String createdBy = null;
        List<ColumnOrder> columnOrders = Collections.emptyList();

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1: // version
                    if (reader.acceptField(header, Codes.I32)) {
                        version = reader.readI32();
                    }
                    break;
                case 2: // schema (required list<SchemaElement>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        schema = reader.readStructList(SchemaElementReader::read);
                    }
                    break;
                case 3: // num_rows
                    if (reader.acceptField(header, Codes.I64)) {
                        numRows = reader.readNonNegativeI64();
                    }
                    break;
                case 4: // row_groups (required list<RowGroup>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        rowGroups = reader.readStructList(RowGroupReader::read);
                    }
                    break;
                case 5: // key_value_metadata (optional list<KeyValue>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        keyValueMetadata = KeyValueMetadataReader.read(reader);
                    }
                    break;
                case 6: // created_by (optional)
                    if (reader.acceptField(header, Codes.BINARY)) {
                        createdBy = reader.readString();
                    }
                    break;
                case 7: // column_orders (optional list<ColumnOrder>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        columnOrders = readColumnOrders(reader);
                    }
                    break;
                case 8: // encryption_algorithm (present only with a plaintext footer)
                    // The footer parses, but the column data is encrypted and
                    // Hardwood cannot decrypt it. Fail fast rather than letting a
                    // later page scan crash with an unattributable error.
                    throw new EncryptedFileException();
                default:
                    reader.skipField(ThriftCompactReader.fieldType(header));
                    break;
            }
        }

        return new FileMetaData(version, schema, numRows, rowGroups, keyValueMetadata, createdBy,
                columnOrders);
    }

    /// The column orders are optional and only refine how statistics are compared, so a list
    /// this reader will not decode leaves them empty — the same shape as a writer that omits
    /// the field.
    private static List<ColumnOrder> readColumnOrders(ThriftCompactReader reader) {
        long listHeader =
                reader.acceptListHeader(Codes.STRUCT);
        if (listHeader == ThriftCompactReader.ABSENT_LIST) {
            return List.of();
        }
        List<ColumnOrder> orders = new ArrayList<>(ThriftCompactReader.listSize(listHeader));
        for (int i = 0; i < ThriftCompactReader.listSize(listHeader); i++) {
            orders.add(ColumnOrderReader.read(reader));
        }
        return Collections.unmodifiableList(orders);
    }
}
