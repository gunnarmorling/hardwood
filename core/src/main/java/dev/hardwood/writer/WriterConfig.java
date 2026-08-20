/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import dev.hardwood.internal.BuildInfo;
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
/// Obtain the defaults with [#defaults] or override individual knobs through [#builder].
public final class WriterConfig {

    /// Default page target: 1 MiB of uncompressed values per data page.
    public static final int DEFAULT_PAGE_TARGET_BYTES = 1 << 20;

    /// Default row-group target: 128 MiB of uncompressed data per row group.
    public static final long DEFAULT_ROW_GROUP_TARGET_BYTES = 128L << 20;

    /// Default `created_by` identifier written into the file footer, in the
    /// `<app> version <version> (build <hash>)` convention Parquet readers parse — for
    /// example `hardwood version 1.1.0 (build a093aab)`. The hash carries a `-dirty` suffix
    /// when the working tree was not clean at build time, and a build that cannot identify
    /// itself reports `unknown` in place of the version or the hash.
    ///
    /// A reader that cannot parse this field cannot tell which writer produced the file, and
    /// applies its writer-specific correctness workarounds to it by default.
    public static final String DEFAULT_CREATED_BY = defaultCreatedBy();

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

    private final int pageTargetBytes;
    private final long rowGroupTargetBytes;
    private final String createdBy;
    private final boolean enableDictionary;
    private final int statisticsTruncationLength;
    private final CompressionCodec codec;
    private final PrecisionLossPolicy precisionLossPolicy;

    private WriterConfig(Builder builder) {
        this.pageTargetBytes = builder.pageTargetBytes;
        this.rowGroupTargetBytes = builder.rowGroupTargetBytes;
        this.createdBy = builder.createdBy;
        this.enableDictionary = builder.enableDictionary;
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

    /// The `created_by` identifier written into the file footer.
    public String createdBy() {
        return createdBy;
    }

    /// Whether eligible columns may be dictionary-encoded, where that is the smaller encoding.
    public boolean enableDictionary() {
        return enableDictionary;
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

    /// Assembles this build's `created_by` identifier from [BuildInfo].
    private static String defaultCreatedBy() {
        return "hardwood version " + BuildInfo.version() + " (build " + BuildInfo.revisionWithDirtyMark() + ")";
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
        private String createdBy = DEFAULT_CREATED_BY;
        private boolean enableDictionary = true;
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

        /// Sets the `created_by` footer identifier; must be non-null.
        public Builder createdBy(String createdBy) {
            if (createdBy == null) {
                throw new IllegalArgumentException("createdBy must not be null");
            }
            this.createdBy = createdBy;
            return this;
        }

        /// Enables or disables dictionary encoding. When enabled — the default — a column chunk is
        /// dictionary-encoded where that produces less than writing its values `PLAIN`, decided
        /// per chunk from the values it holds. When disabled, every chunk is written `PLAIN` with
        /// no dictionary page.
        public Builder enableDictionary(boolean enableDictionary) {
            this.enableDictionary = enableDictionary;
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

        /// Sets the codec each page body is compressed with; must be non-null. Only
        /// `UNCOMPRESSED` and `ZSTD` are currently supported on the write path, and a
        /// non-`UNCOMPRESSED` codec requires its library on the classpath.
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
