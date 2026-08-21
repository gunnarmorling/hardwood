/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

import dev.hardwood.Validity;
import dev.hardwood.internal.util.StringToIntMap;
import dev.hardwood.row.PqInterval;
import dev.hardwood.writer.ColumnBatch;
import dev.hardwood.writer.ListBuilder;
import dev.hardwood.writer.MapBuilder;
import dev.hardwood.writer.StructBuilder;

/// A struct group in the row-oriented layer's plan, and the [StructBuilder] its scope hands
/// to the caller. The record itself is one of these, as is a `MAP`'s key/value entry.
///
/// An `OPTIONAL` struct owns a [NullMaskStage] carrying one bit per instance; a `REQUIRED`
/// one has no bit to carry and owns none.
final class RowStructNode extends RowNode implements StructBuilder {

    private final RowNode[] children;

    /// The field name at each child index, in schema order: what the by-index setters address
    /// and [#byName] is built from.
    private final String[] fieldNames;

    /// Field name to child index. The by-name surface is a boundary that resolves to an index
    /// and then does exactly what the by-index one does, so the resolution runs once per field
    /// per record — which a `Map<String, Integer>` would make an unboxing per call. This is the
    /// same open-addressed, primitive-valued map the read side resolves names through in
    /// `TopLevelFieldMap` and `FlatRowReader`, and it returns a negative index for a name the
    /// struct does not have.
    private final StringToIntMap byName;

    /// Per-instance nulls of an `OPTIONAL` struct, or `null` when the struct is `REQUIRED`.
    private final NullMaskStage nulls;

    /// Which children the active scope has set, so an unset field resolves to a null and a
    /// field set twice is rejected.
    private final boolean[] set;

    private boolean active;

    RowStructNode(String path, RowNode[] children, String[] fieldNames, boolean nullable) {
        super(path);
        this.children = children;
        this.fieldNames = fieldNames;
        this.byName = new StringToIntMap(fieldNames.length);
        for (int i = 0; i < fieldNames.length; i++) {
            // Two fields of one name would make the by-name surface ambiguous, and the index
            // the loser resolves to unreachable. Caught while the map is built rather than by
            // a separate pass, because a collision is exactly what building it detects.
            if (byName.get(fieldNames[i]) >= 0) {
                throw new UnsupportedOperationException(
                        "Schema has two fields named '" + fieldNames[i] + "' under " + describe());
            }
            byName.put(fieldNames[i], i);
        }
        this.nulls = nullable ? new NullMaskStage() : null;
        this.set = new boolean[children.length];
    }

    NullMaskStage nulls() {
        return nulls;
    }

    boolean isActive() {
        return active;
    }

    /// Opens this struct's scope for one present instance.
    void beginScope() {
        if (active) {
            throw reenteredScope();
        }
        active = true;
        Arrays.fill(set, false);
        if (nulls != null) {
            nulls.append(false);
        }
    }

    /// Closes the scope, resolving every field the caller left unset.
    void endScope() {
        active = false;
        for (int i = 0; i < children.length; i++) {
            if (!set[i]) {
                children[i].appendNullInstance();
            }
        }
    }

    void clearActive() {
        active = false;
    }

    @Override
    void appendNullInstance() {
        if (nulls == null) {
            throw requiredField();
        }
        nulls.append(true);
        appendAbsentChildren();
    }

    @Override
    void appendAbsentInstance() {
        if (nulls != null) {
            nulls.append(true);
        }
        appendAbsentChildren();
    }

    private void appendAbsentChildren() {
        for (RowNode child : children) {
            child.appendAbsentInstance();
        }
    }

    @Override
    void collect(RowPlan.Nodes nodes) {
        nodes.structs().add(this);
        for (RowNode child : children) {
            child.collect(nodes);
        }
    }

    @Override
    void fill(ColumnBatch batch) {
        if (nulls == null) {
            return;
        }
        Validity validity = nulls.validity();
        if (validity != null) {
            batch.struct(path, validity);
        }
    }

    @Override
    String kind() {
        return "struct group";
    }

    // ==================== StructBuilder ====================

    @Override
    public StructBuilder setInt(String name, int value) {
        leaf(name, "setInt").setInt(value);
        return this;
    }

    @Override
    public StructBuilder setLong(String name, long value) {
        leaf(name, "setLong").setLong(value);
        return this;
    }

    @Override
    public StructBuilder setFloat(String name, float value) {
        leaf(name, "setFloat").setFloat(value);
        return this;
    }

    @Override
    public StructBuilder setDouble(String name, double value) {
        leaf(name, "setDouble").setDouble(value);
        return this;
    }

    @Override
    public StructBuilder setBoolean(String name, boolean value) {
        leaf(name, "setBoolean").setBoolean(value);
        return this;
    }

    @Override
    public StructBuilder setString(String name, String value) {
        leaf(name, "setString").setString(value);
        return this;
    }

    @Override
    public StructBuilder setBinary(String name, byte[] value) {
        leaf(name, "setBinary").setBinary(value);
        return this;
    }

    @Override
    public StructBuilder setDate(String name, LocalDate value) {
        leaf(name, "setDate").setDate(value);
        return this;
    }

    @Override
    public StructBuilder setTime(String name, LocalTime value) {
        leaf(name, "setTime").setTime(value);
        return this;
    }

    @Override
    public StructBuilder setTimestamp(String name, Instant value) {
        leaf(name, "setTimestamp").setTimestamp(value);
        return this;
    }

    @Override
    public StructBuilder setLocalTimestamp(String name, LocalDateTime value) {
        leaf(name, "setLocalTimestamp").setLocalTimestamp(value);
        return this;
    }

    @Override
    public StructBuilder setDecimal(String name, BigDecimal value) {
        leaf(name, "setDecimal").setDecimal(value);
        return this;
    }

    @Override
    public StructBuilder setUuid(String name, UUID value) {
        leaf(name, "setUuid").setUuid(value);
        return this;
    }

    @Override
    public StructBuilder setInterval(String name, PqInterval value) {
        leaf(name, "setInterval").setInterval(value);
        return this;
    }

    @Override
    public StructBuilder setNull(String name) {
        children[claim(name)].appendNullInstance();
        return this;
    }

    @Override
    public StructBuilder setStruct(String name, Consumer<StructBuilder> filler) {
        return fillStruct(claim(name), filler);
    }

    @Override
    public StructBuilder setList(String name, Consumer<ListBuilder> filler) {
        return fillList(claim(name), filler);
    }

    @Override
    public StructBuilder setMap(String name, Consumer<MapBuilder> filler) {
        return fillMap(claim(name), filler);
    }

    // ==================== StructBuilder, by index ====================

    @Override
    public int getFieldCount() {
        checkActive();
        return children.length;
    }

    @Override
    public String getFieldName(int fieldIndex) {
        checkActive();
        return fieldNames[checkIndex(fieldIndex)];
    }

    @Override
    public StructBuilder setInt(int fieldIndex, int value) {
        leaf(fieldIndex, "setInt").setInt(value);
        return this;
    }

    @Override
    public StructBuilder setLong(int fieldIndex, long value) {
        leaf(fieldIndex, "setLong").setLong(value);
        return this;
    }

    @Override
    public StructBuilder setFloat(int fieldIndex, float value) {
        leaf(fieldIndex, "setFloat").setFloat(value);
        return this;
    }

    @Override
    public StructBuilder setDouble(int fieldIndex, double value) {
        leaf(fieldIndex, "setDouble").setDouble(value);
        return this;
    }

    @Override
    public StructBuilder setBoolean(int fieldIndex, boolean value) {
        leaf(fieldIndex, "setBoolean").setBoolean(value);
        return this;
    }

    @Override
    public StructBuilder setString(int fieldIndex, String value) {
        leaf(fieldIndex, "setString").setString(value);
        return this;
    }

    @Override
    public StructBuilder setBinary(int fieldIndex, byte[] value) {
        leaf(fieldIndex, "setBinary").setBinary(value);
        return this;
    }

    @Override
    public StructBuilder setDate(int fieldIndex, LocalDate value) {
        leaf(fieldIndex, "setDate").setDate(value);
        return this;
    }

    @Override
    public StructBuilder setTime(int fieldIndex, LocalTime value) {
        leaf(fieldIndex, "setTime").setTime(value);
        return this;
    }

    @Override
    public StructBuilder setTimestamp(int fieldIndex, Instant value) {
        leaf(fieldIndex, "setTimestamp").setTimestamp(value);
        return this;
    }

    @Override
    public StructBuilder setLocalTimestamp(int fieldIndex, LocalDateTime value) {
        leaf(fieldIndex, "setLocalTimestamp").setLocalTimestamp(value);
        return this;
    }

    @Override
    public StructBuilder setDecimal(int fieldIndex, BigDecimal value) {
        leaf(fieldIndex, "setDecimal").setDecimal(value);
        return this;
    }

    @Override
    public StructBuilder setUuid(int fieldIndex, UUID value) {
        leaf(fieldIndex, "setUuid").setUuid(value);
        return this;
    }

    @Override
    public StructBuilder setInterval(int fieldIndex, PqInterval value) {
        leaf(fieldIndex, "setInterval").setInterval(value);
        return this;
    }

    @Override
    public StructBuilder setNull(int fieldIndex) {
        children[claim(fieldIndex)].appendNullInstance();
        return this;
    }

    @Override
    public StructBuilder setStruct(int fieldIndex, Consumer<StructBuilder> filler) {
        return fillStruct(claim(fieldIndex), filler);
    }

    @Override
    public StructBuilder setList(int fieldIndex, Consumer<ListBuilder> filler) {
        return fillList(claim(fieldIndex), filler);
    }

    @Override
    public StructBuilder setMap(int fieldIndex, Consumer<MapBuilder> filler) {
        return fillMap(claim(fieldIndex), filler);
    }

    // ==================== Nested scopes ====================

    private StructBuilder fillStruct(int childIndex, Consumer<StructBuilder> filler) {
        RowNode child = children[childIndex];
        if (!(child instanceof RowStructNode struct)) {
            throw wrongVerb(child, "setStruct", "a struct group");
        }
        struct.beginScope();
        filler.accept(struct);
        struct.endScope();
        return this;
    }

    private StructBuilder fillList(int childIndex, Consumer<ListBuilder> filler) {
        RowNode child = children[childIndex];
        if (!(child instanceof RowListNode list)) {
            throw wrongVerb(child, "setList", "a LIST group");
        }
        list.beginScope();
        filler.accept(list);
        list.endScope();
        return this;
    }

    private StructBuilder fillMap(int childIndex, Consumer<MapBuilder> filler) {
        RowNode child = children[childIndex];
        if (!(child instanceof RowMapNode map)) {
            throw wrongVerb(child, "setMap", "a MAP group");
        }
        map.beginScope();
        filler.accept(map);
        map.endScope();
        return this;
    }

    // ==================== Resolution ====================

    private RowLeafNode leaf(String fieldName, String setter) {
        return leafAt(claim(fieldName), setter);
    }

    private RowLeafNode leaf(int fieldIndex, String setter) {
        return leafAt(claim(fieldIndex), setter);
    }

    private RowLeafNode leafAt(int childIndex, String setter) {
        RowNode child = children[childIndex];
        if (!(child instanceof RowLeafNode leafNode)) {
            throw wrongVerb(child, setter, "a leaf field");
        }
        return leafNode;
    }

    /// Resolves a field name against this struct and marks it set, rejecting an unknown name,
    /// a second set of the same field, and any use after the scope has ended.
    private int claim(String fieldName) {
        checkActive();
        // Resolved to "not a field" rather than passed to the map, which hashes the key and
        // would fail a null name as a bare NPE out of a utility class instead of naming the
        // struct it was looked up in.
        int index = fieldName == null ? -1 : byName.get(fieldName);
        if (index < 0) {
            throw new IllegalArgumentException("No field named '" + fieldName + "' in " + describe()
                    + "; it has " + String.join(", ", fieldNames));
        }
        return markSet(index);
    }

    /// The by-index counterpart of [#claim(String)], rejecting an out-of-range position where
    /// that one rejects an unknown name.
    private int claim(int fieldIndex) {
        checkActive();
        return markSet(checkIndex(fieldIndex));
    }

    private int checkIndex(int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= children.length) {
            throw new IndexOutOfBoundsException("Field index " + fieldIndex + " is out of bounds for "
                    + describe() + ", which has " + children.length + " fields");
        }
        return fieldIndex;
    }

    private void checkActive() {
        if (!active) {
            throw new IllegalStateException("This builder's scope has ended; a builder is only valid "
                    + "inside the lambda it was passed to");
        }
    }

    private int markSet(int childIndex) {
        if (set[childIndex]) {
            throw new IllegalArgumentException("Field " + children[childIndex].path
                    + " is already set in this record");
        }
        set[childIndex] = true;
        return childIndex;
    }

    private IllegalArgumentException wrongVerb(RowNode child, String setter, String expected) {
        return new IllegalArgumentException("Field " + child.path + " is a " + child.kind()
                + "; " + setter + " applies to " + expected);
    }

    private String describe() {
        return path.isEmpty() ? "the record" : "struct " + path;
    }
}
