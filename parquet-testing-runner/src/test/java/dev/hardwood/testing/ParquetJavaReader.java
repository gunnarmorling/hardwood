/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.page.DataPage;
import org.apache.parquet.column.page.DataPageV1;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.column.page.PageReader;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.hadoop.util.HadoopInputFile;

/// Reads a Parquet file with parquet-java, the strict reader of the write-path interop gate
/// described in `_designs/WRITER_INTEROP_GATE.md`.
///
/// Rows come back as [Group]s rather than Avro records: the Group model materializes any valid
/// file with no object model in the way, so annotations Avro cannot represent — `UUID`,
/// `FLOAT16`, `INTERVAL`, the unsigned integer widths — are still readable, and a read failure
/// means the bytes are bad rather than that Avro could not model the type. The Avro path used by
/// the read-direction comparison lives in [Utils#readWithParquetJava].
final class ParquetJavaReader {

    private ParquetJavaReader() {
    }

    /// Every row of the file, materialized through parquet-java's Group record model.
    ///
    /// @param file the file to read
    /// @return the rows, in file order
    /// @throws IOException if parquet-java cannot read the file — which, for a file Hardwood
    ///         wrote, is the gate failing
    static List<Group> readGroups(Path file) throws IOException {
        List<Group> rows = new ArrayList<>();
        try (ParquetReader<Group> reader = ParquetReader
                .builder(new GroupReadSupport(), hadoopPath(file))
                .withConf(new Configuration())
                .build()) {

            Group row;
            while ((row = reader.read()) != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    /// The file's footer as parquet-java parses it: the schema, the row groups, and each column
    /// chunk's encodings and statistics.
    ///
    /// @param file the file to read
    /// @return the parsed footer
    /// @throws IOException if parquet-java cannot parse the footer
    static ParquetMetadata readFooter(Path file) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader
                .open(HadoopInputFile.fromPath(hadoopPath(file), new Configuration()))) {
            return reader.getFooter();
        }
    }

    /// Walks every data page of every column chunk through parquet-java's page readers, and
    /// reports what they were: how many there were, and which encodings their *values* declared.
    ///
    /// The page-level value encoding is the only place the encoding choice is observable. A
    /// column chunk's `encodings` list is a union that always contains `PLAIN` — a dictionary
    /// page body is itself `PLAIN` — so it cannot tell a dictionary-only chunk from one that
    /// overflowed its dictionary and fell back mid-chunk. Each page header declares its own
    /// encoding, and those do distinguish them.
    ///
    /// Each page is materialized on the way past, so a page that does not decompress or whose
    /// header does not parse fails here.
    ///
    /// @param file the file to read
    /// @return what the walk found
    /// @throws IOException if parquet-java cannot read a page
    static Pages readPages(Path file) throws IOException {
        int count = 0;
        Set<Encoding> valueEncodings = EnumSet.noneOf(Encoding.class);
        try (ParquetFileReader reader = ParquetFileReader
                .open(HadoopInputFile.fromPath(hadoopPath(file), new Configuration()))) {

            List<ColumnDescriptor> columns = reader.getFileMetaData().getSchema().getColumns();
            PageReadStore rowGroup;
            while ((rowGroup = reader.readNextRowGroup()) != null) {
                for (ColumnDescriptor column : columns) {
                    PageReader pageReader = rowGroup.getPageReader(column);
                    DataPage page;
                    while ((page = pageReader.readPage()) != null) {
                        count++;
                        valueEncodings.add(valueEncoding(page));
                    }
                }
            }
        }
        return new Pages(count, valueEncodings);
    }

    /// What a page walk found: the data page count and the set of encodings their values
    /// declared, across every row group and column of the file.
    ///
    /// @param dataPageCount how many data pages the file holds
    /// @param valueEncodings the distinct value encodings those pages declared
    record Pages(int dataPageCount, Set<Encoding> valueEncodings) {
    }

    /// The encoding a data page declares for its values. The writer produces DataPage V1 only, so
    /// anything else is a change in what is being written rather than a case this reader should
    /// quietly accommodate.
    private static Encoding valueEncoding(DataPage page) {
        if (!(page instanceof DataPageV1 v1)) {
            throw new IllegalStateException(
                    "Expected a V1 data page but got " + page.getClass().getSimpleName());
        }
        return v1.getValueEncoding();
    }

    private static org.apache.hadoop.fs.Path hadoopPath(Path file) {
        return new org.apache.hadoop.fs.Path(file.toUri());
    }
}
