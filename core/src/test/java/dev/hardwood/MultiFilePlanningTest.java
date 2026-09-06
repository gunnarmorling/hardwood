/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.reader.CountingInputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;

import static org.assertj.core.api.Assertions.assertThat;

/// What a multi-file read opens, and when.
///
/// A file is planned when the read reaches it, so a caller who stops early never
/// pays for the files beyond where they stopped. The counts here are deliberately
/// bounds rather than exact numbers: the retriever prefetches ahead until
/// backpressure stops it, and how far it gets is a timing question. That it stops
/// short of the end is not.
class MultiFilePlanningTest {

    private static final int FILE_COUNT = 8;

    private static List<CountingInputFile> countingFiles() {
        List<CountingInputFile> files = new ArrayList<>();
        for (int i = 0; i < FILE_COUNT; i++) {
            files.add(new CountingInputFile(InputFile.of(
                    Paths.get("src/test/resources/multi_file_part" + (i % 2) + ".parquet"))));
        }
        return files;
    }

    private static long touched(List<CountingInputFile> files) {
        return files.stream().filter(f -> f.readCount() > 0).count();
    }

    @Test
    void openingTheFilesReadsOnlyTheFirstFooter() throws IOException {
        List<CountingInputFile> files = countingFiles();
        try (ParquetFileReader reader = ParquetFileReader.openAll(files)) {
            assertThat(touched(files))
                    .as("openAll reads the reference schema, which is the first file's footer")
                    .isEqualTo(1);
            assertThat(reader.getFileCount()).isEqualTo(FILE_COUNT);
        }
    }

    @Test
    void aReadThatStopsEarlyDoesNotOpenEveryFile() throws IOException {
        List<CountingInputFile> files = countingFiles();
        try (ParquetFileReader reader = ParquetFileReader.openAll(files);
             RowReader rows = reader.buildRowReader().build()) {
            if (rows.hasNext()) {
                rows.next();
            }
            assertThat(touched(files))
                    .as("a caller who reads one row should not have paid for all %d files", FILE_COUNT)
                    .isLessThan(FILE_COUNT);
        }
    }

    @Test
    void readingEveryRowStillReadsEveryFile() throws IOException {
        List<CountingInputFile> files = countingFiles();
        long rows = 0;
        try (ParquetFileReader reader = ParquetFileReader.openAll(files);
             RowReader reading = reader.buildRowReader().build()) {
            while (reading.hasNext()) {
                reading.next();
                rows++;
            }
        }
        assertThat(rows).isPositive();
        assertThat(touched(files))
                .as("planning is deferred, not skipped")
                .isEqualTo(FILE_COUNT);
    }
}
