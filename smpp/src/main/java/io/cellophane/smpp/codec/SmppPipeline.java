package io.cellophane.smpp.codec;

import io.netty.channel.ChannelPipeline;

/** Installs the SMPP framing, decoding and encoding handlers on a channel. */
public final class SmppPipeline {

    public static final String FRAME_DECODER = "smpp-frame-decoder";
    public static final String PDU_DECODER = "smpp-pdu-decoder";
    public static final String PDU_ENCODER = "smpp-pdu-encoder";

    private SmppPipeline() {
    }

    public static void install(ChannelPipeline pipeline) {
        install(pipeline, PduFrameDecoder.DEFAULT_MAX_PDU_LENGTH);
    }

    public static void install(ChannelPipeline pipeline, int maxPduLength) {
        pipeline.addLast(FRAME_DECODER, new PduFrameDecoder(maxPduLength));
        pipeline.addLast(PDU_DECODER, new PduDecoder());
        pipeline.addLast(PDU_ENCODER, PduEncoder.INSTANCE);
    }
}
