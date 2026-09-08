/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [KeyValueMetadataWriter], pinning it as the exact inverse of
/// [KeyValueMetadataReader]: what is written here reads back as the same map.
class KeyValueMetadataWriterTest {

    @Test
    void readsBackEqual() throws Exception {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ARROW:schema", "AAAA");
        metadata.put("pandas", "{\"index_columns\": []}");

        assertThat(roundTrip(metadata)).isEqualTo(metadata);
    }

    /// The entries reach the file in the order they were given, and read back in it: the field
    /// is a list, and both sides preserve insertion order.
    @Test
    void preservesEntryOrder() throws Exception {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("z", "1");
        metadata.put("a", "2");
        metadata.put("m", "3");

        assertThat(roundTrip(metadata)).containsExactlyEntriesOf(metadata);
    }

    /// `KeyValue.value` is optional, so a null value writes a struct carrying only its key —
    /// not an empty string. That distinction is what lets a key read from a file that carried
    /// no value be written back unchanged.
    @Test
    void nullValueWritesAKeyWithNoValue() throws Exception {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("marker", null);
        metadata.put("other", "");

        Map<String, String> readBack = roundTrip(metadata);
        assertThat(readBack).containsEntry("marker", null);
        assertThat(readBack).containsEntry("other", "");
    }

    @Test
    void emptyMapWritesAnEmptyList() throws Exception {
        assertThat(roundTrip(Map.of())).isEmpty();
    }

    /// The list must terminate exactly where it ends: a struct that leaked a field-id context
    /// or an unbalanced STOP would desync every field written after it.
    @Test
    void listEndsWhereItStops() throws Exception {
        ThriftCompactWriter writer = new ThriftCompactWriter();
        writer.pushFieldIdContext();
        writer.writeFieldBegin(5, FieldType.LIST);
        KeyValueMetadataWriter.write(writer, Map.of("key", "value"));
        writer.writeFieldBegin(6, FieldType.BINARY);
        writer.writeString("hardwood");

        ThriftCompactReader reader = new ThriftCompactReader(ByteBuffer.wrap(writer.toByteArray()));
        assertThat(ThriftCompactReader.fieldId(reader.readFieldHeader())).isEqualTo((short) 5);
        assertThat(KeyValueMetadataReader.read(reader))
                .containsExactlyEntriesOf(Map.of("key", "value"));

        assertThat(ThriftCompactReader.fieldId(reader.readFieldHeader())).isEqualTo((short) 6);
        assertThat(reader.readString()).isEqualTo("hardwood");
    }

    private static Map<String, String> roundTrip(Map<String, String> metadata) throws Exception {
        ThriftCompactWriter writer = new ThriftCompactWriter();
        writer.pushFieldIdContext();
        writer.writeFieldBegin(5, FieldType.LIST);
        KeyValueMetadataWriter.write(writer, metadata);

        ThriftCompactReader reader = new ThriftCompactReader(ByteBuffer.wrap(writer.toByteArray()));
        reader.readFieldHeader();
        return KeyValueMetadataReader.read(reader);
    }
}
