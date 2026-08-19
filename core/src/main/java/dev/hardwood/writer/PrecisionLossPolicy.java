/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import dev.hardwood.Experimental;

/// What [RowWriter] does with a value carrying more precision than the column it is written
/// to can hold — an [java.time.Instant] with microseconds into a `TIMESTAMP(MILLIS)` column,
/// a [java.math.BigDecimal] with three decimals into a `DECIMAL(_, 2)` column.
///
/// This governs **precision** only. A value the column cannot represent at all — a date
/// beyond the `INT32` day range, an unscaled decimal wider than the declared precision, an
/// `INT(8)` out of range, a `FIXED_LEN_BYTE_ARRAY` of the wrong length — is rejected under
/// every policy, because there is no truncation that would preserve its magnitude.
///
/// The policy applies to the row-oriented layer, which converts logical-type values to their
/// physical representation. The columnar [ColumnBatch] API takes physical values and converts
/// nothing, so nothing there can lose precision.
///
/// **This API is [Experimental]:** the shape may change in future releases.
@Experimental
public enum PrecisionLossPolicy {

    /// Reject the value, naming the field and the column's declared unit or scale. The
    /// default: a narrowing that loses digits is more often a mistake about the schema than
    /// an intention, and the two ways to state the intention — narrowing the value at the
    /// call site (`instant.truncatedTo(ChronoUnit.MILLIS)`, `decimal.setScale(2, …)`) or
    /// selecting this policy — both leave a trace a reader of the code can see.
    ///
    /// A value that happens to be exact at the column's unit or scale is written normally;
    /// only one that would actually lose digits is rejected.
    REJECT,

    /// Drop the digits the column cannot hold, and write the value.
    ///
    /// - `TIME` and `TIMESTAMP`: the sub-unit fraction is dropped. The fraction is never
    ///   negative — the value is carried as a whole-second count plus a nanosecond-of-second
    ///   — so the written instant is the original floored to the column's unit.
    ///   This is what [java.time.Instant#toEpochMilli()] does with the same value.
    /// - `DECIMAL`: the value is rescaled with [java.math.RoundingMode#DOWN], dropping the
    ///   digits beyond the declared scale rather than rounding to the nearest, so the written
    ///   value is never larger in magnitude than the one handed over.
    ///
    /// Neither conversion rounds to the nearest representable value: both drop the digits
    /// that do not fit. The two differ on where a negative value lands — a timestamp floors
    /// toward the past, matching every other Parquet writer, while a decimal truncates toward
    /// zero, which is what dropping its trailing digits means.
    TRUNCATE
}
