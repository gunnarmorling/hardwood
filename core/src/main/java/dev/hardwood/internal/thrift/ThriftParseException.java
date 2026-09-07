/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;

/// A parse failure carrying the field it happened on.
///
/// The id is captured where the failure is raised, not where it is caught:
/// skipping a field reads further headers before it can fail, so the reader's
/// current field id by then is some later one, and reading it in a `catch`
/// names a field that had nothing to do with it.
///
/// The struct is the other half, and only the readers know it. Each annotates
/// what escapes it, and the nesting depth decides which one is entitled to:
/// a field id means one thing in the struct it was read from and something
/// else in the struct enclosing it, so a reader that names its own struct for
/// a failure below it produces a name that looks right and is not. Only the
/// reader standing at the level the failure came from attaches a name, and a
/// failure in a struct no reader annotates — one [ThriftCompactReader#skipStruct]
/// walked past, say — keeps its message and gains no struct at all.
public class ThriftParseException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int fieldId;
    private final int bytesRead;
    private final int structDepth;

    private ThriftParseException(String message, int fieldId, int bytesRead, int structDepth,
            Throwable cause) {
        super(message, cause);
        this.fieldId = fieldId;
        this.bytesRead = bytesRead;
        this.structDepth = structDepth;
    }

    /// The position carried by `t`, or by the parse failure underneath it, or
    /// `-1` when neither is one.
    ///
    /// The position is how far into the buffer the reader had got. It is
    /// relative, because this reader has no idea what the buffer is a slice of:
    /// a caller that knows where the buffer starts in the file adds the two to
    /// get the byte a reader can go and look at, and keeping that knowledge out
    /// of here is what lets one reader parse a footer, a page header and a
    /// column index.
    public static int bytesReadOf(Throwable t) {
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof ThriftParseException parse) {
                return parse.bytesRead;
            }
        }
        return -1;
    }

    /// A failure on the field the reader is standing on, not yet attributed to
    /// a struct.
    static ThriftParseException onField(String message, int fieldId, int bytesRead,
            int structDepth) {
        return new ThriftParseException(message, fieldId, bytesRead, structDepth, null);
    }

    /// Attributes `e` to the struct being read, if the failure came from that
    /// struct's own fields rather than from something nested inside them.
    ///
    /// `outerDepth` is the reader's nesting depth before it began; its own
    /// fields are read one level in from there. A deeper failure belongs to a
    /// struct this reader is only the container of, and naming it here would
    /// resolve the inner struct's field id against the outer struct's fields —
    /// a corrupt `KeyValue.key` reported as `FileMetaData.version`.
    ///
    /// Only parse failures are attributed. A validation failure over an
    /// assembled value already names what it was checking — "ColumnMetaData.
    /// data_page_offset must be non-negative" — and prefixing the struct onto
    /// that says it twice.
    static IOException at(String struct, int outerDepth, IOException e) {
        if (!(e instanceof ThriftParseException parse) || parse.structDepth != outerDepth + 1) {
            return e;
        }
        return new ThriftParseException(
                ThriftFieldNames.describe(struct, parse.fieldId) + " — " + e.getMessage(),
                parse.fieldId, parse.bytesRead, parse.structDepth, e);
    }
}
