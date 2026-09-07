/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.reader.ParquetReadException;

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

    /// Returned by [#readFieldHeader] for the STOP field that ends a struct. No real header packs
    /// to this value: it would take a wire type of `0xFF`, and the type comes from a nibble.
    public static final int STOP_FIELD = -1;

    private static final int FIELD_ID_SHIFT = 8;
    private static final int TYPE_MASK = 0xFF;

    /// Returned by [#acceptListHeader] for a list field that is absent or carries the wrong
    /// element type. No real header packs to this value: it would need a negative element count.
    static final long ABSENT_LIST = -1;

    /// Element type occupies the low byte of a packed list header, the count the rest.
    private static final int ELEMENT_TYPE_BITS = 8;

    /// Bytes a varint may occupy: seven bits each, for a 64-bit value.
    private static final int MAX_VARINT_BYTES = 10;
    /// Shift the last of those bytes contributes at, and so the largest a shift may reach.
    private static final int MAX_VARINT_SHIFT = 7 * (MAX_VARINT_BYTES - 1);

    private final ByteBuffer buffer;
    private final int startPosition;
    private short lastFieldId = 0;
    /// Per-decode cache of repeated column paths, created on first use so the page-header path —
    /// which has none — never allocates one.
    private RepeatedPathCache pathCache;

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

    /// This decode's cache of repeated column paths.
    RepeatedPathCache pathCache() {
        if (pathCache == null) {
            pathCache = new RepeatedPathCache();
        }
        return pathCache;
    }

    /// The reader's current offset into its buffer, for capturing a byte range that was read.
    int position() {
        return buffer.position();
    }

    /// Whether the bytes at the current position are `expected`, without moving the position.
    boolean matchesAt(byte[] expected) {
        if (buffer.remaining() < expected.length) {
            return false;
        }
        int from = buffer.position();
        if (buffer.hasArray()) {
            int offset = buffer.arrayOffset() + from;
            return Arrays.equals(expected, 0, expected.length, buffer.array(), offset, offset + expected.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != buffer.get(from + i)) {
                return false;
            }
        }
        return true;
    }

    /// Advances past `length` bytes already known to be present.
    void skipBytes(int length) {
        if (buffer.remaining() < length) {
            throw new ThriftTruncatedException("Unexpected EOF while skipping " + length + " bytes");
        }
        buffer.position(buffer.position() + length);
    }

    /// A copy of `length` bytes from `from`, independent of this reader's buffer.
    byte[] copyRange(int from, int length) {
        byte[] bytes = new byte[length];
        buffer.get(from, bytes, 0, length);
        return bytes;
    }

    /// Returns a zero-copy, read-only, little-endian view of the next `length` bytes and advances
    /// past them. The returned buffer shares storage with this reader's buffer (no copy), so for a
    /// memory-mapped input it stays backed by the mapped file.
    public ByteBuffer readSlice(int length) {
        if (buffer.remaining() < length) {
            throw new ThriftTruncatedException("Unexpected EOF while slicing " + length + " bytes");
        }
        ByteBuffer slice = buffer.slice(buffer.position(), length)
                .asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(buffer.position() + length);
        return slice;
    }

    /// Read an unsigned varint from the buffer.
    ///
    /// Ten bytes carry the 64 bits a varint can hold, and an eleventh is rejected: Java applies a
    /// `long` shift modulo 64, so its payload would land back over the low bits of the result and
    /// the read would answer with a wrong number instead of failing.
    ///
    /// @throws ParquetReadException if the varint runs past ten bytes
    public long readVarint() {
        if (!buffer.hasRemaining()) {
            throw new ThriftTruncatedException("Unexpected EOF while reading varint");
        }
        // Most of a footer's varints are one byte — every field id, every small size, every enum
        // value — and a non-negative first byte is exactly the single-byte case.
        byte first = buffer.get();
        if (first >= 0) {
            return first;
        }

        long result = first & 0x7FL;
        int shift = 7;
        while (buffer.hasRemaining()) {
            int b = buffer.get() & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > MAX_VARINT_SHIFT) {
                throw new ParquetReadException("Malformed varint: more than " + MAX_VARINT_BYTES + " bytes");
            }
        }
        throw new ThriftTruncatedException("Unexpected EOF while reading varint");
    }

    /// Read a zigzag-encoded signed integer.
    public long readZigzag() {
        long n = readVarint();
        return (n >>> 1) ^ -(n & 1);
    }

    /// Read a single byte.
    public byte readByte() {
        if (!buffer.hasRemaining()) {
            throw new ThriftTruncatedException("Unexpected EOF while reading byte");
        }
        return buffer.get();
    }

    /// Read multiple bytes into a destination array.
    public void readBytes(byte[] dest) {
        if (buffer.remaining() < dest.length) {
            throw new ThriftTruncatedException("Unexpected EOF while reading bytes");
        }
        buffer.get(dest);
    }

    /// Read a boolean value.
    public boolean readBoolean() {
        byte b = readByte();
        if (b == TYPE_BOOLEAN_TRUE) {
            return true;
        }
        else if (b == TYPE_BOOLEAN_FALSE) {
            return false;
        }
        throw new ParquetReadException("Invalid boolean value: " + b);
    }

    /// Read an i32 value (zigzag encoded).
    public int readI32() {
        return (int) readZigzag();
    }

    /// Read an i64 value (zigzag encoded).
    public long readI64() {
        return readZigzag();
    }

    /// Read an i32 that must be non-negative — a size, count or byte-length.
    /// A negative value indicates a malformed or adversarial file and would
    /// otherwise drive a negative allocation or out-of-bounds slice downstream,
    /// so fail fast here with a controlled error naming the field.
    ///
    /// @param fieldName fully-qualified field name for the error message
    public int readNonNegativeI32(String fieldName) {
        int value = readI32();
        if (value < 0) {
            throw new ParquetReadException(
                    "Malformed Parquet metadata: " + fieldName + " must be non-negative but was " + value);
        }
        return value;
    }

    /// Read an i64 that must be non-negative — a size, count or file offset.
    /// See [#readNonNegativeI32] for rationale.
    ///
    /// @param fieldName fully-qualified field name for the error message
    public long readNonNegativeI64(String fieldName) {
        long value = readI64();
        if (value < 0) {
            throw new ParquetReadException(
                    "Malformed Parquet metadata: " + fieldName + " must be non-negative but was " + value);
        }
        return value;
    }

    /// Read a double value (8 bytes, little-endian).
    public double readDouble() {
        if (buffer.remaining() < 8) {
            throw new ThriftTruncatedException("Unexpected EOF while reading double");
        }
        return buffer.getDouble();
    }

    /// Read a binary/string value (length-prefixed).
    ///
    /// The declared length is validated against the bytes still in the buffer before it reaches
    /// the allocation — see [#checkedBinaryLength].
    public byte[] readBinary() {
        int length = checkedBinaryLength(readVarint());
        byte[] data = new byte[length];
        readBytes(data);
        return data;
    }

    /// Read a string value.
    ///
    /// A heap-backed buffer is decoded in place, so the string costs one object rather than a
    /// `byte[]` copy and the string built from it. A memory-mapped file hands its footer and its
    /// page headers over as direct buffers, which take the copying path.
    public String readString() {
        int length = checkedBinaryLength(readVarint());
        if (buffer.hasArray()) {
            if (buffer.remaining() < length) {
                throw new ThriftTruncatedException("Unexpected EOF while reading bytes");
            }
            String value = new String(buffer.array(), buffer.arrayOffset() + buffer.position(), length,
                    StandardCharsets.UTF_8);
            buffer.position(buffer.position() + length);
            return value;
        }
        byte[] data = new byte[length];
        readBytes(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    /// Read a field header and return it packed into an `int`: the field id in the upper bits,
    /// the wire type in the low byte. Returns [#STOP_FIELD] when the STOP field is encountered.
    ///
    /// The header is packed rather than returned as an object because a struct-heavy footer
    /// reads one per field — a wide file's footer runs to tens of millions — and the object
    /// escapes into [#acceptField], so it is allocated for real rather than scalarized.
    /// Unpack it with [#fieldId] and [#fieldType].
    public int readFieldHeader() {
        byte b = readByte();

        if (b == ThriftCompactConstants.STOP) {
            lastFieldId = 0;
            return STOP_FIELD;
        }

        byte type = (byte) (b & 0x0F);
        int fieldIdDelta = (b & 0xF0) >> 4;

        short fieldId;
        if (fieldIdDelta == 0) {
            // Field ID is encoded separately
            fieldId = checkedFieldId(readZigzag());
        }
        else {
            // Field ID is delta from last field
            fieldId = checkedFieldId(lastFieldId + fieldIdDelta);
        }

        lastFieldId = fieldId;
        return (fieldId << FIELD_ID_SHIFT) | type;
    }

    /// The field id of a header returned by [#readFieldHeader].
    public static short fieldId(int fieldHeader) {
        return (short) (fieldHeader >> FIELD_ID_SHIFT);
    }

    /// The wire type of a header returned by [#readFieldHeader], one of
    /// [ThriftCompactConstants.FieldType.Codes].
    public static byte fieldType(int fieldHeader) {
        return (byte) (fieldHeader & TYPE_MASK);
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
    boolean acceptField(int header, byte expectedType) {
        byte type = fieldType(header);
        if (type == expectedType) {
            return true;
        }
        skipField(type);
        return false;
    }

    /// Read a `bool` field, whose value the field header carries in its own type nibble rather
    /// than in a body of its own.
    ///
    /// @param header the field header just read
    /// @param fallback value to report for a field declared as anything but `bool`, which is
    ///     skipped as [#acceptField] would
    boolean readBooleanField(int header, boolean fallback) {
        byte type = fieldType(header);
        if (type == TYPE_BOOLEAN_TRUE) {
            return true;
        }
        if (type == TYPE_BOOLEAN_FALSE) {
            return false;
        }
        skipField(type);
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
    public long readListHeader() {
        byte sizeAndType = readByte();
        int size = (sizeAndType >> 4) & 0x0F;
        byte elementType = (byte) (sizeAndType & 0x0F);

        if (size == 15) {
            // Size is encoded separately
            size = checkedCollectionSize(readVarint());
        }

        return ((long) size << ELEMENT_TYPE_BITS) | elementType;
    }

    /// The element count of a header returned by [#readListHeader].
    public static int listSize(long listHeader) {
        return (int) (listHeader >> ELEMENT_TYPE_BITS);
    }

    /// The element wire type of a header returned by [#readListHeader], one of
    /// [ThriftCompactConstants.ElementType].
    public static byte elementType(long listHeader) {
        return (byte) (listHeader & TYPE_MASK);
    }

    /// Read the header of a **required** list field and check its declared element type.
    ///
    /// See [#acceptListHeader] for why elements of the wrong type cannot be decoded. A required
    /// field has no representation for absent, so reporting an empty collection would answer
    /// with wrong data where a failed read is the honest outcome.
    ///
    /// @param expectedElementType wire code the elements must declare
    /// @param fieldName fully-qualified field name for the error message
    /// @throws ParquetReadException if the list declares a different element type
    long requireListHeader(byte expectedElementType, String fieldName) {
        long header = readListHeader();
        if (elementType(header) != expectedElementType) {
            throw wrongElementType(fieldName, elementType(header), hex(expectedElementType));
        }
        return header;
    }

    /// Read the header of an **optional** list field and check its declared element type,
    /// reporting [#ABSENT_LIST] for a list this reader will not decode.
    ///
    /// Elements of the declared type occupy a different number of bytes than the ones the field
    /// is defined to hold, so decoding them anyway would desynchronise the stream and corrupt
    /// every field that follows. They are instead skipped by the type they declare, leaving the
    /// reader on the byte after the list: the file loses one optional field and stays readable.
    ///
    /// @param expectedElementType wire code the elements must declare
    /// @param fieldName fully-qualified field name for the log message
    /// @return the header, or [#ABSENT_LIST] if the list declares a different element type and
    ///     has been skipped
    long acceptListHeader(byte expectedElementType, String fieldName) {
        long header = readListHeader();
        if (elementType(header) != expectedElementType) {
            skipElements(header);
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + fieldName + ": wrong Thrift element type "
                    + hex(elementType(header)) + " (expected " + hex(expectedElementType) + ")");
            return ABSENT_LIST;
        }
        return header;
    }

    /// Read a required `list<struct>` in full: the collection header followed by every element,
    /// each decoded by `elementReader`.
    ///
    /// @param fieldName fully-qualified field name for the error message
    /// @param elementReader reader for one element
    <T> List<T> readStructList(String fieldName, StructReader<T> elementReader) {
        long header = requireListHeader(Codes.STRUCT, fieldName);
        List<T> values = new ArrayList<>(listSize(header));
        for (int i = 0, n = listSize(header); i < n; i++) {
            values.add(elementReader.read(this));
        }
        return Collections.unmodifiableList(values);
    }

    /// Read a required `list<string>` in full.
    ///
    /// The result comes from [List#of], which holds a one- or two-element list in its own
    /// fields: a `path_in_schema` of that length then costs neither a backing array nor an
    /// unmodifiable wrapper, and a footer holds one such list per column chunk. This is a
    /// property of the short cases only — [List#of] copies the array it is handed — so the
    /// readers of collections that run to one element per column, page or row group fill an
    /// [ArrayList] instead, where the copy would cost more than the wrapper saves.
    ///
    /// @param fieldName fully-qualified field name for the error message
    List<String> readStringList(String fieldName) {
        long header = requireListHeader(Codes.BINARY, fieldName);
        String[] values = new String[listSize(header)];
        for (int i = 0; i < values.length; i++) {
            values[i] = readString();
        }
        return List.of(values);
    }

    /// Read a required `list<binary>` in full.
    ///
    /// @param fieldName fully-qualified field name for the error message
    List<byte[]> readBinaryList(String fieldName) {
        long header = requireListHeader(Codes.BINARY, fieldName);
        List<byte[]> values = new ArrayList<>(listSize(header));
        for (int i = 0, n = listSize(header); i < n; i++) {
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
    boolean[] readBoolArray(String fieldName) {
        long header = readListHeader();
        if (elementType(header) != TYPE_BOOLEAN_TRUE && elementType(header) != TYPE_BOOLEAN_FALSE) {
            throw wrongElementType(fieldName, elementType(header), "bool");
        }
        boolean[] values = new boolean[listSize(header)];
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
    long[] readOptionalI64Array(String fieldName) {
        long header = acceptListHeader(Codes.I64, fieldName);
        if (header == ABSENT_LIST) {
            return null;
        }
        long[] values = new long[listSize(header)];
        for (int i = 0; i < values.length; i++) {
            values[i] = readI64();
        }
        return values;
    }

    /// Validate a declared binary length against the bytes still in the buffer, before it reaches
    /// the allocation in [#readBinary].
    ///
    /// The length is a varint, so it can name a value the buffer cannot hold and one the `int`
    /// range cannot hold. Truncating it to `int` yields a negative length — a
    /// `NegativeArraySizeException`, unchecked and outside the `IOException` contract the
    /// metadata path advertises — or a smaller one, which reads a silently truncated value and
    /// leaves the cursor mid-field. A length inside the `int` range but past the buffer allocates
    /// gigabytes before [#readBytes] gets to reject it. Every string and binary in the footer is
    /// read through here, including each `ColumnIndex.min_values` and `max_values` element, so
    /// this is the one bound between a file-supplied length and an allocation.
    private int checkedBinaryLength(long declaredLength) {
        if (declaredLength < 0) {
            throw new ParquetReadException("Malformed Parquet metadata: binary value declares "
                    + declaredLength + " bytes, which is not a length");
        }
        if (declaredLength > buffer.remaining()) {
            // A shortfall against the buffer is what a too-small peek looks like, and this is
            // the check a long `statistics` bound in a page header trips first — before
            // [#readBytes], which would otherwise be the one to notice. Raising the truncation
            // type is what lets the peek grow; a genuinely corrupt length is caught by the
            // ceiling the growing stops at rather than by this frame.
            throw new ThriftTruncatedException("Malformed Parquet metadata: binary value declares "
                    + declaredLength + " bytes but only " + buffer.remaining() + " remain");
        }
        return Math.toIntExact(declaredLength);
    }

    /// Validate a declared collection size against the bytes still in the buffer: every Thrift
    /// element occupies at least one byte on the wire, so a larger count cannot describe real
    /// data. See [#readListHeader] for what an unvalidated count would drive.
    private int checkedCollectionSize(long declaredSize) {
        if (declaredSize < 0) {
            throw new ParquetReadException("Malformed Parquet metadata: collection declares "
                    + declaredSize + " elements, which is not a size");
        }
        if (declaredSize > buffer.remaining()) {
            // Truncation for the same reason as [#checkedBinaryLength].
            throw new ThriftTruncatedException("Malformed Parquet metadata: collection declares "
                    + declaredSize + " elements but only " + buffer.remaining() + " bytes remain");
        }
        return Math.toIntExact(declaredSize);
    }

    /// Validate a field id, however the header arrived at it, against the `i16` range Thrift
    /// defines for one. Narrowing an out-of-range id instead would fold it onto a real one — id
    /// 65537 reads as id 1 — and the struct reader would then decode that field's bytes as
    /// whatever it holds field 1 to be, which is wrong data rather than a failed read.
    private static short checkedFieldId(long declaredId) {
        if (declaredId < Short.MIN_VALUE || declaredId > Short.MAX_VALUE) {
            throw new ParquetReadException("Malformed Parquet metadata: field id " + declaredId
                    + " is outside the Thrift i16 range");
        }
        return (short) declaredId;
    }

    private static ParquetReadException wrongElementType(String fieldName, byte actual,
            String expected) {
        return new ParquetReadException("Malformed Parquet metadata: " + fieldName
                + " declares Thrift element type " + hex(actual) + " but must be a list of " + expected);
    }

    private static String hex(byte type) {
        return "0x" + Integer.toHexString(type & 0xFF);
    }

    /// Skip every element of a list, set or map whose header has just been read.
    public void skipElements(long header) {
        byte type = elementType(header);
        for (int i = 0, n = listSize(header); i < n; i++) {
            skipElement(type);
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
    public void skipElement(byte elementType) {
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
    public void skipField(byte type) {
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
                // Advance past the value rather than materializing it: a skipped binary is one
                // nobody asked for, and copying it to drop it costs an allocation per field.
                skipBytes(checkedBinaryLength(readVarint()));
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
                throw new ParquetReadException("Unknown field type: " + type);
        }
    }

    /// Skip an entire struct (read until STOP field).
    public void skipStruct() {
        // Save and reset field ID context for nested struct
        short saved = pushFieldIdContext();
        try {
            while (true) {
                int header = readFieldHeader();
                if (header == STOP_FIELD) {
                    break;
                }
                skipField(fieldType(header));
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
        T read(ThriftCompactReader reader);
    }

}
