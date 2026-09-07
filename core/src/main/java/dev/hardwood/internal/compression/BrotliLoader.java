/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.compression;

import java.io.IOException;

import com.aayushatharva.brotli4j.Brotli4jLoader;

/// Loads brotli4j's native library on first use, for both directions of the BROTLI codec.
///
/// The load is what fails when the artifact is on the classpath but carries no binary for this
/// platform, so its `UnsatisfiedLinkError` becomes the [IOException] the compress and
/// decompress paths already declare rather than an error escaping through them.
final class BrotliLoader {

    private static volatile boolean loaded;

    private BrotliLoader() {
    }

    /// Ensures brotli4j's native library is loaded. Every page body of a BROTLI file passes
    /// through here, so the loaded case reads the volatile flag rather than taking the lock.
    ///
    /// @throws UnsupportedOperationException if the native library cannot be loaded
    static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            Brotli4jLoader.ensureAvailability();
            loaded = true;
        }
        catch (UnsatisfiedLinkError e) {
            // The file is correct and this library cannot handle it, which is the
            // same answer as an absent codec dependency: a remedy in the build,
            // not a retry. See CodecLibraries#require.
            throw new UnsupportedOperationException(
                    "Cannot handle BROTLI-compressed Parquet file: native library failed to load. "
                            + e.getMessage(), e);
        }
    }
}
