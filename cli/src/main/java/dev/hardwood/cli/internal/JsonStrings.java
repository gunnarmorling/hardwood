/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

/// Escaping of strings emitted into JSON output.
public final class JsonStrings {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonStrings() {
    }

    /// Escapes `value` for use inside a JSON string literal, per RFC 8259: the
    /// quote and backslash get a backslash escape, and every character below
    /// `U+0020` is escaped — `\b`, `\f`, `\n`, `\r` and `\t` by their short form,
    /// the rest as a four-hex-digit Unicode escape.
    ///
    /// @throws NullPointerException if `value` is `null`
    public static String escape(String value) {
        int length = value.length();
        StringBuilder sb = null;

        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c >= ' ' && c != '"' && c != '\\') {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }

            if (sb == null) {
                sb = new StringBuilder(length + 8);
                sb.append(value, 0, i);
            }
            appendEscaped(sb, c);
        }

        return sb == null ? value : sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, char c) {
        switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\b' -> sb.append("\\b");
            case '\f' -> sb.append("\\f");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> sb.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
        }
    }
}
