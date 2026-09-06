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

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.ExceptionContext.ReadContext;
import dev.hardwood.internal.ExceptionContext.ReadContext.Region;

import static dev.hardwood.internal.ExceptionContext.ReadContext.UNKNOWN_ROW_GROUP;
import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [ExceptionContext].
class ExceptionContextTest {

    @Test
    void addsFileContextToMessage() {
        IllegalStateException original = new IllegalStateException("something broke");
        RuntimeException wrapped = ExceptionContext.addFileContext("test.parquet", original);

        assertThat(wrapped).isInstanceOf(IllegalStateException.class);
        assertThat(wrapped.getMessage()).isEqualTo("[test.parquet] something broke");
        assertThat(wrapped.getCause()).isSameAs(original);
    }

    @Test
    void preservesIllegalArgumentException() {
        IllegalArgumentException original = new IllegalArgumentException("bad arg");
        RuntimeException wrapped = ExceptionContext.addFileContext("file.parquet", original);

        assertThat(wrapped).isInstanceOf(IllegalArgumentException.class);
        assertThat(wrapped.getMessage()).isEqualTo("[file.parquet] bad arg");
    }

    @Test
    void preservesUnsupportedOperationException() {
        UnsupportedOperationException original = new UnsupportedOperationException("nope");
        RuntimeException wrapped = ExceptionContext.addFileContext("file.parquet", original);

        assertThat(wrapped).isInstanceOf(UnsupportedOperationException.class);
        assertThat(wrapped.getMessage()).isEqualTo("[file.parquet] nope");
    }

    @Test
    void idempotentWhenAlreadyWrapped() {
        IllegalStateException original = new IllegalStateException("[test.parquet] already wrapped");
        RuntimeException wrapped = ExceptionContext.addFileContext("test.parquet", original);

        assertThat(wrapped).isSameAs(original);
    }

    @Test
    void nullFileNameReturnsOriginal() {
        IllegalStateException original = new IllegalStateException("error");
        RuntimeException wrapped = ExceptionContext.addFileContext(null, original);

        assertThat(wrapped).isSameAs(original);
    }

    @Test
    void nullMessageHandledGracefully() {
        RuntimeException original = new RuntimeException((String) null);
        RuntimeException wrapped = ExceptionContext.addFileContext("test.parquet", original);

        assertThat(wrapped.getMessage()).isEqualTo("[test.parquet] RuntimeException");
    }

    @Test
    void fallsBackToRuntimeExceptionForExoticType() {
        // A custom RuntimeException subclass without a (String, Throwable) constructor
        RuntimeException original = new CustomException();
        RuntimeException wrapped = ExceptionContext.addFileContext("test.parquet", original);

        assertThat(wrapped).isInstanceOf(RuntimeException.class);
        assertThat(wrapped.getMessage()).isEqualTo("[test.parquet] custom error");
        assertThat(wrapped.getCause()).isSameAs(original);
    }

    private static class CustomException extends RuntimeException {
        CustomException() {
            super("custom error");
        }
    }

    @Test
    void readContextNamesColumnRegionAndOffsetInBothBases() {
        IllegalStateException original = new IllegalStateException("Unknown field type: 15");
        Throwable wrapped = ExceptionContext.enrich(
                new ReadContext("f.parquet", 3, "id", Region.PAGE_HEADER, 41104, true),
                original);

        assertThat(wrapped).isInstanceOf(IllegalStateException.class);
        assertThat(wrapped.getMessage()).isEqualTo(
                "[f.parquet] row group 3, column id, page header at byte 41104 (0x00a090)"
                        + " — Unknown field type: 15");
    }

    /// A catch site that knows only the file must produce exactly what the
    /// file-name path always produced, so existing messages do not shift.
    @Test
    void readContextWithNothingButAFileReadsAsPlainFileContext() {
        IllegalStateException original = new IllegalStateException("something broke");

        assertThat(ExceptionContext.enrich(ReadContext.ofFile("test.parquet"), original)
                .getMessage())
                .isEqualTo(ExceptionContext.addFileContext("test.parquet", original).getMessage());
    }

    /// The file name stays at the front so the already-enriched check still
    /// fires and a message crossing two catch sites is not prefixed twice.
    @Test
    void readContextIsNotAppliedTwice() {
        Throwable once = ExceptionContext.enrich(
                new ReadContext("f.parquet", 0, "id", Region.PAGE_HEADER, 8, true),
                new IllegalStateException("boom"));
        Throwable twice = ExceptionContext.enrich(
                new ReadContext("f.parquet", 0, "id", Region.PAGE_HEADER, 8, true), once);

        assertThat(twice.getMessage()).isEqualTo(once.getMessage());
    }

    /// The row group is what a failure with no byte to name has instead. A
    /// batch that spans a file boundary is the one read that genuinely has no
    /// single row group, and it must not print one.
    @Test
    void readContextNamesTheRowGroupWhereThereIsNoByte() {
        assertThat(ExceptionContext.enrich(
                new ReadContext("f.parquet", 12, "x.list.element", Region.BATCH_ASSEMBLY,
                        ReadContext.UNKNOWN_OFFSET, false),
                new IllegalStateException("first repetition level must be 0 but was 1"))
                .getMessage())
                .isEqualTo("[f.parquet] row group 12, column x.list.element, batch assembly"
                        + " — first repetition level must be 0 but was 1");

        assertThat(ExceptionContext.enrich(
                new ReadContext("f.parquet", UNKNOWN_ROW_GROUP, "x", Region.BATCH_ASSEMBLY,
                        ReadContext.UNKNOWN_OFFSET, false),
                new IllegalStateException("boom")).getMessage())
                .isEqualTo("[f.parquet] column x, batch assembly — boom")
                .doesNotContain("row group");
    }

    /// A footer belongs to the file rather than to any part of it, so a context
    /// with neither row group nor column still reads as a sentence.
    @Test
    void readContextWithNeitherRowGroupNorColumn() {
        assertThat(ExceptionContext.prefix(new ReadContext("f.parquet", UNKNOWN_ROW_GROUP, null,
                Region.FOOTER, 161340, true)))
                .isEqualTo("[f.parquet] footer at byte 161340 (0x02763c) — ");
    }

    /// The wrapped type has to survive, because callers catch on it: the CLI
    /// and dive both branch on `UncheckedIOException`.
    @Test
    void readContextPreservesUncheckedIoExceptionAndItsCause() {
        IOException cause = new IOException("CRC mismatch");
        Throwable wrapped = ExceptionContext.enrich(
                new ReadContext("f.parquet", 1, "id", Region.DATA_PAGE, 2048, true),
                new UncheckedIOException(cause));

        assertThat(wrapped).isInstanceOf(UncheckedIOException.class);
        assertThat(wrapped.getCause()).isSameAs(cause);
        assertThat(wrapped.getMessage())
                .contains("row group 1, column id", "data page at byte 2048", "CRC mismatch");
    }

    /// A catch site that knows the stage but not how far it got must not leave
    /// a dangling "at byte" behind; the clause carries only what it was given.
    @Test
    void readContextOmitsAnOffsetItDoesNotHave() {
        Throwable wrapped = ExceptionContext.enrich(
                new ReadContext("f.parquet", UNKNOWN_ROW_GROUP, "id", Region.PAGE_FETCH,
                        ReadContext.UNKNOWN_OFFSET, false),
                new IllegalStateException("connection reset"));

        assertThat(wrapped.getMessage())
                .isEqualTo("[f.parquet] column id, page fetch — connection reset");
    }

    /// The footer belongs to the file rather than to any column, so its context
    /// names a region and an offset and no column at all.
    @Test
    void readContextWithoutAColumnStillNamesRegionAndOffset() {
        assertThat(ExceptionContext.prefix(
                new ReadContext("f.parquet", UNKNOWN_ROW_GROUP, null, Region.FOOTER, 161340, true)))
                .isEqualTo("[f.parquet] footer at byte 161340 (0x02763c) — ");
    }

    /// Nothing beyond the file leaves the prefix exactly as it has always been,
    /// so a caller can concatenate it without checking.
    @Test
    void prefixWithNothingButAFileIsJustTheFilePrefix() {
        assertThat(ExceptionContext.prefix(ReadContext.ofFile("f.parquet")))
                .isEqualTo("[f.parquet] ");
    }

    /// An offset that only locates the region says so. A decoder that ran out
    /// of input a thousand values into a page knows the page and nothing finer,
    /// and "at byte 4" would read as a pointer to damage that is not there.
    @Test
    void anOffsetThatOnlyLocatesTheRegionSaysSo() {
        Throwable wrapped = ExceptionContext.enrich(
                new ReadContext("f.parquet", UNKNOWN_ROW_GROUP, "flba", Region.DATA_PAGE, 4, false),
                new UncheckedIOException(new IOException("Unexpected EOF")));

        assertThat(wrapped.getMessage()).contains("data page beginning at byte 4");
    }

    /// Indexing an array throws ArrayIndexOutOfBoundsException, which an exact
    /// class match missed — it has no (String, Throwable) constructor, so the
    /// reflective fallback turned it into a bare RuntimeException and a caller
    /// branching on the type stopped seeing it. A dictionary reference past the
    /// end of the dictionary arrives exactly this way.
    @Test
    void anArrayIndexFailureKeepsItsType() {
        Throwable wrapped = ExceptionContext.enrich(
                new ReadContext("f.parquet", UNKNOWN_ROW_GROUP, "k", Region.DATA_PAGE, 40, false),
                new ArrayIndexOutOfBoundsException("Index 7 out of bounds for length 2"));

        assertThat(wrapped).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(wrapped.getMessage()).contains("column k", "Index 7 out of bounds for length 2");
    }
}
