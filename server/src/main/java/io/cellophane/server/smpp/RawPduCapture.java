package io.cellophane.server.smpp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

/**
 * Keeps a copy of the most recent framed PDU's bytes on the channel so the inbox can show the raw hex.
 * Sits between the frame decoder and the PDU decoder; the channel's event loop serialises reads, so the
 * attribute always refers to the PDU currently being handled.
 */
final class RawPduCapture extends ChannelInboundHandlerAdapter {

    static final AttributeKey<byte[]> RAW_PDU = AttributeKey.valueOf("cellophane.rawPdu");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
            ctx.channel().attr(RAW_PDU).set(ByteBufUtil.getBytes(buf));
        }
        ctx.fireChannelRead(msg);
    }
}
