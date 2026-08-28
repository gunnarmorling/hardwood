/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

/// The kind of page stored in a column chunk.
///
/// @see <a href="https://parquet.apache.org/docs/file-format/data-pages/">File Format – Data Pages</a>
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public enum PageType {
    /// A v1 data page: the repetition levels, definition levels and values are compressed
    /// together as a single payload.
    DATA_PAGE,
    /// A page holding an index into the column chunk. Defined by the format, but not written by
    /// any known writer; the column index and offset index serve this purpose instead.
    INDEX_PAGE,
    /// The dictionary for a column chunk. The chunk's dictionary-encoded data pages store
    /// indexes into it rather than values.
    DICTIONARY_PAGE,
    /// A v2 data page: the repetition and definition levels are stored uncompressed and
    /// length-prefixed ahead of the compressed values, so they can be read without decompressing
    /// the page.
    DATA_PAGE_V2,
    /// Placeholder for a page type found in metadata that is not recognized. Reported only
    /// through [ColumnMetaData#encodingStats()], whose counts are informational; a page whose own
    /// header declares an unrecognized type is rejected when read, since it cannot be decoded.
    UNKNOWN
}
