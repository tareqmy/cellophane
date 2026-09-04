package io.cellophane.smpp.codec;

import io.cellophane.smpp.CommandStatus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;

/**
 * Splits the TCP stream into one buffer per PDU using the leading {@code command_length} field.
 * Frames that cannot possibly be a PDU (length below 4 or above the configured maximum) surface as a
 * {@link PduException} with {@code ESME_RINVCMDLEN}.
 */
public final class PduFrameDecoder extends LengthFieldBasedFrameDecoder {

    /** Generous default: SMPP bodies top out around 64 KiB because of {@code message_payload}. */
    public static final int DEFAULT_MAX_PDU_LENGTH = 65_536 + 16;

    public PduFrameDecoder() {
        this(DEFAULT_MAX_PDU_LENGTH);
    }

    public PduFrameDecoder(int maxPduLength) {
        super(maxPduLength, 0, 4, -4, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        try {
            return super.decode(ctx, in);
        } catch (CorruptedFrameException | TooLongFrameException e) {
            throw new PduException(CommandStatus.ESME_RINVCMDLEN.code(), "invalid command_length: " + e.getMessage(), e);
        }
    }
}
