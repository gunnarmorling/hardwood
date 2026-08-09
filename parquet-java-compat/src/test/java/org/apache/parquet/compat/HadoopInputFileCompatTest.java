/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.parquet.compat;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests that `HadoopInputFile`'s factory methods and accessors match their upstream counterparts.
class HadoopInputFileCompatTest {

    private static final String PATH = "../core/src/test/resources/plain_uncompressed.parquet";
    private static final String MISSING_PATH = "../core/src/test/resources/does_not_exist.parquet";

    @Test
    void fromPathReturnsConcreteType() throws IOException {
        // must compile against the concrete type, as upstream code does
        HadoopInputFile file = HadoopInputFile.fromPath(new Path(PATH), new Configuration());

        assertThat(file.getLength()).isPositive();
        assertThat(file.getPath().toUri().getPath()).endsWith("plain_uncompressed.parquet");
    }

    @Test
    void fromPathUncheckedReturnsConcreteType() {
        HadoopInputFile file = HadoopInputFile.fromPathUnchecked(new Path(PATH), new Configuration());

        // getLength() declares no checked exception, as upstream's does not: this method
        // compiles without a throws clause only because that signature matches
        assertThat(file.getLength()).isPositive();
    }

    @Test
    void exposesTheConfigurationItWasCreatedWith() throws IOException {
        Configuration conf = new Configuration();

        HadoopInputFile file = HadoopInputFile.fromPath(new Path(PATH), conf);

        assertThat(file.getConfiguration()).isSameAs(conf);
    }

    @Test
    void toStringNamesThePath() throws IOException {
        Path path = new Path(PATH);

        HadoopInputFile file = HadoopInputFile.fromPath(path, new Configuration());

        assertThat(file.toString()).isEqualTo(path.toString());
    }

    /// The `throws` clauses are part of the signature this shim has to match, but a caller
    /// that handles the exception compiles either way. Asserting them reflectively is what
    /// makes a regression fail the build.
    @Test
    void factoryAndAccessorSignaturesMatchUpstream() throws NoSuchMethodException {
        assertThat(HadoopInputFile.class.getMethod("fromPath", Path.class, Configuration.class)
                .getReturnType()).isEqualTo(HadoopInputFile.class);
        assertThat(HadoopInputFile.class.getMethod("fromPath", Path.class, Configuration.class)
                .getExceptionTypes()).containsExactly(IOException.class);
        assertThat(HadoopInputFile.class.getMethod("fromPathUnchecked", Path.class, Configuration.class)
                .getExceptionTypes()).isEmpty();
        assertThat(HadoopInputFile.class.getMethod("getLength").getExceptionTypes()).isEmpty();
    }

    @Test
    void fromPathFailsOnMissingFile() {
        assertThatThrownBy(() -> HadoopInputFile.fromPath(new Path(MISSING_PATH), new Configuration()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void fromPathUncheckedWrapsFailureOnMissingFile() {
        assertThatThrownBy(() -> HadoopInputFile.fromPathUnchecked(new Path(MISSING_PATH), new Configuration()))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
    }
}
