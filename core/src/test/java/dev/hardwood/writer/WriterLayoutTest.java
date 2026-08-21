/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.Validity;
import dev.hardwood.internal.metadata.PageHeader;
import dev.hardwood.internal.predicate.StatisticsDecoder;
import dev.hardwood.internal.thrift.PageHeaderReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;

import static dev.hardwood.writer.WriterTestSupport.columnMeta;
import static dev.hardwood.writer.WriterTestSupport.oneColumn;
import static dev.hardwood.writer.WriterTestSupport.readInts;
import static dev.hardwood.writer.WriterTestSupport.readListOfInts;
import static org.assertj.core.api.Assertions.assertThat;

/// How a file is banded into pages and row groups, and what each page carries.
///
/// The page and row-group targets are the only layout controls a caller has, and both are
/// byte targets over buffered data rather than row counts. These assert that crossing either
/// boundary changes where the values sit and not which values they are — for flat columns,
/// for a `BOOLEAN` column whose page cut falls away from a word boundary, and for a list
/// column whose single record outgrows a page.
class WriterLayoutTest {

    @Test
    void largeColumnIsSplitAcrossMultiplePages() throws Exception {
        // Comfortably more than one target page (262,144 INT32 values per 1 MiB page).
        int n = 600_000;
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, oneColumn())) {
            writer.writeBatch(batch -> batch.ints(0, values));
        }
        byte[] bytes = out.toByteArray();

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(bytes)))) {
            assertThat(reader.getFileMetaData().numRows()).isEqualTo(n);
            // One 128 MiB row group; 600k values at 262,144 per 1 MiB page ⇒ exactly 3 pages.
            assertThat(reader.getFileMetaData().rowGroups()).hasSize(1);
            ColumnMetaData meta = reader.getFileMetaData().rowGroups().get(0).columns().get(0).metaData();
            assertThat(countDataPages(bytes, meta.dataPageOffset(), meta.numValues())).isEqualTo(3);
            // Arrays.equals over containsExactly: the latter is O(n) with per-element
            // boxing/description and is needlessly slow at 600k elements.
            assertThat(Arrays.equals(readInts(reader, 0), values)).isTrue();
        }
    }

    @Test
    void largeColumnIsSplitAcrossMultipleRowGroups() throws Exception {
        int n = 5_000;
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }

        // 4 KiB target ⇒ 1024 rows per row group ⇒ the single batch spans several groups.
        WriterConfig config = WriterConfig.builder().rowGroupTargetBytes(4096).build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, oneColumn(), config)) {
            writer.writeBatch(batch -> batch.ints(0, values));
        }

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().numRows()).isEqualTo(n);
            // 5000 rows at 1024 per group ⇒ four full groups and a 904-row tail, pinning
            // the cadence arithmetic rather than merely asserting "more than one".
            assertThat(reader.getFileMetaData().rowGroups().stream().map(RowGroup::numRows))
                    .containsExactly(1024L, 1024L, 1024L, 1024L, 904L);
            assertThat(Arrays.equals(readInts(reader, 0), values)).isTrue();
        }
    }

    @Test
    void rowGroupsDoNotShareChunkState() throws Exception {
        // Two row groups whose values do not overlap: the first all-distinct, so it is written
        // PLAIN, the second low-cardinality, so it is dictionary-encoded. Whatever a row group
        // accumulates — statistics, dictionary, the encoding its values argued for — must not
        // carry into the next one.
        int perGroup = 1024;
        int[] first = new int[perGroup];
        int[] second = new int[perGroup];
        for (int i = 0; i < perGroup; i++) {
            first[i] = 1_000_000 + i;      // all distinct, so this chunk is written PLAIN
            second[i] = 10 + i % 4;        // four distinct values, so this one is dictionary-encoded
        }

        WriterConfig config = WriterConfig.builder()
                .rowGroupTargetBytes(perGroup * Integer.BYTES)
                .build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, oneColumn(), config)) {
            writer.writeBatch(batch -> batch.ints(0, first));
            writer.writeBatch(batch -> batch.ints(0, second));
        }

        byte[] file = out.toByteArray();
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(file)))) {
            List<RowGroup> rowGroups = reader.getFileMetaData().rowGroups();
            assertThat(rowGroups).hasSize(2);

            // Statistics describe their own row group, not everything written so far.
            Statistics firstStats = rowGroups.get(0).columns().get(0).metaData().statistics();
            Statistics secondStats = rowGroups.get(1).columns().get(0).metaData().statistics();
            assertThat(StatisticsDecoder.decodeInt(firstStats.minValue())).isEqualTo(1_000_000);
            assertThat(StatisticsDecoder.decodeInt(firstStats.maxValue())).isEqualTo(1_000_000 + perGroup - 1);
            assertThat(StatisticsDecoder.decodeInt(secondStats.minValue())).isEqualTo(10);
            assertThat(StatisticsDecoder.decodeInt(secondStats.maxValue())).isEqualTo(13);

            // The first group's values argued for PLAIN; the second's argue for a dictionary, so
            // the first group's verdict does not leak across the boundary either.
            ColumnMetaData secondChunk = rowGroups.get(1).columns().get(0).metaData();
            assertThat(secondChunk.encodings()).contains(Encoding.RLE_DICTIONARY);

            // And that dictionary holds the second group's four values and nothing else. Reading
            // the values back would not catch a dictionary carrying the first group's entries too,
            // because the indices written against it would still resolve; the entry count does.
            ThriftCompactReader dictionaryPage = new ThriftCompactReader(ByteBuffer.wrap(file),
                    Math.toIntExact(secondChunk.dictionaryPageOffset()));
            assertThat(PageHeaderReader.read(dictionaryPage).dictionaryPageHeader().numValues()).isEqualTo(4);

            // And the values themselves survive, which a dictionary carrying the previous group's
            // entries would not manage.
            int[] expected = new int[2 * perGroup];
            System.arraycopy(first, 0, expected, 0, perGroup);
            System.arraycopy(second, 0, expected, perGroup, perGroup);
            assertThat(readInts(reader, 0)).containsExactly(expected);
        }
    }

    @Test
    void writesCorrectPageCrc() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, oneColumn())) {
            writer.writeBatch(batch -> batch.ints(0, new int[] { 1, 2, 3, 4, 5 }));
        }
        byte[] bytes = out.toByteArray();

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(bytes)))) {
            ColumnMetaData meta = reader.getFileMetaData().rowGroups().get(0).columns().get(0).metaData();
            int offset = Math.toIntExact(meta.dataPageOffset());
            ThriftCompactReader thrift = new ThriftCompactReader(ByteBuffer.wrap(bytes), offset);
            PageHeader header = PageHeaderReader.read(thrift);
            int bodyStart = offset + thrift.getBytesRead();

            assertThat(header.crc()).as("page crc must be written").isNotNull();
            CRC32 crc = new CRC32();
            crc.update(bytes, bodyStart, header.compressedPageSize());
            assertThat(header.crc().intValue()).isEqualTo((int) crc.getValue());
        }
    }

    @Test
    void booleanPagesCutAwayFromAWordBoundary() throws Exception {
        // A BOOLEAN chunk retains its values one bit each, so a page's value range starts at an
        // arbitrary bit rather than an array slot. An odd page target puts those starts away from
        // a 64-bit word boundary: at 25 bytes a page holds 200 values, so the pages begin at bits
        // 0, 8, 16, 24 … of their words. The value pattern is coprime with both 8 and 64, so a
        // shift lost anywhere in the packing changes what reads back.
        int n = 5_000;
        boolean[] values = new boolean[n];
        for (int i = 0; i < n; i++) {
            values[i] = i % 7 == 0 || i % 11 == 3;
        }

        FileSchema schema = FileSchema.builder("m")
                .addColumn("b", PhysicalType.BOOLEAN, RepetitionType.REQUIRED).build();
        WriterConfig config = WriterConfig.builder().pageTargetBytes(25).build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch.booleans(0, values));
        }

        byte[] file = out.toByteArray();
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(file)))) {
            ColumnMetaData meta = columnMeta(reader, 0);
            assertThat(countDataPages(file, meta.dataPageOffset(), meta.numValues()))
                    .as("pages, so most value ranges start mid-word").isGreaterThan(1);
            assertThat(readBooleans(reader, 0)).isEqualTo(values);
        }
    }

    @Test
    void singleLargeListRecordSpansManyPages() throws Exception {
        // One record whose list is far larger than a page: streaming must seal pages part-way
        // through the record, and the reader must reassemble it across pages via rep levels.
        FileSchema schema = FileSchema.builder("schema")
                .list("v", RepetitionType.REQUIRED, el -> el.primitive(PhysicalType.INT32, RepetitionType.REQUIRED))
                .build();

        int n = 5_000;
        int[] offsets = { 0, n };
        int[] elements = new int[n];
        List<Integer> expected = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            elements[i] = i;
            expected.add(i);
        }

        WriterConfig config = WriterConfig.builder().pageTargetBytes(64).build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch.list("v", offsets).ints("v.list.element", elements));
        }

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().numRows()).isEqualTo(1);
            ColumnMetaData meta = reader.getFileMetaData().rowGroups().get(0).columns().get(0).metaData();
            assertThat(meta.numValues()).isEqualTo(n); // the single record's elements span multiple pages
            int leaf = reader.getFileSchema().getColumn("v.list.element").columnIndex();
            assertThat(readListOfInts(reader, leaf)).containsExactly(expected);
        }
    }

    @Test
    void listsSurvivePageAndRowGroupBoundaries() throws Exception {
        // Many records with varying list lengths, absent lists, and interior null elements,
        // written with tiny page and row-group targets so lists straddle both boundaries.
        FileSchema schema = FileSchema.builder("schema")
                .list("v", RepetitionType.OPTIONAL, el -> el.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
                .build();

        int records = 2_000;
        List<List<Integer>> expected = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        List<Boolean> listNulls = new ArrayList<>();
        List<Integer> elements = new ArrayList<>();
        List<Boolean> elementNulls = new ArrayList<>();
        offsets.add(0);
        int element = 0;
        for (int r = 0; r < records; r++) {
            if (r % 7 == 0) {
                listNulls.add(true);
                expected.add(null);
            }
            else {
                listNulls.add(false);
                List<Integer> list = new ArrayList<>();
                int length = r % 4; // 0..3, so empty and non-empty both occur
                for (int k = 0; k < length; k++) {
                    boolean isNull = element % 5 == 0;
                    int value = r * 10 + k;
                    elements.add(value);
                    elementNulls.add(isNull);
                    list.add(isNull ? null : value);
                    element++;
                }
                expected.add(list);
            }
            offsets.add(elements.size());
        }

        WriterConfig config = WriterConfig.builder().pageTargetBytes(64).rowGroupTargetBytes(256).build();
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema, config)) {
            writer.writeBatch(batch -> batch
                    .list("v", toIntArray(offsets), Validity.ofNulls(toBooleanArray(listNulls)))
                    .ints("v.list.element", toIntArray(elements), toBooleanArray(elementNulls)));
        }

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().rowGroups().size()).isGreaterThan(1);
            int leaf = reader.getFileSchema().getColumn("v.list.element").columnIndex();
            assertThat(readListOfInts(reader, leaf)).isEqualTo(expected);
        }
    }

    private static boolean[] readBooleans(ParquetFileReader reader, int columnIndex) {
        try (ColumnReader column = reader.columnReader(columnIndex)) {
            boolean[] result = new boolean[Math.toIntExact(reader.getFileMetaData().numRows())];
            int pos = 0;
            while (column.nextBatch()) {
                int count = column.getValueCount();
                System.arraycopy(column.getBooleans(), 0, result, pos, count);
                pos += count;
            }
            return result;
        }
    }

    /// Walks the column chunk's contiguous data pages from `startOffset`, returning
    /// how many pages it took to cover `totalValues`.
    private static int countDataPages(byte[] file, long startOffset, long totalValues) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(file);
        int offset = Math.toIntExact(startOffset);
        long seen = 0;
        int pages = 0;
        while (seen < totalValues) {
            ThriftCompactReader reader = new ThriftCompactReader(buf, offset);
            PageHeader header = PageHeaderReader.read(reader);
            pages++;
            seen += header.dataPageHeader().numValues();
            offset += reader.getBytesRead() + header.compressedPageSize();
        }
        return pages;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private static boolean[] toBooleanArray(List<Boolean> list) {
        boolean[] array = new boolean[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
