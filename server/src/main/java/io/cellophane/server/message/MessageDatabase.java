package io.cellophane.server.message;

import io.cellophane.smpp.codec.PduCodec;
import io.cellophane.smpp.pdu.Address;
import io.cellophane.smpp.pdu.SubmitSm;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the inbox in a SQLite file so it survives a restart. The in-memory {@link MessageStore} stays the source
 * of truth; every change is journalled here from the listener hook on one background thread, and at start-up
 * {@link #restore} refills the store with what was saved, up to its capacity. Persistence is best effort: a
 * failed write is logged and never fails the submit that caused it.
 */
public final class MessageDatabase implements MessageListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MessageDatabase.class);

    private final Path path;
    private final int capacity;
    private final int pruneEvery;
    private final Connection connection;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cellophane-db");
        t.setDaemon(true);
        return t;
    });
    private int insertsSincePrune;

    /**
     * Opens (creating if needed) the database at {@code path}. {@code capacity} is the store's: the file is
     * trimmed to about that many newest messages so it does not grow without bound.
     */
    public MessageDatabase(Path path, int capacity) {
        this.path = path;
        this.capacity = capacity;
        this.pruneEvery = Math.max(1, capacity / 10);
        Path dir = path.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalStateException("cannot open database " + path + ": directory " + dir
                    + " does not exist");
        }
        if (!Files.isWritable(dir)) {
            throw new IllegalStateException("cannot open database " + path + ": directory " + dir
                    + " is not writable by this process (in the container the app runs as a non-root user;"
                    + " mount the volume at a directory the image already owns, such as /home/cnb)");
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                for (String ddl : schema().split(";")) {
                    if (!ddl.isBlank()) {
                        s.execute(ddl);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path + ": " + e.getMessage(), e);
        }
    }

    private static String schema() {
        try (InputStream in = MessageDatabase.class.getResourceAsStream("/db/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("db/schema.sql missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path path() {
        return path;
    }

    // ---------------------------------------------------------------- restore

    /** Loads the saved messages, oldest first, into {@code store}. Returns how many were loaded. */
    public int restore(MessageStore store) {
        try {
            Map<String, List<Event>> events = loadEvents();
            Map<String, List<Segment>> segments = loadSegments(events);
            List<Message> messages = loadMessages(segments);
            messages.forEach(store::add);
            log.info("restored {} message(s) from {}", messages.size(), path);
            return messages.size();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read database " + path + ": " + e.getMessage(), e);
        }
    }

    private Map<String, List<Event>> loadEvents() throws SQLException {
        Map<String, List<Event>> bySegment = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT segment_id, at, type, detail FROM events ORDER BY id")) {
            while (rs.next()) {
                bySegment.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Event(
                        Instant.parse(rs.getString(2)), EventType.valueOf(rs.getString(3)), rs.getString(4)));
            }
        }
        return bySegment;
    }

    private Map<String, List<Segment>> loadSegments(Map<String, List<Event>> events) throws SQLException {
        Map<String, List<Segment>> byMessage = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, message_id, sequence, received_at, session_id, raw_pdu, status"
                     + " FROM segments ORDER BY rowid")) {
            while (rs.next()) {
                String id = rs.getString(1);
                byte[] raw = rs.getBytes(6);
                try {
                    SubmitSm pdu = (SubmitSm) PduCodec.decode(Unpooled.wrappedBuffer(raw));
                    Inbox.Decoded decoded = Inbox.decode(pdu);
                    Segment segment = new Segment(id, rs.getInt(3), Instant.parse(rs.getString(4)), rs.getString(5),
                            pdu, raw, decoded.udh(), decoded.text(), MessageStatus.valueOf(rs.getString(7)),
                            events.getOrDefault(id, List.of()));
                    byMessage.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(segment);
                } catch (RuntimeException e) {
                    log.warn("skipping saved part {}: {}", id, e.toString());
                }
            }
        }
        return byMessage;
    }

    private List<Message> loadMessages(Map<String, List<Segment>> segments) throws SQLException {
        List<Message> newestFirst = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("SELECT id, received_at, updated_at, account,"
                + " from_ton, from_npi, from_addr, to_ton, to_npi, to_addr, data_coding, encoding, text, concat_ref,"
                + " parts FROM messages ORDER BY rowid DESC LIMIT ?")) {
            s.setInt(1, capacity);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    List<Segment> parts = segments.get(id);
                    if (parts == null) {
                        log.warn("skipping saved message {}: no readable parts", id);
                        continue;
                    }
                    Integer concatRef = rs.getObject(14) == null ? null : rs.getInt(14);
                    newestFirst.add(new Message(id, Instant.parse(rs.getString(2)), Instant.parse(rs.getString(3)),
                            rs.getString(4), new Address(rs.getInt(5), rs.getInt(6), rs.getString(7)),
                            new Address(rs.getInt(8), rs.getInt(9), rs.getString(10)), rs.getInt(11),
                            rs.getString(12), rs.getString(13), concatRef, rs.getInt(15), parts, null));
                }
            }
        }
        return newestFirst.reversed();
    }

    // ---------------------------------------------------------------- journal

    @Override
    public void onMessage(Message message) {
        enqueue(() -> {
            save(message);
            if (++insertsSincePrune >= pruneEvery) {
                insertsSincePrune = 0;
                prune();
            }
        });
    }

    @Override
    public void onUpdated(Message message) {
        enqueue(() -> save(message));
    }

    @Override
    public void onCleared() {
        enqueue(() -> {
            try (Statement s = connection.createStatement()) {
                s.execute("DELETE FROM events");
                s.execute("DELETE FROM segments");
                s.execute("DELETE FROM messages");
            }
        });
    }

    /** Waits until every change queued so far has been written. */
    public void flush() {
        try {
            writer.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("database writer did not drain", e);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("database writer still busy after 10s; some changes may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("closing {}: {}", path, e.getMessage());
        }
    }

    private interface Write {
        void run() throws SQLException;
    }

    private void enqueue(Write write) {
        try {
            writer.execute(() -> {
                try {
                    write.run();
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly();
                    log.warn("database write failed: {}", e.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("database closed; change not saved");
        }
    }

    private void rollbackQuietly() {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // nothing more to do
        }
    }

    /** Upserts the message and its parts and rewrites their timelines, all in one transaction. */
    private void save(Message m) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement message = connection.prepareStatement("INSERT INTO messages (id, received_at,"
                + " updated_at, account, from_ton, from_npi, from_addr, to_ton, to_npi, to_addr, data_coding,"
                + " encoding, text, concat_ref, parts, status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(id) DO UPDATE SET updated_at=excluded.updated_at, text=excluded.text,"
                + " status=excluded.status");
             PreparedStatement segment = connection.prepareStatement("INSERT INTO segments (id, message_id,"
                + " sequence, received_at, session_id, raw_pdu, status) VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT(id) DO UPDATE SET status=excluded.status");
             PreparedStatement deleteEvents = connection.prepareStatement("DELETE FROM events WHERE segment_id=?");
             PreparedStatement event = connection.prepareStatement("INSERT INTO events (segment_id, at, type,"
                + " detail) VALUES (?,?,?,?)")) {
            message.setString(1, m.id());
            message.setString(2, m.receivedAt().toString());
            message.setString(3, m.updatedAt().toString());
            message.setString(4, m.account());
            message.setInt(5, m.from().ton());
            message.setInt(6, m.from().npi());
            message.setString(7, m.from().address());
            message.setInt(8, m.to().ton());
            message.setInt(9, m.to().npi());
            message.setString(10, m.to().address());
            message.setInt(11, m.dataCoding());
            message.setString(12, m.encoding());
            message.setString(13, m.text());
            message.setObject(14, m.concatReference());
            message.setInt(15, m.parts());
            message.setString(16, m.status().name());
            message.executeUpdate();
            for (Segment s : m.segments()) {
                segment.setString(1, s.messageId());
                segment.setString(2, m.id());
                segment.setInt(3, s.sequence());
                segment.setString(4, s.receivedAt().toString());
                segment.setString(5, s.sessionId());
                segment.setBytes(6, s.rawPdu());
                segment.setString(7, s.status().name());
                segment.executeUpdate();
                deleteEvents.setString(1, s.messageId());
                deleteEvents.executeUpdate();
                for (Event e : s.events()) {
                    event.setString(1, s.messageId());
                    event.setString(2, e.at().toString());
                    event.setString(3, e.type().name());
                    event.setString(4, e.detail());
                    event.addBatch();
                }
                event.executeBatch();
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Drops everything older than the newest {@code capacity} messages, mirroring the store's eviction. */
    private void prune() throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement messages = connection.prepareStatement("DELETE FROM messages WHERE rowid <="
                + " (SELECT rowid FROM messages ORDER BY rowid DESC LIMIT 1 OFFSET ?)");
             Statement s = connection.createStatement()) {
            messages.setInt(1, capacity);
            int dropped = messages.executeUpdate();
            if (dropped > 0) {
                s.execute("DELETE FROM segments WHERE message_id NOT IN (SELECT id FROM messages)");
                s.execute("DELETE FROM events WHERE segment_id NOT IN (SELECT id FROM segments)");
                log.debug("pruned {} message(s) from {}", dropped, path);
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Row counts, for tests and diagnostics. */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        flush();
        try (Statement s = connection.createStatement()) {
            for (String table : List.of("messages", "segments", "events")) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return counts;
    }
}
