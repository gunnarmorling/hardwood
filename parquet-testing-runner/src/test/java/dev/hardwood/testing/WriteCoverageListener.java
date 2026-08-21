/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/// Carries the run's coverage observations out of the test JVM.
///
/// The verdict cannot be an `@AfterAll`: it spans test classes, and it runs in a second Surefire
/// execution — a different JVM — so nothing static survives to be asserted on. This listener,
/// registered through `META-INF/services`, empties the output directory as the run starts and
/// writes what [CoverageRegistry] accumulated as it ends. [WriteCoverageVerdictTest] then merges
/// the files.
///
/// The verdict execution loads this listener too, and must not clear what it is about to read.
/// [#MODE_PROPERTY] is what tells the two apart: the verdict execution sets it to
/// [#VERIFY_MODE] and the listener stands down.
public final class WriteCoverageListener implements TestExecutionListener {

    /// The system property naming what this JVM is for.
    public static final String MODE_PROPERTY = "hardwood.writeCoverage";

    /// The value the verdict execution sets, under which nothing is recorded or cleared.
    public static final String VERIFY_MODE = "verify";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (recording()) {
            CoverageRegistry.clearOutput();
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (recording()) {
            CoverageRegistry.flush();
        }
    }

    private static boolean recording() {
        return !VERIFY_MODE.equals(System.getProperty(MODE_PROPERTY));
    }
}
