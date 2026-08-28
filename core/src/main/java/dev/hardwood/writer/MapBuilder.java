/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.writer;

import java.util.function.Consumer;

import dev.hardwood.Experimental;

/// Appends the entries of one `MAP` instance.
///
/// A map's entries are a repeated struct of a `key` and a `value`, so an entry is populated
/// through the same [StructBuilder] a nested struct uses, with those two field names:
///
/// ```java
/// row.setMap("props", props -> props
///         .addEntry(entry -> entry
///                 .setString("key", "region")
///                 .setString("value", "eu-central-1")));
/// ```
///
/// This shape carries every key and value type the schema can declare, including struct, list
/// and map values, which a typed `put` per key/value combination could not. The `key` is
/// `REQUIRED` by the format, so an entry that leaves it unset throws; the `value` follows the
/// schema like any other field.
///
/// A filler that appends no entry writes an empty map, which is distinct from the absent map
/// that leaving the field unset writes.
///
/// The builder is valid only inside the lambda it was passed to. Retaining one and using it
/// afterwards throws [IllegalStateException] rather than appending to a later map.
///
/// **This API is [Experimental]:** the shape may change in future releases.
@Experimental
public interface MapBuilder {

    /// Appends one entry, populating its `key` and `value` fields through `filler`.
    ///
    /// @param filler populates the entry
    /// @return this builder, for chaining
    /// @throws IllegalArgumentException if the entry's `key` is left unset
    /// @throws IllegalStateException if this builder's scope has ended
    MapBuilder addEntry(Consumer<StructBuilder> filler);
}
