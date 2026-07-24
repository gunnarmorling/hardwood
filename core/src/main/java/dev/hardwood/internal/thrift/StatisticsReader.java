/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;

import dev.hardwood.metadata.Statistics;

/// Reader for the Thrift Statistics struct from Parquet metadata.
///
/// Prefers fields 5/6 (`max_value`/`min_value` with correct sort order)
/// over deprecated fields 1/2 (`max`/`min`).
public class StatisticsReader {

    public static Statistics read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static Statistics readInternal(ThriftCompactReader reader) throws IOException {
        byte[] deprecatedMax = null;
        byte[] deprecatedMin = null;
        Long nullCount = null;
        Long distinctCount = null;
        byte[] maxValue = null;
        byte[] minValue = null;
        // is_max_value_exact / is_min_value_exact default to true when absent (parquet.thrift).
        boolean maxValueExact = true;
        boolean minValueExact = true;

        while (true) {
            ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
            if (header == null) {
                break;
            }

            switch (header.fieldId()) {
                case 1: // max (deprecated)
                    if (header.type() == 0x08) {
                        deprecatedMax = reader.readBinary();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 2: // min (deprecated)
                    if (header.type() == 0x08) {
                        deprecatedMin = reader.readBinary();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 3: // null_count
                    if (header.type() == 0x06) {
                        nullCount = reader.readI64();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 4: // distinct_count
                    if (header.type() == 0x06) {
                        distinctCount = reader.readI64();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 5: // max_value (preferred)
                    if (header.type() == 0x08) {
                        maxValue = reader.readBinary();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 6: // min_value (preferred)
                    if (header.type() == 0x08) {
                        minValue = reader.readBinary();
                    }
                    else {
                        reader.skipField(header.type());
                    }
                    break;
                case 7: // is_max_value_exact — a compact bool carries its value in the type nibble
                    maxValueExact = isBooleanTrue(header.type());
                    break;
                case 8: // is_min_value_exact
                    minValueExact = isBooleanTrue(header.type());
                    break;
                default:
                    reader.skipField(header.type());
                    break;
            }
        }

        // Prefer fields 5/6 over deprecated 1/2
        boolean deprecated = (minValue == null && maxValue == null);
        byte[] resolvedMin = minValue != null ? minValue : deprecatedMin;
        byte[] resolvedMax = maxValue != null ? maxValue : deprecatedMax;

        return new Statistics(resolvedMin, resolvedMax, nullCount, distinctCount, deprecated,
                minValueExact, maxValueExact);
    }

    /// A Thrift compact bool field carries its value in the field-type nibble: `0x01` is true,
    /// `0x02` is false. There is no separate body to consume.
    private static boolean isBooleanTrue(byte fieldType) {
        return fieldType == ThriftCompactConstants.FieldType.BOOLEAN_TRUE.code();
    }
}
