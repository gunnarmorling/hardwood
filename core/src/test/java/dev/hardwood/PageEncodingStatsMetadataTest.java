/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.PageEncodingStats;
import dev.hardwood.metadata.PageType;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.ParquetFileReader;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies that the `encoding_stats` field on Thrift `ColumnMetaData` (field 13) is surfaced on
/// the public [dev.hardwood.metadata.ColumnMetaData] record. The fixture
/// `dictionary_uncompressed.parquet` writes column `id` as plain data pages and column `category`
/// with a dictionary, so a single footer parse covers both a chunk with only a data-page entry and
/// one that also counts a dictionary page.
class PageEncodingStatsMetadataTest {

    @Test
    void surfacesEncodingStatsFromFooter() throws Exception {
        Path parquetFile = Paths.get("src/test/resources/dictionary_uncompressed.parquet");

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquetFile))) {
            RowGroup rowGroup = reader.getFileMetaData().rowGroups().getFirst();

            ColumnChunk idChunk = rowGroup.columns().getFirst();
            assertThat(idChunk.metaData().pathInSchema().toString()).isEqualTo("id");
            assertThat(idChunk.metaData().encodingStats())
                    .containsExactly(new PageEncodingStats(PageType.DATA_PAGE, Encoding.PLAIN, 1));

            ColumnChunk categoryChunk = rowGroup.columns().get(1);
            assertThat(categoryChunk.metaData().pathInSchema().toString()).isEqualTo("category");
            assertThat(categoryChunk.metaData().encodingStats()).containsExactly(
                    new PageEncodingStats(PageType.DICTIONARY_PAGE, Encoding.PLAIN, 1),
                    new PageEncodingStats(PageType.DATA_PAGE, Encoding.RLE_DICTIONARY, 1));
        }
    }
}
