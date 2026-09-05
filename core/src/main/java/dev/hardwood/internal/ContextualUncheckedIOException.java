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

import dev.hardwood.internal.ExceptionContext.ReadContext;

/// An [UncheckedIOException] that has kept the context its message was built
/// from, so a caller can act on the offset instead of parsing it back out of
/// the text.
///
/// It is an `UncheckedIOException` and nothing about catching one changes;
/// that is the point. The reader, the CLI commands and dive all branch on the
/// type, and a context that could only be had by catching something else would
/// be a context nobody catches.
///
/// Only this type carries a context, because only this type can carry one that
/// means anything. An I/O failure and a Thrift parse failure are both rooted in
/// an [IOException] and are positioned in the file; the decoders raise
/// [IllegalStateException] and [IllegalArgumentException], and their positions
/// are inside a decompressed page, which has no offset in the file at all.
public final class ContextualUncheckedIOException extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    /// Not serialised — the message carries the same facts in readable form,
    /// and an exception that has been through serialisation is being read
    /// rather than acted on. Readers must treat `null` as "no context", which
    /// they have to anyway for the failures that never had one.
    private final transient ReadContext context;

    ContextualUncheckedIOException(String message, IOException cause, ReadContext context) {
        super(message, cause);
        this.context = context;
    }

    /// Where the read that failed had got to, or `null` after deserialisation.
    public ReadContext readContext() {
        return context;
    }
}
