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
/// registered through `META-INF/services`, writes what [CoverageRegistry] accumulated as the run
/// ends; [WriteCoverageVerdictTest] then merges the files.
///
/// Emptying the directory of a previous run is the build's job, not this listener's: Surefire may
/// fork per class and every fork loads the same listener, so one that cleared on start would
/// delete the files its siblings had written. The module's POM clears it once, before either
/// execution.
///
/// The verdict execution loads this listener too, and must not add its own cells to what it is
/// about to judge. [#MODE_PROPERTY] is what tells the two apart: the verdict execution sets it to
/// [#VERIFY_MODE] and the listener stands down.
public final class WriteCoverageListener implements TestExecutionListener {

    /// The system property naming what this JVM is for.
    public static final String MODE_PROPERTY = "hardwood.writeCoverage";

    /// The value the verdict execution sets, under which nothing is recorded or cleared.
    public static final String VERIFY_MODE = "verify";

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
