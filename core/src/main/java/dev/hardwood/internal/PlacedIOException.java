/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal;

import java.io.IOException;

/// A read that would not complete, knowing where it was.
///
/// The transport half of the same rule [dev.hardwood.reader.ParquetReadException]
/// follows: the place is taken when the failure is raised, because that is the
/// only moment it is known, and rendered into the message when it is read. A
/// caller catches [IOException] exactly as before.
public class PlacedIOException extends IOException {

    private static final long serialVersionUID = 1L;

    /// Not serialised — the message states the same facts, and an exception
    /// that has been through serialisation is being read rather than acted on.
    private final transient ReadScope.Place place;

    public PlacedIOException(String message) {
        super(message);
        this.place = ReadScope.current();
    }

    public PlacedIOException(String message, Throwable cause) {
        super(message, cause);
        this.place = ReadScope.current();
    }

    /// The message, behind where the read was.
    @Override
    public String getMessage() {
        String message = super.getMessage();
        return place == null ? message : place.describe() + message;
    }
}
