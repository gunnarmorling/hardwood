/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.internal.bloomfilter.BloomFilterHeader;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.reader.ParquetReadException;

public class BloomFilterHeaderReader {

    public static BloomFilterHeader read(ThriftCompactReader reader) {
        int saved = reader.pushFieldIdContext(ThriftStruct.BLOOM_FILTER_HEADER);
        try {
            return readInternal(reader);
        } finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static BloomFilterHeader readInternal(ThriftCompactReader reader) {
        int numBytes = -1;
        BloomFilterHeader.Algorithm algorithm = null;
        BloomFilterHeader.Hash hash = null;
        BloomFilterHeader.Compression compression = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1 -> numBytes = readRequiredBitsetSize(reader, ThriftCompactReader.fieldType(header));
                case 2 -> {
                    short variant = readUnionVariant(reader,
                            ThriftCompactReader.fieldType(header), ThriftStruct.BLOOM_FILTER_ALGORITHM);
                    algorithm = BloomFilterHeader.Algorithm.fromVariant(variant);
                }
                case 3 -> {
                    short variant = readUnionVariant(reader,
                            ThriftCompactReader.fieldType(header), ThriftStruct.BLOOM_FILTER_HASH);
                    hash = BloomFilterHeader.Hash.fromVariant(variant);
                }
                case 4 -> {
                    short variant = readUnionVariant(reader,
                            ThriftCompactReader.fieldType(header), ThriftStruct.BLOOM_FILTER_COMPRESSION);
                    compression = BloomFilterHeader.Compression.fromVariant(variant);
                }
                default -> reader.skipField(ThriftCompactReader.fieldType(header));
            }
        }

        if (numBytes < 0) {
            throw invalidHeader("missing required field 'numBytes'");
        }
        if (algorithm == null || hash == null || compression == null) {
            throw invalidHeader("missing required field(s) "
                    + (algorithm == null ? "algorithm " : "")
                    + (hash == null ? "hash " : "")
                    + (compression == null ? "compression " : ""));
        }

        return new BloomFilterHeader(numBytes, algorithm, hash, compression);
    }

    private static int readRequiredBitsetSize(ThriftCompactReader reader, byte type) {
        if (type != Codes.I32) {
            throw wrongWireType(reader, type);
        }
        return reader.readNonNegativeI32();
    }

    private static short readUnionVariant(ThriftCompactReader reader, byte type, ThriftStruct union) {
        if (type != Codes.STRUCT) {
            // Still standing on the header's own field, so this names it:
            // `BloomFilterHeader.algorithm`, from the table rather than by hand.
            throw wrongWireType(reader, type);
        }
        int saved = reader.pushFieldIdContext(union);
        try {
            int variant = reader.readFieldHeader();
            if (variant == ThriftCompactReader.STOP_FIELD) {
                throw invalidHeader(union.structName() + " has no variant set");
            }
            // The variant's value is an empty struct; a different wire type would make skipField
            // consume the wrong number of bytes and desync the rest of the header.
            if (ThriftCompactReader.fieldType(variant) != Codes.STRUCT) {
                // Inside the union now, so this names the variant it stopped on.
                throw wrongWireType(reader, ThriftCompactReader.fieldType(variant));
            }
            reader.skipField(ThriftCompactReader.fieldType(variant)); // consume the variant's value (the empty inner struct)
            if (reader.readFieldHeader() != ThriftCompactReader.STOP_FIELD) {
                throw invalidHeader(union.structName() + " has more than one variant set");
            }
            return ThriftCompactReader.fieldId(variant);
        } finally {
            reader.popFieldIdContext(saved);
        }
    }

    /// A field carrying something other than the wire type the format gives it,
    /// named as the field the reader is standing on. The name says which struct,
    /// so the message does not repeat it.
    private static ParquetReadException wrongWireType(ThriftCompactReader reader, byte type) {
        return reader.malformed("wrong wire type 0x" + Integer.toHexString(type & 0xFF));
    }

    /// A complaint about the header rather than about any one field of it, so
    /// nothing names a field: a required field that never arrived is discovered
    /// at the STOP that ends the struct, where the last field read is whichever
    /// one came before it and has nothing to do with what is missing.
    private static ParquetReadException invalidHeader(String message) {
        return new ParquetReadException("Invalid BloomFilterHeader: " + message);
    }
}
