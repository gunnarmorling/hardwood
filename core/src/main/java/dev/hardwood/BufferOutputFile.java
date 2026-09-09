/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood;

import java.nio.ByteBuffer;

/// An [OutputFile] that keeps the file it is written in memory and hands it back.
///
/// The destination for producing Parquet bytes without a filesystem: a file staged for an
/// object store, a payload put on a wire, or one written and read back in the same process.
/// Obtained from [OutputFile#inMemory()], and the counterpart of [InputFile#of(ByteBuffer)] —
/// what [#buffer()] returns is what that method takes:
///
/// ```java
/// BufferOutputFile out = OutputFile.inMemory();
/// try (ParquetFileWriter writer = ParquetFileWriter.create(out, schema)) {
///     // write the file
/// }
/// try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(out.buffer()))) {
///     // read it back
/// }
/// ```
///
/// The file is held on the heap in full, so the memory this needs is the size of the finished
/// file on top of what the writer holds for the row group it has open.
public interface BufferOutputFile extends OutputFile {

    /// The file that was written.
    ///
    /// The returned buffer holds the whole file and nothing else: its position is `0` and its
    /// limit and capacity are the number of bytes written, so it can be handed to
    /// [InputFile#of(ByteBuffer)] as it is. It is a view of the accumulated bytes rather than
    /// a copy of them, and reading it does not consume anything — each call returns a fresh
    /// view of the same bytes.
    ///
    /// @return the bytes of the finished file
    /// @throws IllegalStateException if the file has not been closed, or was discarded
    ByteBuffer buffer();
}
