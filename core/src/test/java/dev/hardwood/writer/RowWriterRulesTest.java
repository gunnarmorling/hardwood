/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.internal.writer.ByteBufferOutputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.metadata.SchemaElement;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The rules the row-oriented layer enforces: what a record must cover, which verb fits which
/// field, how long a builder is valid, and which of the two write APIs a file is bound to.
///
/// The schema rules this layer adds are about *addressing* a shape: the by-name setters need
/// sibling names to be unique, and the builders reach a list's values through an element node
/// below the entry, which the legacy two-level lists do not have. The columnar API addresses by
/// index and dotted path and writes both. Whether a shape can be produced at all is settled by
/// [ParquetFileWriter#create] before either view exists, and is asserted in
/// `WriterSchemaShapeTest`.
class RowWriterRulesTest {

    private static FileSchema schema() {
        return FileSchema.builder("schema")
                .addColumn("id", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("name", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, new LogicalType.StringType())
                .list("tags", RepetitionType.OPTIONAL,
                        element -> element.primitive(PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED))
                .struct("address", RepetitionType.OPTIONAL, address -> address
                        .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED))
                .build();
    }

    @Test
    void unsetRequiredFieldFailsTheRecord() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row.setString("name", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id")
                .hasMessageContaining("REQUIRED"));
    }

    @Test
    void unsetRequiredFieldInsideAPresentStructFailsTheRecord() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row
                .setInt("id", 1)
                .setStruct("address", address -> { })))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address.city"));
    }

    /// The rule covers group fields too: a `REQUIRED` struct or list has no null to fall back
    /// on, so leaving it unset fails the record rather than writing an empty one.
    @Test
    void unsetRequiredGroupFailsTheRecord() throws Exception {
        FileSchema schema = FileSchema.builder("schema")
                .struct("address", RepetitionType.REQUIRED, address -> address
                        .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL))
                .list("tags", RepetitionType.REQUIRED,
                        element -> element.primitive(PhysicalType.INT32, RepetitionType.REQUIRED))
                .build();

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema)) {
            RowWriter rows = writer.rowWriter();
            assertThatThrownBy(() -> rows.writeRow(row -> row.setList("tags", tags -> { })))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("address");
            assertThatThrownBy(() -> rows.writeRow(row -> row.setStruct("address", address -> { })))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tags");
            rows.writeRow(row -> row.setStruct("address", address -> { }).setList("tags", tags -> { }));
        }

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().numRows()).isEqualTo(1);
        }
    }

    @Test
    void settingTheSameFieldTwiceIsRejected() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row
                .setInt("id", 1)
                .setString("name", "a")
                .setString("name", "b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already set"));
    }

    @Test
    void unknownFieldNameIsRejected() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row.setInt("nope", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No field named 'nope'"));
    }

    /// A null name is not a field either, and says so the same way. The name resolves through
    /// a map that hashes its key, so without this it would surface as a bare `NullPointerException`
    /// from inside that map rather than as a rejection naming the struct.
    @Test
    void nullFieldNameIsRejectedTheSameWayAnUnknownOneIs() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row.setInt(null, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No field named 'null'")
                .hasMessageContaining("the record"));
    }

    @Test
    void setterThatDoesNotFitTheFieldIsRejected() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row.setLong("id", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setLong requires an INT64 column"));
    }

    @Test
    void verbThatDoesNotFitTheFieldShapeIsRejected() throws Exception {
        withRowWriter(rows -> {
            assertThatThrownBy(() -> rows.writeRow(row -> row.setStruct("tags", tags -> { })))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LIST group");
            assertThatThrownBy(() -> rows.writeRow(row -> row.setInt("id", 1)
                    .setList("tags", tags -> tags.addStruct(entry -> { }))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("addStruct applies to a struct element");
        });
    }

    @Test
    void nullEntryInAListOfRequiredElementsIsRejected() throws Exception {
        withRowWriter(rows -> assertThatThrownBy(() -> rows.writeRow(row -> row
                .setInt("id", 1)
                .setList("tags", tags -> tags.addNull())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REQUIRED"));
    }

    @Test
    void builderRetainedBeyondItsScopeIsRejected() throws Exception {
        AtomicReference<StructBuilder> escaped = new AtomicReference<>();
        withRowWriter(rows -> {
            rows.writeRow(row -> {
                row.setInt("id", 1);
                escaped.set(row);
            });
            assertThatThrownBy(() -> escaped.get().setInt("id", 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scope has ended");
        });
    }

    @Test
    void listBuilderRetainedBeyondItsScopeIsRejected() throws Exception {
        AtomicReference<ListBuilder> escaped = new AtomicReference<>();
        withRowWriter(rows -> {
            rows.writeRow(row -> row.setInt("id", 1).setList("tags", escaped::set));
            assertThatThrownBy(() -> escaped.get().addBinary(new byte[] { 1 }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scope has ended");
        });
    }

    /// A record that fails is staged in full or not at all, so a caller that handles the
    /// failure and carries on writes a file holding exactly the records that succeeded.
    @Test
    void failedRecordLeavesTheStagedBatchUntouched() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema())) {
            RowWriter rows = writer.rowWriter();
            rows.writeRow(row -> row.setInt("id", 1).setString("name", "first")
                    .setList("tags", tags -> tags.addBinary(new byte[] { 1 })));
            assertThatThrownBy(() -> rows.writeRow(row -> row
                    .setInt("id", 2)
                    .setString("name", "doomed")
                    .setList("tags", tags -> tags.addBinary(new byte[] { 2 }))
                    .setStruct("address", address -> { })))
                    .isInstanceOf(IllegalArgumentException.class);
            rows.writeRow(row -> row.setInt("id", 3).setString("name", "third")
                    .setList("tags", tags -> tags.addBinary(new byte[] { 3 })));
        }

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().numRows()).isEqualTo(2);
            try (RowReader rows = reader.rowReader()) {
                rows.next();
                assertThat(rows.getInt("id")).isEqualTo(1);
                assertThat(rows.getList("tags").size()).isEqualTo(1);
                rows.next();
                assertThat(rows.getInt("id")).isEqualTo(3);
                assertThat(rows.getString("name")).isEqualTo("third");
                assertThat(rows.getList("tags").size()).isEqualTo(1);
                assertThat(rows.hasNext()).isFalse();
            }
        }
    }

    @Test
    void aFileIsWrittenThroughOneApiOrTheOther() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema())) {
            writer.rowWriter().writeRow(row -> row.setInt("id", 1));
            assertThatThrownBy(() -> writer.columnWriter().writeBatch(batch -> batch.ints(0, new int[] { 1 })))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rowWriter()");
        }

        ByteBufferOutputFile other = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(other, schema())) {
            writer.columnWriter().writeBatch(batch -> batch
                    .ints(0, new int[] { 1 })
                    .bytes(1, new byte[][] { { 1 } })
                    .list("tags", new int[] { 0, 0 })
                    .bytes("tags.list.element", new byte[0][])
                    .bytes("address.city", new byte[][] { { 1 } }));
            assertThatThrownBy(writer::rowWriter)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("columnWriter()");
        }
    }

    @Test
    void theSameRowWriterIsReturnedEveryTime() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema())) {
            assertThat(writer.rowWriter()).isSameAs(writer.rowWriter());
            writer.rowWriter().writeRow(row -> row.setInt("id", 1));
        }
    }

    @Test
    void theSameColumnWriterIsReturnedEveryTime() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema())) {
            assertThat(writer.columnWriter()).isSameAs(writer.columnWriter());
            writer.columnWriter().writeBatch(batch -> batch
                    .ints(0, new int[] { 1 })
                    .bytes(1, new byte[][] { { 1 } })
                    .list("tags", new int[] { 0, 0 })
                    .bytes("tags.list.element", new byte[0][])
                    .bytes("address.city", new byte[][] { { 1 } }));
        }
    }

    @Test
    void writingAfterCloseIsRejected() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        ParquetFileWriter writer = ParquetFileWriter.create(out, schema());
        RowWriter rows = writer.rowWriter();
        rows.writeRow(row -> row.setInt("id", 1));
        writer.close();

        assertThatThrownBy(() -> rows.writeRow(row -> row.setInt("id", 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(writer::rowWriter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    /// A filler must not re-enter the scope it is inside. Without this the staging silently
    /// restarts the scope and the columnar layer reports the mismatch at flush time, naming
    /// neither the record nor the call that caused it.
    @Test
    void reenteringAnOpenScopeIsRejectedAtTheCallSite() throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema())) {
            RowWriter rows = writer.rowWriter();
            assertThatThrownBy(() -> rows.writeRow(row -> {
                row.setInt("id", 1);
                uncheckedWriteRow(rows);
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start a record while it is already being written");
            rows.writeRow(row -> row.setInt("id", 2));
        }

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(ByteBuffer.wrap(out.toByteArray())))) {
            assertThat(reader.getFileMetaData().numRows()).isEqualTo(1);
        }
    }

    @Test
    void reenteringAnOpenListScopeIsRejectedAtTheCallSite() throws Exception {
        FileSchema schema = FileSchema.builder("schema")
                .list("people", RepetitionType.REQUIRED, element -> element.struct(RepetitionType.REQUIRED,
                        person -> person.addColumn("name", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED)))
                .build();

        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema)) {
            RowWriter rows = writer.rowWriter();
            assertThatThrownBy(() -> rows.writeRow(row -> row.setList("people", people -> people
                    .addStruct(person -> {
                        person.setBinary("name", new byte[] { 1 });
                        people.addStruct(nested -> nested.setBinary("name", new byte[] { 2 }));
                    }))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("while it is already being written");
        }
    }

    /// Calls `writeRow` from inside a filler, where the checked `IOException` cannot be
    /// declared.
    private static void uncheckedWriteRow(RowWriter rows) {
        try {
            rows.writeRow(nested -> nested.setInt("id", 99));
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /// A legacy two-level list is producible — `create` accepts it and the columnar API writes
    /// it — but its entry *is* the element, so there is no element node below the entry for the
    /// builders to navigate to. That divergence is the only one between the two views, so it is
    /// pinned on both sides of the same schema.
    @Test
    void aLegacyTwoLevelListIsWritableColumnarAndRejectedByTheRowView() throws Exception {
        FileSchema schema = FileSchema.fromSchemaElements(List.of(
                SchemaElement.root("schema", 1),
                SchemaElement.group("items", RepetitionType.OPTIONAL, 1, new LogicalType.ListType()),
                SchemaElement.primitive("element", PhysicalType.INT32, RepetitionType.REPEATED)));

        try (ParquetFileWriter writer = ParquetFileWriter.create(new ByteBufferOutputFile(), schema)) {
            assertThatThrownBy(writer::rowWriter)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("items")
                    .hasMessageContaining("legacy two-level list")
                    .hasMessageContaining("The columnar API writes this schema");
        }
        try (ParquetFileWriter writer = ParquetFileWriter.create(new ByteBufferOutputFile(), schema)) {
            writer.columnWriter().writeBatch(batch -> batch
                    .list("items", new int[] { 0, 2 })
                    .ints("items.element", new int[] { 1, 2 }));
        }
    }

    /// The same divergence for the other legacy two-level form, where the entry is a group of
    /// several fields and is therefore itself the element.
    @Test
    void aLegacyTwoLevelListOfStructsIsRejectedByTheRowView() throws Exception {
        FileSchema schema = FileSchema.fromSchemaElements(List.of(
                SchemaElement.root("schema", 1),
                SchemaElement.group("items", RepetitionType.OPTIONAL, 1, new LogicalType.ListType()),
                SchemaElement.group("element", RepetitionType.REPEATED, 2),
                SchemaElement.primitive("a", PhysicalType.INT32, RepetitionType.REQUIRED),
                SchemaElement.primitive("b", PhysicalType.INT32, RepetitionType.REQUIRED)));

        try (ParquetFileWriter writer = ParquetFileWriter.create(new ByteBufferOutputFile(), schema)) {
            assertThatThrownBy(writer::rowWriter)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("items.element")
                    .hasMessageContaining("the list's element itself")
                    .hasMessageContaining("The columnar API writes this schema");
        }
    }

    /// Two sibling fields of one name would leave the by-name setters ambiguous and the second
    /// field's index unreachable through them, so the row layer refuses the schema when the
    /// view is opened. `FileSchema.Builder` permits the shape, and a file carrying it can be
    /// read, so the rejection has to live here.
    @Test
    void twoFieldsOfOneNameAreRejectedUpFront() throws Exception {
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("id", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("id", PhysicalType.INT64, RepetitionType.OPTIONAL)
                .build();

        try (ParquetFileWriter writer = ParquetFileWriter.create(new ByteBufferOutputFile(), schema)) {
            assertThatThrownBy(writer::rowWriter)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("two fields named 'id'")
                    .hasMessageContaining("the record");
        }
    }

    @Test
    void twoFieldsOfOneNameInsideANestedStructAreRejectedUpFront() throws Exception {
        FileSchema schema = FileSchema.builder("schema")
                .struct("address", RepetitionType.OPTIONAL, address -> address
                        .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED)
                        .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL))
                .build();

        try (ParquetFileWriter writer = ParquetFileWriter.create(new ByteBufferOutputFile(), schema)) {
            assertThatThrownBy(writer::rowWriter)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("two fields named 'city'")
                    .hasMessageContaining("struct address");
        }
    }

    /// Runs a body against an open row writer over the standard schema. The file it produces
    /// is irrelevant to these tests; only the rejection is.
    private static void withRowWriter(RowWriterBody body) throws Exception {
        ByteBufferOutputFile out = new ByteBufferOutputFile();
        try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema())) {
            RowWriter rows = writer.rowWriter();
            body.accept(rows);
            // Leave one valid record behind so the file closes on a complete batch.
            rows.writeRow(row -> row.setInt("id", 0));
        }
    }

    private interface RowWriterBody {
        void accept(RowWriter rows) throws Exception;
    }
}
