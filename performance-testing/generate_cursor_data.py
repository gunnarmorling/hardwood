#
#  SPDX-License-Identifier: Apache-2.0
#
#  Copyright The original authors
#
#  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

"""
Generates the corpus for CursorDecodeBenchmark: dictionary-encoded flat columns
exercising the fused cursor path (run-structured HybridStreamCursor consumption
of definition levels and dictionary indices).

For each physical type (INT32, INT64, FLOAT, DOUBLE), six files are written:

  - cursor_{type}_all_present.parquet           optional, 0% nulls (dict=32)
        All-present def-level RLE (generalizes the #721 single-run fast path).
  - cursor_{type}_null_heavy.parquet            optional, ~50% nulls in alternating blocks (dict=32)
        Long all-null RLE runs and long present stretches (null-heavy pages).
  - cursor_{type}_low_card.parquet              optional, 0% nulls, 4-entry dict, long index runs
        RLE-rich dictionary index stream (low-cardinality scatter).
  - cursor_{type}_required.parquet              required column (dict=256, 8-bit indices)
        Decode floor with realistic production-like cardinality.
  - cursor_{type}_required_low_card.parquet     required column (dict=4, index runs of 256)
        Long RLE index runs on a required column (Arrays.fill scatter).
  - cursor_{type}_required_high_card.parquet    required column (dict=4096, 12-bit indices)
        Heavy bit-packing stress on wide dictionary indices.

All files are dictionary-encoded, UNCOMPRESSED, DataPageV1, and ~2 M rows.
Uncompressed keeps codec cost out of the timed path so JMH isolates decode +
assembly on a single core (HardwoodContext.create(1)).

Usage:
    python performance-testing/generate_cursor_data.py [output_dir]
"""

import os
import sys

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

NUM_ROWS = 2_000_000
DICT_SIZE = 32
LOW_CARD_DICT_SIZE = 4
REQUIRED_DICT_SIZE = 256
HIGH_CARD_DICT_SIZE = 4096
# Block length for null_heavy: alternating present/null blocks → long RLE runs
# on the definition-level stream.
NULL_HEAVY_BLOCK = 1024
# Run length for low_card: long stretches of the same dictionary index.
LOW_CARD_RUN = 256

DEFAULT_OUTPUT_DIR = os.path.join(
    "performance-testing", "test-data-setup", "target", "benchmark-data"
)

TYPES = {
    "int32":  (pa.int32(),  lambda rng, n: rng.integers(0, 10_000, size=n, dtype=np.int32)),
    "int64":  (pa.int64(),  lambda rng, n: rng.integers(0, 1_000_000, size=n, dtype=np.int64)),
    "float":  (pa.float32(), lambda rng, n: rng.standard_normal(n).astype(np.float32)),
    "double": (pa.float64(), lambda rng, n: rng.standard_normal(n).astype(np.float64)),
}


def _write(table, path):
    pq.write_table(
        table, path,
        use_dictionary=True,
        compression="NONE",
        data_page_version="1.0",
    )
    size_mb = os.path.getsize(path) / 1e6
    return size_mb


def _all_present(rng, arrow_type, gen_fn):
    pool = gen_fn(rng, DICT_SIZE)
    indices = rng.integers(0, DICT_SIZE, size=NUM_ROWS, dtype=np.int64)
    values = pool[indices]
    return pa.array(values, type=arrow_type)


def _null_heavy(rng, arrow_type, gen_fn):
    """~50% nulls in alternating present/null blocks for RLE-rich def levels."""
    pool = gen_fn(rng, DICT_SIZE)
    indices = rng.integers(0, DICT_SIZE, size=NUM_ROWS, dtype=np.int64)
    values = pool[indices]
    # True = null. Blocks: [present, null, present, null, ...]
    mask = np.zeros(NUM_ROWS, dtype=bool)
    for start in range(NULL_HEAVY_BLOCK, NUM_ROWS, 2 * NULL_HEAVY_BLOCK):
        end = min(start + NULL_HEAVY_BLOCK, NUM_ROWS)
        mask[start:end] = True
    return pa.array(values, type=arrow_type, mask=mask)


def _low_card(rng, arrow_type, gen_fn):
    """Low-cardinality values with long repeated index runs."""
    pool = gen_fn(rng, LOW_CARD_DICT_SIZE)
    n_runs = (NUM_ROWS + LOW_CARD_RUN - 1) // LOW_CARD_RUN
    run_ids = rng.integers(0, LOW_CARD_DICT_SIZE, size=n_runs, dtype=np.int64)
    indices = np.repeat(run_ids, LOW_CARD_RUN)[:NUM_ROWS]
    values = pool[indices]
    return pa.array(values, type=arrow_type)


def write_type_files(output_dir, type_name, arrow_type, gen_fn, rng):
    scenarios = (
        ("all_present", _all_present(rng, arrow_type, gen_fn), True,
         "0% null, all-present def RLE"),
        ("null_heavy", _null_heavy(rng, arrow_type, gen_fn), True,
         f"~50% null in {NULL_HEAVY_BLOCK}-row blocks"),
        ("low_card", _low_card(rng, arrow_type, gen_fn), True,
         f"dict={LOW_CARD_DICT_SIZE}, index runs of {LOW_CARD_RUN}"),
    )
    for name, col, nullable, desc in scenarios:
        field = pa.field("value", arrow_type, nullable=nullable)
        table = pa.table({"value": col}, schema=pa.schema([field]))
        path = os.path.join(output_dir, f"cursor_{type_name}_{name}.parquet")
        size_mb = _write(table, path)
        print(f"  {os.path.basename(path):45s}  {size_mb:6.1f} MB  ({desc})")

    # Required (no def levels) — the decode floor.
    # Dict size 256 → 8-bit indices, mostly bit-packed; realistic for
    # production columns like product IDs, zip codes, etc.
    pool = gen_fn(rng, REQUIRED_DICT_SIZE)
    indices = rng.integers(0, REQUIRED_DICT_SIZE, size=NUM_ROWS, dtype=np.int64)
    col = pa.array(pool[indices], type=arrow_type)
    field = pa.field("value", arrow_type, nullable=False)
    table = pa.table({"value": col}, schema=pa.schema([field]))
    path = os.path.join(output_dir, f"cursor_{type_name}_required.parquet")
    size_mb = _write(table, path)
    print(f"  {os.path.basename(path):45s}  {size_mb:6.1f} MB  "
          f"(required floor, dict={REQUIRED_DICT_SIZE})")

    # Required low-cardinality — long RLE index runs on a required column.
    pool_lo = gen_fn(rng, LOW_CARD_DICT_SIZE)
    n_runs = (NUM_ROWS + LOW_CARD_RUN - 1) // LOW_CARD_RUN
    run_ids = rng.integers(0, LOW_CARD_DICT_SIZE, size=n_runs, dtype=np.int64)
    lo_indices = np.repeat(run_ids, LOW_CARD_RUN)[:NUM_ROWS]
    col_lo = pa.array(pool_lo[lo_indices], type=arrow_type)
    field_lo = pa.field("value", arrow_type, nullable=False)
    table_lo = pa.table({"value": col_lo}, schema=pa.schema([field_lo]))
    path_lo = os.path.join(output_dir, f"cursor_{type_name}_required_low_card.parquet")
    size_mb_lo = _write(table_lo, path_lo)
    print(f"  {os.path.basename(path_lo):45s}  {size_mb_lo:6.1f} MB  "
          f"(required, dict={LOW_CARD_DICT_SIZE}, index runs of {LOW_CARD_RUN})")

    # Required high-cardinality — heavy bit-packing (12-bit indices), stresses
    # the fused path's decode side with wide index values.
    pool_hi = gen_fn(rng, HIGH_CARD_DICT_SIZE)
    hi_indices = rng.integers(0, HIGH_CARD_DICT_SIZE, size=NUM_ROWS, dtype=np.int64)
    col_hi = pa.array(pool_hi[hi_indices], type=arrow_type)
    field_hi = pa.field("value", arrow_type, nullable=False)
    table_hi = pa.table({"value": col_hi}, schema=pa.schema([field_hi]))
    path_hi = os.path.join(output_dir, f"cursor_{type_name}_required_high_card.parquet")
    size_mb_hi = _write(table_hi, path_hi)
    print(f"  {os.path.basename(path_hi):45s}  {size_mb_hi:6.1f} MB  "
          f"(required, dict={HIGH_CARD_DICT_SIZE})")


def main():
    output_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUTPUT_DIR
    os.makedirs(output_dir, exist_ok=True)
    rng = np.random.default_rng(42)

    print(f"Generating cursor-decode benchmark corpus in {output_dir} ...")
    for type_name, (arrow_type, gen_fn) in TYPES.items():
        print(f"\n{type_name}:")
        write_type_files(output_dir, type_name, arrow_type, gen_fn, rng)

    total = len(TYPES) * 7
    print(f"\nWrote {total} files to {output_dir}")


if __name__ == "__main__":
    main()
