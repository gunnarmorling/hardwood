/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

/// Thrown when the writer cannot produce the file, for a reason that is neither
/// the caller's misuse nor the destination failing.
///
/// A compression codec that rejects a buffer it was handed is the case this
/// covers: nothing the caller passed was wrong, nothing about the destination is
/// involved, and the same call will fail the same way next time.
///
/// **Trying again will not help.** That is what separates this from
/// [java.io.IOException], which the writer raises when writing to the destination
/// failed — a full disk, an S3 failure after its own retries have run out — and
/// where a second attempt may well succeed. A caller deciding whether to retry
/// can decide on the type alone.
///
/// Distinct again from the unchecked types the writer raises for misuse — an
/// unknown column, a value outside its annotation's range, a `REQUIRED` field
/// left unset, writing after `close()`. Those say the calling code is wrong; this
/// says the calling code is right and the file still could not be written.
///
/// Unchecked, because there is nothing for a caller to do at the point of failure.
public class ParquetWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ParquetWriteException(String message) {
        super(message);
    }

    public ParquetWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
