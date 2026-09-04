package io.cellophane.smpp.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

class UdhTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void parsesEightBitConcatHeader() {
        byte[] userData = HEX.parseHex("050003ab0201" + "48656c6c6f");

        Udh udh = Udh.parse(userData);

        assertThat(udh.length()).isEqualTo(6);
        assertThat(udh.concat()).contains(new Udh.Concat(0xab, 2, 1));
        assertThat(new String(udh.body(userData))).isEqualTo("Hello");
    }

    @Test
    void parsesSixteenBitConcatHeader() {
        Udh udh = Udh.parse(HEX.parseHex("06080412340302" + "ff"));

        assertThat(udh.concat()).contains(new Udh.Concat(0x1234, 3, 2));
    }

    @Test
    void parsesMultipleElementsAndKeepsUnknownOnes() {
        Udh udh = Udh.parse(HEX.parseHex("0b" + "050400200020" + "0003010201"));

        assertThat(udh.elements()).containsExactly(
                new Udh.Element(Udh.IE_PORT_16, HEX.parseHex("00200020")),
                new Udh.Element(Udh.IE_CONCAT_8, HEX.parseHex("010201")));
        assertThat(udh.concat()).contains(new Udh.Concat(1, 2, 1));
    }

    @Test
    void emptyHeaderIsValid() {
        Udh udh = Udh.parse(new byte[] {0x00, 0x41});

        assertThat(udh.elements()).isEmpty();
        assertThat(udh.concat()).isEmpty();
        assertThat(udh.body(new byte[] {0x00, 0x41})).containsExactly(0x41);
    }

    @Test
    void buildsAndSerialisesConcatHeaders() {
        assertThat(HEX.formatHex(Udh.concat8(0xab, 3, 2).toBytes())).isEqualTo("050003ab0302");
        assertThat(HEX.formatHex(Udh.concat16(0x1234, 3, 2).toBytes())).isEqualTo("06080412340302");
        assertThat(HEX.formatHex(Udh.concat8(1, 1, 1).prepend(new byte[] {0x41}))).isEqualTo("05000301010141");
        assertThat(Udh.parse(Udh.concat16(7, 2, 2).toBytes())).isEqualTo(Udh.concat16(7, 2, 2));
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThatThrownBy(() -> Udh.parse(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Udh.parse(HEX.parseHex("0500"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
        assertThatThrownBy(() -> Udh.parse(HEX.parseHex("0300ff00"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overruns");
        assertThatThrownBy(() -> new Udh.Concat(1, 2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Udh.parse(HEX.parseHex("040002ab02")).concat()).as("wrong IE length").isEmpty();
        assertThat(new Udh(List.of()).length()).isEqualTo(1);
    }
}
