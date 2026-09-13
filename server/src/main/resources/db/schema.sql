-- Cellophane inbox persistence (CELLOPHANE_DB). One row per logical message, one per submit_sm part,
-- one per timeline entry. Instants are ISO-8601 text so they round-trip exactly.
CREATE TABLE IF NOT EXISTS messages (
    id          TEXT PRIMARY KEY,
    received_at TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    account     TEXT    NOT NULL,
    from_ton    INTEGER NOT NULL,
    from_npi    INTEGER NOT NULL,
    from_addr   TEXT    NOT NULL,
    to_ton      INTEGER NOT NULL,
    to_npi      INTEGER NOT NULL,
    to_addr     TEXT    NOT NULL,
    data_coding INTEGER NOT NULL,
    encoding    TEXT    NOT NULL,
    text        TEXT,
    concat_ref  INTEGER,
    parts       INTEGER NOT NULL,
    status      TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS segments (
    id          TEXT PRIMARY KEY,
    message_id  TEXT    NOT NULL,
    sequence    INTEGER NOT NULL,
    received_at TEXT    NOT NULL,
    session_id  TEXT    NOT NULL,
    raw_pdu     BLOB    NOT NULL,
    status      TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS segments_message ON segments (message_id);

CREATE TABLE IF NOT EXISTS events (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    segment_id TEXT NOT NULL,
    at         TEXT NOT NULL,
    type       TEXT NOT NULL,
    detail     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS events_segment ON events (segment_id);
