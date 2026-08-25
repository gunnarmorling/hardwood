/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.hardwood.internal.compression.CodecLibraries;
import dev.hardwood.metadata.CompressionCodec;

/// Tuning knobs for [ParquetFileWriter].
///
/// The two size targets bound the writer's output granularity and peak memory:
///
/// - **Page target** — the writer splits a column chunk into data pages of at most this
///   many uncompressed bytes.
/// - **Row-group target** — the writer flushes a row group once its buffered
///   uncompressed data reaches this many bytes, bounding how much it holds in memory.
///
/// How a column's values are stored is a [ColumnEncoding], set file-wide or per leaf column;
/// how the resulting page bodies are compressed is the [CompressionCodec]. Both are configured
/// here rather than on the schema.
///
/// A file's `created_by` identifier and its key-value metadata are set on [ParquetFileWriter].
///
/// Obtain the defaults with [#defaults] or override individual knobs through [#builder].
public final class WriterConfig {

    /// Default page target: 1 MiB of uncompressed values per data page.
    public static final int DEFAULT_PAGE_TARGET_BYTES = 1 << 20;

    /// Default row-group target: 128 MiB of uncompressed data per row group.
    public static final long DEFAULT_ROW_GROUP_TARGET_BYTES = 128L << 20;

    /// Default statistics truncation length: `BYTE_ARRAY` `min` / `max` bounds longer than
    /// 64 bytes are truncated and flagged inexact.
    public static final int DEFAULT_STATISTICS_TRUNCATION_LENGTH = 64;

    /// Default page compression codec: `ZSTD` when the zstd-jni library is on the classpath,
    /// otherwise `UNCOMPRESSED`. Choosing a codec explicitly through [Builder#codec] still
    /// requires that codec's library and fails at writer creation when it is missing; this
    /// default only avoids imposing the ZSTD dependency on callers who did not ask to compress.
    public static final CompressionCodec DEFAULT_CODEC = defaultCodec();

    /// Default precision-loss policy: reject a value the column cannot hold exactly, rather
    /// than silently dropping the digits that do not fit.
    public static final PrecisionLossPolicy DEFAULT_PRECISION_LOSS_POLICY = PrecisionLossPolicy.REJECT;

    /// Default encoding policy: [ColumnEncoding#AUTO], leaving each column chunk's encoding to
    /// the size comparison the writer makes once the row group is buffered.
    public static final ColumnEncoding DEFAULT_ENCODING = ColumnEncoding.AUTO;

    private final int pageTargetBytes;
    private final long rowGroupTargetBytes;
    private final ColumnEncoding defaultEncoding;
    private final Map<String, ColumnEncoding> columnEncodings;
    private final int statisticsTruncationLength;
    private final CompressionCodec codec;
    private final PrecisionLossPolicy precisionLossPolicy;

    private WriterConfig(Builder builder) {
        this.pageTargetBytes = builder.pageTargetBytes;
        this.rowGroupTargetBytes = builder.rowGroupTargetBytes;
        this.defaultEncoding = builder.defaultEncoding;
        this.columnEncodings = Map.copyOf(builder.columnEncodings);
        this.statisticsTruncationLength = builder.statisticsTruncationLength;
        this.codec = builder.codec;
        this.precisionLossPolicy = builder.precisionLossPolicy;
    }

    /// The default configuration.
    public static WriterConfig defaults() {
        return builder().build();
    }

    /// A builder pre-populated with the defaults.
    public static Builder builder() {
        return new Builder();
    }

    /// Maximum uncompressed bytes of values per data page.
    public int pageTargetBytes() {
        return pageTargetBytes;
    }

    /// Uncompressed byte threshold at which a row group is flushed.
    public long rowGroupTargetBytes() {
        return rowGroupTargetBytes;
    }

    /// The encoding policy for columns with no override of their own.
    public ColumnEncoding defaultEncoding() {
        return defaultEncoding;
    }

    /// The per-column encoding policies, keyed by dotted leaf path. Unmodifiable, and empty
    /// where no column was named.
    public Map<String, ColumnEncoding> columnEncodings() {
        return columnEncodings;
    }

    /// The policy governing `columnPath`: its own override where it has one, the file-wide
    /// default otherwise. Package-private — [ParquetFileWriter] resolves every column through
    /// it at creation, and a caller has [#defaultEncoding] and [#columnEncodings] to read.
    ///
    /// @param columnPath the column's dotted leaf path
    /// @return the policy in force for that column
    ColumnEncoding encodingFor(String columnPath) {
        return columnEncodings.getOrDefault(columnPath, defaultEncoding);
    }

    /// The maximum length of a `BYTE_ARRAY` `min` / `max` statistics bound before it is
    /// truncated (and flagged inexact).
    public int statisticsTruncationLength() {
        return statisticsTruncationLength;
    }

    /// The codec each page body is compressed with.
    public CompressionCodec codec() {
        return codec;
    }

    /// What [RowWriter] does with a value carrying more precision than its column can hold.
    public PrecisionLossPolicy precisionLossPolicy() {
        return precisionLossPolicy;
    }

    /// `ZSTD` when its library is loadable, otherwise `UNCOMPRESSED`. The class is only probed
    /// for presence, not initialized, so picking the default never triggers the native load.
    private static CompressionCodec defaultCodec() {
        return CodecLibraries.isPresent("com.github.luben.zstd.Zstd")
                ? CompressionCodec.ZSTD
                : CompressionCodec.UNCOMPRESSED;
    }

    /// Builder for [WriterConfig].
    public static final class Builder {

        private int pageTargetBytes = DEFAULT_PAGE_TARGET_BYTES;
        private long rowGroupTargetBytes = DEFAULT_ROW_GROUP_TARGET_BYTES;
        private ColumnEncoding defaultEncoding = DEFAULT_ENCODING;
        private final Map<String, ColumnEncoding> columnEncodings = new LinkedHashMap<>();
        private int statisticsTruncationLength = DEFAULT_STATISTICS_TRUNCATION_LENGTH;
        private CompressionCodec codec = DEFAULT_CODEC;
        private PrecisionLossPolicy precisionLossPolicy = DEFAULT_PRECISION_LOSS_POLICY;

        private Builder() {
        }

        /// Sets the page target; must be at least one `INT32` (4 bytes).
        public Builder pageTargetBytes(int pageTargetBytes) {
            if (pageTargetBytes < Integer.BYTES) {
                throw new IllegalArgumentException(
                        "pageTargetBytes must be at least " + Integer.BYTES + " but was " + pageTargetBytes);
            }
            this.pageTargetBytes = pageTargetBytes;
            return this;
        }

        /// Sets the row-group target; must be positive.
        public Builder rowGroupTargetBytes(long rowGroupTargetBytes) {
            if (rowGroupTargetBytes <= 0) {
                throw new IllegalArgumentException(
                        "rowGroupTargetBytes must be positive but was " + rowGroupTargetBytes);
            }
            this.rowGroupTargetBytes = rowGroupTargetBytes;
            return this;
        }

        /// Sets the encoding policy for every column without an override of its own; must be
        /// non-null. Defaults to [ColumnEncoding#AUTO].
        ///
        /// A default that no column of the schema can carry is rejected when the writer is
        /// created, so a file-wide `BYTE_STREAM_SPLIT` over a schema holding a `BYTE_ARRAY`
        /// column fails rather than quietly resolving that column to something else.
        public Builder encoding(ColumnEncoding encoding) {
            if (encoding == null) {
                throw new IllegalArgumentException("encoding must not be null");
            }
            this.defaultEncoding = encoding;
            return this;
        }

        /// Sets the encoding policy for one leaf column, overriding the file-wide default; both
        /// arguments must be non-null.
        ///
        /// The column is named by its **dotted leaf path** as the schema spells it, synthetic
        /// `list.element` and `key_value.key` segments included — `readings.list.element`, not
        /// `readings`. A path matching no leaf column of the schema, or a policy its physical
        /// type cannot carry, is rejected when the writer is created.
        public Builder encoding(String columnPath, ColumnEncoding encoding) {
            if (columnPath == null) {
                throw new IllegalArgumentException("columnPath must not be null");
            }
            if (encoding == null) {
                throw new IllegalArgumentException("encoding must not be null for column " + columnPath);
            }
            this.columnEncodings.put(columnPath, encoding);
            return this;
        }

        /// Sets the maximum `BYTE_ARRAY` `min` / `max` statistics bound length; must be
        /// positive. A bound longer than this is truncated and flagged inexact.
        public Builder statisticsTruncationLength(int statisticsTruncationLength) {
            if (statisticsTruncationLength <= 0) {
                throw new IllegalArgumentException("statisticsTruncationLength must be positive but was "
                        + statisticsTruncationLength);
            }
            this.statisticsTruncationLength = statisticsTruncationLength;
            return this;
        }

        /// Sets the codec each page body is compressed with; must be non-null.
        ///
        /// `UNCOMPRESSED`, `GZIP`, `SNAPPY`, `ZSTD`, `LZ4_RAW` and `BROTLI` are written.
        /// Everything but the first two needs its library on the classpath, which is checked
        /// when the writer is created rather than here.
        ///
        /// The other two members of [CompressionCodec] are not produced, and neither is
        /// waiting on a later release. `LZ4` names the Hadoop framing the format deprecated in
        /// favour of `LZ4_RAW`; files already written with it are still read, so the refusal is
        /// on this side only. `LZO` has no maintained JVM implementation and is refused in both
        /// directions. Asking for either fails when the writer is created.
        public Builder codec(CompressionCodec codec) {
            if (codec == null) {
                throw new IllegalArgumentException("codec must not be null");
            }
            this.codec = codec;
            return this;
        }

        /// Sets what [RowWriter] does with a value carrying more precision than its column
        /// can hold — an [java.time.Instant] with microseconds written to a
        /// `TIMESTAMP(MILLIS)` column, say; must be non-null. Defaults to
        /// [PrecisionLossPolicy#REJECT].
        public Builder precisionLossPolicy(PrecisionLossPolicy precisionLossPolicy) {
            if (precisionLossPolicy == null) {
                throw new IllegalArgumentException("precisionLossPolicy must not be null");
            }
            this.precisionLossPolicy = precisionLossPolicy;
            return this;
        }

        /// Builds the immutable configuration.
        public WriterConfig build() {
            return new WriterConfig(this);
        }
    }
}
