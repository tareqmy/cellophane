package io.cellophane.server.api;

import io.cellophane.server.message.Message;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.text.Udh;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Everything the inbox knows about one message, including the decoded PDU fields and the raw bytes. */
public record MessageDetail(String id, Instant receivedAt, String account, String sessionId, AddressView from,
                            AddressView to, String text, String encoding, int parts, Integer part, String status,
                            PduView pdu, UdhView udh, String rawPduHex) {

    private static final HexFormat HEX = HexFormat.of();

    public record AddressView(int ton, int npi, String address) {
        static AddressView of(Address a) {
            return new AddressView(a.ton(), a.npi(), a.address());
        }
    }

    public record TlvView(int tag, String name, int length, String hex) {
        static TlvView of(Tlv t) {
            return new TlvView(t.tag(), t.tagName(), t.length(), HEX.formatHex(t.value()));
        }
    }

    public record PduView(int sequenceNumber, String serviceType, int esmClass, int protocolId, int priorityFlag,
                          String scheduleDeliveryTime, String validityPeriod, int registeredDelivery,
                          int replaceIfPresent, int dataCoding, int smDefaultMsgId, String shortMessageHex,
                          List<TlvView> tlvs) {
        static PduView of(SubmitSm p) {
            return new PduView(p.sequenceNumber(), p.serviceType(), p.esmClass(), p.protocolId(), p.priorityFlag(),
                    p.scheduleDeliveryTime(), p.validityPeriod(), p.registeredDelivery(), p.replaceIfPresent(),
                    p.dataCoding(), p.smDefaultMsgId(), HEX.formatHex(p.shortMessage()),
                    p.tlvs().stream().map(TlvView::of).toList());
        }
    }

    public record UdhElementView(int id, String hex) {
    }

    public record UdhView(Integer reference, Integer total, Integer sequence, List<UdhElementView> elements) {
        static UdhView of(Udh udh) {
            Udh.Concat c = udh.concat().orElse(null);
            return new UdhView(c == null ? null : c.reference(), c == null ? null : c.total(),
                    c == null ? null : c.sequence(),
                    udh.elements().stream().map(e -> new UdhElementView(e.id(), HEX.formatHex(e.data()))).toList());
        }
    }

    public static MessageDetail of(Message m) {
        MessageSummary s = MessageSummary.of(m);
        return new MessageDetail(s.id(), s.receivedAt(), s.account(), m.sessionId(), AddressView.of(m.from()),
                AddressView.of(m.to()), s.text(), s.encoding(), s.parts(), s.part(), s.status(),
                PduView.of(m.pdu()), m.udh() == null ? null : UdhView.of(m.udh()), HEX.formatHex(m.rawPdu()));
    }
}
