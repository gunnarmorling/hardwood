/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/// Produces a fixture under `target/` exactly once, however many JVMs ask for it.
///
/// Surefire runs this module's tests in several forks, which makes the obvious
/// `if (!Files.exists(f)) { create(f); }` guard wrong twice over. The forks race to
/// create the fixture, and `f` exists from the moment creation *starts*, so a fork
/// that loses the race does not wait — it reads a half-written file, or lists a
/// clone that is still being checked out.
///
/// Both follow from the fixture being visible before it is finished, so the producer
/// writes to a private path that is renamed into place once it returns: the fixture
/// only ever appears complete. One fork is elected to run it by creating a claim
/// file, `O_EXCL` being the one mutual exclusion a shared checkout can rely on —
/// advisory locks are silently ignored on a bind-mounted filesystem, where every
/// caller believes it holds the lock.
final class SharedFixture {

    /// How long a fork waits for the one producing the fixture. Generous, since that
    /// can be a network clone; a fork that dies mid-production leaves its claim behind,
    /// and waiting forever on it would look like a hung build rather than a failed one.
    private static final Duration PRODUCTION_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private SharedFixture() {
    }

    /// Produces `target` if it is not already there, and returns it.
    ///
    /// The producer is handed a path that does not exist and may create either a file
    /// or a directory tree at it. It runs at most once across all forks; the others
    /// block until it has finished and then find the result.
    static synchronized Path produceOnce(Path target, Producer producer) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);

        // A separate file rather than the fixture itself: the fixture must not exist
        // until it is complete, which is the whole point.
        Path claim = parent.resolve(target.getFileName() + ".claim");
        long deadline = System.nanoTime() + PRODUCTION_TIMEOUT.toNanos();

        while (!Files.exists(target)) {
            try {
                Files.createFile(claim);
            }
            catch (FileAlreadyExistsException e) {
                awaitProducingFork(target, claim, deadline);
                continue;
            }

            try {
                produceAndPublish(target, parent, producer);
            }
            finally {
                Files.deleteIfExists(claim);
            }
        }

        return target;
    }

    private static void produceAndPublish(Path target, Path parent, Producer producer)
            throws IOException {
        Path incomplete = parent.resolve(
                target.getFileName() + ".incomplete-" + ProcessHandle.current().pid());
        deleteRecursively(incomplete);
        try {
            producer.produce(incomplete);
            Files.move(incomplete, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException | RuntimeException e) {
            deleteRecursively(incomplete);
            throw e;
        }
    }

    private static void awaitProducingFork(Path target, Path claim, long deadline)
            throws IOException {
        if (System.nanoTime() - deadline >= 0) {
            throw new IOException(target + " was claimed by another fork that never produced it."
                    + " Delete " + claim + " and re-run.");
        }
        try {
            Thread.sleep(POLL_INTERVAL);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + target, e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(path)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /// Creates the fixture at the given path, which does not yet exist.
    @FunctionalInterface
    interface Producer {
        void produce(Path path) throws IOException;
    }
}
