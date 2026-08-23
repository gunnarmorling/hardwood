/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import dev.hardwood.metadata.Statistics;

/// Accumulates a `FLOAT16` column chunk's `min` / `max` / `null_count`.
///
/// A `FLOAT16` is two little-endian bytes of an IEEE half-precision value in a
/// `FIXED_LEN_BYTE_ARRAY`, and its type-defined order compares the *represented* value, not the
/// bytes. The floating-point rules of [FloatStatisticsCollector] therefore apply rather than the
/// binary ones: `NaN` never extends the bounds, and a zero bound is sign-normalized so a
/// reader's `[min, max]` test is correct for either signed zero.
final class Float16StatisticsCollector implements BinaryStatistics {

    private static final int BYTES = 2;

    private float min;
    private float max;
    private long nullCount;
    private long nanCount;
    private boolean hasValues;

    @Override
    public void accept(byte[] value) {
        float half = decode(value);
        if (Float.isNaN(half)) {
            nanCount++;
            return; // NaN never participates in min/max
        }
        if (!hasValues) {
            min = half;
            max = half;
            hasValues = true;
            return;
        }
        if (Float.compare(half, min) < 0) {
            min = half;
        }
        if (Float.compare(half, max) > 0) {
            max = half;
        }
    }

    @Override
    public void acceptNull() {
        nullCount++;
    }

    @Override
    public Statistics toStatistics() {
        byte[] minValue = null;
        byte[] maxValue = null;
        if (hasValues) {
            minValue = encode(min == 0.0f ? -0.0f : min);
            maxValue = encode(max == 0.0f ? 0.0f : max);
        }
        return new Statistics(minValue, maxValue, nullCount, null, false, true, true, nanCount);
    }

    private static float decode(byte[] value) {
        if (value.length != BYTES) {
            throw new IllegalArgumentException("A FLOAT16 value is " + BYTES + " bytes, not " + value.length);
        }
        return Float.float16ToFloat((short) ((value[1] & 0xFF) << 8 | value[0] & 0xFF));
    }

    private static byte[] encode(float value) {
        short bits = Float.floatToFloat16(value);
        return new byte[] { (byte) bits, (byte) (bits >>> 8) };
    }
}
