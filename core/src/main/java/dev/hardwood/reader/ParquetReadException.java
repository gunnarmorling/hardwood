/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.reader;

/// Thrown when a file's own bytes are wrong: the read reached them and they do
/// not say something a Parquet file can say.
///
/// A bad magic number, a footer that will not parse, a dictionary page the
/// metadata places outside its column chunk, a page whose checksum fails, values
/// that do not decode under the encoding the file declares for them.
///
/// **Trying again will not help.** That is what separates this from
/// [java.io.IOException], which the reader raises when the bytes did not arrive —
/// a disk error, an S3 failure after its own retries have run out — and where a
/// second attempt may well succeed. A caller deciding whether to retry can decide
/// on the type alone.
///
/// Distinct again from [UnsupportedOperationException], which the reader raises
/// for a file that is entirely correct and that this library cannot read: Parquet
/// Modular Encryption, an encoding not implemented, a compression codec whose
/// library is absent. Retrying will not help there either, but the remedy is a
/// dependency or a different tool rather than a different file.
///
/// Unchecked, because there is nothing for a caller to do at the point of failure.
public class ParquetReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ParquetReadException(String message) {
        super(message);
    }

    public ParquetReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
