/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import dev.hardwood.InputFile;
import dev.hardwood.internal.ExceptionContext;
import dev.hardwood.jfr.FileOpenedEvent;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.schema.FileSchema;

/// Lazily opens input files and caches each parsed footer for one
/// [dev.hardwood.reader.ParquetFileReader].
public final class FileMetadataCache {

    private final List<InputFile> inputFiles;
    private final Object lifecycleLock = new Object();
    private final Map<Integer, CompletableFuture<PreparedFile>> fileFutures = new HashMap<>();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private boolean closeStarted;

    FileMetadataCache(List<InputFile> inputFiles) {
        if (inputFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one file must be provided");
        }
        this.inputFiles = List.copyOf(inputFiles);
    }

    public FileMetadataCache(List<InputFile> inputFiles, FileMetaData firstFileMetaData,
                             FileSchema firstFileSchema) {
        this(inputFiles);
        InputFile first = this.inputFiles.getFirst();
        PreparedFile prepared = new PreparedFile(first, firstFileMetaData, firstFileSchema,
                firstFileMetaData.rowGroups());
        seedFirstFile(prepared);
    }

    List<InputFile> inputFiles() {
        return inputFiles;
    }

    /// Seeds metadata already read by a caller that owns this cache. Existing
    /// complete metadata, such as the first footer read by ParquetFileReader,
    /// is never replaced by an iterator-specific row-group subset.
    void setFirstFile(FileSchema schema, List<RowGroup> rowGroups) {
        InputFile first = inputFiles.getFirst();
        PreparedFile prepared = new PreparedFile(first, null, schema, rowGroups);
        seedFirstFile(prepared);
    }

    public FileMetaData getFileMetaData(int fileIndex) throws IOException {
        PreparedFile prepared = getFileChecked(fileIndex);
        if (prepared.metaData() == null) {
            throw new IllegalStateException("File metadata is unavailable for index " + fileIndex);
        }
        return prepared.metaData();
    }

    /// Gets or loads a prepared file, blocking if necessary.
    ///
    /// Unwraps the [CompletionException] that [CompletableFuture#join] would
    /// otherwise wrap around the load failure, so callers see the original
    /// exception (e.g. [UncheckedIOException]) directly rather than as a
    /// `CompletionException` cause.
    PreparedFile getFile(int fileIndex) {
        CompletableFuture<PreparedFile> future = registerLoad(fileIndex, true);
        try {
            return future.join();
        }
        catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    PreparedFile getFileChecked(int fileIndex) throws IOException {
        try {
            return getFile(fileIndex);
        }
        catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    void prefetch(int fileIndex) {
        registerLoad(fileIndex, false);
    }

    /// Prevents new loads, waits for every admitted load, and releases cached
    /// results. Input-file ownership remains with the caller.
    public void close() {
        List<CompletableFuture<PreparedFile>> registeredLoads;
        synchronized (lifecycleLock) {
            if (closeStarted) {
                registeredLoads = null;
            }
            else {
                closeStarted = true;
                registeredLoads = List.copyOf(fileFutures.values());
            }
        }

        if (registeredLoads == null) {
            closeCompletion.join();
            return;
        }

        try {
            for (CompletableFuture<PreparedFile> future : registeredLoads) {
                try {
                    future.join();
                }
                catch (RuntimeException ignored) {
                }
            }
        }
        finally {
            synchronized (lifecycleLock) {
                fileFutures.clear();
            }
            closeCompletion.complete(null);
        }
    }

    private void seedFirstFile(PreparedFile prepared) {
        synchronized (lifecycleLock) {
            ensureOpen();
            fileFutures.putIfAbsent(0, CompletableFuture.completedFuture(prepared));
        }
    }

    private CompletableFuture<PreparedFile> registerLoad(int fileIndex, boolean required) {
        synchronized (lifecycleLock) {
            if (closeStarted) {
                if (required) {
                    throw new IllegalStateException("FileMetadataCache is closed");
                }
                return null;
            }
            if (fileIndex < 0 || fileIndex >= inputFiles.size()) {
                if (required) {
                    Objects.checkIndex(fileIndex, inputFiles.size());
                }
                return null;
            }
            return fileFutures.computeIfAbsent(fileIndex, this::loadFileAsync);
        }
    }

    private void ensureOpen() {
        if (closeStarted) {
            throw new IllegalStateException("FileMetadataCache is closed");
        }
    }

    private CompletableFuture<PreparedFile> loadFileAsync(int fileIndex) {
        return CompletableFuture.supplyAsync(() -> loadFile(fileIndex));
    }

    private PreparedFile loadFile(int fileIndex) {
        InputFile inputFile = inputFiles.get(fileIndex);
        try {
            inputFile.open();
        }
        catch (IOException e) {
            throw new UncheckedIOException(
                    ExceptionContext.filePrefix(inputFile.name()) + "Failed to open file", e);
        }

        FileOpenedEvent event = new FileOpenedEvent();
        event.begin();

        try {
            FileMetaData metaData = ParquetMetadataReader.readMetadata(inputFile);
            FileSchema schema = FileSchema.fromSchemaElements(metaData.schema());

            event.file = inputFile.name();
            event.fileSize = inputFile.length();
            event.rowGroupCount = metaData.rowGroups().size();
            event.columnCount = schema.getColumnCount();
            event.commit();

            return new PreparedFile(inputFile, metaData, schema, metaData.rowGroups());
        }
        catch (IOException e) {
            throw new UncheckedIOException(
                    ExceptionContext.filePrefix(inputFile.name()) + "Failed to read metadata", e);
        }
        catch (RuntimeException e) {
            throw ExceptionContext.addFileContext(inputFile.name(), e);
        }
    }

    record PreparedFile(
            InputFile inputFile,
            FileMetaData metaData,
            FileSchema schema,
            List<RowGroup> rowGroups
    ) {}
}
