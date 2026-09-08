/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.bloomfilter;

/// Parsed `BloomFilterHeader` thrift struct that precedes a column chunk's bloom filter.
public record BloomFilterHeader(
        int numBytes,
        Algorithm algorithm,
        Hash hash,
        Compression compression
) {
    public enum Algorithm {
        BLOCK;
    }

    public enum Hash {
        XXHASH;
    }

    public enum Compression {
        UNCOMPRESSED;
    }
}
