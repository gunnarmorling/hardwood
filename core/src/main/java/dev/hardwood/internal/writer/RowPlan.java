/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;
import dev.hardwood.writer.ColumnBatch;
import dev.hardwood.writer.PrecisionLossPolicy;
import dev.hardwood.writer.StructBuilder;

/// The schema as the row-oriented layer sees it: a tree of [RowNode]s that doubles as the
/// builders handed to a caller, and the staging one batch of records accumulates into.
///
/// Built once per writer and reused for the whole file. Staging is checkpointed per record
/// and rolled back if the record fails, so a rejected value leaves the batch exactly as it
/// was and the caller can carry on with the next record.
///
/// The plan keeps flat arrays of every node that stages something, so checkpointing, rolling
/// back, filling and resetting are loops over arrays rather than walks of the tree.
public final class RowPlan {

    /// Collects the tree's nodes by role while the plan is built.
    record Nodes(List<RowLeafNode> leaves, List<RowStructNode> structs, List<RowRepeatedNode> repeated) {

        Nodes() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private final RowStructNode root;
    private final LeafStage[] stages;
    private final int[] stageMarks;
    private final LeafStage[] variableWidthStages;

    private final RowStructNode[] structs;
    private final NullMaskStage[] structNulls;
    private final int[] structMarks;

    private final RowRepeatedNode[] repeated;
    private final int[] repeatedMarks;
    private final int[] repeatedNullMarks;

    private final RowLeafNode[] leaves;

    private RowPlan(RowStructNode root, Nodes nodes) {
        this.root = root;
        this.leaves = nodes.leaves().toArray(new RowLeafNode[0]);
        this.stages = new LeafStage[leaves.length];
        for (int i = 0; i < leaves.length; i++) {
            stages[i] = leaves[i].stage();
        }
        this.stageMarks = new int[stages.length];
        List<LeafStage> variableWidth = new ArrayList<>();
        for (LeafStage stage : stages) {
            if (stage instanceof LeafStage.BinaryStage) {
                variableWidth.add(stage);
            }
        }
        this.variableWidthStages = variableWidth.toArray(new LeafStage[0]);

        this.structs = nodes.structs().toArray(new RowStructNode[0]);
        this.structNulls = new NullMaskStage[structs.length];
        for (int i = 0; i < structs.length; i++) {
            structNulls[i] = structs[i].nulls();
        }
        this.structMarks = new int[structs.length];

        this.repeated = nodes.repeated().toArray(new RowRepeatedNode[0]);
        this.repeatedMarks = new int[repeated.length];
        this.repeatedNullMarks = new int[repeated.length];
    }

    /// Builds the plan for a schema, rejecting the shapes the write path cannot produce.
    ///
    /// @param schema the schema to write
    /// @param precisionLossPolicy what a leaf does with a value finer than it can hold
    /// @return the plan
    /// @throws UnsupportedOperationException if the schema has a shape the writer cannot
    ///         produce
    public static RowPlan build(FileSchema schema, PrecisionLossPolicy precisionLossPolicy) {
        RowStructNode root = buildStruct(schema.getRootNode(), "", schema, false, false, precisionLossPolicy);
        Nodes nodes = new Nodes();
        root.collect(nodes);
        return new RowPlan(root, nodes);
    }

    /// Stages one record, resolving the fields the filler left unset and undoing everything
    /// it staged if it fails.
    ///
    /// @param filler populates the record
    public void writeRecord(Consumer<StructBuilder> filler) {
        // Before the checkpoint, not after: a record started from inside another record's
        // filler would otherwise overwrite the marks the outer record has to roll back to,
        // leaving its values staged when it fails.
        if (root.isActive()) {
            throw root.reenteredScope();
        }
        checkpoint();
        try {
            root.beginScope();
            filler.accept(root);
            root.endScope();
        }
        catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    /// Hands every staged column, struct mask and list offset to the batch.
    public void fill(ColumnBatch batch) {
        for (RowLeafNode leaf : leaves) {
            leaf.fill(batch);
        }
        for (RowStructNode struct : structs) {
            struct.fill(batch);
        }
        for (RowRepeatedNode node : repeated) {
            node.fill(batch);
        }
    }

    /// Returns the staging to empty, ready for the next batch.
    public void reset() {
        for (LeafStage stage : stages) {
            stage.reset();
        }
        for (NullMaskStage nulls : structNulls) {
            if (nulls != null) {
                nulls.reset();
            }
        }
        for (RowRepeatedNode node : repeated) {
            node.reset();
            if (node.nulls() != null) {
                node.nulls().reset();
            }
        }
    }

    /// The staged payload of the variable-width columns, which bounds how much a batch holds
    /// beyond its record count.
    public long variableWidthBytes() {
        long bytes = 0;
        for (LeafStage stage : variableWidthStages) {
            bytes += stage.variableWidthBytes();
        }
        return bytes;
    }

    private void checkpoint() {
        for (int i = 0; i < stages.length; i++) {
            stageMarks[i] = stages[i].count;
        }
        for (int i = 0; i < structs.length; i++) {
            structMarks[i] = structNulls[i] == null ? 0 : structNulls[i].count();
        }
        for (int i = 0; i < repeated.length; i++) {
            repeatedMarks[i] = repeated[i].instanceCount();
            NullMaskStage nulls = repeated[i].nulls();
            repeatedNullMarks[i] = nulls == null ? 0 : nulls.count();
        }
    }

    private void rollback() {
        for (int i = 0; i < stages.length; i++) {
            stages[i].truncate(stageMarks[i]);
        }
        for (int i = 0; i < structs.length; i++) {
            if (structNulls[i] != null) {
                structNulls[i].truncate(structMarks[i]);
            }
            structs[i].clearActive();
        }
        for (int i = 0; i < repeated.length; i++) {
            repeated[i].truncate(repeatedMarks[i]);
            NullMaskStage nulls = repeated[i].nulls();
            if (nulls != null) {
                nulls.truncate(repeatedNullMarks[i]);
            }
            repeated[i].clearActive();
        }
    }

    // ==================== Plan construction ====================

    /// Builds a struct node — the record's root, a nested struct, or a map's key/value entry.
    /// `nullableAncestor` tracks whether any enclosing struct is `OPTIONAL`, which the write
    /// path cannot combine with a repeated field below it.
    private static RowStructNode buildStruct(SchemaNode.GroupNode group, String path, FileSchema schema,
                                             boolean nullable, boolean nullableAncestor,
                                             PrecisionLossPolicy policy) {
        List<SchemaNode> children = group.children();
        RowNode[] nodes = new RowNode[children.size()];
        String[] fieldNames = new String[children.size()];
        for (int i = 0; i < children.size(); i++) {
            SchemaNode child = children.get(i);
            nodes[i] = buildNode(child, childPath(path, child.name()), schema, nullable || nullableAncestor, policy);
            fieldNames[i] = child.name();
        }
        // The node builds its own name-to-index map from these, and rejects a duplicate name
        // as it does so.
        return new RowStructNode(path, nodes, fieldNames, nullable);
    }

    private static RowNode buildNode(SchemaNode node, String path, FileSchema schema, boolean nullableAncestor,
                                     PrecisionLossPolicy policy) {
        return switch (node) {
            case SchemaNode.PrimitiveNode leaf -> {
                if (leaf.repetitionType() == RepetitionType.REPEATED) {
                    throw new UnsupportedOperationException("Field " + path
                            + " is a REPEATED leaf (a legacy two-level list); the writer produces "
                            + "three-level LIST groups only");
                }
                yield new RowLeafNode(path, schema.getColumn(leaf.columnIndex()), policy);
            }
            case SchemaNode.GroupNode group -> buildGroup(group, path, schema, nullableAncestor, policy);
        };
    }

    private static RowNode buildGroup(SchemaNode.GroupNode group, String path, FileSchema schema,
                                      boolean nullableAncestor, PrecisionLossPolicy policy) {
        boolean optional = group.repetitionType() == RepetitionType.OPTIONAL;
        if (group.isList() || group.isMap()) {
            if (nullableAncestor) {
                throw new UnsupportedOperationException("A nullable struct enclosing a repeated field ("
                        + path + ") is not yet supported by the writer");
            }
            SchemaNode.GroupNode repeated = repeatedChild(group, path);
            if (group.isMap()) {
                // A map's entries are a repeated struct of `key` and `value`, populated
                // through the same builder a nested struct uses.
                RowStructNode entry = buildStruct(repeated, childPath(path, repeated.name()),
                        schema, false, false, policy);
                return new RowMapNode(path, optional, entry);
            }
            SchemaNode element = onlyChild(repeated, path);
            String elementPath = childPath(childPath(path, repeated.name()), element.name());
            return new RowListNode(path, optional, buildNode(element, elementPath, schema, false, policy));
        }
        if (group.repetitionType() == RepetitionType.REPEATED) {
            throw new UnsupportedOperationException("Group " + path
                    + " is REPEATED but carries no LIST or MAP annotation; the writer cannot produce it");
        }
        // Any other group — a plain struct, or one carrying an annotation the write path does
        // not interpret, such as VARIANT — is written field by field like a struct.
        return buildStruct(group, path, schema, optional, nullableAncestor, policy);
    }

    private static SchemaNode.GroupNode repeatedChild(SchemaNode.GroupNode group, String path) {
        SchemaNode child = onlyChild(group, path);
        if (!(child instanceof SchemaNode.GroupNode repeated)
                || repeated.repetitionType() != RepetitionType.REPEATED) {
            throw new UnsupportedOperationException("Group " + path
                    + " is annotated LIST or MAP but does not hold a single repeated group");
        }
        return repeated;
    }

    private static SchemaNode onlyChild(SchemaNode.GroupNode group, String path) {
        if (group.children().size() != 1) {
            throw new UnsupportedOperationException("Group " + path + " holds "
                    + group.children().size() + " fields where the LIST or MAP layout it is part of "
                    + "requires exactly one");
        }
        return group.children().get(0);
    }

    private static String childPath(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }
}
