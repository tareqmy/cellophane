package io.cellophane.server.api;

import io.cellophane.server.message.Event;
import io.cellophane.server.message.Message;
import io.cellophane.server.message.Segment;
import io.cellophane.smpp.codec.PduAnnotator;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.cellophane.smpp.pdu.Tlv;
import io.cellophane.smpp.text.Udh;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Everything the inbox knows about one message: each part's decoded PDU, its raw bytes and their annotation. */
public record MessageDetail(String id, Instant receivedAt, Instant updatedAt, String account, AddressView from,
                            AddressView to, String text, String encoding, int dataCoding, int parts,
                            int partsReceived, Integer concatReference, String status, List<SegmentView> segments) {

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

    /** A byte range of the raw PDU and what it means. */
    public record FieldView(int offset, int length, String name, String value) {
        static FieldView of(PduAnnotator.Field f) {
            return new FieldView(f.offset(), f.length(), f.name(), f.value());
        }
    }

    /** A timeline entry. */
    public record EventView(Instant at, String type, String detail) {
        static EventView of(Event e) {
            return new EventView(e.at(), e.type().name(), e.detail());
        }
    }

    public record SegmentView(String messageId, int sequence, Instant receivedAt, String sessionId, String text,
                              String status, List<EventView> events, PduView pdu, UdhView udh, String rawPduHex,
                              List<FieldView> fields) {
        static SegmentView of(Segment s) {
            return new SegmentView(s.messageId(), s.sequence(), s.receivedAt(), s.sessionId(), s.text(),
                    s.status().name(), s.events().stream().map(EventView::of).toList(), PduView.of(s.pdu()),
                    s.udh() == null ? null : UdhView.of(s.udh()), HEX.formatHex(s.rawPdu()),
                    PduAnnotator.annotate(s.rawPdu()).stream().map(FieldView::of).toList());
        }
    }

    public static MessageDetail of(Message m) {
        return new MessageDetail(m.id(), m.receivedAt(), m.updatedAt(), m.account(), AddressView.of(m.from()),
                AddressView.of(m.to()), m.text(), m.encoding(), m.dataCoding(), m.parts(), m.partsReceived(),
                m.concatReference(), m.status().name(), m.segments().stream().map(SegmentView::of).toList());
    }
}
