package io.cellophane.smpp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmppTest {

    @Test
    void headerIsSixteenBytes() {
        assertThat(Smpp.HEADER_LENGTH).isEqualTo(16);
    }

    @Test
    void interfaceVersionIsThreeFour() {
        assertThat(Smpp.INTERFACE_VERSION_3_4).isEqualTo((byte) 0x34);
    }
}
