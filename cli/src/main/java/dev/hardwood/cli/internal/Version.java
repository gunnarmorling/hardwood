/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.internal;

import dev.hardwood.internal.BuildInfo;

/// Resolves the human-readable version string for the CLI and TUI.
public final class Version {

    private static final String VERSION =
            Fmt.fmt("%s (%s)", BuildInfo.version(), BuildInfo.revisionWithDirtyMark());

    private Version() {
    }

    /// Returns the version in the form `<project-version> (<short-sha>[-dirty])`,
    /// e.g. `1.0.0-SNAPSHOT (a093aab-dirty)`. The `-dirty` suffix is appended when
    /// the working tree had any tracked or untracked changes at build time, and
    /// either component reads `unknown` when the build could not determine it.
    public static String getVersion() {
        return VERSION;
    }
}
