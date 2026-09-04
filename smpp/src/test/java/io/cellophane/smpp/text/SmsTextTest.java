package io.cellophane.smpp.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SmsTextTest {

    @Test
    void classifiesDataCodingValues() {
        assertThat(DataCoding.alphabet(0x00)).isEqualTo(DataCoding.Alphabet.GSM7);
        assertThat(DataCoding.alphabet(0x01)).isEqualTo(DataCoding.Alphabet.ASCII);
        assertThat(DataCoding.alphabet(0x03)).isEqualTo(DataCoding.Alphabet.LATIN1);
        assertThat(DataCoding.alphabet(0x04)).isEqualTo(DataCoding.Alphabet.BINARY);
        assertThat(DataCoding.alphabet(0x08)).isEqualTo(DataCoding.Alphabet.UCS2);
        assertThat(DataCoding.alphabet(0x0B)).isEqualTo(DataCoding.Alphabet.UNKNOWN);
        assertThat(DataCoding.alphabet(0xF0)).as("class 0 GSM7 flash").isEqualTo(DataCoding.Alphabet.GSM7);
        assertThat(DataCoding.alphabet(0xF5)).as("class 1 8-bit data").isEqualTo(DataCoding.Alphabet.BINARY);
        assertThat(DataCoding.alphabet(0xE0)).as("MWI store UCS2").isEqualTo(DataCoding.Alphabet.UCS2);
        assertThat(DataCoding.alphabet(0xC0)).as("MWI discard GSM7").isEqualTo(DataCoding.Alphabet.GSM7);
        assertThat(DataCoding.messageClass(0xF0)).contains(0);
        assertThat(DataCoding.messageClass(0x11)).contains(1);
        assertThat(DataCoding.messageClass(0x08)).isEmpty();
    }

    @Test
    void decodesEachTextAlphabet() {
        assertThat(SmsText.decode(0x00, Gsm7.encode("Ωmega"))).contains("Ωmega");
        assertThat(SmsText.decode(0x00, Gsm7.pack(Gsm7.encode("hello")), true)).contains("hello");
        assertThat(SmsText.decode(0x01, "plain".getBytes(StandardCharsets.US_ASCII))).contains("plain");
        assertThat(SmsText.decode(0x03, "café".getBytes(StandardCharsets.ISO_8859_1))).contains("café");
        assertThat(SmsText.decode(0x08, "বাংলা".getBytes(StandardCharsets.UTF_16BE))).contains("বাংলা");
        assertThat(SmsText.decode(0x04, new byte[] {1, 2})).isEmpty();
    }

    @Test
    void encodesAndChoosesTheSmallestAlphabet() {
        assertThat(SmsText.chooseDataCoding("Your OTP is 482913")).isEqualTo(DataCoding.SMSC_DEFAULT);
        assertThat(SmsText.chooseDataCoding("আপনার OTP")).isEqualTo(DataCoding.UCS2);
        assertThat(SmsText.encode(0x08, "Hi")).containsExactly(0x00, 0x48, 0x00, 0x69);
        assertThat(SmsText.encode(0x00, "Hi")).containsExactly(0x48, 0x69);
        assertThatThrownBy(() -> SmsText.encode(0x04, "Hi")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SmsText.encode(0x03, "🙂")).isInstanceOf(IllegalArgumentException.class);
    }
}
