package io.cellophane.server.message;

import io.cellophane.smpp.pdu.Address;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A logical message in the inbox: what the sending application meant to send, reassembled from one or more
 * {@link Segment}s when it was concatenated.
 *
 * @param receivedAt      when the first part arrived
 * @param updatedAt       when the latest part arrived
 * @param text            the parts' text joined in order; null when the data is binary
 * @param concatReference the concat reference number shared by the parts, or null for a single-part message
 * @param parts           the number of parts announced by the sender (1 when not concatenated)
 */
public record Message(String id, Instant receivedAt, Instant updatedAt, String account, Address from, Address to,
                      int dataCoding, String encoding, String text, Integer concatReference, int parts,
                      List<Segment> segments, MessageStatus status) {

    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(status, "status");
        if (parts < 1 || segments.isEmpty()) {
            throw new IllegalArgumentException("a message needs at least one part and one segment");
        }
        segments = segments.stream().sorted(Comparator.comparingInt(Segment::sequence)).toList();
    }

    /** A new message whose first (and possibly only) part is {@code first}. The message id is the segment's. */
    static Message of(String account, String encoding, Integer concatReference, int parts, Segment first) {
        List<Segment> segments = List.of(first);
        return new Message(first.messageId(), first.receivedAt(), first.receivedAt(), account,
                first.pdu().source(), first.pdu().destination(), first.pdu().dataCoding(), encoding,
                composeText(segments), concatReference, parts, segments, MessageStatus.ACCEPTED);
    }

    public int partsReceived() {
        return segments.size();
    }

    public boolean isComplete() {
        return segments.size() >= parts;
    }

    public boolean hasSegment(int sequence) {
        return segments.stream().anyMatch(s -> s.sequence() == sequence);
    }

    /** This message with one more part added. */
    Message withSegment(Segment segment) {
        List<Segment> all = new ArrayList<>(segments);
        all.add(segment);
        return new Message(id, receivedAt, segment.receivedAt(), account, from, to, dataCoding, encoding,
                composeText(all), concatReference, parts, all, status);
    }

    private static String composeText(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments.stream().sorted(Comparator.comparingInt(Segment::sequence)).toList()) {
            if (s.text() == null) {
                return null;
            }
            sb.append(s.text());
        }
        return sb.toString();
    }
}
