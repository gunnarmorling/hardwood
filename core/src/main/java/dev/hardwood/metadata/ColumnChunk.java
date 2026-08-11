/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

import java.io.IOException;

/// Column chunk metadata.
///
/// @param metaData column metadata
/// @param offsetIndexOffset file offset of the offset index for this column chunk, or `null` if absent
/// @param offsetIndexLength length of the offset index in bytes, or `null` if absent
/// @param columnIndexOffset file offset of the column index for this column chunk, or `null` if absent
/// @param columnIndexLength length of the column index in bytes, or `null` if absent
/// @param filePath file holding this chunk's data under the legacy split-file layout, or the empty
///     string — never `null` — when the data sits in the file being read, as it does in every
///     layout Hardwood can decode. Reading a chunk with a non-empty `filePath` fails; its
///     metadata is still readable
/// @see <a href="https://parquet.apache.org/docs/file-format/data-pages/columnchunks/">File Format – Column Chunks</a>
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public record ColumnChunk(
        ColumnMetaData metaData,
        Long offsetIndexOffset,
        Integer offsetIndexLength,
        Long columnIndexOffset,
        Integer columnIndexLength,
        String filePath) {

    /// A `null` `filePath` is normalised to the empty string, so the two ways of saying "this
    /// file" — the field absent and the field set to the empty string the spec allows — have one
    /// representation, and [#requireSameFile] has nothing to guard against.
    public ColumnChunk {
        filePath = filePath == null ? "" : filePath;
    }

    /// Fails unless this chunk's data sits in the file being read.
    ///
    /// A non-empty [#filePath] puts the data in a different file — the legacy split-file layout,
    /// which Hardwood does not read. Every offset in the chunk's metadata then addresses that
    /// other file, so following `data_page_offset` here would decode whatever happens to sit at
    /// that offset in this one and hand it back as data.
    ///
    /// This is checked where the data is about to be read rather than while parsing the footer,
    /// so that the metadata of such a file stays inspectable.
    ///
    /// @throws IOException if this chunk's data lives in another file
    public void requireSameFile() throws IOException {
        if (!filePath.isEmpty()) {
            throw new IOException("Column chunk stores its data in a separate file ('" + filePath
                    + "'); the split-file layout is not supported");
        }
    }

    /// Computes the absolute file offset where a column chunk's data starts.
    public long chunkStartOffset() {
        Long dictOffset = metaData().dictionaryPageOffset();
        return (dictOffset != null && dictOffset > 0)
                ? dictOffset
                : metaData().dataPageOffset();
    }

}
