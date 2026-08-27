/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Sweeps every bit width 0–32 for INT32 and 0–64 for INT64 to verify the bulk miniblock
/// decoder handles all width-specialised paths (0, 1–8, 9–32, 33–64).
///
/// Each case encodes 50 values: a full miniblock of 32 plus a partial miniblock of 17, which
/// exercises the partial-last-miniblock truncation in [DeltaBinaryPackedDecoder].
///
/// Value construction forces the encoder to select approximately the target bit width:
/// - Width 0: constant column — all deltas zero.
/// - Width 1–31 (INT32) / 1–63 (INT64): most deltas = 1 (minDelta=1), one delta = 1&lt;&lt;w, so
///   the largest residual is (1&lt;&lt;w)-1 which requires exactly w bits.
/// - Width 32 (INT32): alternating Integer.MIN_VALUE / Integer.MAX_VALUE — the wrap-around case.
/// - Width 64 (INT64): alternating Long.MIN_VALUE / Long.MAX_VALUE — the wrap-around case.
class DeltaBinaryPackedDecoderWidthSweepTest {

    private static final int VALUE_COUNT = 50;

    // ==================== INT32 ====================

    static IntStream int32BitWidths() {
        return IntStream.rangeClosed(0, 32);
    }

    @ParameterizedTest(name = "INT32 width {0}")
    @MethodSource("int32BitWidths")
    void sweepsInt32BitWidths(int w) throws IOException {
        int[] values = buildInt32Values(w);
        byte[] encoded = DeltaBinaryPackedEncoder.encodeInts(values, 0, VALUE_COUNT);
        int[] decoded = new int[VALUE_COUNT];
        new DeltaBinaryPackedDecoder(encoded, 0).readInts(decoded, null, 0);
        assertThat(decoded).as("INT32 width %d", w).containsExactly(values);
    }

    private static int[] buildInt32Values(int w) {
        int[] values = new int[VALUE_COUNT];
        if (w == 0) {
            // Constant column: all deltas zero
            Arrays.fill(values, 42);
        }
        else if (w == 32) {
            // Wrap-around case: delta from MAX_VALUE to MIN_VALUE wraps to 1 modulo 2^32
            for (int i = 0; i < VALUE_COUNT; i++) {
                values[i] = i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            }
        }
        else {
            // Most deltas = 1 (minDelta=1), one delta = 1<<w.
            // Largest residual = (1<<w)-1, which requires exactly w bits.
            values[0] = 0;
            for (int i = 1; i < VALUE_COUNT; i++) {
                values[i] = values[i - 1] + (i == 25 ? (1 << w) : 1);
            }
        }
        return values;
    }

    // ==================== INT64 ====================

    static IntStream int64BitWidths() {
        return IntStream.rangeClosed(0, 64);
    }

    @ParameterizedTest(name = "INT64 width {0}")
    @MethodSource("int64BitWidths")
    void sweepsInt64BitWidths(int w) throws IOException {
        long[] values = buildInt64Values(w);
        byte[] encoded = DeltaBinaryPackedEncoder.encodeLongs(values, 0, VALUE_COUNT);
        long[] decoded = new long[VALUE_COUNT];
        new DeltaBinaryPackedDecoder(encoded, 0).readLongs(decoded, null, 0);
        assertThat(decoded).as("INT64 width %d", w).containsExactly(values);
    }

    private static long[] buildInt64Values(int w) {
        long[] values = new long[VALUE_COUNT];
        if (w == 0) {
            // Constant column: all deltas zero
            Arrays.fill(values, 42L);
        }
        else if (w == 64) {
            // Wrap-around case: delta from Long.MAX_VALUE to Long.MIN_VALUE wraps to 1 modulo 2^64
            for (int i = 0; i < VALUE_COUNT; i++) {
                values[i] = i % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
        else {
            // Most deltas = 1 (minDelta=1), one delta = 1L<<w.
            // Largest residual = (1L<<w)-1, which requires exactly w bits.
            values[0] = 0L;
            for (int i = 1; i < VALUE_COUNT; i++) {
                values[i] = values[i - 1] + (i == 25 ? (1L << w) : 1L);
            }
        }
        return values;
    }
}
