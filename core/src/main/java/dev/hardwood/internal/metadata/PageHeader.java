/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.metadata;

import dev.hardwood.metadata.PageType;

/// Header for a page in Parquet.
public record PageHeader(
        PageType type,
        int uncompressedPageSize,
        int compressedPageSize,
        DataPageHeader dataPageHeader,
        DataPageHeaderV2 dataPageHeaderV2,
        DictionaryPageHeader dictionaryPageHeader,
        Integer crc) {
}
