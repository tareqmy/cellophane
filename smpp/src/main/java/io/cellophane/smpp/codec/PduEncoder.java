package io.cellophane.smpp.codec;

import io.cellophane.smpp.pdu.Pdu;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Writes {@link Pdu} objects to the wire. Stateless, so one instance can serve every channel. */
@Sharable
public final class PduEncoder extends MessageToByteEncoder<Pdu> {

    public static final PduEncoder INSTANCE = new PduEncoder();

    @Override
    protected void encode(ChannelHandlerContext ctx, Pdu msg, ByteBuf out) {
        PduCodec.encode(msg, out);
    }
}
