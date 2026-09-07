/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;

import dev.hardwood.internal.bloomfilter.BloomFilterHeader;
import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;

public class BloomFilterHeaderReader {

    public static BloomFilterHeader read(ThriftCompactReader reader) throws IOException {
        int depth = reader.structDepth();
        try {
            return readFields(reader);
        }
        catch (IOException e) {
            throw ThriftParseException.at("BloomFilterHeader", depth, e);
        }
    }

    private static BloomFilterHeader readFields(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        } finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static BloomFilterHeader readInternal(ThriftCompactReader reader) throws IOException {
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
                    short variant = readUnionVariant(reader, ThriftCompactReader.fieldType(header), "algorithm");
                    algorithm = BloomFilterHeader.Algorithm.fromVariant(variant);
                }
                case 3 -> {
                    short variant = readUnionVariant(reader, ThriftCompactReader.fieldType(header), "hash");
                    hash = BloomFilterHeader.Hash.fromVariant(variant);
                }
                case 4 -> {
                    short variant = readUnionVariant(reader, ThriftCompactReader.fieldType(header), "compression");
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

    private static int readRequiredBitsetSize(ThriftCompactReader reader, byte type) throws IOException {
        if (type != Codes.I32) {
            throw wrongWireType("required field 'numBytes'", type);
        }
        return reader.readNonNegativeI32("BloomFilterHeader.numBytes");
    }

    private static short readUnionVariant(ThriftCompactReader reader, byte type, String name) throws IOException {
        if (type != Codes.STRUCT) {
            throw wrongWireType("union field '" + name + "'", type);
        }
        short saved = reader.pushFieldIdContext();
        try {
            int variant = reader.readFieldHeader();
            if (variant == ThriftCompactReader.STOP_FIELD) {
                throw invalidHeader("union field '" + name + "' has no variant set");
            }
            // The variant's value is an empty struct; a different wire type would make skipField
            // consume the wrong number of bytes and desync the rest of the header.
            if (ThriftCompactReader.fieldType(variant) != Codes.STRUCT) {
                throw wrongWireType("union field '" + name + "' variant " + ThriftCompactReader.fieldId(variant), ThriftCompactReader.fieldType(variant));
            }
            reader.skipField(ThriftCompactReader.fieldType(variant)); // consume the variant's value (the empty inner struct)
            if (reader.readFieldHeader() != ThriftCompactReader.STOP_FIELD) {
                throw invalidHeader("union field '" + name + "' has more than one variant set");
            }
            return ThriftCompactReader.fieldId(variant);
        } finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static IllegalStateException wrongWireType(String fieldDescription, byte type) {
        return invalidHeader(fieldDescription + " has wrong wire type 0x" + Integer.toHexString(type & 0xFF));
    }

    private static IllegalStateException invalidHeader(String message) {
        return new IllegalStateException("Invalid BloomFilterHeader: " + message);
    }
}
