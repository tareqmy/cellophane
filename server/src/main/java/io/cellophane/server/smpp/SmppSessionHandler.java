package io.cellophane.server.smpp;

import static io.cellophane.smpp.CommandStatus.ESME_RALYBND;
import static io.cellophane.smpp.CommandStatus.ESME_RINVBNDSTS;
import static io.cellophane.smpp.CommandStatus.ESME_RINVCMDID;
import static io.cellophane.smpp.CommandStatus.ESME_RINVPASWD;
import static io.cellophane.smpp.CommandStatus.ESME_RINVSYSID;
import static io.cellophane.smpp.CommandStatus.ESME_ROK;

import io.cellophane.server.account.Account;
import io.cellophane.server.account.AccountRegistry;
import io.cellophane.server.operator.Operator;
import io.cellophane.server.operator.ReceiptDispatcher;
import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.codec.PduException;
import io.cellophane.smpp.pdu.Bind;
import io.cellophane.smpp.pdu.BindResp;
import io.cellophane.smpp.pdu.DeliverSmResp;
import io.cellophane.smpp.pdu.EnquireLink;
import io.cellophane.smpp.pdu.GenericNack;
import io.cellophane.smpp.pdu.Pdu;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.pdu.Unbind;
import io.cellophane.smpp.pdu.UnknownPdu;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** The fake operator's side of one ESME connection: authenticates binds and accepts submits into the inbox. */
final class SmppSessionHandler extends SimpleChannelInboundHandler<Pdu> {

    private static final Logger log = LoggerFactory.getLogger(SmppSessionHandler.class);

    private final String smscSystemId;
    private final AccountRegistry accounts;
    private final SessionRegistry sessions;
    private final Operator operator;
    private final ReceiptDispatcher receipts;
    private SmppSession session;

    SmppSessionHandler(String smscSystemId, AccountRegistry accounts, SessionRegistry sessions, Operator operator,
                       ReceiptDispatcher receipts) {
        this.smscSystemId = smscSystemId;
        this.accounts = accounts;
        this.sessions = sessions;
        this.operator = operator;
        this.receipts = receipts;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        session = sessions.open(ctx.channel());
        log.info("[{}] ESME connected from {}", session.id(), session.remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        sessions.close(session);
        log.info("[{}] ESME disconnected", session.id());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Pdu pdu) {
        switch (pdu) {
            case Bind bind -> onBind(ctx, bind);
            case SubmitSm submit -> onSubmit(ctx, submit);
            case EnquireLink enquire -> ctx.writeAndFlush(enquire.respond());
            case Unbind unbind -> {
                log.info("[{}] unbind", session.id());
                ctx.writeAndFlush(unbind.respond(ESME_ROK.code())).addListener(ChannelFutureListener.CLOSE);
            }
            case DeliverSmResp resp -> {
                log.debug("[{}] deliver_sm_resp seq={} status={}", session.id(), resp.sequenceNumber(),
                        CommandStatus.describe(resp.commandStatus()));
                receipts.onReceiptAck(session, resp);
            }
            case GenericNack nack -> log.warn("[{}] ESME sent generic_nack seq={} status={}", session.id(),
                    nack.sequenceNumber(), CommandStatus.describe(nack.commandStatus()));
            case UnknownPdu unknown -> {
                log.warn("[{}] unsupported command {}", session.id(), unknown);
                ctx.writeAndFlush(new GenericNack(ESME_RINVCMDID.code(), unknown.sequenceNumber()));
            }
            default -> log.debug("[{}] ignoring {}", session.id(), pdu);
        }
    }

    private void onBind(ChannelHandlerContext ctx, Bind bind) {
        if (session.isBound()) {
            log.warn("[{}] bind while already bound", session.id());
            ctx.writeAndFlush(bind.respond(ESME_RALYBND.code(), smscSystemId));
            return;
        }
        Optional<Account> account = accounts.authenticate(bind.systemId(), bind.password());
        if (account.isEmpty()) {
            CommandStatus status = accounts.find(bind.systemId()).isPresent() ? ESME_RINVPASWD : ESME_RINVSYSID;
            log.warn("[{}] bind rejected for system_id '{}': {}", session.id(), bind.systemId(), status);
            ctx.writeAndFlush(bind.respond(status.code(), smscSystemId)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        BindType type = BindType.of(bind.command());
        sessions.bind(session, account.get(), type);
        log.info("[{}] '{}' bound as {}", session.id(), bind.systemId(), type);
        ctx.writeAndFlush(new BindResp(bind.command().response().orElseThrow(), ESME_ROK.code(),
                bind.sequenceNumber(), smscSystemId, List.of(Tlv.ofByte(Tlv.Tag.SC_INTERFACE_VERSION, 0x34))));
        receipts.flush(session);
    }

    private void onSubmit(ChannelHandlerContext ctx, SubmitSm submit) {
        if (!session.canTransmit()) {
            log.warn("[{}] submit_sm on a session that is {}", session.id(),
                    session.isBound() ? "bound as receiver" : "not bound");
            ctx.writeAndFlush(submit.respond(ESME_RINVBNDSTS.code(), ""));
            return;
        }
        byte[] raw = ctx.channel().attr(RawPduCapture.RAW_PDU).get();
        Operator.Outcome outcome = operator.onSubmit(session.account().orElseThrow().systemId(), session.id(),
                submit, raw != null ? raw : PduCodec.encode(submit));
        session.countSubmit();
        ctx.writeAndFlush(submit.respond(outcome.commandStatus(), outcome.messageId()));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof PduException e) {
            log.warn("[{}] undecodable PDU: {}", session.id(), e.getMessage());
            ChannelFuture nack = ctx.writeAndFlush(e.toNack());
            if (!e.headerKnown()) {
                // The byte stream itself is corrupt; nothing after this point can be framed.
                nack.addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }
        log.error("[{}] session failed", session.id(), cause);
        ctx.close();
    }
}
