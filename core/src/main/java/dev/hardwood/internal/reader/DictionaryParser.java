/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.nio.ByteBuffer;

import dev.hardwood.internal.compression.Decompressor;
import dev.hardwood.internal.metadata.PageHeader;
import dev.hardwood.internal.thrift.PageHeaderReader;
import dev.hardwood.internal.thrift.ThriftCompactReader;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.PageType;
import dev.hardwood.reader.ParquetReadException;
import dev.hardwood.schema.ColumnSchema;

/// Parses dictionary pages from column chunk data.
///
/// Extracted from [PageScanner] so that both [IndexedFetchPlan] and
/// [PageScanner] can reuse the same parsing logic.
public final class DictionaryParser {

    private DictionaryParser() {}

    /// Total byte length of the dictionary page starting at the beginning of `region` — its header
    /// plus its compressed body — or `-1` when the region does not begin with a dictionary page.
    ///
    /// A page header states its own body length, so a caller holding only the page's start offset
    /// can read a bounded probe, call this, and learn exactly how many bytes the page occupies
    /// without knowing where the following page begins.
    ///
    /// @param region buffer whose first bytes are a page header
    /// @return the page's total length in bytes, or `-1` if it is not a dictionary page
    public static int pageLength(ByteBuffer region) {
        ThriftCompactReader reader = new ThriftCompactReader(region, 0);
        PageHeader header = PageHeaderReader.read(reader);

        if (header.type() != PageType.DICTIONARY_PAGE) {
            return -1;
        }
        return reader.getBytesRead() + header.compressedPageSize();
    }

    /// Parses a dictionary page from a byte region covering the dictionary area
    /// of a column chunk (the bytes between the chunk start and the first data page).
    ///
    /// @param dictRegion buffer covering the dictionary region
    /// @param columnSchema the column schema (for type info)
    /// @param metaData the column metadata (for codec)
    /// @param context the hardwood context (for decompressor)
    /// @return the parsed dictionary, or `null` if no dictionary page is found
    public static Dictionary parse(ByteBuffer dictRegion, ColumnSchema columnSchema,
                            ColumnMetaData metaData, HardwoodContextImpl context) {
        ThriftCompactReader probeReader = new ThriftCompactReader(dictRegion, 0);
        PageHeader header = PageHeaderReader.read(probeReader);

        if (header.type() != PageType.DICTIONARY_PAGE) {
            return null;
        }

        int headerSize = probeReader.getBytesRead();
        int compressedSize = header.compressedPageSize();
        ByteBuffer compressedData = dictRegion.slice(headerSize, compressedSize);

        if (header.crc() != null) {
            CrcValidator.assertCorrectCrc(header.crc(), compressedData, columnSchema.name());
        }

        return decompress(compressedData, header.dictionaryPageHeader().numValues(),
                header.uncompressedPageSize(), columnSchema, metaData.codec(), context);
    }

    /// Parses a dictionary from a buffer given the chunk layout. Locates the
    /// dictionary region between `dictAreaStart` and `firstDataPageOffset`,
    /// slices it from the buffer, and parses.
    ///
    /// @param buffer the buffer containing the chunk data
    /// @param bufferFileOffset absolute file offset of the buffer's start
    /// @param dictAreaStart absolute file offset where the dictionary region starts
    /// @param firstDataPageOffset absolute file offset of the first data page
    /// @param columnSchema the column schema
    /// @param metaData the column metadata
    /// @param context the hardwood context
    /// @return the parsed dictionary, or `null` if no dictionary
    static Dictionary parseFromBuffer(ByteBuffer buffer, long bufferFileOffset,
                                       long dictAreaStart, long firstDataPageOffset,
                                       ColumnSchema columnSchema, ColumnMetaData metaData,
                                       HardwoodContextImpl context) {
        int dictRelOffset = Math.toIntExact(dictAreaStart - bufferFileOffset);
        int dictRegionSize = Math.toIntExact(firstDataPageOffset - dictAreaStart);
        ByteBuffer dictRegion = buffer.slice(dictRelOffset, dictRegionSize);
        return parse(dictRegion, columnSchema, metaData, context);
    }

    private static Dictionary decompress(ByteBuffer compressedData, int numValues,
                                          int uncompressedSize, ColumnSchema column,
                                          CompressionCodec codec,
                                          HardwoodContextImpl context) {
        // An absent codec library leaves as it arrived. It names the dependency to
        // add, which is the only actionable thing about it, and it is not the file
        // being wrong — so it must not be caught and re-typed as one.
        Decompressor decompressor = context.decompressorFactory().getDecompressor(codec);
        try {
            byte[] data = decompressor.decompress(compressedData, uncompressedSize);
            return Dictionary.parse(data, numValues, column.type(), column.typeLength());
        }
        catch (UnsupportedOperationException e) {
            // A decompressor that exists but will not run here — libdeflate below Java 22 —
            // says the same thing an absent one does, so it leaves as it arrived.
            throw e;
        }
        catch (RuntimeException e) {
            throw new ParquetReadException("Failed to parse dictionary for column '" + column.name()
                    + "' (type=" + column.type()
                    + ", numValues=" + numValues
                    + ", uncompressedSize=" + uncompressedSize
                    + ", compressedSize=" + compressedData.remaining()
                    + ", codec=" + codec + ")", e);
        }
    }
}
