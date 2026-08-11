/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;

/// Reader for Thrift Compact Protocol using direct ByteBuffer access.
/// Reference: https://github.com/apache/thrift/blob/master/doc/specs/thrift-compact-protocol.md
///
/// The readers built on this class share one policy for input that does not match the
/// format: a field whose wire type disagrees is skipped ([#acceptField]), while a collection
/// whose element type disagrees is never decoded as if it did ([#requireListHeader],
/// [#acceptListHeader]).
public class ThriftCompactReader {

    private static final System.Logger LOG = System.getLogger(ThriftCompactReader.class.getName());

    // The reader dispatches on the raw wire byte in a switch, whose case labels must
    // be compile-time constants, so it references the shared byte codes in
    // [ThriftCompactConstants.FieldType.Codes] rather than the enum values.
    private static final byte TYPE_BOOLEAN_TRUE = Codes.BOOLEAN_TRUE;
    private static final byte TYPE_BOOLEAN_FALSE = Codes.BOOLEAN_FALSE;
    private static final byte TYPE_BYTE = Codes.BYTE;
    private static final byte TYPE_I16 = Codes.I16;
    private static final byte TYPE_I32 = Codes.I32;
    private static final byte TYPE_I64 = Codes.I64;
    private static final byte TYPE_DOUBLE = Codes.DOUBLE;
    private static final byte TYPE_BINARY = Codes.BINARY;
    private static final byte TYPE_LIST = Codes.LIST;
    private static final byte TYPE_SET = Codes.SET;
    private static final byte TYPE_MAP = Codes.MAP;
    private static final byte TYPE_STRUCT = Codes.STRUCT;

    private final ByteBuffer buffer;
    private final int startPosition;
    private short lastFieldId = 0;

    /// Creates a reader that reads directly from a ByteBuffer.
    ///
    /// @param buffer the buffer to read from (position should be at start of data)
    public ThriftCompactReader(ByteBuffer buffer) {
        this.buffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        this.startPosition = 0;
    }

    /// Creates a reader that reads from a ByteBuffer starting at a specific offset.
    ///
    /// @param buffer the buffer to read from
    /// @param offset the offset within the buffer to start reading
    public ThriftCompactReader(ByteBuffer buffer, int offset) {
        this.buffer = buffer.slice(offset, buffer.limit() - offset).order(ByteOrder.LITTLE_ENDIAN);
        this.startPosition = 0;
    }

    /// Returns the number of bytes read from the buffer.
    public int getBytesRead() {
        return buffer.position() - startPosition;
    }

    /// Returns the number of bytes still available to read in the buffer.
    public int remaining() {
        return buffer.remaining();
    }

    /// Returns a zero-copy, read-only, little-endian view of the next `length` bytes and advances
    /// past them. The returned buffer shares storage with this reader's buffer (no copy), so for a
    /// memory-mapped input it stays backed by the mapped file.
    public ByteBuffer readSlice(int length) throws EOFException {
        if (buffer.remaining() < length) {
            throw new EOFException("Unexpected EOF while slicing " + length + " bytes");
        }
        ByteBuffer slice = buffer.slice(buffer.position(), length)
                .asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(buffer.position() + length);
        return slice;
    }

    /// Read an unsigned varint from the buffer.
    public long readVarint() throws EOFException {
        long result = 0;
        int shift = 0;
        while (buffer.hasRemaining()) {
            int b = buffer.get() & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new EOFException("Unexpected EOF while reading varint");
    }

    /// Read a zigzag-encoded signed integer.
    public long readZigzag() throws IOException {
        long n = readVarint();
        return (n >>> 1) ^ -(n & 1);
    }

    /// Read a single byte.
    public byte readByte() throws EOFException {
        if (!buffer.hasRemaining()) {
            throw new EOFException("Unexpected EOF while reading byte");
        }
        return buffer.get();
    }

    /// Read multiple bytes into a destination array.
    public void readBytes(byte[] dest) throws EOFException {
        if (buffer.remaining() < dest.length) {
            throw new EOFException("Unexpected EOF while reading bytes");
        }
        buffer.get(dest);
    }

    /// Read a boolean value.
    public boolean readBoolean() throws IOException {
        byte b = readByte();
        if (b == TYPE_BOOLEAN_TRUE) {
            return true;
        }
        else if (b == TYPE_BOOLEAN_FALSE) {
            return false;
        }
        throw new IOException("Invalid boolean value: " + b);
    }

    /// Read an i32 value (zigzag encoded).
    public int readI32() throws IOException {
        return (int) readZigzag();
    }

    /// Read an i64 value (zigzag encoded).
    public long readI64() throws IOException {
        return readZigzag();
    }

    /// Read an i32 that must be non-negative — a size, count or byte-length.
    /// A negative value indicates a malformed or adversarial file and would
    /// otherwise drive a negative allocation or out-of-bounds slice downstream,
    /// so fail fast here with a controlled error naming the field.
    ///
    /// @param fieldName fully-qualified field name for the error message
    public int readNonNegativeI32(String fieldName) throws IOException {
        int value = readI32();
        if (value < 0) {
            throw new IOException(
                    "Malformed Parquet metadata: " + fieldName + " must be non-negative but was " + value);
        }
        return value;
    }

    /// Read an i64 that must be non-negative — a size, count or file offset.
    /// See [#readNonNegativeI32] for rationale.
    ///
    /// @param fieldName fully-qualified field name for the error message
    public long readNonNegativeI64(String fieldName) throws IOException {
        long value = readI64();
        if (value < 0) {
            throw new IOException(
                    "Malformed Parquet metadata: " + fieldName + " must be non-negative but was " + value);
        }
        return value;
    }

    /// Read a double value (8 bytes, little-endian).
    public double readDouble() throws EOFException {
        if (buffer.remaining() < 8) {
            throw new EOFException("Unexpected EOF while reading double");
        }
        return buffer.getDouble();
    }

    /// Read a binary/string value (length-prefixed).
    public byte[] readBinary() throws IOException {
        int length = (int) readVarint();
        byte[] data = new byte[length];
        readBytes(data);
        return data;
    }

    /// Read a string value.
    public String readString() throws IOException {
        return new String(readBinary(), StandardCharsets.UTF_8);
    }

    /// Read a field header and return field info.
    /// Returns null when STOP field is encountered.
    public FieldHeader readFieldHeader() throws IOException {
        byte b = readByte();

        if (b == 0) {
            // STOP field
            lastFieldId = 0;
            return null;
        }

        byte type = (byte) (b & 0x0F);
        int fieldIdDelta = (b & 0xF0) >> 4;

        short fieldId;
        if (fieldIdDelta == 0) {
            // Field ID is encoded separately
            fieldId = (short) readZigzag();
        }
        else {
            // Field ID is delta from last field
            fieldId = (short) (lastFieldId + fieldIdDelta);
        }

        lastFieldId = fieldId;
        return new FieldHeader(fieldId, type);
    }

    /// Gate a struct field on its declared wire type: returns `true` with the reader positioned
    /// on the value when the field is of `expectedType`, and otherwise skips the field and
    /// returns `false`.
    ///
    /// A field of an unexpected type is skipped rather than rejected because its declared type
    /// is enough to consume it correctly whatever it holds, so the cost stays bounded to that
    /// one field and the rest of the struct still parses — Thrift's own rule for fields a reader
    /// does not recognise.
    ///
    /// @param header the field header just read
    /// @param expectedType wire code the field must declare, from [ThriftCompactConstants.FieldType.Codes]
    public boolean acceptField(FieldHeader header, byte expectedType) throws IOException {
        if (header.type() == expectedType) {
            return true;
        }
        skipField(header.type());
        return false;
    }

    /// Read a `bool` field, whose value the field header carries in its own type nibble rather
    /// than in a body of its own.
    ///
    /// @param header the field header just read
    /// @param fallback value to report for a field declared as anything but `bool`, which is
    ///     skipped as [#acceptField] would
    public boolean readBooleanField(FieldHeader header, boolean fallback) throws IOException {
        if (header.type() == TYPE_BOOLEAN_TRUE) {
            return true;
        }
        if (header.type() == TYPE_BOOLEAN_FALSE) {
            return false;
        }
        skipField(header.type());
        return fallback;
    }

    /// Read a list/set header.
    ///
    /// A long-form element count is validated against the bytes still in the buffer. Every Thrift
    /// element occupies at least one byte on the wire, so a count larger than the remainder cannot
    /// describe real data. Callers that pre-size a collection from the count would otherwise turn
    /// a five-byte varint into a multi-gigabyte allocation, and a count past the `int` range would
    /// wrap to a negative capacity (an unchecked exception) or to zero (a silently empty
    /// collection) instead of a controlled error naming the file.
    public CollectionHeader readListHeader() throws IOException {
        byte sizeAndType = readByte();
        int size = (sizeAndType >> 4) & 0x0F;
        byte elementType = (byte) (sizeAndType & 0x0F);

        if (size == 15) {
            // Size is encoded separately
            size = checkedCollectionSize(readVarint());
        }

        return new CollectionHeader(elementType, size);
    }

    /// Read the header of a **required** list field and check its declared element type.
    ///
    /// See [#acceptListHeader] for why elements of the wrong type cannot be decoded. A required
    /// field has no representation for absent, so reporting an empty collection would answer
    /// with wrong data where a failed read is the honest outcome.
    ///
    /// @param expectedElementType wire code the elements must declare
    /// @param fieldName fully-qualified field name for the error message
    /// @throws IOException if the list declares a different element type
    public CollectionHeader requireListHeader(byte expectedElementType, String fieldName) throws IOException {
        CollectionHeader header = readListHeader();
        if (header.elementType() != expectedElementType) {
            throw wrongElementType(fieldName, header.elementType(), hex(expectedElementType));
        }
        return header;
    }

    /// Read the header of an **optional** list field and check its declared element type,
    /// reporting `null` for a list this reader will not decode.
    ///
    /// Elements of the declared type occupy a different number of bytes than the ones the field
    /// is defined to hold, so decoding them anyway would desynchronise the stream and corrupt
    /// every field that follows. They are instead skipped by the type they declare, leaving the
    /// reader on the byte after the list: the file loses one optional field and stays readable.
    ///
    /// @param expectedElementType wire code the elements must declare
    /// @param fieldName fully-qualified field name for the log message
    /// @return the header, or `null` if the list declares a different element type and has been
    ///     skipped
    public CollectionHeader acceptListHeader(byte expectedElementType, String fieldName) throws IOException {
        CollectionHeader header = readListHeader();
        if (header.elementType() != expectedElementType) {
            skipElements(header);
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + fieldName + ": wrong Thrift element type "
                    + hex(header.elementType()) + " (expected " + hex(expectedElementType) + ")");
            return null;
        }
        return header;
    }

    /// Read a required `list<struct>` in full: the collection header followed by every element,
    /// each decoded by `elementReader`.
    ///
    /// @param fieldName fully-qualified field name for the error message
    /// @param elementReader reader for one element
    public <T> List<T> readStructList(String fieldName, StructReader<T> elementReader) throws IOException {
        CollectionHeader header = requireListHeader(Codes.STRUCT, fieldName);
        List<T> values = new ArrayList<>(header.size());
        for (int i = 0; i < header.size(); i++) {
            values.add(elementReader.read(this));
        }
        return Collections.unmodifiableList(values);
    }

    /// Read a required `list<string>` in full.
    ///
    /// @param fieldName fully-qualified field name for the error message
    public List<String> readStringList(String fieldName) throws IOException {
        CollectionHeader header = requireListHeader(Codes.BINARY, fieldName);
        List<String> values = new ArrayList<>(header.size());
        for (int i = 0; i < header.size(); i++) {
            values.add(readString());
        }
        return Collections.unmodifiableList(values);
    }

    /// Read a required `list<binary>` in full.
    ///
    /// @param fieldName fully-qualified field name for the error message
    public List<byte[]> readBinaryList(String fieldName) throws IOException {
        CollectionHeader header = requireListHeader(Codes.BINARY, fieldName);
        List<byte[]> values = new ArrayList<>(header.size());
        for (int i = 0; i < header.size(); i++) {
            values.add(readBinary());
        }
        return Collections.unmodifiableList(values);
    }

    /// Read a required `list<bool>` in full.
    ///
    /// The element type nibble carries `0x01` or `0x02` for `bool` depending on the writer, so
    /// both are accepted — see [ThriftCompactConstants.ElementType].
    ///
    /// @param fieldName fully-qualified field name for the error message
    public boolean[] readBoolArray(String fieldName) throws IOException {
        CollectionHeader header = readListHeader();
        if (header.elementType() != TYPE_BOOLEAN_TRUE && header.elementType() != TYPE_BOOLEAN_FALSE) {
            throw wrongElementType(fieldName, header.elementType(), "bool");
        }
        boolean[] values = new boolean[header.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = readBoolean();
        }
        return values;
    }

    /// Read an optional `list<i64>` in full: the collection header followed by its elements.
    ///
    /// A list declaring any other element type is skipped and reported as `null`; see
    /// [#acceptListHeader].
    ///
    /// An absent list is `null` and a present but empty one is a zero-length array, a
    /// distinction the metadata records carry through to their callers.
    ///
    /// @param fieldName fully-qualified field name for the log message
    public long[] readOptionalI64Array(String fieldName) throws IOException {
        CollectionHeader header = acceptListHeader(Codes.I64, fieldName);
        if (header == null) {
            return null;
        }
        long[] values = new long[header.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = readI64();
        }
        return values;
    }

    /// Validate a declared collection size against the bytes still in the buffer: every Thrift
    /// element occupies at least one byte on the wire, so a larger count cannot describe real
    /// data. See [#readListHeader] for what an unvalidated count would drive.
    private int checkedCollectionSize(long declaredSize) throws IOException {
        if (declaredSize < 0 || declaredSize > buffer.remaining()) {
            throw new IOException("Malformed Parquet metadata: collection declares "
                    + declaredSize + " elements but only " + buffer.remaining() + " bytes remain");
        }
        return (int) declaredSize;
    }

    private static IOException wrongElementType(String fieldName, byte actual, String expected) {
        return new IOException("Malformed Parquet metadata: " + fieldName
                + " declares Thrift element type " + hex(actual) + " but must be a list of " + expected);
    }

    private static String hex(byte type) {
        return "0x" + Integer.toHexString(type & 0xFF);
    }

    /// Skip every element of a list, set or map whose header has just been read.
    public void skipElements(CollectionHeader header) throws IOException {
        for (int i = 0; i < header.size(); i++) {
            skipElement(header.elementType());
        }
    }

    /// Skip one element of a list, set or map.
    ///
    /// This is [#skipField] for every type but `bool`, which is encoded differently in the two
    /// positions: a `bool` **field** carries its value in the type nibble of its own header and
    /// has no payload, while a `bool` **element** has no header and occupies one byte on the
    /// wire. Skipping a `list<bool>` with [#skipField] would therefore consume none of it and
    /// leave the cursor on the first element, desynchronising every field that follows.
    ///
    /// The element type nibble carries `0x01` or `0x02` for `bool` depending on the writer, so
    /// both are accepted. The byte is consumed without validating it as a boolean: a skip path
    /// should not fail a read that the reader is choosing not to interpret.
    public void skipElement(byte elementType) throws IOException {
        if (elementType == TYPE_BOOLEAN_TRUE || elementType == TYPE_BOOLEAN_FALSE) {
            readByte();
            return;
        }
        skipField(elementType);
    }

    /// Skip a field of the given type.
    ///
    /// Elements of a collection are skipped through [#skipElement], not through a recursive
    /// call to this method — see there for why the two differ.
    public void skipField(byte type) throws IOException {
        switch (type) {
            case TYPE_BOOLEAN_TRUE:
            case TYPE_BOOLEAN_FALSE:
                // Boolean value is in the type byte itself
                break;
            case TYPE_BYTE:
                readByte();
                break;
            case TYPE_I16:
            case TYPE_I32:
            case TYPE_I64:
                readZigzag();
                break;
            case TYPE_DOUBLE:
                readDouble();
                break;
            case TYPE_BINARY:
                readBinary();
                break;
            case TYPE_LIST:
            case TYPE_SET:
                skipElements(readListHeader());
                break;
            case TYPE_MAP:
                // Bounded against the buffer for the same reason a long-form list count is: an
                // unchecked size truncates to a smaller count (under-skipping, which desyncs the
                // stream) or to a negative one (skipping nothing at all).
                int mapSize = checkedCollectionSize(readVarint());
                if (mapSize > 0) {
                    byte kvTypes = readByte();
                    byte keyType = (byte) ((kvTypes >> 4) & 0x0F);
                    byte valueType = (byte) (kvTypes & 0x0F);
                    for (int i = 0; i < mapSize; i++) {
                        skipElement(keyType);
                        skipElement(valueType);
                    }
                }
                break;
            case TYPE_STRUCT:
                skipStruct();
                break;
            default:
                throw new IOException("Unknown field type: " + type);
        }
    }

    /// Skip an entire struct (read until STOP field).
    public void skipStruct() throws IOException {
        // Save and reset field ID context for nested struct
        short saved = pushFieldIdContext();
        try {
            while (true) {
                FieldHeader header = readFieldHeader();
                if (header == null) {
                    break;
                }
                skipField(header.type());
            }
        }
        finally {
            popFieldIdContext(saved);
        }
    }

    /// Save the current last field ID and reset it for reading a nested struct.
    public short pushFieldIdContext() {
        short saved = lastFieldId;
        lastFieldId = 0;
        return saved;
    }

    /// Restore the last field ID after reading a nested struct.
    public void popFieldIdContext(short savedFieldId) {
        lastFieldId = savedFieldId;
    }

    /// Decodes one element of a `list<struct>` from the reader it is given, which is positioned
    /// on the element's first field header.
    @FunctionalInterface
    public interface StructReader<T> {
        T read(ThriftCompactReader reader) throws IOException;
    }

    public static record FieldHeader(short fieldId, byte type) {
    }

    public static record CollectionHeader(byte elementType, int size) {
    }
}
