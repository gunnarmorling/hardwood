/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.predicate;

/// Three-valued outcome of evaluating a filter predicate against a unit's metadata.
///
/// Extends the boolean "can this unit be dropped?" question with its dual: metadata can also
/// prove that **every** row of a unit matches, in which case per-row predicate evaluation over
/// the unit is redundant.
///
/// The three values do not draw on the same sources. Absence can be proven by min/max
/// statistics, by a bloom filter, or by the dictionary of a fully dictionary-encoded chunk, and
/// any one of them is sufficient. Universality can only be proven by statistics: a bloom filter
/// and a dictionary each answer "is this value present?", which bounds what a unit *can* hold
/// but says nothing about what every row *does* hold.
///
/// A decision is always conservative: when metadata is absent, partial, or untrusted, the
/// decision is [#MIGHT_MATCH] and rows are evaluated individually.
public enum FilterDecision {

    /// Metadata proves no row matches; the unit can be skipped entirely.
    /// Equivalent to `canDrop == true`.
    CANNOT_MATCH,

    /// Metadata cannot decide; rows must be evaluated individually.
    /// Equivalent to `canDrop == false`.
    MIGHT_MATCH,

    /// Statistics prove every row matches; the unit can be read with
    /// per-row predicate evaluation skipped.
    ALWAYS_MATCHES;

    /// Combines two decisions under logical AND.
    static FilterDecision and(FilterDecision a, FilterDecision b) {
        if (a == CANNOT_MATCH || b == CANNOT_MATCH) {
            return CANNOT_MATCH;
        }
        if (a == ALWAYS_MATCHES && b == ALWAYS_MATCHES) {
            return ALWAYS_MATCHES;
        }
        return MIGHT_MATCH;
    }

    /// Combines two decisions under logical OR.
    static FilterDecision or(FilterDecision a, FilterDecision b) {
        if (a == ALWAYS_MATCHES || b == ALWAYS_MATCHES) {
            return ALWAYS_MATCHES;
        }
        if (a == CANNOT_MATCH && b == CANNOT_MATCH) {
            return CANNOT_MATCH;
        }
        return MIGHT_MATCH;
    }
}
