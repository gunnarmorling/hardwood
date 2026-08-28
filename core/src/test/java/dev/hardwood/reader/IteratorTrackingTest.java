/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.reader;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.internal.reader.CountingInputFile;
import dev.hardwood.schema.ColumnProjection;

import static org.assertj.core.api.Assertions.assertThat;

/// A [ParquetFileReader] tracks the [dev.hardwood.internal.reader.RowGroupIterator]s
/// it hands to child readers so that [ParquetFileReader#close()] can tear down an
/// iterator whose child reader the caller never closed.
///
/// Where a child exclusively owns its iterator — the single-column
/// [ParquetFileReader#columnReader(int)] path — that tracking must not outlive the
/// child, or a reader used for many sequential column reads retains every finished
/// child's work list until it is itself closed.
class IteratorTrackingTest {

    private static final String FILE = "src/test/resources/page_index_test.parquet";

    @Test
    void closingAChildReaderStopsTrackingItsIterator() throws Exception {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(Paths.get(FILE)))) {
            assertThat(reader.trackedIteratorCount()).isZero();

            for (int i = 0; i < 4; i++) {
                ColumnReader columnReader = reader.columnReader(0);
                assertThat(reader.trackedIteratorCount())
                        .as("an open child reader's iterator is tracked")
                        .isEqualTo(1);
                while (columnReader.nextBatch()) {
                    // drain
                }
                columnReader.close();
                assertThat(reader.trackedIteratorCount())
                        .as("iteration %d must not leave its iterator behind", i)
                        .isZero();
            }

            // The row-reader and multi-column paths share one iterator across
            // sibling readers, so no individual child owns it and closing them
            // leaves it tracked until the parent closes. Only the single-column
            // path hands out an iterator its reader exclusively owns.
            try (RowReader rows = reader.rowReader()) {
                while (rows.hasNext()) {
                    rows.next();
                }
            }
            try (ColumnReaders columns = reader.columnReaders(ColumnProjection.columns("id"))) {
                while (columns.nextBatch()) {
                    // drain
                }
            }
            assertThat(reader.trackedIteratorCount()).isEqualTo(2);
        }
    }

    @Test
    void parentCloseStillTearsDownAnUnclosedChildsIterator() throws Exception {
        CountingInputFile file = new CountingInputFile(InputFile.of(Paths.get(FILE)));
        ParquetFileReader reader = ParquetFileReader.open(file);

        ColumnReader leaked = reader.columnReader(0);
        assertThat(leaked.nextBatch()).isTrue();
        assertThat(reader.trackedIteratorCount())
                .as("the never-closed child is still tracked")
                .isEqualTo(1);

        reader.close();

        assertThat(reader.trackedIteratorCount()).isZero();
        assertThat(file.closeCount())
                .as("the parent still owns and closes the input")
                .isEqualTo(1);
    }
}
