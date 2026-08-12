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
import org.aesh.command.option.Option;

import dev.hardwood.InputFile;
import dev.hardwood.cli.dive.internal.Plurals;
import dev.hardwood.cli.internal.Fmt;
import dev.hardwood.cli.internal.LevelSummary;
import dev.hardwood.cli.internal.Sizes;
import dev.hardwood.cli.internal.table.RowTable;
import dev.hardwood.internal.thrift.ColumnIndexReader;
import dev.hardwood.internal.thrift.OffsetIndexReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnIndex;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.OffsetIndex;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.metadata.SizeStatistics;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

@CommandDefinition(name = "columns", description = "Show compressed, uncompressed and unencoded byte sizes per column, ranked.", generateHelp = true)
public class InspectColumnsCommand implements Command<CommandInvocation> {

    /// Fixed render width for the level blocks. The output is meant to be
    /// diffed and pasted, so it must not vary with the terminal it ran in.
    private static final int DETAIL_LEVEL_WIDTH = 60;

    @Mixin
    FileMixin fileMixin;

    @Option(shortName = 'c', name = "column", description = "Print per-row-group detail and level histograms for a single column.")
    String column;

    @Option(name = "row-group", description = "Restrict --column output to a single row group.")
    Integer rowGroup;

    @Override
    public CommandResult execute(CommandInvocation ci) {
        if (rowGroup != null && column == null) {
            System.err.println("--row-group requires --column");
            return CommandResult.FAILURE;
        }
        InputFile inputFile = fileMixin.toInputFile();
        if (inputFile == null) {
            return CommandResult.FAILURE;
        }

        try (ParquetFileReader reader = ParquetFileReader.open(inputFile)) {
            FileMetaData metadata = reader.getFileMetaData();
            try {
                inputFile.open();
                if (column != null) {
                    return printColumnDetail(metadata, reader.getFileSchema(), inputFile);
                }
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

    /// Prints one row per row group for a single column, then the level
    /// histograms. Level histograms combine by addition, so the default block
    /// covers the whole file exactly; `--row-group` narrows both.
    private CommandResult printColumnDetail(FileMetaData metadata, FileSchema schema, InputFile inputFile) {
        ColumnSchema columnSchema = findColumn(schema);
        if (columnSchema == null) {
            System.err.println("No such column: " + column);
            return CommandResult.FAILURE;
        }
        if (rowGroup != null && (rowGroup < 0 || rowGroup >= metadata.rowGroups().size())) {
            System.err.println("No such row group: " + rowGroup
                    + " (file has " + metadata.rowGroups().size() + ")");
            return CommandResult.FAILURE;
        }

        System.out.println();
        System.out.println(header(columnSchema));
        System.out.println();

        List<String[]> rows = new ArrayList<>();
        List<List<LevelSummary.LevelRow>> definitionLevels = new ArrayList<>();
        List<List<LevelSummary.LevelRow>> repetitionLevels = new ArrayList<>();
        for (int rg = 0; rg < metadata.rowGroups().size(); rg++) {
            if (rowGroup != null && rowGroup != rg) {
                continue;
            }
            ColumnChunk chunk = chunkOf(metadata.rowGroups().get(rg), columnSchema);
            if (chunk == null) {
                continue;
            }
            LevelSummary summary = LevelSummary.of(schema, columnSchema, chunk.metaData());
            rows.add(detailRow(rg, chunk, summary, inputFile));
            if (summary != null) {
                definitionLevels.add(summary.definitionLevels());
                repetitionLevels.add(summary.repetitionLevels());
            }
        }
        System.out.println(RowTable.renderTable(
                new String[]{"RG", "Values", "Nulls", "Records", "Present", "Fan-out", "Unencoded", "Size stats"},
                rows));

        String scope = rowGroup != null ? "RG #" + rowGroup : "all row groups";
        printLevelBlock("Definition levels", scope, columnSchema.maxDefinitionLevel(),
                LevelSummary.combineLevels(definitionLevels));
        printLevelBlock("Repetition levels", scope, columnSchema.maxRepetitionLevel(),
                LevelSummary.combineLevels(repetitionLevels));
        return CommandResult.SUCCESS;
    }

    private static void printLevelBlock(String title, String scope, int maxLevel,
                                        List<LevelSummary.LevelRow> levels) {
        if (levels.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(Fmt.fmt("%s (%s, max %d)", title, scope, maxLevel));
        // A fixed width rather than the terminal's: the output is meant to be
        // diffed and pasted, so it must not vary with the window it ran in.
        for (String line : LevelSummary.renderLevels(levels, DETAIL_LEVEL_WIDTH)) {
            System.out.println(line);
        }
    }

    private String[] detailRow(int rg, ColumnChunk chunk, LevelSummary summary, InputFile inputFile) {
        ColumnMetaData cmd = chunk.metaData();
        Statistics statistics = cmd.statistics();
        return new String[]{
                String.valueOf(rg),
                Fmt.fmt("%,d", cmd.numValues()),
                statistics != null && statistics.nullCount() != null
                        ? Fmt.fmt("%,d", statistics.nullCount())
                        : "-",
                summary != null && summary.maxRepetitionLevel() > 0 && summary.hasRecords()
                        ? Fmt.fmt("%,d", summary.records())
                        : "-",
                summary != null && summary.maxDefinitionLevel() > 0 && summary.hasPresentValues()
                        ? Fmt.fmt("%,d", summary.presentValues())
                        : "-",
                summary != null && summary.maxRepetitionLevel() > 0 && summary.hasAvgFanOut()
                        ? Fmt.fmt("%.2f", summary.avgFanOut())
                        : "-",
                summary != null && summary.hasUnencoded()
                        ? Sizes.format(summary.unencodedBytes())
                        : "-",
                coverage(chunk, summary, inputFile)
        };
    }

    /// How much of the chunk the file describes: the chunk-level statistics
    /// always, and the per-page copies in the column index when it has them.
    private static String coverage(ColumnChunk chunk, LevelSummary summary, InputFile inputFile) {
        if (summary == null) {
            return "-";
        }
        ColumnIndex columnIndex = readColumnIndex(chunk, inputFile);
        if (columnIndex == null
                || (columnIndex.definitionLevelHistograms() == null
                        && columnIndex.repetitionLevelHistograms() == null)) {
            return "chunk only";
        }
        int pages = countPages(chunk, inputFile);
        return pages >= 0 ? "chunk + " + Plurals.format(pages, "page", "pages") : "chunk + pages";
    }

    private static ColumnIndex readColumnIndex(ColumnChunk chunk, InputFile inputFile) {
        Long offset = chunk.columnIndexOffset();
        Integer length = chunk.columnIndexLength();
        if (offset == null || length == null || length <= 0) {
            return null;
        }
        try {
            return ColumnIndexReader.read(new ThriftCompactReader(inputFile.readRange(offset, length)));
        }
        catch (IOException e) {
            return null;
        }
    }

    private String header(ColumnSchema columnSchema) {
        StringBuilder header = new StringBuilder(columnSchema.fieldPath().toString());
        header.append("  ").append(columnSchema.type().name());
        if (columnSchema.logicalType() != null) {
            header.append(" / ").append(columnSchema.logicalType());
        }
        header.append("  max def ").append(columnSchema.maxDefinitionLevel());
        header.append("  max rep ").append(columnSchema.maxRepetitionLevel());
        return header.toString();
    }

    private ColumnSchema findColumn(FileSchema schema) {
        for (ColumnSchema candidate : schema.getColumns()) {
            if (candidate.fieldPath().matchesDottedName(column)) {
                return candidate;
            }
        }
        return null;
    }

    private static ColumnChunk chunkOf(RowGroup rowGroup, ColumnSchema columnSchema) {
        for (ColumnChunk chunk : rowGroup.columns()) {
            if (chunk.metaData().pathInSchema().equals(columnSchema.fieldPath())) {
                return chunk;
            }
        }
        return null;
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
