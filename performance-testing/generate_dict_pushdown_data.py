#
#  SPDX-License-Identifier: Apache-2.0
#
#  Copyright The original authors
#
#  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

"""Generate the fixture for the dictionary push-down benchmark
(DictionaryPushDownBenchmark).

Writes:
  <output_dir>/dict_pushdown.parquet
    - `category`: low-cardinality string, dictionary-encoded. Every row group
      draws from the same CARDINALITY values, so its dictionary is small and its
      min/max span the whole value range.
    - `payload`: int64 payload read on every scan, so the benchmark measures a
      realistic read and not just planning.

  <output_dir>/dict_pushdown_no_stats.parquet
    A byte-for-byte copy of the above with `category`'s `encoding_stats` dropped
    from the footer. Without that field a reader cannot establish that the
    dictionary covers the whole chunk, so push-down is ineligible and never
    reads a dictionary page. Everything else — pages, offsets, statistics, the
    dictionary pages themselves — is identical, which makes the pair a control
    for the cost of push-down on a probe that prunes nothing.

`category` values are `cat_<n>` for even `n` only. That leaves odd-numbered
names — `cat_1`, `cat_3`, … — lexicographically *inside* every row group's
min/max but absent from every dictionary, which is exactly the case statistics
cannot decide and the dictionary can. Probing an even name instead makes every
row group survive, so the dictionary read is pure overhead; the two together
bound the win and the cost.

Dictionary encoding is forced on with a page-size limit high enough that no
chunk falls back to plain pages, since a fallback would make `encoding_stats`
report a non-dictionary data page and disable push-down for that chunk.

Usage: python performance-testing/generate_dict_pushdown_data.py [output_dir]
"""
import os
import shutil
import sys

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools"))
from parquet_annotators import drop_encoding_stats  # noqa: E402

DEFAULT_OUTPUT_DIR = os.path.join(
    "performance-testing", "test-data-setup", "target", "benchmark-data"
)
FILE_NAME = "dict_pushdown.parquet"
NO_STATS_FILE_NAME = "dict_pushdown_no_stats.parquet"
NUM_ROWS = 10_000_000
ROW_GROUP_SIZE = 1_000_000
CARDINALITY = 512


def main():
    output_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUTPUT_DIR
    os.makedirs(output_dir, exist_ok=True)
    path = os.path.join(output_dir, FILE_NAME)

    # Even suffixes only: odd ones stay in range but out of every dictionary.
    names = [f"cat_{2 * i}" for i in range(CARDINALITY)]
    ids = np.arange(NUM_ROWS, dtype=np.int64)
    category = pa.array([names[i % CARDINALITY] for i in range(NUM_ROWS)], type=pa.string())

    table = pa.table(
        {"category": category, "payload": pa.array(ids, type=pa.int64())},
        schema=pa.schema([("category", pa.string(), False), ("payload", pa.int64(), False)]),
    )

    pq.write_table(
        table,
        path,
        row_group_size=ROW_GROUP_SIZE,
        use_dictionary=True,
        dictionary_pagesize_limit=8 * 1024 * 1024,
        compression="snappy",
        write_statistics=True,
    )

    metadata = pq.ParquetFile(path).metadata
    print(f"Wrote {path}")
    print(f"  rows={metadata.num_rows} row_groups={metadata.num_row_groups} "
          f"cardinality={CARDINALITY}")
    for rg in range(min(1, metadata.num_row_groups)):
        col = metadata.row_group(rg).column(0)
        print(f"  category encodings={col.encodings} "
              f"min={col.statistics.min} max={col.statistics.max}")

    no_stats_path = os.path.join(output_dir, NO_STATS_FILE_NAME)
    shutil.copyfile(path, no_stats_path)
    drop_encoding_stats(no_stats_path, "category")
    print(f"Wrote {no_stats_path} (category encoding_stats dropped)")


if __name__ == "__main__":
    main()
