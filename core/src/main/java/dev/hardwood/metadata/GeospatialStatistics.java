/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

import java.util.List;

/// @param bbox bounding box, or `null` if absent
/// @param geospatialTypes geospatial type codes of all instances in a geometry/geography column,
///     or an empty list if not known. Values are Well-Known Binary (WKB) geometry type codes:
///     1=Point, 2=LineString, 3=Polygon, 4=MultiPoint, 5=MultiLineString, 6=MultiPolygon,
///     7=GeometryCollection, each offset by 1000 for a Z coordinate, 2000 for M, and 3000 for
///     both — so a 3D point is 1001.
/// @see <a href="https://github.com/apache/parquet-format/blob/master/Geospatial.md#statistics">Geospatial – statistics</a>
/// @see <a href="https://github.com/apache/parquet-format/blob/master/Geospatial.md#geospatial-types">Geospatial - types</a>
public record GeospatialStatistics(
        BoundingBox bbox,
        List<Integer> geospatialTypes
) {
}
