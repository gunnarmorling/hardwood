/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

/// The field names of the Thrift structs this package reads, as `parquet.thrift`
/// spells them.
///
/// Consulted only when a parse has failed, to say which field a reader was on
/// rather than only its id. A field id means nothing on its own — 3 is
/// `num_rows` on `FileMetaData` and `max_values` on `ColumnIndex` — so the
/// lookup is keyed by the struct as well.
///
/// A struct appears here only if some reader attributes failures to it, and it
/// must then list every field id `parquet.thrift` gives that struct — including
/// ids no reader reads, which arrive here by way of `skipField`. A table that
/// stops early does not merely lose a name: [#describe] falls through to its
/// second form and asserts the format has no such field, which for a field the
/// format does define is worse than saying nothing.
///
/// For a union the names are its member names, which are what the ids identify;
/// the members' own fields belong to the member struct, not to the union.
///
/// #820 vendors `parquet.thrift` itself as the in-tree reference; when it lands,
/// this table is what it replaces.
final class ThriftFieldNames {

    private ThriftFieldNames() {
    }

    /// `Struct.field` for a field the struct defines, or `Struct field N` when
    /// it does not.
    ///
    /// The second form is worth saying rather than hiding: a field id the
    /// struct has no room for means the bytes did not decode to anything the
    /// format allows there, which is a stronger statement than an unexpected
    /// type on a field that does exist.
    static String describe(String struct, int fieldId) {
        String name = nameOf(struct, fieldId);
        return name != null ? struct + "." + name : struct + " field " + fieldId;
    }

    private static String nameOf(String struct, int fieldId) {
        return switch (struct) {
            case "BloomFilterHeader" -> switch (fieldId) {
                case 1 -> "numBytes";
                case 2 -> "algorithm";
                case 3 -> "hash";
                case 4 -> "compression";
                default -> null;
            };
            case "BoundingBox" -> switch (fieldId) {
                case 1 -> "xmin";
                case 2 -> "xmax";
                case 3 -> "ymin";
                case 4 -> "ymax";
                case 5 -> "zmin";
                case 6 -> "zmax";
                case 7 -> "mmin";
                case 8 -> "mmax";
                default -> null;
            };
            case "ColumnChunk" -> switch (fieldId) {
                case 1 -> "file_path";
                case 2 -> "file_offset";
                case 3 -> "meta_data";
                case 4 -> "offset_index_offset";
                case 5 -> "offset_index_length";
                case 6 -> "column_index_offset";
                case 7 -> "column_index_length";
                case 8 -> "crypto_metadata";
                case 9 -> "encrypted_column_metadata";
                default -> null;
            };
            case "ColumnIndex" -> switch (fieldId) {
                case 1 -> "null_pages";
                case 2 -> "min_values";
                case 3 -> "max_values";
                case 4 -> "boundary_order";
                case 5 -> "null_counts";
                case 6 -> "repetition_level_histograms";
                case 7 -> "definition_level_histograms";
                case 8 -> "nan_counts";
                default -> null;
            };
            case "ColumnMetaData" -> switch (fieldId) {
                case 1 -> "type";
                case 2 -> "encodings";
                case 3 -> "path_in_schema";
                case 4 -> "codec";
                case 5 -> "num_values";
                case 6 -> "total_uncompressed_size";
                case 7 -> "total_compressed_size";
                case 8 -> "key_value_metadata";
                case 9 -> "data_page_offset";
                case 10 -> "index_page_offset";
                case 11 -> "dictionary_page_offset";
                case 12 -> "statistics";
                case 13 -> "encoding_stats";
                case 14 -> "bloom_filter_offset";
                case 15 -> "bloom_filter_length";
                case 16 -> "size_statistics";
                case 17 -> "geospatial_statistics";
                default -> null;
            };
            case "ColumnOrder" -> switch (fieldId) {
                case 1 -> "TYPE_ORDER";
                case 2 -> "IEEE_754_TOTAL_ORDER";
                default -> null;
            };
            case "DataPageHeader" -> switch (fieldId) {
                case 1 -> "num_values";
                case 2 -> "encoding";
                case 3 -> "definition_level_encoding";
                case 4 -> "repetition_level_encoding";
                case 5 -> "statistics";
                default -> null;
            };
            case "DataPageHeaderV2" -> switch (fieldId) {
                case 1 -> "num_values";
                case 2 -> "num_nulls";
                case 3 -> "num_rows";
                case 4 -> "encoding";
                case 5 -> "definition_levels_byte_length";
                case 6 -> "repetition_levels_byte_length";
                case 7 -> "is_compressed";
                case 8 -> "statistics";
                default -> null;
            };
            case "DictionaryPageHeader" -> switch (fieldId) {
                case 1 -> "num_values";
                case 2 -> "encoding";
                case 3 -> "is_sorted";
                default -> null;
            };
            case "FileMetaData" -> switch (fieldId) {
                case 1 -> "version";
                case 2 -> "schema";
                case 3 -> "num_rows";
                case 4 -> "row_groups";
                case 5 -> "key_value_metadata";
                case 6 -> "created_by";
                case 7 -> "column_orders";
                case 8 -> "encryption_algorithm";
                case 9 -> "footer_signing_key_metadata";
                default -> null;
            };
            case "GeospatialStatistics" -> switch (fieldId) {
                case 1 -> "bbox";
                case 2 -> "geospatial_types";
                default -> null;
            };
            case "KeyValue" -> switch (fieldId) {
                case 1 -> "key";
                case 2 -> "value";
                default -> null;
            };
            case "LogicalType" -> switch (fieldId) {
                case 1 -> "STRING";
                case 2 -> "MAP";
                case 3 -> "LIST";
                case 4 -> "ENUM";
                case 5 -> "DECIMAL";
                case 6 -> "DATE";
                case 7 -> "TIME";
                case 8 -> "TIMESTAMP";
                // `parquet.thrift` reserves 9 rather than defining it, but
                // LogicalTypeReader reads it, so a failure over it is on a
                // field this reader does recognise.
                case 9 -> "INTERVAL";
                case 10 -> "INTEGER";
                case 11 -> "UNKNOWN";
                case 12 -> "JSON";
                case 13 -> "BSON";
                case 14 -> "UUID";
                case 15 -> "FLOAT16";
                case 16 -> "VARIANT";
                case 17 -> "GEOMETRY";
                case 18 -> "GEOGRAPHY";
                default -> null;
            };
            case "OffsetIndex" -> switch (fieldId) {
                case 1 -> "page_locations";
                case 2 -> "unencoded_byte_array_data_bytes";
                default -> null;
            };
            case "PageEncodingStats" -> switch (fieldId) {
                case 1 -> "page_type";
                case 2 -> "encoding";
                case 3 -> "count";
                default -> null;
            };
            case "PageHeader" -> switch (fieldId) {
                case 1 -> "type";
                case 2 -> "uncompressed_page_size";
                case 3 -> "compressed_page_size";
                case 4 -> "crc";
                case 5 -> "data_page_header";
                case 6 -> "index_page_header";
                case 7 -> "dictionary_page_header";
                case 8 -> "data_page_header_v2";
                default -> null;
            };
            case "PageLocation" -> switch (fieldId) {
                case 1 -> "offset";
                case 2 -> "compressed_page_size";
                case 3 -> "first_row_index";
                default -> null;
            };
            case "RowGroup" -> switch (fieldId) {
                case 1 -> "columns";
                case 2 -> "total_byte_size";
                case 3 -> "num_rows";
                case 4 -> "sorting_columns";
                case 5 -> "file_offset";
                case 6 -> "total_compressed_size";
                case 7 -> "ordinal";
                default -> null;
            };
            case "SchemaElement" -> switch (fieldId) {
                case 1 -> "type";
                case 2 -> "type_length";
                case 3 -> "repetition_type";
                case 4 -> "name";
                case 5 -> "num_children";
                case 6 -> "converted_type";
                case 7 -> "scale";
                case 8 -> "precision";
                case 9 -> "field_id";
                case 10 -> "logicalType";
                default -> null;
            };
            case "SizeStatistics" -> switch (fieldId) {
                case 1 -> "unencoded_byte_array_data_bytes";
                case 2 -> "repetition_level_histogram";
                case 3 -> "definition_level_histogram";
                default -> null;
            };
            case "Statistics" -> switch (fieldId) {
                case 1 -> "max";
                case 2 -> "min";
                case 3 -> "null_count";
                case 4 -> "distinct_count";
                case 5 -> "max_value";
                case 6 -> "min_value";
                case 7 -> "is_max_value_exact";
                case 8 -> "is_min_value_exact";
                case 9 -> "nan_count";
                default -> null;
            };
            default -> null;
        };
    }
}
