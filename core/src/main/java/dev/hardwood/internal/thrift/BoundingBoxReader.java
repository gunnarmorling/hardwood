/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.BoundingBox;

/// Reader for the Thrift BoundingBox struct from Parquet metadata.
public class BoundingBoxReader {

    public static BoundingBox read(ThriftCompactReader reader) {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static BoundingBox readInternal(ThriftCompactReader reader) {
        Double xmin = null;
        Double xmax = null;
        Double ymin = null;
        Double ymax = null;
        Double zmin = null;
        Double zmax = null;
        Double mmin = null;
        Double mmax = null;

        while (true) {
            int header = reader.readFieldHeader();
            if (header == ThriftCompactReader.STOP_FIELD) {
                break;
            }

            switch (ThriftCompactReader.fieldId(header)) {
                case 1 -> xmin = readRequiredDouble(reader, ThriftCompactReader.fieldType(header), "xmin");
                case 2 -> xmax = readRequiredDouble(reader, ThriftCompactReader.fieldType(header), "xmax");
                case 3 -> ymin = readRequiredDouble(reader, ThriftCompactReader.fieldType(header), "ymin");
                case 4 -> ymax = readRequiredDouble(reader, ThriftCompactReader.fieldType(header), "ymax");
                case 5 -> zmin = readOptionalDouble(reader, ThriftCompactReader.fieldType(header));
                case 6 -> zmax = readOptionalDouble(reader, ThriftCompactReader.fieldType(header));
                case 7 -> mmin = readOptionalDouble(reader, ThriftCompactReader.fieldType(header));
                case 8 -> mmax = readOptionalDouble(reader, ThriftCompactReader.fieldType(header));
                default -> reader.skipField(ThriftCompactReader.fieldType(header));
            }
        }

        if (xmin == null || xmax == null || ymin == null || ymax == null) {
            throw new IllegalStateException(
                    "Invalid BoundingBox: missing required field(s) "
                            + (xmin == null ? "xmin " : "")
                            + (xmax == null ? "xmax " : "")
                            + (ymin == null ? "ymin " : "")
                            + (ymax == null ? "ymax " : ""));
        }

        return new BoundingBox(xmin, xmax, ymin, ymax, zmin, zmax, mmin, mmax);
    }

    private static double readRequiredDouble(ThriftCompactReader reader, byte type, String name) {
        if (type != Codes.DOUBLE) {
            throw new IllegalStateException(
                    "Invalid BoundingBox: required field '" + name
                            + "' has wrong wire type 0x" + Integer.toHexString(type & 0xFF));
        }
        return reader.readDouble();
    }

    private static Double readOptionalDouble(ThriftCompactReader reader, byte type) {
        if (type == Codes.DOUBLE) {
            return reader.readDouble();
        }
        reader.skipField(type);
        return null;
    }
}
