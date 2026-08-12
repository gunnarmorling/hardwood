/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Mixin;

import dev.hardwood.InputFile;
import dev.hardwood.cli.internal.Fmt;
import dev.hardwood.cli.internal.Sizes;
import dev.hardwood.cli.internal.table.RowTable;
import dev.hardwood.internal.thrift.OffsetIndexReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.OffsetIndex;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.metadata.SizeStatistics;
import dev.hardwood.reader.ParquetFileReader;

@CommandDefinition(name = "columns", description = "Show compressed, uncompressed and unencoded byte sizes per column, ranked.", generateHelp = true)
public class InspectColumnsCommand implements Command<CommandInvocation> {

    @Mixin
    FileMixin fileMixin;

    @Override
    public CommandResult execute(CommandInvocation ci) {
        InputFile inputFile = fileMixin.toInputFile();
        if (inputFile == null) {
            return CommandResult.FAILURE;
        }

        try (ParquetFileReader reader = ParquetFileReader.open(inputFile)) {
            FileMetaData metadata = reader.getFileMetaData();
            try {
                inputFile.open();
                List<ColumnSize> sizes = aggregateSizes(metadata, inputFile);
                sizes.sort(Comparator.comparingLong(ColumnSize::compressed).reversed());
                printRanked(sizes);
            }
            finally {
                inputFile.close();
            }
        }
        catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return CommandResult.FAILURE;
        }

        return CommandResult.SUCCESS;
    }

    private static List<ColumnSize> aggregateSizes(FileMetaData metadata, InputFile inputFile) {
        Map<String, ColumnSize> byColumn = new LinkedHashMap<>();

        for (RowGroup rg : metadata.rowGroups()) {
            for (ColumnChunk cc : rg.columns()) {
                ColumnMetaData cmd = cc.metaData();
                String path = Sizes.columnPath(cmd);
                int pageCount = countPages(cc, inputFile);
                long unencoded = unencodedSize(cmd);
                ColumnSize existing = byColumn.get(path);
                if (existing == null) {
                    byColumn.put(path, new ColumnSize(path, cmd.type().name(), cmd.codec().name(),
                            cmd.totalCompressedSize(), cmd.totalUncompressedSize(), pageCount, pageCount >= 0,
                            Math.max(unencoded, 0), unencoded >= 0));
                }
                else {
                    int combinedPages = (existing.pageCountAvailable() && pageCount >= 0)
                            ? existing.pageCount() + pageCount
                            : -1;
                    byColumn.put(path, new ColumnSize(path, existing.type(), existing.codec(),
                            existing.compressed() + cmd.totalCompressedSize(),
                            existing.uncompressed() + cmd.totalUncompressedSize(),
                            combinedPages,
                            existing.pageCountAvailable() && pageCount >= 0,
                            existing.unencoded() + Math.max(unencoded, 0),
                            existing.unencodedAvailable() && unencoded >= 0));
                }
            }
        }

        return new ArrayList<>(byColumn.values());
    }

    /// The chunk's unencoded `BYTE_ARRAY` size, or -1 when the writer records
    /// none. A column is only reported as a whole if every one of its chunks
    /// has the field: a partial sum reads as a real total and understates it.
    private static long unencodedSize(ColumnMetaData cmd) {
        SizeStatistics sizeStatistics = cmd.sizeStatistics();
        if (sizeStatistics == null || sizeStatistics.unencodedByteArrayDataBytes() == null) {
            return -1;
        }
        return sizeStatistics.unencodedByteArrayDataBytes();
    }

    private static int countPages(ColumnChunk cc, InputFile inputFile) {
        Long offset = cc.offsetIndexOffset();
        Integer length = cc.offsetIndexLength();
        if (offset == null || length == null || length <= 0) {
            return -1;
        }
        try {
            ByteBuffer buffer = inputFile.readRange(offset, length);
            OffsetIndex oi = OffsetIndexReader.read(new ThriftCompactReader(buffer));
            return oi.pageLocations().size();
        }
        catch (IOException e) {
            return -1;
        }
    }

    private void printRanked(List<ColumnSize> sizes) {
        String[] headers = {"Rank", "Column", "Type", "Compressed", "Uncompressed", "Unencoded", "Ratio", "# Pages"};
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            ColumnSize s = sizes.get(i);
            double ratio = s.uncompressed() > 0 ? (100.0 * s.compressed() / s.uncompressed()) : 100.0;
            rows.add(new String[]{
                    String.valueOf(i + 1),
                    s.path(),
                    s.type(),
                    Sizes.format(s.compressed()),
                    Sizes.format(s.uncompressed()),
                    s.unencodedAvailable() ? Sizes.format(s.unencoded()) : "-",
                    Fmt.fmt("%.1f%%", ratio),
                    s.pageCountAvailable() ? String.valueOf(s.pageCount()) : "-"
            });
        }
        System.out.println(RowTable.renderTable(headers, rows));
    }

    private record ColumnSize(String path, String type, String codec, long compressed, long uncompressed,
                              int pageCount, boolean pageCountAvailable, long unencoded,
                              boolean unencodedAvailable) {
    }
}
