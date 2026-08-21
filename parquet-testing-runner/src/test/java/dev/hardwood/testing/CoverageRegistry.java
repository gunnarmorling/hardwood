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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.parquet.column.Encoding;

import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.PhysicalType;

import static dev.hardwood.testing.Coverage.BoundaryClass;
import static dev.hardwood.testing.Coverage.RepetitionShape;
import static dev.hardwood.testing.Coverage.StorageForm;

/// What the write-path tests actually produced, accumulated across a whole test run.
///
/// Recording happens in [ParquetJavaReader], which every write-path test already funnels its
/// file through, so no test opts in and one added later contributes by running. What is recorded
/// is what parquet-java found in the bytes — the encoding each data page declared, the codec and
/// null count the footer reports, the annotation its schema carries — never what the test
/// intended to write. A writer that quietly stopped producing an encoding would still satisfy a
/// registry keyed on intent.
///
/// The exception is [#observeBoundary], which has no file to read: an annotation's declared
/// range governs values on their way *into* the writer, so a value refused produces no bytes at
/// all. Those cells are recorded by the test that offers the value.
///
/// The run's cells are flushed to a file by [WriteCoverageListener], because the verdict runs in
/// a second Surefire execution — a different JVM — and because Surefire may fork this one.
final class CoverageRegistry {

    private CoverageRegistry() {
    }

    /// The directory the run's cells are flushed to, under the module's build output so that a
    /// clean build starts from nothing.
    static final Path OUTPUT_DIRECTORY = Paths.get("target", "write-coverage");

    private static final Set<String> CELLS = ConcurrentHashMap.newKeySet();

    /// Files already walked, so that a test calling more than one [ParquetJavaReader] entry
    /// point on the same file walks it once.
    private static final Set<String> WALKED_FILES = ConcurrentHashMap.newKeySet();

    /// Claims `file` for observation.
    ///
    /// @param file the file about to be walked
    /// @return `true` for the first caller, `false` once it has been walked
    static boolean claim(Path file) {
        return WALKED_FILES.add(file.toAbsolutePath().toString());
    }

    /// Records one column chunk, as the footer and the page walk describe it.
    ///
    /// @param type the column's physical type
    /// @param typeLength its declared length, or `null` where the type has none
    /// @param annotationKey the column's annotation, spelled by [LogicalTypeKey]
    /// @param pageEncodings the encodings this chunk's data pages declared
    /// @param codec the codec its page bodies are compressed with
    /// @param shape its repetition shape, or `null` where the chunk does not state the null count
    ///        the three `OPTIONAL` shapes are told apart by
    static void observeColumnChunk(PhysicalType type, Integer typeLength, String annotationKey,
            Set<Encoding> pageEncodings, CompressionCodec codec, RepetitionShape shape) {

        for (Encoding encoding : pageEncodings) {
            CELLS.add(Coverage.typeEncoding(type, encoding));
            CELLS.add(Coverage.encodingCodec(encoding, codec));
            if (typeLength != null) {
                CELLS.add(Coverage.fixedLengthEncoding(typeLength, encoding));
            }
        }
        if (shape != null) {
            CELLS.add(Coverage.typeRepetition(type, shape));
        }

        StorageForm form = pageEncodings.contains(Encoding.RLE_DICTIONARY)
                ? StorageForm.DICTIONARY
                : StorageForm.NO_DICTIONARY;
        CELLS.add(Coverage.annotationStorage(annotationKey, Coverage.carrier(type, typeLength), form));
    }

    /// Records a group node's annotation, which has no chunk of its own.
    ///
    /// @param annotationKey the annotation, spelled by [LogicalTypeKey]
    static void observeGroupAnnotation(String annotationKey) {
        CELLS.add(Coverage.annotationStorage(annotationKey, StorageForm.GROUP.name(), StorageForm.GROUP));
    }

    /// Records that an annotation was exercised at one boundary class.
    ///
    /// @param annotationKey the annotation, spelled by [LogicalTypeKey]
    /// @param carrier the carrier's spelling, from [Coverage#carrier]
    /// @param boundary where in its range the value sat, or how one outside it was refused
    static void observeBoundary(String annotationKey, String carrier, BoundaryClass boundary) {
        CELLS.add(Coverage.annotationBoundary(annotationKey, carrier, boundary));
    }

    /// The cells recorded so far in this JVM.
    static Set<String> cells() {
        return Set.copyOf(CELLS);
    }

    /// Empties [#OUTPUT_DIRECTORY] of a previous run's observations, so that a cell covered then
    /// and not now is reported as the gap it has become.
    ///
    /// @throws UncheckedIOException if a stale file cannot be removed
    static void clearOutput() {
        try {
            if (!Files.isDirectory(OUTPUT_DIRECTORY)) {
                return;
            }
            try (Stream<Path> stale = Files.list(OUTPUT_DIRECTORY)) {
                for (Path file : stale.toList()) {
                    Files.delete(file);
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot clear stale write-path coverage observations", e);
        }
    }

    /// Writes this JVM's cells to [#OUTPUT_DIRECTORY], one per line, under a name unique to the
    /// process so that forked JVMs do not overwrite one another.
    ///
    /// @throws UncheckedIOException if the file cannot be written, which would leave the verdict
    ///         reporting gaps that were in fact covered
    static void flush() {
        if (CELLS.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(OUTPUT_DIRECTORY);
            List<String> sorted = new ArrayList<>(new TreeSet<>(CELLS));
            Files.write(OUTPUT_DIRECTORY.resolve("cells-" + ProcessHandle.current().pid() + ".txt"),
                    sorted, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot flush write-path coverage observations", e);
        }
    }
}
