/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AvroNamesTest {

    private static final Pattern AVRO_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Test
    void leavesLegalNamesUnchanged() {
        assertThat(AvroNames.sanitize("name")).isEqualTo("name");
        assertThat(AvroNames.sanitize("_Name_42")).isEqualTo("_Name_42");
    }

    @Test
    void replacesIllegalCharacters() {
        assertThat(AvroNames.sanitize("say \"hi\"")).isEqualTo("say__hi_");
        assertThat(AvroNames.sanitize("total (usd)")).isEqualTo("total__usd_");
        assertThat(AvroNames.sanitize("a.b")).isEqualTo("a_b");
        assertThat(AvroNames.sanitize("café")).isEqualTo("caf_");
        assertThat(AvroNames.sanitize("bell\u0007field")).isEqualTo("bell_field");
    }

    @Test
    void prefixesLeadingDigit() {
        assertThat(AvroNames.sanitize("1foo")).isEqualTo("_1foo");
        assertThat(AvroNames.sanitize("9")).isEqualTo("_9");
    }

    @Test
    void mapsEmptyNameToUnderscore() {
        assertThat(AvroNames.sanitize("")).isEqualTo("_");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "1", "-", "say \"hi\"", "a.b.c", "bell\u0007field", "café", "___", "9lives", "ä€" })
    void alwaysProducesALegalAvroName(String name) {
        assertThat(AvroNames.sanitize(name)).matches(AVRO_NAME);
    }
}
