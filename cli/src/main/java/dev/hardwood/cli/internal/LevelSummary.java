/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import java.util.ArrayList;
import java.util.List;

import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.metadata.SizeStatistics;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

/// Derived view of a column chunk's size statistics: the repetition- and
/// definition-level histograms with each bucket named from the schema, the
/// quantities that follow from them, and a check of those quantities against
/// the chunk's own declared counts.
///
/// A raw histogram is unreadable on its own — `[52428, 104857, 39321]`
/// does not say which bucket counts an absent field and which counts an
/// empty list, and that distinction exists nowhere else in the metadata.
/// The names follow from the column's path through the schema alone.
///
/// Holds no formatting and performs no I/O, so `dive` and
/// `hardwood inspect columns` can render one instance in their own idioms.
/// Optional quantities are exposed as a `has…()` / value pair rather than as
/// a boxed component, so nothing here allocates per level.
///
/// @param numValues the chunk's declared value count
/// @param unencodedBytes unencoded size of the `BYTE_ARRAY` data, meaningful only when [#hasUnencoded()]
/// @param hasUnencoded whether the file records an unencoded size for this chunk
/// @param maxDefinitionLevel the column's maximum definition level
/// @param maxRepetitionLevel the column's maximum repetition level
/// @param firstRepeatedLevel definition level of the column's outermost repeated node, or 0 when it has none
/// @param definitionLevels one row per definition level, or empty when the file records no usable histogram
/// @param repetitionLevels one row per repetition level, or empty when the file records no usable histogram
/// @param mismatch description of a disagreement between the declared counts and the histograms, or `null` when they agree
public record LevelSummary(
        long numValues,
        long unencodedBytes,
        boolean hasUnencoded,
        int maxDefinitionLevel,
        int maxRepetitionLevel,
        int firstRepeatedLevel,
        List<LevelRow> definitionLevels,
        List<LevelRow> repetitionLevels,
        String mismatch) {

    /// One bucket of a level histogram: the level, the schema-derived name for
    /// what a value at that level means, the count, and its share of the total.
    public record LevelRow(int level, String label, long count, double share) {
    }

    private static final char[] EIGHTHS = {'▏', '▎', '▍', '▌', '▋', '▊', '▉', '█'};

    private static final int LABEL_WIDTH = 20;
    private static final int COUNT_WIDTH = 10;

    /// Inner widths at which a rendered level row still has room for the
    /// percentage, and for the percentage plus a bar. Below the first, the
    /// level, label and count are all that fit.
    private static final int PERCENTAGE_WIDTH_FLOOR = 44;
    private static final int BAR_WIDTH_FLOOR = 56;

    /// Width below which not even the level, label and count columns fit.
    /// Rows rendered into less than this overflow it, in the same way the
    /// facts pane's other rows do; the surrounding widget clips them.
    public static final int MINIMUM_WIDTH = 5 + LABEL_WIDTH + COUNT_WIDTH;

    /// A node along a column's path that raises the definition level,
    /// paired with the name of the field enclosing it. The enclosing name
    /// is what a `REPEATED` node is labelled with.
    private record LevelNode(String name, String dottedPath, RepetitionType repetitionType, String parentName) {
    }

    /// Builds the summary for one column chunk, or returns `null` when the
    /// file records no size statistics for it — the same convention the rest
    /// of the metadata uses for an absent optional structure.
    public static LevelSummary of(FileSchema schema, ColumnSchema column, ColumnMetaData metaData) {
        SizeStatistics statistics = metaData.sizeStatistics();
        if (statistics == null) {
            return null;
        }
        int maxDefinitionLevel = column.maxDefinitionLevel();
        int maxRepetitionLevel = column.maxRepetitionLevel();
        List<LevelRow> definitionLevels = rows(statistics.definitionLevelHistogram(),
                definitionLabels(schema, column), maxDefinitionLevel);
        List<LevelRow> repetitionLevels = rows(statistics.repetitionLevelHistogram(),
                repetitionLabels(schema, column), maxRepetitionLevel);
        Long unencoded = statistics.unencodedByteArrayDataBytes();
        return new LevelSummary(
                metaData.numValues(),
                unencoded != null ? unencoded : 0L,
                unencoded != null,
                maxDefinitionLevel,
                maxRepetitionLevel,
                firstRepeatedLevel(schema, column),
                definitionLevels,
                repetitionLevels,
                mismatch(metaData, definitionLevels, repetitionLevels, maxDefinitionLevel));
    }

    /// Whether the file records a definition-level histogram that can be read.
    /// A writer may omit one, emit an empty one, or emit one whose length does
    /// not match the column's maximum level; all three are unusable here.
    public boolean hasDefinitionHistogram() {
        return !definitionLevels.isEmpty();
    }

    /// Whether the file records a usable repetition-level histogram.
    public boolean hasRepetitionHistogram() {
        return !repetitionLevels.isEmpty();
    }

    /// Whether the chunk's record count is known. A non-repeated column writes
    /// no repetition histogram, but every one of its values is its own record.
    public boolean hasRecords() {
        return hasRepetitionHistogram() || maxRepetitionLevel == 0;
    }

    public long records() {
        return hasRepetitionHistogram() ? repetitionLevels.get(0).count() : numValues;
    }

    /// Whether the chunk's present-value count is known. A required column
    /// writes no definition histogram, but every one of its values is present.
    public boolean hasPresentValues() {
        return hasDefinitionHistogram() || maxDefinitionLevel == 0;
    }

    public long presentValues() {
        return hasDefinitionHistogram() ? definitionLevels.get(maxDefinitionLevel).count() : numValues;
    }

    /// Total of the definition histogram, which the format defines as the
    /// chunk's value count — the denominator every definition share is taken
    /// against.
    public long definitionTotal() {
        return total(definitionLevels);
    }

    public boolean hasAvgFanOut() {
        return hasDefinitionHistogram() && hasRecords() && records() > 0;
    }

    /// Level slots per record: how many values the chunk stores for each row
    /// it covers.
    public double avgFanOut() {
        return definitionTotal() / (double) records();
    }

    /// Only defined for a singly-repeated column. With nested repetition one
    /// average has no unambiguous referent, so no figure is offered.
    public boolean hasAvgListLength() {
        return maxRepetitionLevel == 1 && hasDefinitionHistogram() && hasRepetitionHistogram()
                && records() - elementlessRecords() > 0;
    }

    /// Mean length of the lists that have at least one element, so an absent
    /// or empty list does not drag the figure toward zero.
    public double avgListLength() {
        long below = elementlessRecords();
        return (definitionTotal() - below) / (double) (records() - below);
    }

    public boolean hasAvgValueSize() {
        return hasUnencoded && hasPresentValues() && presentValues() > 0;
    }

    public double avgValueSize() {
        return unencodedBytes / (double) presentValues();
    }

    /// The length prefixes `unencoded_byte_array_data_bytes` excludes: PLAIN
    /// writes a four-byte length before each value, so the two together are
    /// the real PLAIN size.
    public long lengthPrefixBytes() {
        return 4L * presentValues();
    }

    /// Adds level histograms together across chunks. Counts at the same level
    /// sum, so a file-wide histogram is exact rather than a sample of one row
    /// group; shares are recomputed against the combined total. Chunks whose
    /// histogram the file does not record contribute nothing.
    ///
    /// @throws IllegalArgumentException if two chunks disagree on how many
    ///         levels the column has, which no file for one column should
    public static List<LevelRow> combineLevels(List<List<LevelRow>> perChunk) {
        long[] counts = null;
        List<LevelRow> labels = null;
        for (List<LevelRow> rows : perChunk) {
            if (rows.isEmpty()) {
                continue;
            }
            if (counts == null) {
                counts = new long[rows.size()];
                labels = rows;
            }
            else if (rows.size() != counts.length) {
                throw new IllegalArgumentException("chunks of one column disagree on level count: "
                        + counts.length + " and " + rows.size());
            }
            for (int level = 0; level < rows.size(); level++) {
                counts[level] += rows.get(level).count();
            }
        }
        if (counts == null) {
            return List.of();
        }
        long total = 0;
        for (long count : counts) {
            total += count;
        }
        List<LevelRow> combined = new ArrayList<>(counts.length);
        for (int level = 0; level < counts.length; level++) {
            double share = total > 0 ? counts[level] / (double) total : 0.0;
            combined.add(new LevelRow(level, labels.get(level).label(), counts[level], share));
        }
        return combined;
    }

    /// Renders `share` as a bar `cells` wide. Eighth-block characters give
    /// sub-cell resolution, so a bucket holding a thousandth of the values
    /// still reads as present instead of rounding away to nothing. Only a
    /// share of exactly zero renders empty.
    public static String bar(double share, int cells) {
        if (share <= 0 || cells <= 0) {
            return "";
        }
        int eighths = Math.max(1, Math.toIntExact(Math.round(share * cells * 8)));
        StringBuilder bar = new StringBuilder();
        bar.append("█".repeat(eighths / 8));
        int remainder = eighths % 8;
        if (remainder > 0) {
            bar.append(EIGHTHS[remainder - 1]);
        }
        return bar.toString();
    }

    /// Renders the level rows as plain text, one line each, so `dive` and
    /// `inspect` show the same characters. Columns drop as the pane narrows:
    /// the bar first, then the percentage, leaving the level, its label and
    /// the count at any width.
    public static List<String> renderLevels(List<LevelRow> rows, int innerWidth) {
        boolean showPercentage = innerWidth >= PERCENTAGE_WIDTH_FLOOR;
        int barCells = innerWidth >= BAR_WIDTH_FLOOR ? innerWidth - PERCENTAGE_WIDTH_FLOOR : 0;
        List<String> lines = new ArrayList<>(rows.size());
        for (LevelRow row : rows) {
            StringBuilder line = new StringBuilder();
            line.append("  ").append(row.level()).append("  ");
            line.append(padRight(row.label(), LABEL_WIDTH));
            line.append(Fmt.fmt("%" + COUNT_WIDTH + "s", Fmt.fmt("%,d", row.count())));
            if (showPercentage) {
                line.append(Fmt.fmt("  %5.1f%%", row.share() * 100));
            }
            String bar = barCells > 0 ? bar(row.share(), barCells) : "";
            if (!bar.isEmpty()) {
                line.append(' ').append(bar);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String padRight(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    /// Values counted below the outermost repeated node — the records whose
    /// list is absent or empty, which contribute no element.
    private long elementlessRecords() {
        long below = 0;
        for (int level = 0; level < firstRepeatedLevel && level < definitionLevels.size(); level++) {
            below += definitionLevels.get(level).count();
        }
        return below;
    }

    private static long total(List<LevelRow> rows) {
        long sum = 0;
        for (LevelRow row : rows) {
            sum += row.count();
        }
        return sum;
    }

    /// Pairs a histogram with its labels. A histogram whose length disagrees
    /// with the column's maximum level cannot be indexed by level, so it is
    /// dropped here and reported through [#mismatch()] rather than rendered
    /// against the wrong names.
    private static List<LevelRow> rows(long[] histogram, String[] labels, int maxLevel) {
        if (histogram == null || histogram.length != maxLevel + 1) {
            return List.of();
        }
        long sum = 0;
        for (long count : histogram) {
            sum += count;
        }
        List<LevelRow> rows = new ArrayList<>(histogram.length);
        for (int level = 0; level < histogram.length; level++) {
            double share = sum > 0 ? histogram[level] / (double) sum : 0.0;
            rows.add(new LevelRow(level, labels[level], histogram[level], share));
        }
        return rows;
    }

    /// The chunk is self-checking: its declared value count must equal both
    /// histogram totals, and its null count must equal the values that never
    /// reached the maximum definition level. A writer that disagrees is
    /// reporting a defect worth surfacing rather than rendering silently.
    private static String mismatch(ColumnMetaData metaData, List<LevelRow> definitionLevels,
                                   List<LevelRow> repetitionLevels, int maxDefinitionLevel) {
        long numValues = metaData.numValues();
        if (!definitionLevels.isEmpty() && total(definitionLevels) != numValues) {
            return Fmt.fmt("values %,d, sum(def) %,d", numValues, total(definitionLevels));
        }
        if (!repetitionLevels.isEmpty() && total(repetitionLevels) != numValues) {
            return Fmt.fmt("values %,d, sum(rep) %,d", numValues, total(repetitionLevels));
        }
        Statistics statistics = metaData.statistics();
        if (statistics == null || statistics.nullCount() == null || definitionLevels.isEmpty()) {
            return null;
        }
        long impliedNulls = numValues - definitionLevels.get(maxDefinitionLevel).count();
        if (statistics.nullCount() != impliedNulls) {
            return Fmt.fmt("nulls %,d, implied by def %,d", statistics.nullCount(), impliedNulls);
        }
        return null;
    }

    /// Definition level of the column's outermost repeated node, or 0 when it
    /// has none. Definition levels below it count records that hold no element.
    private static int firstRepeatedLevel(FileSchema schema, ColumnSchema column) {
        List<LevelNode> nodes = levelNodes(schema, column);
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).repetitionType() == RepetitionType.REPEATED) {
                return index + 1;
            }
        }
        return 0;
    }

    /// Names each definition level `0..maxDefinitionLevel` by the node a
    /// value at that level failed to reach, so `websites empty` and
    /// `element null` replace bucket indices.
    static String[] definitionLabels(FileSchema schema, ColumnSchema column) {
        int maxDefinitionLevel = column.maxDefinitionLevel();
        List<LevelNode> nodes = levelNodes(schema, column);
        String[] labels = new String[maxDefinitionLevel + 1];
        for (int level = 0; level < maxDefinitionLevel; level++) {
            LevelNode node = nodes.get(level);
            labels[level] = node.repetitionType() == RepetitionType.REPEATED
                    ? emptyLabel(node)
                    : node.name() + " null";
        }
        labels[maxDefinitionLevel] = column.name() + " present";
        return labels;
    }

    /// Names each repetition level `0..maxRepetitionLevel`. Level 0 always
    /// starts a new record; level `i` continues the `i`-th repeated node.
    static String[] repetitionLabels(FileSchema schema, ColumnSchema column) {
        String[] labels = new String[column.maxRepetitionLevel() + 1];
        labels[0] = "new record";
        int level = 1;
        for (LevelNode node : levelNodes(schema, column)) {
            if (node.repetitionType() == RepetitionType.REPEATED && level < labels.length) {
                labels[level] = node.dottedPath();
                level++;
            }
        }
        return labels;
    }

    /// A repeated node is named for the field enclosing it, so a LIST reads
    /// `websites empty` rather than naming the synthetic `list` node the
    /// annotation introduces, and a MAP reads `common empty` rather than
    /// `key_value`. An unannotated repeated field at the top level has no
    /// enclosing field and falls back to its own name.
    private static String emptyLabel(LevelNode node) {
        return (node.parentName() != null ? node.parentName() : node.name()) + " empty";
    }

    /// Collects the nodes along the column's path whose repetition type
    /// raises the definition level, in root-to-leaf order. There are
    /// exactly `maxDefinitionLevel` of them; a shorter list means the
    /// schema and the column's computed level disagree, which surfaces as
    /// an out-of-bounds read rather than a silently mislabelled histogram.
    private static List<LevelNode> levelNodes(FileSchema schema, ColumnSchema column) {
        List<LevelNode> nodes = new ArrayList<>();
        SchemaNode current = schema.getRootNode();
        StringBuilder dotted = new StringBuilder();
        String parentName = null;
        for (String element : column.fieldPath().elements()) {
            SchemaNode child = childNamed(current, element);
            if (!dotted.isEmpty()) {
                dotted.append('.');
            }
            dotted.append(element);
            RepetitionType repetition = child.repetitionType();
            if (repetition == RepetitionType.OPTIONAL || repetition == RepetitionType.REPEATED) {
                nodes.add(new LevelNode(element, dotted.toString(), repetition, parentName));
            }
            parentName = element;
            current = child;
        }
        return nodes;
    }

    private static SchemaNode childNamed(SchemaNode parent, String name) {
        if (!(parent instanceof SchemaNode.GroupNode group)) {
            throw new IllegalStateException("expected a group while walking to " + name
                    + ", found leaf " + parent.name());
        }
        for (SchemaNode child : group.children()) {
            if (child.name().equals(name)) {
                return child;
            }
        }
        throw new IllegalStateException("no child named " + name + " under " + group.name());
    }
}
