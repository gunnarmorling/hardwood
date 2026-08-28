/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

/// The number of pages a writer produced for one combination of page type and encoding within a
/// column chunk.
///
/// A chunk's full set of these is available from [ColumnMetaData#encodingStats()]; the field is
/// optional in the format, so it may be absent. When present it is complete: the counts account
/// for every page in the chunk. A file that encodes the field in some other way than the format
/// defines reads as absent rather than as a partial list.
///
/// @param pageType the kind of page counted
/// @param encoding the encoding those pages were written with
/// @param count the number of pages written with this page type and encoding
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public record PageEncodingStats(PageType pageType, Encoding encoding, int count) {
}
