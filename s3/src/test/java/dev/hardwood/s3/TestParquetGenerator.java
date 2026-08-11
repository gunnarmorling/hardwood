/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.s3;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import dev.hardwood.internal.thrift.ThriftCompactConstants;
import dev.hardwood.internal.thrift.ThriftCompactConstants.ElementType;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType;

/// Generates minimal valid Parquet files in pure Java for testing.
///
/// Produces files with REQUIRED INT64 columns, PLAIN encoding, no compression,
/// data page v1. This is the simplest valid Parquet format — no nulls, no
/// dictionary, no repetition/definition levels.
final class TestParquetGenerator {

    private static final byte[] MAGIC = "PAR1".getBytes(StandardCharsets.US_ASCII);

    /// Generates a Parquet file with one page per column per row group.
    static byte[] generate(int numRowGroups, int rowsPerRowGroup, int numColumns) {
        return generate(numRowGroups, rowsPerRowGroup, numColumns, rowsPerRowGroup);
    }

    /// Generates a Parquet file with the given dimensions.
    ///
    /// Column values follow a deterministic pattern: column `cN` in row group `rg`
    /// at row `r` has value `rg * rowsPerRowGroup + r + N * 1_000_000`.
    /// This ensures each column has distinct, verifiable values.
    ///
    /// @param numRowGroups number of row groups
    /// @param rowsPerRowGroup rows per row group
    /// @param numColumns number of INT64 columns (named "c0", "c1", ...)
    /// @param rowsPerPage rows per data page (smaller values = more pages per column)
    /// @return the complete Parquet file as a byte array
    static byte[] generate(int numRowGroups, int rowsPerRowGroup, int numColumns, int rowsPerPage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);

        // Track column chunk offsets and sizes for footer metadata
        long[][] chunkOffsets = new long[numRowGroups][numColumns];
        int[][] chunkSizes = new int[numRowGroups][numColumns];

        for (int rg = 0; rg < numRowGroups; rg++) {
            long rowOffset = (long) rg * rowsPerRowGroup;
            for (int col = 0; col < numColumns; col++) {
                chunkOffsets[rg][col] = out.size();
                int chunkBytes = 0;

                // Write multiple pages per column chunk
                int rowsWritten = 0;
                while (rowsWritten < rowsPerRowGroup) {
                    int pageRows = Math.min(rowsPerPage, rowsPerRowGroup - rowsWritten);
                    int pageDataSize = pageRows * 8;

                    byte[] pageHeader = dataPageHeader(pageRows, pageDataSize);
                    out.writeBytes(pageHeader);

                    ByteBuffer buf = ByteBuffer.allocate(pageDataSize).order(ByteOrder.LITTLE_ENDIAN);
                    for (int row = 0; row < pageRows; row++) {
                        buf.putLong(rowOffset + rowsWritten + row + col * 1_000_000L);
                    }
                    out.writeBytes(buf.array());

                    chunkBytes += pageHeader.length + pageDataSize;
                    rowsWritten += pageRows;
                }

                chunkSizes[rg][col] = chunkBytes;
            }
        }

        // Build and write footer
        byte[] footer = fileMetaData(numRowGroups, rowsPerRowGroup, numColumns,
                chunkOffsets, chunkSizes);
        out.writeBytes(footer);

        // Footer length (4 bytes LE) + trailing magic
        ByteBuffer footerLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        footerLen.putInt(footer.length);
        out.writeBytes(footerLen.array());
        out.writeBytes(MAGIC);

        return out.toByteArray();
    }

    // ==================== Page Header ====================

    /// Builds a DataPageV1 page header.
    ///
    /// PageHeader {
    ///   1: type = DATA_PAGE (0)
    ///   2: uncompressed_page_size
    ///   3: compressed_page_size (= uncompressed, no compression)
    ///   5: data_page_header {
    ///     1: num_values
    ///     2: encoding = PLAIN (0)
    ///     3: definition_level_encoding = RLE (0)
    ///     4: repetition_level_encoding = RLE (0)
    ///   }
    /// }
    private static byte[] dataPageHeader(int numValues, int pageSize) {
        ThriftWriter w = new ThriftWriter();
        w.field(1, FieldType.I32).zigzagI32(0);
        w.field(2, FieldType.I32).zigzagI32(pageSize);
        w.field(3, FieldType.I32).zigzagI32(pageSize);
        w.field(5, FieldType.STRUCT).pushStruct();
        w.field(1, FieldType.I32).zigzagI32(numValues);
        w.field(2, FieldType.I32).zigzagI32(0);
        w.field(3, FieldType.I32).zigzagI32(0);
        w.field(4, FieldType.I32).zigzagI32(0);
        w.stop();
        w.stop();
        return w.toByteArray();
    }

    // ==================== Footer (FileMetaData) ====================

    /// Builds the Thrift-encoded FileMetaData.
    ///
    /// FileMetaData {
    ///   1: version = 2
    ///   2: schema = [root, c0, c1, ...]
    ///   3: num_rows
    ///   4: row_groups = [...]
    ///   6: created_by
    /// }
    private static byte[] fileMetaData(int numRowGroups, int rowsPerRowGroup,
                                        int numColumns, long[][] chunkOffsets,
                                        int[][] chunkSizes) {
        ThriftWriter w = new ThriftWriter();

        // version
        w.field(1, FieldType.I32).zigzagI32(2);

        // schema: list<SchemaElement>
        w.field(2, FieldType.LIST).listHeader(ElementType.STRUCT, numColumns + 1);
        // Root element: name="schema", num_children=N
        w.pushStruct();
        w.field(4, FieldType.BINARY).binary("schema");
        w.field(5, FieldType.I32).zigzagI32(numColumns);
        w.stop();
        // Column elements: type=INT64, repetition_type=REQUIRED, name="cN"
        for (int col = 0; col < numColumns; col++) {
            w.pushStruct();
            w.field(1, FieldType.I32).zigzagI32(2);  // type = INT64
            w.field(3, FieldType.I32).zigzagI32(0);  // repetition_type = REQUIRED
            w.field(4, FieldType.BINARY).binary("c" + col);
            w.stop();
        }

        // num_rows
        w.field(3, FieldType.I64).zigzagI64((long) numRowGroups * rowsPerRowGroup);

        // row_groups: list<RowGroup>
        w.field(4, FieldType.LIST).listHeader(ElementType.STRUCT, numRowGroups);
        for (int rg = 0; rg < numRowGroups; rg++) {
            w.pushStruct();
            // columns: list<ColumnChunk>
            w.field(1, FieldType.LIST).listHeader(ElementType.STRUCT, numColumns);
            for (int col = 0; col < numColumns; col++) {
                w.pushStruct();
                columnChunk(w, col, chunkOffsets[rg][col], chunkSizes[rg][col], rowsPerRowGroup);
            }

            // total_byte_size
            long rgSize = 0;
            for (int col = 0; col < numColumns; col++) {
                rgSize += chunkSizes[rg][col];
            }
            w.field(2, FieldType.I64).zigzagI64(rgSize);
            // num_rows
            w.field(3, FieldType.I64).zigzagI64(rowsPerRowGroup);
            w.stop();
        }

        // created_by
        w.field(6, FieldType.BINARY).binary("hardwood-test-generator");
        w.stop();
        return w.toByteArray();
    }

    /// Builds a ColumnChunk with embedded ColumnMetaData.
    ///
    /// ColumnChunk {
    ///   2: file_offset
    ///   3: meta_data {
    ///     1: type = INT64 (2)
    ///     2: encodings = [PLAIN]
    ///     3: path_in_schema = ["cN"]
    ///     4: codec = UNCOMPRESSED (0)
    ///     5: num_values
    ///     6: total_uncompressed_size
    ///     7: total_compressed_size
    ///     9: data_page_offset
    ///   }
    /// }
    private static void columnChunk(ThriftWriter w, int colIndex, long offset,
                                     int size, int numValues) {
        w.field(2, FieldType.I64).zigzagI64(offset);
        w.field(3, FieldType.STRUCT).pushStruct();
        w.field(1, FieldType.I32).zigzagI32(2);          // INT64
        w.field(2, FieldType.LIST).listHeader(ElementType.I32, 1);
        w.zigzagI32(0);                           // PLAIN
        w.field(3, FieldType.LIST).listHeader(ElementType.BINARY, 1);
        w.binary("c" + colIndex);
        w.field(4, FieldType.I32).zigzagI32(0);           // UNCOMPRESSED
        w.field(5, FieldType.I64).zigzagI64(numValues);
        w.field(6, FieldType.I64).zigzagI64(size);
        w.field(7, FieldType.I64).zigzagI64(size);
        w.field(9, FieldType.I64).zigzagI64(offset);
        w.stop();
        w.stop();
    }

    // ==================== Thrift Compact Protocol ====================

    /// Minimal Thrift Compact Protocol writer.
    ///
    /// Reference: https://github.com/apache/thrift/blob/master/doc/specs/thrift-compact-protocol.md
    static class ThriftWriter {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private int lastFieldId = 0;
        private final java.util.ArrayDeque<Integer> fieldIdStack = new java.util.ArrayDeque<>();

        ThriftWriter field(int fieldId, FieldType type) {
            int delta = fieldId - lastFieldId;
            if (delta > 0 && delta <= 15) {
                buf.write((delta << 4) | type.code());
            }
            else {
                buf.write(type.code());
                writeVarInt((fieldId << 1) ^ (fieldId >> 31));
            }
            lastFieldId = fieldId;
            return this;
        }

        ThriftWriter zigzagI32(int value) {
            writeVarInt((value << 1) ^ (value >> 31));
            return this;
        }

        ThriftWriter zigzagI64(long value) {
            writeVarInt((value << 1) ^ (value >> 63));
            return this;
        }

        ThriftWriter binary(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            buf.write(bytes, 0, bytes.length);
            return this;
        }

        ThriftWriter listHeader(ElementType elementType, int size) {
            if (size <= 14) {
                buf.write((size << 4) | elementType.code());
            }
            else {
                buf.write(0xF0 | elementType.code());
                writeVarInt(size);
            }
            return this;
        }

        /// Ends the current struct (writes STOP byte) and restores the enclosing
        /// struct's field ID context from the stack.
        void stop() {
            buf.write(ThriftCompactConstants.STOP);
            lastFieldId = fieldIdStack.isEmpty() ? 0 : fieldIdStack.pop();
        }

        /// Saves current field context and resets for a new struct scope.
        /// Call before writing fields of a nested struct (either via `field(N, FieldType.STRUCT)`
        /// or as a struct element in a list). The matching `stop()` restores the context.
        ThriftWriter pushStruct() {
            fieldIdStack.push(lastFieldId);
            lastFieldId = 0;
            return this;
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }

        private void writeVarInt(long value) {
            value = value & 0xFFFFFFFFFFFFFFFFL;
            while (value > 0x7F) {
                buf.write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buf.write((int) value & 0x7F);
        }
    }
}
