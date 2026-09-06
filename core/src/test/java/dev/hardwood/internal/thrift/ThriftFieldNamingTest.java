/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A parse failure should say which field of which struct it was standing on.
/// The struct is the reader's knowledge and the field id the raw reader's, and
/// neither is much use without the other.
class ThriftFieldNamingTest {

    /// `0x1f` is a field header for field 1 — delta 1 from the struct's start —
    /// declaring wire type 15, which is not a type. Field 1 of `PageHeader` is
    /// `type`, so the message can name it.
    @Test
    void namesAFieldTheStructDefines() {
        assertThatThrownBy(() -> PageHeaderReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(new byte[] { 0x1f }))))
                .isInstanceOf(IOException.class)
                .hasMessage("PageHeader.type — Unknown field type: 15");
    }

    /// `0xff` is delta 15, which no struct in the format has room for. Saying
    /// so is stronger than naming a type: it means the bytes did not decode to
    /// anything that belongs there at all.
    @Test
    void saysWhenAFieldIdIsNotOneTheStructHas() {
        assertThatThrownBy(() -> PageHeaderReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(new byte[] { (byte) 0xff }))))
                .isInstanceOf(IOException.class)
                .hasMessage("PageHeader field 15 — Unknown field type: 15");
    }

    /// The innermost reader is the one standing on the field; an outer struct's
    /// name against it would point at a field that is not the one that failed.
    @Test
    void theInnermostStructWins() {
        // field 5 of PageHeader is data_page_header, a struct; inside it, 0x1f
        // is field 1 — num_values — with an invalid type.
        assertThatThrownBy(() -> PageHeaderReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(new byte[] { 0x5c, 0x1f }))))
                .isInstanceOf(IOException.class)
                .hasMessage("DataPageHeader.num_values — Unknown field type: 15");
    }

    /// The position travels with the failure so a caller that knows where the
    /// buffer starts can name the byte, not the region. Reporting the region's
    /// start instead is right only when the damage is at its front, which is
    /// exactly the case a hand-made fixture tends to produce.
    @Test
    void theFailureCarriesHowFarIntoTheBufferItGot() {
        // Two good fields, then a header declaring an invalid type. The bad
        // header is the fifth byte, at index 4 — the position reported is where
        // that field begins, not where the reader came to rest one byte later.
        byte[] bytes = { 0x15, 0x02, 0x15, 0x04, (byte) 0x1f };

        assertThatThrownBy(() -> PageHeaderReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(bytes))))
                .isInstanceOf(ThriftParseException.class)
                .satisfies(t -> assertThat(ThriftParseException.bytesReadOf(t)).isEqualTo(4));
    }

    /// A failure that is not a parse has no position, and a caller must be able
    /// to tell that apart from a position of zero.
    @Test
    void somethingThatIsNotAParseFailureHasNoPosition() {
        assertThat(ThriftParseException.bytesReadOf(new IllegalStateException("nope")))
                .isEqualTo(-1);
    }

    /// A field id means one thing in the struct it was read from and something
    /// else in the struct enclosing it, so a reader may only name its own
    /// fields. `FileMetaData.key_value_metadata` holds `KeyValue` structs whose
    /// field 1 is `key`; field 1 of `FileMetaData` is `version`, and naming
    /// that would point at a field nowhere near the damage.
    @Test
    void anEnclosingStructDoesNotNameANestedStructsField() throws IOException {
        // FileMetaData field 5, key_value_metadata: a one-element list of
        // structs whose first field header declares an invalid wire type.
        byte[] bytes = { 0x59, 0x1c, 0x1f };

        assertThatThrownBy(() -> FileMetaDataReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(bytes))))
                .isInstanceOf(IOException.class)
                .hasMessage("KeyValue.key — Unknown field type: 15");
    }

    /// A struct nobody annotates gets no name rather than its container's. The
    /// message is then exactly what it was before struct naming existed, which
    /// is worse than a right name and far better than a wrong one.
    @Test
    void aStructNoReaderAnnotatesIsNotNamedAtAll() {
        // PageHeader field 5 declared a struct, so it is skipped wholesale;
        // inside it, a field header declaring an invalid wire type.
        byte[] bytes = { 0x5c, 0x1f };

        assertThatThrownBy(() -> DataPageHeaderV2Reader.read(
                new ThriftCompactReader(ByteBuffer.wrap(bytes))))
                .isInstanceOf(IOException.class)
                .hasMessage("Unknown field type: 15");
    }

    /// `LogicalType` is a union, so its ids name variants. Field 1 is `STRING`;
    /// `scale` is a field of the `DecimalType` the union's field 5 holds, and
    /// resolving one against the other named a field of a type the file never
    /// claimed to be.
    @Test
    void aUnionsFieldsAreItsVariants() {
        assertThatThrownBy(() -> LogicalTypeReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(new byte[] { 0x1f }))))
                .isInstanceOf(IOException.class)
                .hasMessage("LogicalType.STRING — Unknown field type: 15");
    }

    /// The second form of the name asserts the format has no such field, so it
    /// must not appear for one the format defines. `RowGroup.sorting_columns`
    /// is field 4 and this reader skips it, which is not the same as the format
    /// not having it.
    @Test
    void aFieldTheFormatDefinesIsNamedEvenWhenTheReaderSkipsIt() {
        assertThatThrownBy(() -> RowGroupReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(new byte[] { 0x4f }))))
                .isInstanceOf(IOException.class)
                .hasMessage("RowGroup.sorting_columns — Unknown field type: 15");
    }

    /// A field id past everything the format defines keeps the second form,
    /// which is the stronger statement it was always meant to be.
    @Test
    void aFieldIdTheFormatDoesNotDefineKeepsTheBareForm() {
        assertThatThrownBy(() -> RowGroupReader.read(
                new ThriftCompactReader(ByteBuffer.wrap(new byte[] { (byte) 0x8f }))))
                .isInstanceOf(IOException.class)
                .hasMessage("RowGroup field 8 — Unknown field type: 15");
    }
}
