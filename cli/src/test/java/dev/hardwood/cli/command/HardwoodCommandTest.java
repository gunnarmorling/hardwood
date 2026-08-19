/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.hardwood.cli.internal.Version;

import static org.assertj.core.api.Assertions.assertThat;

class HardwoodCommandTest {

    /// `hardwood <project-version> (<revision>)`. The project version is required to be
    /// semver-shaped, since Maven always supplies it; the revision is left open because it
    /// comes from `git` and legitimately reads `unknown` in a build outside a checkout.
    private static final Pattern VERSION_LINE = Pattern.compile("hardwood \\d+\\.\\d+\\.\\d+\\S* \\(\\S+\\)");

    @Test
    void helpFlagPrintsUsage() {
        Cli.Result result = Cli.launch("--help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("hardwood");
    }

    /// Asserts the printed version is a real one, not just the literal `hardwood` prefix the
    /// format string contributes — that prefix prints even when the build identity failed to
    /// resolve and every component degraded to `unknown`. Comparing the output against
    /// [Version#getVersion] alone would not catch that either: both sides read the same
    /// degraded value and agree. Only the shape does, so it is asserted first.
    @Test
    void versionFlagPrintsTheResolvedBuildVersion() {
        Cli.Result result = Cli.launch("--version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).matches(VERSION_LINE);
        assertThat(result.output()).isEqualTo("hardwood " + Version.getVersion());
    }

    @Test
    void unknownCommandExitsNonZero() {
        Cli.Result result = Cli.launch("frobnicate");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void failingCommandExitsNonZero() {
        Cli.Result result = Cli.launch("schema", "-f", "/no/such/file.parquet");
        assertThat(result.exitCode()).isNotZero();
    }
}
