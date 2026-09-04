package io.cellophane.smpp.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class Gsm7Test {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void roundTripsBasicAndExtensionCharacters() {
        String text = "Hello @£$¥ Ω {curly} [square] ~ | \\ ^ € 12345";

        byte[] septets = Gsm7.encode(text);

        assertThat(Gsm7.decode(septets)).isEqualTo(text);
        assertThat(Gsm7.septetLength(text)).isEqualTo(septets.length);
        assertThat(Gsm7.canEncode(text)).isTrue();
    }

    @Test
    void basicTableMapsAsciiLettersToThemselves() {
        assertThat(Gsm7.encode("Az09 ?")).containsExactly('A', 'z', '0', '9', ' ', '?');
        assertThat(Gsm7.encode("@")).containsExactly(0x00);
        assertThat(Gsm7.encode("é")).containsExactly(0x05);
        assertThat(Gsm7.encode("€")).containsExactly(0x1B, 0x65);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        assertThat(Gsm7.canEncode("বাংলা")).isFalse();
        assertThatThrownBy(() -> Gsm7.encode("smile 🙂"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the GSM 7-bit alphabet");
    }

    @Test
    void escapeWithUndefinedCodeFallsBackToBasicTable() {
        assertThat(Gsm7.decode(new byte[] {0x1B, 'A'})).isEqualTo("A");
        assertThat(Gsm7.decode(new byte[] {0x1B})).isEqualTo(" ");
    }

    @Test
    void packsUsingTheWellKnownVectors() {
        assertThat(HEX.formatHex(Gsm7.pack(Gsm7.encode("hello")))).isEqualTo("e8329bfd06");
        assertThat(HEX.formatHex(Gsm7.pack(Gsm7.encode("hellohello")))).isEqualTo("e8329bfd4697d9ec37");
    }

    @Test
    void unpacksWithExplicitCount() {
        assertThat(Gsm7.decode(Gsm7.unpack(HEX.parseHex("e8329bfd4697d9ec37"), 10))).isEqualTo("hellohello");
        assertThatThrownBy(() -> Gsm7.unpack(HEX.parseHex("e8"), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unpacksWithoutCountAndDropsCrPaddingSeptet() {
        // 7 septets pack into 7 octets leaving 7 spare bits; a sender fills them with CR per GSM 03.38.
        byte[] seven = Gsm7.encode("1234567");
        byte[] padded = Gsm7.pack(new byte[] {'1', '2', '3', '4', '5', '6', '7', 0x0D});
        assertThat(padded).hasSize(7);

        assertThat(Gsm7.unpack(padded)).isEqualTo(seven);
        assertThat(Gsm7.unpack(Gsm7.pack(seven))).isEqualTo(seven);
        assertThat(Gsm7.decode(Gsm7.unpack(Gsm7.pack(Gsm7.encode("12345678"))))).isEqualTo("12345678");
        assertThat(Gsm7.decode(Gsm7.unpack(Gsm7.pack(Gsm7.encode("hello"))))).isEqualTo("hello");
    }

    @Test
    void packAndUnpackAreInversesForEveryLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            sb.append((char) ('a' + (i % 26)));
            byte[] septets = Gsm7.encode(sb);
            assertThat(Gsm7.unpack(Gsm7.pack(septets), septets.length)).as("length " + septets.length)
                    .isEqualTo(septets);
        }
    }
}
