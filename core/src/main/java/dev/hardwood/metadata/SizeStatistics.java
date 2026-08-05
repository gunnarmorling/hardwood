/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

import java.util.List;

/// Size statistics for a column chunk: the unencoded size of its `BYTE_ARRAY` data
/// and its repetition- and definition-level histograms.
///
/// A level histogram is indexed by level — entry `i` holds the number of values at
/// level `i`, so the histogram has `maxLevel + 1` entries. It yields the null and
/// empty-list counts of a chunk without decoding its level streams.
///
/// Every field is optional in the Parquet format. A writer that omits one is
/// reported as `null`, which is distinct from a present but empty histogram.
///
/// @param unencodedByteArrayDataBytes total unencoded size of the `BYTE_ARRAY` data in this chunk, or `null` if absent
/// @param repetitionLevelHistogram number of values at each repetition level `0..maxRepetitionLevel`, or `null` if absent
/// @param definitionLevelHistogram number of values at each definition level `0..maxDefinitionLevel`, or `null` if absent
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public record SizeStatistics(
        Long unencodedByteArrayDataBytes,
        List<Long> repetitionLevelHistogram,
        List<Long> definitionLevelHistogram) {
}
