/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.util.Arrays;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.BoundingBox;

/// Reader for the Thrift BoundingBox struct from Parquet metadata.
public class BoundingBoxReader {

    public static BoundingBox read(ThriftCompactReader reader) {
        int saved = reader.pushFieldIdContext(ThriftStruct.BOUNDING_BOX);
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
                case 1 -> xmin = requiredDouble(reader, header);
                case 2 -> xmax = requiredDouble(reader, header);
                case 3 -> ymin = requiredDouble(reader, header);
                case 4 -> ymax = requiredDouble(reader, header);
                case 5 -> zmin = optionalDouble(reader, header);
                case 6 -> zmax = optionalDouble(reader, header);
                case 7 -> mmin = optionalDouble(reader, header);
                case 8 -> mmax = optionalDouble(reader, header);
                default -> reader.skipField(ThriftCompactReader.fieldType(header));
            }
        }

        int[] missing = new int[4];
        int absent = 0;
        if (xmin == null) {
            missing[absent++] = 1;
        }
        if (xmax == null) {
            missing[absent++] = 2;
        }
        if (ymin == null) {
            missing[absent++] = 3;
        }
        if (ymax == null) {
            missing[absent++] = 4;
        }
        if (absent > 0) {
            throw ThriftCompactReader.missingFields(ThriftStruct.BOUNDING_BOX,
                    Arrays.copyOf(missing, absent));
        }

        return new BoundingBox(xmin, xmax, ymin, ymax, zmin, zmax, mmin, mmax);
    }

    /// One of the four coordinates a bounding box cannot do without, so a wrong
    /// wire type fails here rather than being reported as a coordinate that never
    /// arrived.
    private static double requiredDouble(ThriftCompactReader reader, int header) {
        reader.requireField(header, Codes.DOUBLE);
        return reader.readDouble();
    }

    /// One of the four a bounding box can, so a wrong wire type is skipped and
    /// logged and the box keeps the coordinates it does have.
    private static Double optionalDouble(ThriftCompactReader reader, int header) {
        return reader.acceptField(header, Codes.DOUBLE) ? reader.readDouble() : null;
    }
}
