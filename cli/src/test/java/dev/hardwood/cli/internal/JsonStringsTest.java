/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonStringsTest {

    @Test
    void leavesPlainTextUnchanged() {
        assertThat(JsonStrings.escape("")).isEmpty();
        assertThat(JsonStrings.escape("plain name")).isEqualTo("plain name");
    }

    @Test
    void escapesQuoteAndBackslash() {
        assertThat(JsonStrings.escape("say \"hi\"")).isEqualTo("say \\\"hi\\\"");
        assertThat(JsonStrings.escape("c:\\tmp")).isEqualTo("c:\\\\tmp");
    }

    @Test
    void escapesWhitespaceControlCharactersByShortForm() {
        assertThat(JsonStrings.escape("a\nb")).isEqualTo("a\\nb");
        assertThat(JsonStrings.escape("a\rb")).isEqualTo("a\\rb");
        assertThat(JsonStrings.escape("a\tb")).isEqualTo("a\\tb");
        assertThat(JsonStrings.escape("a\bb")).isEqualTo("a\\bb");
        assertThat(JsonStrings.escape("a\fb")).isEqualTo("a\\fb");
    }

    @Test
    void escapesRemainingControlCharactersAsUnicode() {
        assertThat(JsonStrings.escape("bell\u0007field")).isEqualTo("bell\\u0007field");
        assertThat(JsonStrings.escape("\u0000")).isEqualTo("\\u0000");
        assertThat(JsonStrings.escape("\u001f")).isEqualTo("\\u001f");
    }

    @Test
    void leavesCharactersAtAndAboveSpaceUnchanged() {
        assertThat(JsonStrings.escape("\u0020\u007f\u00e4\u20ac")).isEqualTo("\u0020\u007f\u00e4\u20ac");
    }
}
