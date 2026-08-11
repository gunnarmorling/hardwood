/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.hardwood.internal.thrift.ThriftCompactConstants.FieldType.Codes;
import dev.hardwood.metadata.BoundingBox;
import dev.hardwood.metadata.GeospatialStatistics;

/// Reader for the Thrift GeospatialStatistics struct from Parquet metadata.
public class GeospatialStatisticsReader {

    public static GeospatialStatistics read(ThriftCompactReader reader) throws IOException {
        short saved = reader.pushFieldIdContext();
        try {
            return readInternal(reader);
        }
        finally {
            reader.popFieldIdContext(saved);
        }
    }

    private static GeospatialStatistics readInternal(ThriftCompactReader reader) throws IOException {
        BoundingBox bbox = null;
        List<Integer> geospatialTypes = List.of();

        while (true) {
            ThriftCompactReader.FieldHeader header = reader.readFieldHeader();
            if (header == null) {
                break;
            }

            switch (header.fieldId()) {
                case 1: // bbox (optional BoundingBox)
                    if (reader.acceptField(header, Codes.STRUCT)) {
                        bbox = BoundingBoxReader.read(reader);
                    }
                    break;
                case 2: // geospatial_types (optional list<i32>)
                    if (reader.acceptField(header, Codes.LIST)) {
                        geospatialTypes = readGeospatialTypes(reader);
                    }
                    break;
                default:
                    reader.skipField(header.type());
                    break;
            }
        }

        return new GeospatialStatistics(bbox, geospatialTypes);
    }

    private static List<Integer> readGeospatialTypes(ThriftCompactReader reader) throws IOException {
        ThriftCompactReader.CollectionHeader listHeader =
                reader.acceptListHeader(Codes.I32, "GeospatialStatistics.geospatial_types");
        if (listHeader == null) {
            return List.of();
        }
        List<Integer> geospatialTypes = new ArrayList<>(listHeader.size());
        for (int i = 0; i < listHeader.size(); i++) {
            geospatialTypes.add(reader.readI32());
        }
        return Collections.unmodifiableList(geospatialTypes);
    }
}
