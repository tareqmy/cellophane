package io.cellophane.smpp.codec;

import io.cellophane.smpp.pdu.Pdu;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/** Turns each framed PDU buffer from {@link PduFrameDecoder} into a {@link Pdu}. */
public final class PduDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        out.add(PduCodec.decode(msg));
    }
}
