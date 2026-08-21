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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.Validity;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.FieldPath;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.LayerKind;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

import static org.assertj.core.api.Assertions.assertThat;

/// Copies a nested file column by column, reading through [ColumnReader]'s layer model and
/// writing through [ColumnBatch]'s group verbs, and asserts the copy is byte-identical to its
/// source. Nesting is the shape where the two columnar APIs describe the same file most
/// differently, so this pins that a caller can in fact bridge them without going through the
/// row layer, and that the bridge is exact rather than merely equivalent.
///
/// It also pins the four places the bridge is not a pass-through, each of which is a silent
/// wrong answer or an exception if a caller assumes otherwise:
///
/// - **Layers do not name their group.** `getLayerKind(k)` reports `STRUCT` or `REPEATED` but
///   not which group layer `k` is, and the writer's only group address is a dot path. So
///   `layerGroupsOf` re-derives the correspondence from the schema, reapplying the rule
///   [LayerKind] documents — including skipping the synthetic `list` / `key_value` segment,
///   which is a path segment but not a layer.
/// - **`LayerKind` does not separate `LIST` from `MAP`**, but the writer has distinct `list`
///   and `map` verbs that reject each other's groups, so the kind comes from the schema too.
/// - **Layers are per leaf, group verbs are per group.** Two leaves under one struct both
///   report it, and setting a group twice is rejected, so shared ancestors need de-duplicating.
/// - **Leaf validity is not the writer's leaf null mask.** `getLeafValidity()` answers "is
///   there a value in this slot", which is false wherever an `OPTIONAL` ancestor is absent, so
///   a `REQUIRED` leaf under an `OPTIONAL` struct reports nulls — and the writer refuses a mask
///   on a `REQUIRED` column. For `BYTE_ARRAY` the same slots come back as Java `null`s that the
///   all-present setter rejects, so they must be plugged with a placeholder first.
///
/// The fixture is sized so the reader hands the copy more than one batch, on boundaries that
/// have nothing to do with the ones the source was written on. Byte-identity across that
/// mismatch is the assertion worth making, because it is what makes a batch an arrival unit
/// rather than a property of the file — and it is what lets a copier submit whatever the
/// reader gives it. It holds while the row-group target is not crossed inside an arriving
/// batch; a target small enough to be crossed mid-batch moves the cut with the arrival
/// granularity, and the copy then reproduces the data but not the layout.
class ColumnarNestedCopyTest {

    @Test
    void aNestedFileCopiedThroughTheColumnarApisIsByteIdentical() throws Exception {
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("id", PhysicalType.INT32, RepetitionType.REQUIRED)
                .struct("address", RepetitionType.OPTIONAL, address -> address
                        .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                                new LogicalType.StringType())
                        .addColumn("zip", PhysicalType.INT32, RepetitionType.OPTIONAL))
                .list("tags", RepetitionType.OPTIONAL, element -> element.primitive(
                        PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, new LogicalType.StringType()))
                .map("props", RepetitionType.OPTIONAL, PhysicalType.BYTE_ARRAY,
                        new LogicalType.StringType(),
                        value -> value.primitive(PhysicalType.INT64, RepetitionType.OPTIONAL))
                .build();

        byte[] source = writeSource(schema);

        // ---- the copy ----
        int batches = 0;
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileReader reader = open(source);
             ColumnReaders readers = reader.columnReaders(ColumnProjection.all());
             ParquetFileWriter writer = ParquetFileWriter.create(out, schema)) {

            List<List<GroupRef>> layerGroups = deriveLayerGroups(schema);

            while (readers.nextBatch()) {
                batches++;
                writer.writeBatch(batch -> {
                    Set<String> alreadySet = new HashSet<>();
                    for (int c = 0; c < schema.getColumnCount(); c++) {
                        ColumnReader col = readers.getColumnReader(c);
                        List<GroupRef> ancestors = layerGroups.get(c);
                        for (int l = 0; l < col.getLayerCount(); l++) {
                            GroupRef g = ancestors.get(l);
                            if (!alreadySet.add(g.path)) {
                                continue;
                            }
                            if (col.getLayerKind(l) == LayerKind.STRUCT) {
                                batch.struct(g.path, col.getLayerValidity(l));
                            }
                            else if (!g.optional) {
                                // Same asymmetry as the leaf: a REQUIRED list/map refuses a
                                // mask, so the two-argument form is the only one that fits.
                                if (g.isMap) {
                                    batch.map(g.path, col.getLayerOffsets(l));
                                }
                                else {
                                    batch.list(g.path, col.getLayerOffsets(l));
                                }
                            }
                            else if (g.isMap) {
                                batch.map(g.path, col.getLayerOffsets(l), col.getLayerValidity(l));
                            }
                            else {
                                batch.list(g.path, col.getLayerOffsets(l), col.getLayerValidity(l));
                            }
                        }
                        copyLeaf(batch, col, c);
                    }
                });
            }
        }

        // The reader's batches do not coincide with the ones the source was written in, so
        // the copy is only a pass-through if they need not: a batch is an arrival unit, not a
        // property of the file. Without this the loop below runs once and neither the
        // per-batch reset of `alreadySet` nor offsets restarting at a batch are exercised.
        assertThat(batches).isGreaterThan(1);
        assertThat(out.toByteArray()).isEqualTo(source);
    }

    private static void copyLeaf(ColumnBatch batch, ColumnReader col, int index) {
        Validity nulls = col.getLeafValidity();
        // The reader's leaf validity answers "is there a value in this slot", which is false
        // wherever an OPTIONAL ancestor is absent — so a REQUIRED leaf under an OPTIONAL
        // struct reports nulls. The writer's leaf mask answers "is this column's own value
        // null" and is refused outright on a REQUIRED column. Different quantities: the
        // branch has to be on the column's repetition, not on whether the mask has bits set.
        if (col.getColumnSchema().repetitionType() != RepetitionType.OPTIONAL) {
            switch (col.getColumnSchema().type()) {
                case INT32 -> batch.ints(index, col.getInts());
                case INT64 -> batch.longs(index, col.getLongs());
                case FLOAT -> batch.floats(index, col.getFloats());
                case DOUBLE -> batch.doubles(index, col.getDoubles());
                case BOOLEAN -> batch.booleans(index, col.getBooleans());
                case BYTE_ARRAY -> batch.bytes(index, plugHoles(col.getBinaries(), 0));
                case FIXED_LEN_BYTE_ARRAY -> batch.fixed(index,
                        plugHoles(col.getBinaries(), col.getColumnSchema().typeLength()));
                default -> throw new UnsupportedOperationException("INT96 is not writable");
            }
            return;
        }
        switch (col.getColumnSchema().type()) {
            case INT32 -> batch.ints(index, col.getInts(), nulls);
            case INT64 -> batch.longs(index, col.getLongs(), nulls);
            case FLOAT -> batch.floats(index, col.getFloats(), nulls);
            case DOUBLE -> batch.doubles(index, col.getDoubles(), nulls);
            case BOOLEAN -> batch.booleans(index, col.getBooleans(), nulls);
            case BYTE_ARRAY -> batch.bytes(index, col.getBinaries(), nulls);
            case FIXED_LEN_BYTE_ARRAY -> batch.fixed(index, col.getBinaries(), nulls);
            default -> throw new UnsupportedOperationException("INT96 is not writable");
        }
    }

    /// A `REQUIRED` leaf under an `OPTIONAL` ancestor has no value where the ancestor is
    /// absent, and the reader reports that as a Java `null` in the `byte[][]`. The writer
    /// validates every slot of an all-present array before the levels get a chance to mark it
    /// ignorable, so the holes have to be plugged with a placeholder it will never encode.
    ///
    /// Plugged into a copy of the array, not in place: [ColumnReader#getBinaries] memoizes
    /// what it returns for the batch, so writing placeholders into it would leave a second
    /// reader of the same column seeing empty arrays where the reader reported absence.
    private static byte[][] plugHoles(byte[][] source, int fixedLength) {
        byte[][] values = source.clone();
        byte[] filler = new byte[fixedLength];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                values[i] = filler;
            }
        }
        return values;
    }

    /// A group the writer must be told about, and which layer of which leaf carries its data.
    private record GroupRef(String path, boolean isMap, boolean optional) {}

    /// Re-derives, per leaf column, the ordered groups its layers correspond to — the rule
    /// `LayerKind` documents: an `OPTIONAL` group or a `LIST`/`MAP` group contributes a layer,
    /// a `REQUIRED` group and the synthetic scaffolding inside a `LIST`/`MAP` do not.
    private static List<List<GroupRef>> deriveLayerGroups(FileSchema schema) {
        List<List<GroupRef>> perColumn = new ArrayList<>();
        for (ColumnSchema column : schema.getColumns()) {
            perColumn.add(layerGroupsOf(schema, column.fieldPath()));
        }
        return perColumn;
    }

    private static List<GroupRef> layerGroupsOf(FileSchema schema, FieldPath leafPath) {
        List<GroupRef> groups = new ArrayList<>();
        SchemaNode node = schema.getRootNode();
        StringBuilder path = new StringBuilder();
        List<String> elements = leafPath.elements();
        // Every element but the leaf itself is a group on the chain.
        for (int i = 0; i < elements.size() - 1; i++) {
            String segment = elements.get(i);
            node = childNamed(node, segment);
            if (!(node instanceof SchemaNode.GroupNode group)) {
                throw new IllegalStateException("Not a group: " + segment);
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(segment);
            if (group.isList() || group.isMap()) {
                groups.add(new GroupRef(path.toString(), group.isMap(),
                        group.repetitionType() == RepetitionType.OPTIONAL));
                // Skip the synthetic repeated group: it contributes no layer, but it IS a
                // path segment, so the next iteration must not append it to the group path.
                i++;
                String synthetic = elements.get(i);
                node = childNamed(node, synthetic);
                path.append('.').append(synthetic);
            }
            else if (group.repetitionType() == RepetitionType.OPTIONAL) {
                groups.add(new GroupRef(path.toString(), false, true));
            }
        }
        return groups;
    }

    private static SchemaNode childNamed(SchemaNode node, String name) {
        for (SchemaNode child : ((SchemaNode.GroupNode) node).children()) {
            if (child.name().equals(name)) {
                return child;
            }
        }
        throw new IllegalStateException("No child " + name);
    }

    /// Enough records that the reader hands the copy more than one batch, cycling the four
    /// container states every nested column has — present with entries, present and empty,
    /// absent, and (for the struct) present with its own `OPTIONAL` leaf unset — so a batch
    /// boundary falls somewhere inside each of them rather than only between whole shapes.
    private static final int RECORDS = 4_000;

    /// Entries on the wide records' list. The reader sizes a batch to a 6 MB value budget
    /// divided by the bytes one row occupies, and a list's fan-out multiplies that width, so
    /// this is the cheapest lever that makes the file arrive in more than one batch.
    private static final int WIDE_TAGS = 250;

    private static byte[] writeSource(FileSchema schema) throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema)) {
            RowWriter rows = writer.rowWriter();
            for (int i = 0; i < RECORDS; i++) {
                int id = i;
                rows.writeRow(row -> {
                    row.setInt("id", id);
                    switch (id % 4) {
                        case 0 -> row
                                .setStruct("address", a -> a.setString("city", "Berlin" + id)
                                        .setInt("zip", 10115 + id))
                                .setList("tags", t -> {
                                    for (int e = 0; e < WIDE_TAGS; e++) {
                                        t.addString("a" + id + "-" + e);
                                    }
                                })
                                .setMap("props", p -> p.addEntry(
                                        e -> e.setString("key", "x" + id).setLong("value", id)));
                        // An absent struct, an empty list and an absent map.
                        case 1 -> row.setList("tags", t -> { });
                        // A present struct whose OPTIONAL leaf is unset, and an empty map.
                        case 2 -> row
                                .setStruct("address", a -> a.setString("city", "Aarhus" + id))
                                .setMap("props", p -> { });
                        // A null list distinct from case 1's empty one, and a null map value.
                        default -> row
                                .setNull("tags")
                                .setMap("props", p -> p.addEntry(
                                        e -> e.setString("key", "y" + id).setNull("value")));
                    }
                });
            }
        }
        return out.toByteArray();
    }

    private static ParquetFileReader open(byte[] file) throws Exception {
        return ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(file)));
    }

}
