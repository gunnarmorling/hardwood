/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.avro;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import dev.hardwood.avro.internal.AvroPlanNode;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;
import dev.hardwood.row.PqVariant;
import dev.hardwood.row.StructAccessor;

/// Reads Parquet rows as Avro [GenericRecord] instances.
///
/// Wraps a Hardwood [RowReader] and materializes each row into a
/// `GenericRecord` using the converted Avro schema. Values are stored
/// in Avro's raw representation (e.g. timestamps as `Long`, `bytes`-backed
/// decimals as `ByteBuffer`, `fixed`-typed columns as `GenericData.Fixed`),
/// matching the behavior of parquet-java's `AvroReadSupport`.
///
/// ```java
/// try (AvroRowReader reader = AvroReaders.createRowReader(fileReader)) {
///     while (reader.hasNext()) {
///         GenericRecord record = reader.next();
///         long id = (Long) record.get("id");
///     }
/// }
/// ```
public class AvroRowReader implements AutoCloseable {

    private final RowReader rowReader;

    /// Per-value accessor decisions, taken from the Parquet schema when the Avro
    /// schema was converted. Materialization switches on these rather than on the
    /// converted Avro shape, which no longer distinguishes a Variant group from an
    /// ordinary `{metadata, value}` struct.
    private final AvroPlanNode plan;

    AvroRowReader(RowReader rowReader, AvroPlanNode plan) {
        this.rowReader = rowReader;
        this.plan = plan;
    }

    /// Check if there are more rows to read.
    ///
    /// @return true if there are more rows
    public boolean hasNext() {
        return rowReader.hasNext();
    }

    /// Advance to the next row and return it as a GenericRecord.
    ///
    /// @return the current row as a GenericRecord
    public GenericRecord next() {
        rowReader.next();
        return materializeRecord(rowReader, plan);
    }

    /// Returns the Avro schema used for materialization.
    ///
    /// @return the Avro record schema
    public Schema getSchema() {
        return plan.avro();
    }

    @Override
    public void close() {
        rowReader.close();
    }

    /// Materialize a row or a nested struct. [RowReader] and [PqStruct] are both
    /// [StructAccessor], so the two differ only in the plan node they are read with.
    private GenericRecord materializeRecord(StructAccessor accessor, AvroPlanNode node) {
        Schema recordSchema = node.avro();
        GenericRecord record = new GenericData.Record(recordSchema);
        List<Schema.Field> fields = recordSchema.getFields();
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).name();
            if (accessor.isNull(name)) {
                record.put(i, null);
                continue;
            }
            record.put(i, materializeField(accessor, name, node.child(i)));
        }
        return record;
    }

    private Object materializeField(StructAccessor accessor, String name, AvroPlanNode node) {
        return switch (node.kind()) {
            case BOOLEAN -> accessor.getBoolean(name);
            case INT -> accessor.getInt(name);
            case LONG -> accessor.getLong(name);
            case UNSIGNED_INT32 -> Integer.toUnsignedLong(accessor.getInt(name));
            case FLOAT -> accessor.getFloat(name);
            case DOUBLE -> accessor.getDouble(name);
            case STRING -> accessor.getString(name);
            case UUID -> uuidString(accessor.getUuid(name));
            case BINARY -> wrapBytes(accessor.getBinary(name));
            case DECIMAL -> decimalBytes(accessor.getDecimal(name));
            case FIXED -> wrapFixed(accessor.getBinary(name), node.avro());
            case STRUCT -> materializeRecord(accessor.getStruct(name), node);
            case VARIANT -> materializeVariant(accessor.getVariant(name), node.avro());
            case LIST -> materializeList(accessor.getList(name), node.listElement());
            case MAP -> materializeMap(accessor.getMap(name), node.mapValue());
            case OTHER -> accessor.getValue(name);
        };
    }

    private static GenericRecord materializeVariant(PqVariant variant, Schema recordSchema) {
        if (variant == null) {
            return null;
        }
        GenericRecord record = new GenericData.Record(recordSchema);
        record.put(0, ByteBuffer.wrap(variant.metadata()));
        record.put(1, ByteBuffer.wrap(variant.value()));
        return record;
    }

    private List<Object> materializeList(PqList pqList, AvroPlanNode element) {
        List<Object> result = new ArrayList<>(pqList.size());
        for (int i = 0; i < pqList.size(); i++) {
            if (pqList.isNull(i)) {
                result.add(null);
                continue;
            }
            result.add(materializeListElement(pqList, i, element));
        }
        return result;
    }

    /// List elements come back already decoded from [PqList#get], so the plan
    /// selects the representation rather than the accessor.
    private Object materializeListElement(PqList pqList, int index, AvroPlanNode node) {
        return switch (node.kind()) {
            case UNSIGNED_INT32 -> {
                Object raw = pqList.get(index);
                yield raw instanceof Integer i ? Integer.toUnsignedLong(i) : raw;
            }
            case UUID -> {
                Object val = pqList.get(index);
                yield val instanceof UUID u ? u.toString() : val;
            }
            case DECIMAL -> {
                Object val = pqList.get(index);
                yield val instanceof BigDecimal d ? decimalBytes(d) : wrapRawBytes(val);
            }
            case BINARY -> wrapRawBytes(pqList.get(index));
            case FIXED -> {
                // Read the raw physical bytes, not the decoded element: a decimal
                // stored as FIXED_LEN_BYTE_ARRAY decodes to BigDecimal via
                // get(index), but Avro `fixed` needs the on-disk bytes — the same
                // form the top-level / struct / map paths read via getBinary.
                Object val = pqList.getRaw(index);
                yield val instanceof byte[] bytes ? wrapFixed(bytes, node.avro()) : val;
            }
            case STRUCT -> {
                Object val = pqList.get(index);
                yield val instanceof PqStruct struct ? materializeRecord(struct, node) : val;
            }
            case VARIANT -> {
                Object val = pqList.get(index);
                yield val instanceof PqVariant variant ? materializeVariant(variant, node.avro()) : val;
            }
            case LIST -> {
                Object val = pqList.get(index);
                yield val instanceof PqList nested ? materializeList(nested, node.listElement()) : val;
            }
            case MAP -> {
                Object val = pqList.get(index);
                yield val instanceof PqMap nested ? materializeMap(nested, node.mapValue()) : val;
            }
            // Already in their Avro representation as decoded.
            case BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, OTHER -> pqList.get(index);
        };
    }

    private Map<String, Object> materializeMap(PqMap pqMap, AvroPlanNode value) {
        Map<String, Object> result = new HashMap<>(pqMap.size());
        for (PqMap.Entry entry : pqMap.getEntries()) {
            String key = entry.getStringKey();
            if (entry.isValueNull()) {
                result.put(key, null);
                continue;
            }
            result.put(key, materializeMapValue(entry, value));
        }
        return result;
    }

    private Object materializeMapValue(PqMap.Entry entry, AvroPlanNode node) {
        return switch (node.kind()) {
            case BOOLEAN -> entry.getBooleanValue();
            case INT -> entry.getIntValue();
            case LONG -> entry.getLongValue();
            case UNSIGNED_INT32 -> Integer.toUnsignedLong(entry.getIntValue());
            case FLOAT -> entry.getFloatValue();
            case DOUBLE -> entry.getDoubleValue();
            case STRING -> entry.getStringValue();
            case UUID -> uuidString(entry.getUuidValue());
            case BINARY -> wrapBytes(entry.getBinaryValue());
            case DECIMAL -> decimalBytes(entry.getDecimalValue());
            case FIXED -> wrapFixed(entry.getBinaryValue(), node.avro());
            case STRUCT -> materializeRecord(entry.getStructValue(), node);
            case VARIANT -> materializeVariant(entry.getVariantValue(), node.avro());
            case LIST -> materializeList(entry.getListValue(), node.listElement());
            case MAP -> materializeMap(entry.getMapValue(), node.mapValue());
            case OTHER -> entry.getValue();
        };
    }

    /// Encode a decimal as the two's-complement big-endian unscaled bytes Avro's
    /// `decimal` logical type expects on a `BYTES` schema.
    private static ByteBuffer decimalBytes(BigDecimal value) {
        return value == null ? null : ByteBuffer.wrap(value.unscaledValue().toByteArray());
    }

    private static String uuidString(UUID value) {
        return value == null ? null : value.toString();
    }

    private static ByteBuffer wrapBytes(byte[] bytes) {
        return bytes != null ? ByteBuffer.wrap(bytes) : null;
    }

    /// Wrap an already-decoded list element as Avro `bytes`, leaving a value the
    /// column did not decode to a byte array untouched.
    private static Object wrapRawBytes(Object value) {
        return value instanceof byte[] bytes ? ByteBuffer.wrap(bytes) : value;
    }

    /// Wrap raw bytes as a [GenericData.Fixed] of the schema's declared size.
    ///
    /// Avro requires a `GenericFixed` for a `fixed`-typed field; a bare
    /// [ByteBuffer] is silently accepted by [GenericRecord#put] but fails when the
    /// record is serialized through a `GenericDatumWriter`. A `FIXED_LEN_BYTE_ARRAY`
    /// column stores exactly `type_length` bytes per value, so a payload whose width
    /// does not match the declared `fixed` size is malformed and rejected.
    private static GenericData.Fixed wrapFixed(byte[] bytes, Schema fixedSchema) {
        if (bytes == null) {
            return null;
        }
        int size = fixedSchema.getFixedSize();
        if (bytes.length != size) {
            throw new IllegalArgumentException(
                    "FIXED value of " + bytes.length + " bytes does not match fixed(" + size
                            + ") schema '" + fixedSchema.getFullName() + "'");
        }
        return new GenericData.Fixed(fixedSchema, bytes);
    }
}
