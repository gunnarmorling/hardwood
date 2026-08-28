/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end coverage for the `ENUM` logical type (hardwood-hq/hardwood#847).
/// `ENUM` stores a UTF-8 payload, and the Parquet specification tells readers
/// without a native enum type to interpret it as a string, so it decodes exactly
/// like `UTF8` — through `getString` and through the generic accessors, at every
/// nesting position. `getBinary` still yields the undecoded payload.
///
/// Fixture: `tools/simple-datagen.py` → `enum_nested_test.parquet`. Three rows
/// with the same ENUM representation at the top level, inside a struct, as a
/// list element, and as a map value.
class EnumLogicalTypeReadTest {

    private static final Path FILE = Paths.get("src/test/resources/enum_nested_test.parquet");

    private static ParquetFileReader open() throws IOException {
        return ParquetFileReader.open(InputFile.of(FILE));
    }

    @Test
    void enumDecodesToStringAtEveryNestingPosition() throws IOException {
        try (ParquetFileReader reader = open();
             RowReader rows = reader.rowReader()) {
            rows.next();
            assertThat(rows.getString("status")).isEqualTo("ACTIVE");

            PqStruct meta = rows.getStruct("meta");
            assertThat(meta.getString("kind")).isEqualTo("PRIMARY");

            PqList tags = rows.getList("tags");
            assertThat(tags.strings()).containsExactly("RED", "GREEN");

            PqMap labels = rows.getMap("labels");
            assertThat(labels.getEntries().getFirst().getStringValue()).isEqualTo("ON");
        }
    }

    @Test
    void genericAccessorsDecodeEnumToStringToo() throws IOException {
        try (ParquetFileReader reader = open();
             RowReader rows = reader.rowReader()) {
            rows.next();
            assertThat(rows.getValue("status")).isEqualTo("ACTIVE");
            assertThat(rows.getStruct("meta").getValue("kind")).isEqualTo("PRIMARY");
            assertThat(rows.getList("tags").get(0)).isEqualTo("RED");
            assertThat(rows.getList("tags").values()).containsExactly("RED", "GREEN");
            assertThat(rows.getMap("labels").getEntries().getFirst().getValue()).isEqualTo("ON");
        }
    }

    @Test
    void rawAccessorsStillYieldTheUndecodedPayload() throws IOException {
        try (ParquetFileReader reader = open();
             RowReader rows = reader.rowReader()) {
            rows.next();
            assertThat(rows.getBinary("status")).isEqualTo("ACTIVE".getBytes(StandardCharsets.UTF_8));
            assertThat(rows.getList("tags").getRaw(0)).isEqualTo("RED".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void nullEnumValuesStayNullAtEveryNestingPosition() throws IOException {
        try (ParquetFileReader reader = open();
             RowReader rows = reader.rowReader()) {
            rows.next();
            rows.next();
            rows.next();
            assertThat(rows.getString("status")).isNull();
            assertThat(rows.getStruct("meta").getString("kind")).isNull();
            assertThat(rows.getList("tags").isNull(0)).isTrue();
            assertThat(rows.getList("tags").strings()).containsExactly(null, "RED");
            assertThat(rows.getMap("labels").getEntries().getFirst().isValueNull()).isTrue();
        }
    }
}
