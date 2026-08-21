/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import dev.hardwood.OutputFile;
import dev.hardwood.internal.compression.Compressor;
import dev.hardwood.internal.encoding.LevelEncoder;
import dev.hardwood.internal.encoding.RleBitPackingHybridEncoder;
import dev.hardwood.internal.thrift.PageHeaderWriter;
import dev.hardwood.internal.thrift.ThriftCompactWriter;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.writer.ColumnEncoding;

/// Accumulates one column's chunk for the current row group in two phases.
///
/// **While records arrive**, the [RecordShredder] streams a record range's entries into this
/// buffer through [#accept] (as a [RecordShredder.LevelSink]). Nothing is encoded: the levels are
/// retained a byte per entry, each present value is interned into the dictionary or copied into
/// the [ValueEncoder]'s store, and a page is *cut* — planned, not produced — each time the entry
/// count reaches the page capacity, even part-way through a record.
///
/// **At flush**, the tail page is cut and the plan is encoded: the dictionary page where the
/// chunk has one, then each planned page's levels and values framed, compressed and written
/// straight to the output. Retaining the values rather than the encoded pages is what lets a
/// page's bytes be produced after the whole chunk is known; it costs no copy that streaming did
/// not already make, since a value is copied out of the caller's array either way.
///
/// This buffer owns the **type-agnostic** half of a column chunk — the repetition and
/// definition level streams, page cutting, compression, CRC, and (in dictionary mode) the
/// integer index stream. The **type-specific** half — reading the typed value, the `PLAIN`
/// value section, the dictionary, and statistics — lives in a per-type [ValueEncoder], driven
/// value by value as entries arrive. The shredder emits only source positions, so this buffer
/// never sees a typed value.
///
/// A page body is `[rep levels?][def levels?][value section]`, each level stream prefixed by its
/// 4-byte little-endian length. The value section is `[1-byte index bit width][RLE/bit-packed
/// indices]` for a dictionary-encoded chunk (`RLE_DICTIONARY`), and otherwise the present values
/// under whichever encoding the column's [ColumnEncoding] resolves to — `PLAIN`, one of the
/// delta encodings, or `BYTE_STREAM_SPLIT`. The assembled body is compressed with the chunk's
/// [Compressor] before framing, and
/// the page header records both the uncompressed and the stored compressed size; the dictionary
/// page body is compressed the same way. The CRC-32 is taken over the stored bytes, matching what
/// the reader validates.
///
/// **A chunk is encoded one way throughout**, under the column's [ColumnEncoding].
///
/// Under [ColumnEncoding#AUTO] the writer decides: present values are interned in first-seen
/// order through the [ValueEncoder], which makes the chunk's cardinality exact, and at flush the
/// dictionary page plus an index stream is weighed against the values as `PLAIN`; the smaller
/// wins, and a chunk that decides against a dictionary resolves its indices back into values and
/// writes no dictionary page. Each page still declares its own index bit width, sized from that
/// page's largest index rather than the dictionary's final size, so a page whose values sit low
/// in the dictionary pays only for the bits they need.
///
/// That same comparison also runs **while values arrive**, over the prefix the chunk holds, at
/// 8192 present values and doubling from there. A dictionary losing two probes running is given
/// up on the spot, so a column whose values are all distinct stops interning after a few
/// thousand of them rather than hashing a row group's worth into a table flush would reject.
/// The produced file is unaffected — a chunk abandoned early is one the flush comparison would
/// have decided against, and carries the same encoding either way.
///
/// Under any other policy the encoding is settled before a value arrives: no dictionary is built,
/// so the chunk pays neither the interning nor the index array and states no `distinct_count`.
/// Each value's `PLAIN` width is still accumulated, because that total is what bounds the
/// buffered row group and plans the page cuts — it is the writer's measure of buffered data, not
/// a term of the `AUTO` comparison alone.
final class ColumnChunkBuffer implements RecordShredder.LevelSink {

    private final PhysicalType type;
    private final int maxDefLevel;
    private final int maxRepLevel;
    private final long defLevelBits; // RLE level-stream bits charged per entry (0 when unlevelled)
    private final long repLevelBits;
    private long bufferedBits; // running uncompressed bit estimate across this row group's chunk
    private final int pageValues; // level entries per data page

    /// This chunk's repetition and definition levels, one unsigned byte per entry, `null` when the
    /// column is unlevelled. Levels are retained rather than encoded as they arrive because a
    /// page's level stream can only be cut once the page's boundaries are known at flush.
    private final ByteArrayBuilder repLevels;
    private final ByteArrayBuilder defLevels;

    /// This chunk's dictionary indices, for the entries encoded against the dictionary. Values
    /// the chunk did not intern live in the [ValueEncoder]'s store instead.
    private int[] indices;
    private int indexCount;
    private int plainCount;  // values appended to the value encoder's store
    private int entryCount;  // level entries accumulated across the chunk

    /// The page plan: how many level entries and how many present values each data page covers,
    /// appended as pages are cut while records arrive and consumed when the pages are encoded at
    /// flush. The whole chunk shares one encoding, so a page carries no encoding of its own.
    private int[] planEntries = new int[16];
    private int[] planValueCount = new int[16];
    private int planCount;
    private int plannedEntries;
    private int plannedValues;

    /// What this chunk's present values would occupy `PLAIN`-encoded, accumulated as they arrive:
    /// the other half of the encoding comparison.
    private long plainValueBits;

    /// The page body under construction, reused across the chunk's pages: a fresh builder per
    /// page would regrow from nothing to the page size every time.
    private final ByteArrayBuilder body = new ByteArrayBuilder();

    /// The RLE encoder every one of this chunk's streams runs through — the level streams and,
    /// for a dictionary-encoded chunk, each page's index stream. Reset per stream rather than
    /// allocated per stream, for the same reason [#body] is: each would otherwise regrow its
    /// buffer from 64 bytes to the stream's size, three times per page.
    private final RleBitPackingHybridEncoder rle = new RleBitPackingHybridEncoder(0);
    private long numValues;        // total level entries across the chunk's pages
    private long dataPagesUncompressedSize; // sum of header + uncompressed body across data pages
    private long dictionaryPageUncompressedSize; // header + uncompressed body of the dictionary page

    private final Compressor compressor;
    private final CompressionCodec codec;

    private final ValueEncoder values;
    private final boolean boundedStatistics; // whether min/max are well defined for this column's order
    /// How large this chunk's dictionary may grow before the analysis gives up on it. The bound
    /// exists to cap memory, not to choose an encoding: a column whose dictionary outgrows it is
    /// written `PLAIN` because its values are too many to be worth describing, which the
    /// comparison would have concluded anyway in all but a narrow band.
    private final long dictionaryAnalysisCapBytes;
    private boolean dictionaryAlive; // false once the chunk has given up its dictionary
    /// False once the chunk has thrown away what it was counting distinct values with, after
    /// which it can no longer state its cardinality and writes no `distinct_count`.
    private boolean cardinalityKnown = true;

    /// Present-value count at which this chunk's dictionary is next weighed against `PLAIN`,
    /// doubling after each probe. Counted in values rather than pages: [#pageValues] follows the
    /// schema's widest column, so a narrow schema's chunk would never reach a page boundary
    /// inside a row group and would never be probed.
    private int nextProbeValues = FIRST_PROBE_VALUES;
    /// Consecutive probes at which the dictionary was losing. The dictionary is given up on the
    /// second, which is what keeps a column whose distinct values are front-loaded — all
    /// distinct through one probe, repeating afterwards — from losing a dictionary that pays.
    private int losingProbes;

    /// Present values a chunk holds before its dictionary is first weighed against `PLAIN`. Far
    /// enough in that the dictionary's fixed cost is fairly sampled and the index bit width has
    /// settled; early enough that a chunk which abandons here never interns the rest.
    private static final int FIRST_PROBE_VALUES = 8192;

    /// Consecutive losing probes that give the dictionary up.
    private static final int LOSING_PROBES_TO_ABANDON = 2;

    /// This column's resolved encoding policy. [ColumnEncoding#AUTO] leaves the choice to the
    /// size comparison at flush; anything else names the encoding every chunk of this column
    /// carries, and no dictionary is built at all.
    private final ColumnEncoding encoding;

    /// @param column the column's schema (physical type, level depths)
    /// @param pageValues maximum number of level entries per data page
    /// @param encoding this column's resolved encoding policy
    /// @param dictionaryAnalysisCapBytes the dictionary size past which the chunk is written `PLAIN`
    /// @param compressor compresses each page body before framing
    /// @param codec the codec `compressor` applies, recorded in the chunk metadata
    ColumnChunkBuffer(ColumnSchema column, int pageValues,
                      ColumnEncoding encoding, long dictionaryAnalysisCapBytes, int statisticsTruncationLength,
                      Compressor compressor, CompressionCodec codec) {
        this.type = column.type();
        this.maxDefLevel = column.maxDefinitionLevel();
        this.maxRepLevel = column.maxRepetitionLevel();
        this.defLevelBits = maxDefLevel > 0 ? LevelEncoder.bitWidth(maxDefLevel) : 0;
        this.repLevelBits = maxRepLevel > 0 ? LevelEncoder.bitWidth(maxRepLevel) : 0;
        if (maxDefLevel > LevelEncoder.MAX_STORABLE_LEVEL || maxRepLevel > LevelEncoder.MAX_STORABLE_LEVEL) {
            throw new UnsupportedOperationException("Column " + column.name() + " nests deeper than the writer"
                    + " supports: levels must fit " + LevelEncoder.MAX_STORABLE_LEVEL);
        }
        this.pageValues = pageValues;
        this.indices = new int[Math.max(1, pageValues)];
        this.defLevels = maxDefLevel > 0 ? new ByteArrayBuilder(Math.max(1, pageValues)) : null;
        this.repLevels = maxRepLevel > 0 ? new ByteArrayBuilder(Math.max(1, pageValues)) : null;
        this.encoding = encoding;
        this.values = ValueEncoder.forColumn(column, pageValues, encoding, statisticsTruncationLength);
        this.boundedStatistics = StatisticsOrder.supportsBounds(column);
        this.dictionaryAlive = values.dictionaryCapable();
        this.dictionaryAnalysisCapBytes = dictionaryAnalysisCapBytes;
        this.compressor = compressor;
        this.codec = codec;
    }

    /// Binds the value encoder to this batch's source, then shreds records
    /// `[fromRecord, fromRecord + count)` of this column straight into the page buffers, sealing
    /// pages as they fill.
    void append(RecordShredder shredder, ColumnSource source, int columnIndex, int fromRecord, int count) {
        values.reset(source);
        shredder.shred(columnIndex, fromRecord, count, this);
    }

    @Override
    public void accept(int repetitionLevel, int definitionLevel, int valueIndex) {
        boolean present = valueIndex >= 0;
        if (maxRepLevel > 0) {
            repLevels.write(repetitionLevel);
        }
        if (maxDefLevel > 0) {
            defLevels.write(definitionLevel);
        }
        bufferedBits += repLevelBits + defLevelBits;
        if (present) {
            if (dictionaryAlive) {
                if (indexCount == indices.length) {
                    indices = Arrays.copyOf(indices, ValueEncoder.grownCapacity(indices.length));
                }
                indices[indexCount++] = values.intern(valueIndex);
                if (values.dictionaryPlainBytes() > dictionaryAnalysisCapBytes) {
                    giveUpDictionary();
                }
            }
            else {
                values.store(valueIndex);
                plainCount++;
            }
            values.stat(valueIndex);
            long valueBits = values.valueBits(valueIndex);
            plainValueBits += valueBits;
            bufferedBits += valueBits;
            // After the running totals take this value, so the probe weighs exactly what the
            // chunk holds — the same predicate flush applies, on a prefix.
            if (dictionaryAlive && indexCount == nextProbeValues) {
                probeDictionary();
            }
        }
        else {
            values.statNull();
        }
        entryCount++;
        if (entryCount - plannedEntries == pageValues) {
            planPage();
        }
    }

    /// Weighs the dictionary against `PLAIN` over the values interned so far and gives it up once
    /// it has lost [#LOSING_PROBES_TO_ABANDON] probes in a row, so a column whose values are all
    /// distinct stops interning after a few thousand of them instead of hashing a row group's
    /// worth into a table the flush comparison then rejects.
    ///
    /// Losing twice in a row rather than once is what separates a column that is genuinely
    /// all-distinct from one whose distinct values are merely front-loaded. The latter looks the
    /// same at the first probe and has stopped minting new values by the second, where its
    /// distinct ratio has halved and the comparison has swung back to the dictionary; it keeps
    /// what it has and is decided at flush, as it is when no probe fires at all.
    private void probeDictionary() {
        if (dictionaryWins()) {
            losingProbes = 0;
        }
        else if (++losingProbes == LOSING_PROBES_TO_ABANDON) {
            giveUpDictionary();
            return;
        }
        nextProbeValues *= 2;
    }

    /// Turns the values interned so far back into stored values and releases the dictionary, so
    /// the chunk carries its values once rather than twice. Called when the dictionary outgrows
    /// the analysis cap, when a probe has found it losing twice running, and at flush when the
    /// comparison decides against it.
    private void giveUpDictionary() {
        for (int i = 0; i < indexCount; i++) {
            values.storeDictionaryValue(indices[i]);
        }
        plainCount += indexCount;
        indexCount = 0;
        values.dropDictionary();
        dictionaryAlive = false;
        cardinalityKnown = false;
    }

    /// Starts the next row group's chunk of this column, keeping every buffer this one grew: the
    /// level stores, the index store, the page plan and the value encoder's store are emptied by
    /// resetting their counts, and the dictionary and statistics — which describe one chunk and
    /// must never carry into the next — start over.
    void reset() {
        if (repLevels != null) {
            repLevels.reset();
        }
        if (defLevels != null) {
            defLevels.reset();
        }
        values.startChunk();
        indexCount = 0;
        plainCount = 0;
        entryCount = 0;
        planCount = 0;
        plannedEntries = 0;
        plannedValues = 0;
        numValues = 0;
        bufferedBits = 0;
        plainValueBits = 0;
        dataPagesUncompressedSize = 0;
        dictionaryPageUncompressedSize = 0;
        dictionaryAlive = values.dictionaryCapable();
        cardinalityKnown = true;
        nextProbeValues = FIRST_PROBE_VALUES;
        losingProbes = 0;
    }

    /// A running estimate of this chunk's buffered uncompressed bits — each entry's level bits
    /// plus each present value's `PLAIN` width. The row-group writer sums this across columns to
    /// decide when to flush, the byte-sized replacement for a fixed rows-per-group proxy once
    /// variable-width values make the per-row cost non-constant.
    long bufferedBits() {
        return bufferedBits;
    }

    /// Cuts the trailing page, then encodes and writes the whole column chunk — dictionary page
    /// (when present) then data pages — to `out` starting at `chunkStartOffset`, and returns its
    /// metadata. The caller captures `chunkStartOffset` before invoking this.
    ColumnMetaData flushTo(OutputFile out, ColumnSchema column, long chunkStartOffset) throws IOException {
        planPage();
        // Read the cardinality before the dictionary can be given up below: a chunk that still
        // has the structure it counted with can state the count exactly, whichever encoding the
        // comparison then chooses.
        long distinctCount = cardinalityKnown ? values.exactDistinctCount() : ValueEncoder.UNKNOWN_DISTINCT_COUNT;
        boolean hasDictionary = dictionaryWins();
        if (!hasDictionary && indexCount > 0) {
            // The values are interned but the chunk is not paying for a dictionary, so resolve
            // them back into stored values and encode those.
            giveUpDictionary();
        }
        int dictionarySize = hasDictionary ? values.dictionarySize() : 0;
        long dataPageOffset = chunkStartOffset;
        Long dictionaryPageOffset = null;
        long dictionaryCompressedSize = 0;
        long dictionaryUncompressedSize = 0;
        if (hasDictionary) {
            byte[] dictionaryPage = buildDictionaryPage();
            dictionaryPageOffset = chunkStartOffset;
            dataPageOffset = chunkStartOffset + dictionaryPage.length;
            dictionaryCompressedSize = dictionaryPage.length;
            dictionaryUncompressedSize = dictionaryPageUncompressedSize;
            out.write(ByteBuffer.wrap(dictionaryPage));
        }
        long dataPagesCompressedSize = 0;
        int entryFrom = 0;
        int valueFrom = 0;
        for (int page = 0; page < planCount; page++) {
            dataPagesCompressedSize += writeDataPage(out, entryFrom, planEntries[page], valueFrom,
                    planValueCount[page], dictionarySize);
            entryFrom += planEntries[page];
            valueFrom += planValueCount[page];
            numValues += planEntries[page];
        }
        // Page headers are stored uncompressed either way; only the bodies differ, so the
        // compressed total is what was actually written and the uncompressed total restores
        // each body to its pre-compression size.
        long totalCompressed = dictionaryCompressedSize + dataPagesCompressedSize;
        long totalUncompressed = dictionaryUncompressedSize + dataPagesUncompressedSize;
        return new ColumnMetaData(
                type,
                encodings(hasDictionary),
                column.fieldPath(),
                codec,
                numValues,
                totalUncompressed,
                totalCompressed,
                Map.of(),
                dataPageOffset,
                dictionaryPageOffset,
                statistics(distinctCount),
                null,
                null,
                null,
                // The writer does not emit encoding_stats or size_statistics yet.
                List.of(),
                null);
    }

    /// Whether the dictionary is worth keeping over what this chunk holds: the dictionary body
    /// plus an index stream at the bit width its cardinality needs, against the values written
    /// `PLAIN`. At flush this decides the chunk's encoding; against a prefix it is what
    /// [#probeDictionary] weighs, which is the same question asked earlier and of less data.
    /// Sizes are compared uncompressed — comparing compressed sizes would
    /// mean trial-compressing both forms, which is not repaid by the rare chunk where compression
    /// reverses the ranking — and the index stream's run headers are left out of the estimate,
    /// being a fraction of a percent of it.
    private boolean dictionaryWins() {
        if (!dictionaryAlive || values.dictionarySize() == 0) {
            return false;
        }
        long indexBits = (long) indexCount * LevelEncoder.bitWidth(values.dictionarySize() - 1);
        long dictionaryBytes = values.dictionaryPlainBytes() + ceilDiv(indexBits, Byte.SIZE);
        return dictionaryBytes < ceilDiv(plainValueBits, Byte.SIZE);
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    /// The chunk statistics, carrying the exact distinct count where the chunk knows it, and with
    /// the bounds dropped when this column's sort order is not the one its collector computes in.
    /// See [StatisticsOrder].
    private Statistics statistics(long distinctCount) {
        Statistics statistics = values.statistics();
        if (distinctCount != ValueEncoder.UNKNOWN_DISTINCT_COUNT) {
            statistics = new Statistics(statistics.minValue(), statistics.maxValue(), statistics.nullCount(),
                    distinctCount, statistics.isMinMaxDeprecated(), statistics.isMinValueExact(),
                    statistics.isMaxValueExact(), statistics.nanCount());
        }
        return boundedStatistics ? statistics : StatisticsOrder.withoutBounds(statistics);
    }

    /// Cuts a page at the entries accumulated since the last cut, recording how many present
    /// values it covers. Nothing is encoded here: a page's bytes are produced at flush, once the
    /// chunk's encoding has been chosen.
    private void planPage() {
        int entries = entryCount - plannedEntries;
        if (entries == 0) {
            return;
        }
        if (planCount == planEntries.length) {
            int grown = ValueEncoder.grownCapacity(planEntries.length);
            planEntries = Arrays.copyOf(planEntries, grown);
            planValueCount = Arrays.copyOf(planValueCount, grown);
        }
        planEntries[planCount] = entries;
        planValueCount[planCount] = indexCount + plainCount - plannedValues;
        planCount++;
        plannedEntries = entryCount;
        plannedValues = indexCount + plainCount;
    }

    /// Encodes one planned page and writes it straight to `out`, returning the bytes written.
    /// Data pages are streamed rather than buffered: the chunk's encoding is settled before any
    /// of them is produced, so nothing has to be revisited once written.
    private long writeDataPage(OutputFile out, int entryFrom, int entries, int valueFrom, int valueCount,
                               int dictionarySize) throws IOException {
        buildBody(entryFrom, entries, valueFrom, valueCount, dictionarySize);
        int uncompressedLength = body.length();
        // UNCOMPRESSED stores the body as it stands, so the page is written straight out of the
        // body buffer; every other codec produces its own array.
        byte[] storedArray;
        int storedLength;
        if (codec == CompressionCodec.UNCOMPRESSED) {
            storedArray = body.array();
            storedLength = uncompressedLength;
        }
        else {
            storedArray = compress(body.array(), 0, uncompressedLength);
            storedLength = storedArray.length;
        }
        // CRC-32 over the page body as stored on disk (compressed), matching what the reader
        // validates.
        CRC32 crc = new CRC32();
        crc.update(storedArray, 0, storedLength);
        ThriftCompactWriter header = new ThriftCompactWriter();
        Encoding valuesEncoding = dictionarySize > 0 ? Encoding.RLE_DICTIONARY : valueEncoding();
        PageHeaderWriter.writeDataPageV1(header, entries, uncompressedLength, storedLength,
                (int) crc.getValue(), valuesEncoding);
        byte[] headerBytes = header.toByteArray();
        out.write(ByteBuffer.wrap(headerBytes));
        out.write(ByteBuffer.wrap(storedArray, 0, storedLength));
        dataPagesUncompressedSize += headerBytes.length + uncompressedLength;
        return (long) headerBytes.length + storedLength;
    }

    /// Frames one page's body: the repetition and definition level streams (each RLE, each
    /// prefixed by a 4-byte little-endian length) ahead of the value section. For a dictionary
    /// page the value section is a 1-byte index bit width followed by the RLE/bit-packed indices
    /// (running to the page end, not length-prefixed); otherwise it is the page's present values
    /// under [#storedEncoding]. `dictionarySize` is the chunk's dictionary size, which says only
    /// whether the chunk is dictionary-encoded; the page's index bit width comes from its own
    /// largest index.
    private void buildBody(int entryFrom, int entries, int valueFrom, int valueCount, int dictionarySize) {
        body.reset();
        if (maxRepLevel > 0) {
            writeLevels(body, repLevels, entryFrom, entries, maxRepLevel);
        }
        if (maxDefLevel > 0) {
            writeLevels(body, defLevels, entryFrom, entries, maxDefLevel);
        }
        if (dictionarySize > 0) {
            // Each page declares its own index bit width, so a page carries only the bits its own
            // largest index needs rather than the bits the dictionary's final size would need.
            // A page with no present value has no index to size, and falls back to the chunk's.
            int bitWidth = valueCount > 0
                    ? LevelEncoder.bitWidth(maxIndex(valueFrom, valueCount))
                    : LevelEncoder.bitWidth(dictionarySize - 1);
            body.write(bitWidth);
            rle.reset(bitWidth);
            rle.writeInts(indices, valueFrom, valueCount);
            rle.writeTo(body);
        }
        else {
            values.encodeInto(body, storedEncoding(), valueFrom, valueCount);
        }
    }

    /// The encoding a chunk without a dictionary writes its values with: the column's policy,
    /// with [ColumnEncoding#AUTO] resolving to `PLAIN` — the outcome of a comparison the
    /// dictionary lost, or of a chunk with nothing to intern.
    private ColumnEncoding storedEncoding() {
        return encoding == ColumnEncoding.AUTO ? ColumnEncoding.PLAIN : encoding;
    }

    /// The metadata [Encoding] naming what [#storedEncoding] produces.
    private Encoding valueEncoding() {
        return switch (storedEncoding()) {
            case PLAIN -> Encoding.PLAIN;
            case DELTA_BINARY_PACKED -> Encoding.DELTA_BINARY_PACKED;
            case DELTA_LENGTH_BYTE_ARRAY -> Encoding.DELTA_LENGTH_BYTE_ARRAY;
            case DELTA_BYTE_ARRAY -> Encoding.DELTA_BYTE_ARRAY;
            case BYTE_STREAM_SPLIT -> Encoding.BYTE_STREAM_SPLIT;
            case AUTO -> throw new IllegalStateException("AUTO resolves before it is written");
        };
    }

    /// The largest dictionary index in one page's value range.
    private int maxIndex(int valueFrom, int valueCount) {
        int max = 0;
        for (int i = valueFrom; i < valueFrom + valueCount; i++) {
            max = Math.max(max, indices[i]);
        }
        return max;
    }

    /// Builds the dictionary page: a `DICTIONARY_PAGE` header over the distinct values,
    /// `PLAIN`-encoded in index order and compressed with the chunk's codec. Records the page's
    /// uncompressed size (header plus uncompressed body) for the chunk metadata.
    private byte[] buildDictionaryPage() {
        byte[] body = values.encodeDictionaryBody();
        byte[] stored = codec == CompressionCodec.UNCOMPRESSED ? body : compress(body, 0, body.length);
        CRC32 crc = new CRC32();
        crc.update(stored);
        ThriftCompactWriter header = new ThriftCompactWriter();
        PageHeaderWriter.writeDictionaryPageV1(header, values.dictionarySize(), body.length, stored.length,
                (int) crc.getValue(), Encoding.PLAIN);
        byte[] headerBytes = header.toByteArray();
        dictionaryPageUncompressedSize = headerBytes.length + body.length;
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        page.writeBytes(headerBytes);
        page.writeBytes(stored);
        return page.toByteArray();
    }

    /// Compresses a page body with the chunk's codec. Compressing an in-memory buffer that
    /// fails is unrecoverable, so a codec error surfaces as an unchecked exception rather than
    /// forcing a checked-exception path through the [RecordShredder.LevelSink] callback.
    private byte[] compress(byte[] data, int offset, int length) {
        try {
            return compressor.compress(data, offset, length);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to " + codec + "-compress a page body", e);
        }
    }

    /// The deduplicated set of encodings the chunk actually uses: `RLE` for the level streams
    /// where the column is levelled, the encoding its data pages carry, and `PLAIN` additionally
    /// where a dictionary page is present, that body being `PLAIN` itself.
    ///
    /// `PLAIN` is listed only when something in the chunk is actually plain. Listing it
    /// unconditionally was true while a chunk was either `PLAIN` or a dictionary whose body is,
    /// and stops being true of a chunk written with an optional encoding.
    private List<Encoding> encodings(boolean hasDictionary) {
        List<Encoding> encodings = new ArrayList<>(3);
        if (maxDefLevel > 0 || maxRepLevel > 0) {
            encodings.add(Encoding.RLE);
        }
        if (hasDictionary) {
            encodings.add(Encoding.PLAIN);
            encodings.add(Encoding.RLE_DICTIONARY);
        }
        else {
            encodings.add(valueEncoding());
        }
        return encodings;
    }

    /// Appends one level stream to the page body: a 4-byte little-endian length, then the stream.
    /// The length is only known once the stream is encoded, so its four bytes are reserved and
    /// filled afterwards rather than the stream being built elsewhere to be measured and copied.
    private void writeLevels(ByteArrayBuilder body, ByteArrayBuilder levels, int from, int count,
                             int maxLevel) {
        int lengthAt = body.reserve(Integer.BYTES);
        int start = body.length();
        rle.reset(LevelEncoder.bitWidth(maxLevel));
        byte[] stored = levels.array();
        for (int i = 0; i < count; i++) {
            rle.writeInt(stored[from + i] & 0xFF);
        }
        rle.writeTo(body);
        // After the stream has been written: appending may have replaced the backing array.
        writeIntLittleEndian(body.array(), lengthAt, body.length() - start);
    }

    private static void writeIntLittleEndian(byte[] dest, int at, int value) {
        dest[at] = (byte) value;
        dest[at + 1] = (byte) (value >>> 8);
        dest[at + 2] = (byte) (value >>> 16);
        dest[at + 3] = (byte) (value >>> 24);
    }
}
