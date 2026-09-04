package io.cellophane.smpp.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.EnquireLink;
import io.cellophane.smpp.pdu.Pdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class SmppPipelineTest {

    private static EmbeddedChannel channel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        SmppPipeline.install(ch.pipeline());
        return ch;
    }

    @Test
    void reassemblesPdusSplitAcrossTcpSegments() {
        EmbeddedChannel ch = channel();
        byte[] wire = PduCodec.encode(SubmitSm.of(1, Address.alphanumeric("A"), Address.international("1"), 0,
                "hello".getBytes()));

        ch.writeInbound(Unpooled.wrappedBuffer(wire, 0, 10));
        Object incomplete = ch.readInbound();
        assertThat(incomplete).isNull();
        ch.writeInbound(Unpooled.wrappedBuffer(wire, 10, wire.length - 10));

        Pdu pdu = ch.readInbound();
        assertThat(pdu).isInstanceOf(SubmitSm.class);
    }

    @Test
    void splitsMultiplePdusArrivingInOneSegment() {
        EmbeddedChannel ch = channel();
        ByteBuf buf = Unpooled.buffer();
        PduCodec.encode(new EnquireLink(1), buf);
        PduCodec.encode(new EnquireLink(2), buf);

        ch.writeInbound(buf);

        assertThat((Pdu) ch.readInbound()).isEqualTo(new EnquireLink(1));
        assertThat((Pdu) ch.readInbound()).isEqualTo(new EnquireLink(2));
    }

    @Test
    void encodesOutboundPdus() {
        EmbeddedChannel ch = channel();

        ch.writeOutbound(new EnquireLink(9));

        ByteBuf out = ch.readOutbound();
        assertThat(HexFormat.of().formatHex(ByteBufUtil.getBytes(out)))
                .isEqualTo("00000010" + "00000015" + "00000000" + "00000009");
        out.release();
    }

    @Test
    void surfacesGarbageLengthAsPduException() {
        EmbeddedChannel ch = channel();

        Throwable failure = null;
        try {
            ch.writeInbound(Unpooled.wrappedBuffer(HexFormat.of().parseHex("00000002" + "00000015")));
        } catch (Throwable t) {
            failure = t;
        }

        assertThat(failure).isInstanceOfSatisfying(PduException.class,
                e -> assertThat(e.commandStatus()).isEqualTo(CommandStatus.ESME_RINVCMDLEN.code()));
    }
}
