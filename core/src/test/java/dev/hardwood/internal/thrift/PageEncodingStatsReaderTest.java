/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.PageEncodingStats;
import dev.hardwood.metadata.PageType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Verifies how `ColumnMetaData.encoding_stats` (field 13) is decoded, using hand-crafted Thrift
/// Compact Protocol bytes so each shape can be isolated. Field header byte is
/// `(fieldIdDelta << 4) | type`; list header byte is `(size << 4) | elementType`; `i32` values are
/// zigzag varints (`zigzag(-1) = 1`, `zigzag(1) = 2`, `zigzag(2) = 4`, `zigzag(8) = 16`); `0x00` is
/// STOP.
class PageEncodingStatsReaderTest {

    /// Field 13 as a list of structs, i.e. the `encoding_stats` field header (`0xD9`) followed by
    /// the list header for `count` elements. The trailing `0x00` closing the ColumnMetaData struct
    /// is supplied by each test.
    private static final int ENCODING_STATS_FIELD = 0xD9;

    private static ThriftCompactReader reader(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return new ThriftCompactReader(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void readsEntriesInWrittenOrder() throws IOException {
        // Two entries: (DICTIONARY_PAGE, PLAIN, 1) then (DATA_PAGE, RLE_DICTIONARY, 2).
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x2C,
                0x15, 0x04, 0x15, 0x00, 0x15, 0x02, 0x00,
                0x15, 0x00, 0x15, 0x10, 0x15, 0x04, 0x00,
                0x00));

        assertThat(metaData.encodingStats()).containsExactly(
                new PageEncodingStats(PageType.DICTIONARY_PAGE, Encoding.PLAIN, 1),
                new PageEncodingStats(PageType.DATA_PAGE, Encoding.RLE_DICTIONARY, 2));
    }

    @Test
    void absentFieldYieldsEmptyList() throws IOException {
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void unrecognizedPageTypeIsCarriedThroughAsUnknown() throws IOException {
        // page_type = 7, which no released version of the format defines. The counts are
        // informational, so this must not make the footer unreadable.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x1C,
                0x15, 0x0E, 0x15, 0x00, 0x15, 0x02, 0x00,
                0x00));

        assertThat(metaData.encodingStats())
                .containsExactly(new PageEncodingStats(PageType.UNKNOWN, Encoding.PLAIN, 1));
    }

    @Test
    void unrecognizedEncodingIsCarriedThroughAsUnknown() throws IOException {
        // encoding = 10, which no released version of the format defines. Like an unrecognized
        // page type, it must not make the footer unreadable.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x1C,
                0x15, 0x00, 0x15, 0x14, 0x15, 0x02, 0x00,
                0x00));

        assertThat(metaData.encodingStats())
                .containsExactly(new PageEncodingStats(PageType.DATA_PAGE, Encoding.UNKNOWN, 1));
    }

    @Test
    void nonListFieldIsSkipped() throws IOException {
        // Field 13 declared as an i32 rather than a list. The field is optional and informational,
        // so it is skipped rather than failing the footer.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(0xD5, 0x02, 0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void impossibleEntryCountIsRejectedAsMalformedMetadata() {
        // Long-form list size 2^31, which casts to a negative capacity. Pre-sizing from it throws
        // IllegalArgumentException, which escapes ParquetMetadataReader's catch (IOException) and
        // reaches the caller unchecked and without the file name.
        assertThatThrownBy(() -> ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0xFC,
                0x80, 0x80, 0x80, 0x80, 0x08,
                0x00)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2147483648 elements");
    }

    @Test
    void unknownStructFieldIsSkipped() throws IOException {
        // A field 4 the format does not define today, appended after count.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x1C,
                0x15, 0x00, 0x15, 0x00, 0x15, 0x02, 0x15, 0x02, 0x00,
                0x00));

        assertThat(metaData.encodingStats())
                .containsExactly(new PageEncodingStats(PageType.DATA_PAGE, Encoding.PLAIN, 1));
    }

    @Test
    void missingCountDropsTheField() throws IOException {
        // Entry carrying only page_type and encoding. All three fields are required, so the
        // entry cannot be reconstructed — and since the counts only mean anything as a complete
        // partition of the chunk's pages, the whole field is reported as absent.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x1C,
                0x15, 0x00, 0x15, 0x00, 0x00,
                0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void negativeCountDropsTheField() throws IOException {
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x1C,
                0x15, 0x00, 0x15, 0x00, 0x15, 0x01, 0x00,
                0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void oneMalformedEntryDropsTheWholeField() throws IOException {
        // A well-formed (DATA_PAGE, PLAIN, 1) entry followed by one missing its count. Keeping
        // the first would present 1 page as the chunk's full page inventory when it is not.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x2C,
                0x15, 0x00, 0x15, 0x00, 0x15, 0x02, 0x00,
                0x15, 0x04, 0x15, 0x00, 0x00,
                0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void nonStructListElementTypeDropsTheField() throws IOException {
        // Field 13 declared as list<i32> (element type 0x05) carrying the value 1. Elements are
        // read as structs, so decoding it would misread value bytes as field headers; skipping
        // by the declared element type consumes it instead.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x15,
                0x02,
                0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void wrongWireTypeOnRequiredFieldDropsTheField() throws IOException {
        // page_type declared as i64 (0x06) rather than i32.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x1C,
                0x16, 0x00, 0x15, 0x00, 0x15, 0x02, 0x00,
                0x00));

        assertThat(metaData.encodingStats()).isEmpty();
    }

    @Test
    void malformedFieldDoesNotDisturbLaterFields() throws IOException {
        // encoding_stats as list<i32>, then field 14 (bloom_filter_offset, i64) = 8. The skip
        // has to leave the reader exactly on the next field header for this to decode.
        ColumnMetaData metaData = ColumnMetaDataReader.read(reader(
                ENCODING_STATS_FIELD, 0x15,
                0x02,
                0x16, 0x10,
                0x00));

        assertThat(metaData.encodingStats()).isEmpty();
        assertThat(metaData.bloomFilterOffset()).isEqualTo(8L);
    }
}
