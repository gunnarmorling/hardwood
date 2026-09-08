/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal;

/// A correct Parquet file this library will not read, naming the file it was.
///
/// Named but never positioned. The file is not at fault, so no byte of it is
/// worth putting in front of anyone, and the remedy — add a dependency, use
/// another tool — is the same wherever in the file the limit was met. That it
/// takes only the name where its siblings take a place is the whole difference,
/// and it follows from the type rather than from a decision at any raise site.
///
/// An [UnsupportedOperationException], because that is what the reader's
/// exception model says this is and what a caller already catches.
public class UnsupportedFileException extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    /// Not serialised — the message states the same thing.
    private final transient String fileName;

    public UnsupportedFileException(String message) {
        super(message);
        ReadScope.Place place = ReadScope.current();
        this.fileName = place == null ? null : place.fileName();
    }

    public UnsupportedFileException(String message, Throwable cause) {
        super(message, cause);
        ReadScope.Place place = ReadScope.current();
        this.fileName = place == null ? null : place.fileName();
    }

    /// The message, behind the file it was raised for.
    @Override
    public String getMessage() {
        return ExceptionContext.filePrefix(fileName) + super.getMessage();
    }
}
