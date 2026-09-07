/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;

/// Utility for enriching exception messages with file-name context.
///
/// All exception-wrapping for file-name enrichment is centralised here so that
/// reader classes share the same logic.
public final class ExceptionContext {

    private ExceptionContext() {
    }

    /// Returns the `[fileName] ` prefix used to mark exception messages with
    /// their originating file. Returns the empty string when `fileName` is `null`,
    /// so callers can always concatenate the result without a null check.
    public static String filePrefix(String fileName) {
        return fileName != null ? "[" + fileName + "] " : "";
    }

    /// Where a read had got to when it failed: which file, which row group and
    /// column, which region of the file was being parsed, and how far into that
    /// region the parse reached.
    ///
    /// Every part except the file name is optional. A catch site passes what it
    /// has — `column` and `region` may be `null`, `rowGroup` may be
    /// [#UNKNOWN_ROW_GROUP] and `offset` may be [#UNKNOWN_OFFSET] — and the
    /// formatted message omits what is missing, so a context carrying only a
    /// file name reads exactly as [#addFileContext] always did.
    ///
    /// The row group earns its place on the failures that have no byte to
    /// name. Where the offset is exact it is largely redundant, since a file
    /// offset resolves to a row group through the footer; where there is no
    /// offset — a fetch that failed before reading, a page that would not
    /// assemble — it is the only thing narrowing the column down to a part of
    /// it, and it is the coordinate `hardwood dive` navigates by.
    ///
    ///
    /// @param fileName the file being read, may be `null`
    /// @param rowGroup the row group index, or [#UNKNOWN_ROW_GROUP]
    /// @param column   the column path, may be `null`
    /// @param region   what was being parsed, may be `null`
    /// @param offset   how far the parse reached, or [#UNKNOWN_OFFSET]
    /// @param exact    whether `offset` is where the read stopped, rather than
    ///                 where the region it was reading begins
    public record ReadContext(String fileName, int rowGroup, String column, Region region,
            long offset, boolean exact) {

        /// A region of a Parquet file, as a message names it.
        ///
        /// A closed set rather than free text: the words end up in a message a
        /// reader compares against another message, so they have to be the same
        /// words every time, and a typo in a string literal is not something a
        /// failure path will tell you about.
        ///
        /// The list is of a file's regions, not of one module's reads — the
        /// column and offset indexes are reached by the CLI rather than by the
        /// read path, but a reader should not have to know which of them
        /// produced the words.
        public enum Region {

            /// Reading or parsing a column chunk's dictionary.
            DICTIONARY_PAGE("dictionary page"),

            /// Parsing the Thrift header in front of a page.
            PAGE_HEADER("page header"),

            /// Fetching a page's bytes, before any decode.
            PAGE_FETCH("page fetch"),

            /// Decompressing or decoding a page's values.
            DATA_PAGE("data page"),

            /// Assembling decoded pages into a batch.
            BATCH_ASSEMBLY("batch assembly"),

            /// Parsing a chunk's column index.
            COLUMN_INDEX("column index"),

            /// Parsing a chunk's offset index.
            OFFSET_INDEX("offset index"),

            /// Parsing the file's Thrift footer.
            FOOTER("footer");

            private final String text;

            Region(String text) {
                this.text = text;
            }

            /// How a message names this region.
            @Override
            public String toString() {
                return text;
            }
        }

        /// Offset value meaning "the catch site does not know where it was".
        public static final long UNKNOWN_OFFSET = -1;

        /// Row group value meaning "the catch site does not know which one".
        ///
        /// A batch assembled across a file boundary is the one read that
        /// genuinely spans row groups; every other catch site knows its own.
        public static final int UNKNOWN_ROW_GROUP = -1;

        /// A context that names only the file, which is all some read paths know.
        public static ReadContext ofFile(String fileName) {
            return new ReadContext(fileName, UNKNOWN_ROW_GROUP, null, null, UNKNOWN_OFFSET,
                    false);
        }
    }

    /// The context clause a message carries after its `[file] ` prefix, or the
    /// empty string when the context names nothing beyond the file.
    static String contextClause(ReadContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.rowGroup() != ReadContext.UNKNOWN_ROW_GROUP) {
            sb.append("row group ").append(context.rowGroup());
        }
        if (context.column() != null && !context.column().isEmpty()) {
            append(sb, "column " + context.column());
        }
        if (context.region() != null) {
            append(sb, context.region().toString());
        }
        if (context.offset() != ReadContext.UNKNOWN_OFFSET) {
            // An offset that only locates the region says so. "at byte 4" for a
            // failure a thousand values into the page reads as a pointer to the
            // damage, and there is nothing wrong at byte 4.
            // An offset that only locates the region says so. "at byte 4" for a
            // failure a thousand values into the page reads as a pointer to the
            // damage, and there is nothing wrong at byte 4.
            String where = (context.exact() ? "at byte " : "beginning at byte ")
                    + context.offset() + String.format(" (0x%06x)", context.offset());
            if (sb.isEmpty()) {
                sb.append(where);
            }
            else {
                sb.append(' ').append(where);
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(part);
    }


    /// The `[file] clause — ` prefix for a message a caller assembles itself.
    ///
    /// Paths that throw a checked [IOException] cannot hand it to
    /// [#enrich], which rebuilds a [RuntimeException]; they build their
    /// own message and want the same prefix in front of it. Returns just the
    /// file prefix when the context names nothing more, so the result is always
    /// safe to concatenate.
    public static String prefix(ReadContext context) {
        String clause = contextClause(context);
        return filePrefix(context.fileName()) + (clause.isEmpty() ? "" : clause + " — ");
    }

    /// Enriches whatever a read task caught. A [RuntimeException] keeps its
    /// type. An [IOException] becomes an [UncheckedIOException] carrying the
    /// same context, so it can cross a task boundary without the pipeline
    /// re-wrapping it in something generic that loses the message. Anything
    /// else — an [Error], say — propagates untouched.
    public static Throwable enrich(ReadContext context, Throwable t) {
        RuntimeException e;
        if (t instanceof RuntimeException re) {
            e = re;
        }
        else if (t instanceof IOException ioe) {
            e = new UncheckedIOException(
                    ioe.getMessage() != null ? ioe.getMessage() : "I/O failure", ioe);
        }
        else {
            return t;
        }
        // Guard before retitling, not after: prepending the clause moves the
        // `[file] ` prefix off the front, where the already-enriched check
        // cannot see it, and a message crossing two catch sites gains the
        // context twice.
        if (hasFilePrefix(e.getMessage())) {
            return e;
        }
        Throwable cause = e.getCause();
        if (cause != null && hasFilePrefix(cause.getMessage())) {
            return e;
        }
        String clause = contextClause(context);
        RuntimeException titled = clause.isEmpty() ? e : retitle(clause, e, context);
        return addFileContext(context.fileName(), titled, context);
    }

    /// Prefixes `e`'s message with `clause`, keeping its type and cause.
    ///
    /// Separated by a dash rather than a colon because the message being
    /// prefixed usually ends up carrying its own — `CRC mismatch: expected …`
    /// behind `at byte 41104:` reads as one sentence punctuated twice.
    private static RuntimeException retitle(String clause, RuntimeException e, ReadContext context) {
        String message = e.getMessage();
        return rewrap(e, clause + " — " + (message != null ? message : e.getClass().getSimpleName()),
                context);
    }

    /// Amends the exception message with a `[fileName] ` prefix. Preserves the
    /// original exception type and cause chain. Returns the original exception
    /// unchanged when the file name is unavailable or the prefix is already present.
    ///
    /// @param fileName the originating file name, may be `null`
    /// @param e        the exception to enrich
    /// @return the enriched (or original) exception — never `null`
    public static RuntimeException addFileContext(String fileName, RuntimeException e) {
        return addFileContext(fileName, e, null);
    }

    private static RuntimeException addFileContext(String fileName, RuntimeException e,
            ReadContext context) {
        if (fileName == null || fileName.isEmpty()) {
            return e;
        }
        String prefix = "[" + fileName + "] ";
        String originalMessage = e.getMessage();
        if (hasFilePrefix(originalMessage)) {
            return e;
        }
        // If the cause already carries file context (e.g. assembly-thread error
        // propagated through CompletionException), don't add a second layer.
        Throwable cause = e.getCause();
        if (cause != null && hasFilePrefix(cause.getMessage())) {
            return e;
        }
        return rewrap(e, prefix
                + (originalMessage != null ? originalMessage : e.getClass().getSimpleName()),
                context);
    }

    /// Re-throws `e`'s content under `newMessage`, preserving its type and cause
    /// chain. Shared by the file-name prefix and the read-context clause so a
    /// message gains context without changing what a caller can catch.
    ///
    /// **Cause-chain note for [UncheckedIOException]:** the type requires an
    /// [IOException] cause, so the original is attached as suppressed and the
    /// cause slot holds the inner [IOException].
    private static RuntimeException rewrap(RuntimeException e, String newMessage,
            ReadContext context) {
        String originalMessage = e.getMessage();
        if (e instanceof UncheckedIOException uio) {
            // UncheckedIOException requires an IOException cause, so we can't chain
            // the original UncheckedIOException as the cause. Preserve it as suppressed.
            IOException ioCause = uio.getCause();
            if (ioCause == null) {
                ioCause = new IOException(originalMessage);
            }
            UncheckedIOException wrapped = context != null
                    ? new ContextualUncheckedIOException(newMessage, ioCause, context)
                    : new UncheckedIOException(newMessage, ioCause);
            wrapped.addSuppressed(e);
            return wrapped;
        }
        if (e.getClass() == IllegalArgumentException.class) {
            return new IllegalArgumentException(newMessage, e);
        }
        if (e.getClass() == IllegalStateException.class) {
            return new IllegalStateException(newMessage, e);
        }
        if (e.getClass() == NullPointerException.class) {
            NullPointerException wrapped = new NullPointerException(newMessage);
            wrapped.initCause(e);
            return wrapped;
        }
        // `instanceof`, not an exact class match: indexing an array throws
        // ArrayIndexOutOfBoundsException, and an exact match sent it to the
        // reflective path below, where it has no (String, Throwable)
        // constructor and came out a bare RuntimeException. A corrupt
        // dictionary reference reaches a caller this way, and one that catches
        // on the type — dive does — stopped seeing it.
        if (e instanceof IndexOutOfBoundsException) {
            IndexOutOfBoundsException wrapped = new IndexOutOfBoundsException(newMessage);
            wrapped.initCause(e);
            return wrapped;
        }

        // For CompletionException and other wrapper types: try to preserve the type
        // via the (String, Throwable) constructor. For CompletionException, preserve
        // the original cause rather than wrapping the CompletionException itself,
        // so the cause chain stays shallow.
        try {
            Throwable preservedCause = (e instanceof CompletionException && e.getCause() != null)
                    ? e.getCause()
                    : e;
            return e.getClass()
                    .getConstructor(String.class, Throwable.class)
                    .newInstance(newMessage, preservedCause);
        }
        catch (ReflectiveOperationException ignored) {
            // Type cannot be preserved
        }
        return new RuntimeException(newMessage, e);
    }

    private static boolean hasFilePrefix(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) != '[') {
            return false;
        }
        return message.indexOf("] ") > 1;
    }
}
