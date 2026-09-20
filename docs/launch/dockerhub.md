# Docker Hub description

**Short description** (100 chars max):

Mailpit for SMS: fake SMPP operator with a live inbox, decoded PDUs and chaos rules for testing

**Full description:**

# Cellophane

**The see-through SMSC.** A fake mobile operator with a real inbox, for developing and testing SMS sending.

```bash
docker run -p 2775:2775 -p 8025:8025 tareqmy/cellophane
```

Bind your SMPP client to `localhost:2775` (system_id `cellophane`, password `cellophane`) and open
http://localhost:8025. Every message lands in a live inbox where you can read the decoded text, see
concatenated parts reassembled, inspect every PDU field next to its hex dump, and watch the delivery receipts
arrive. Then make the operator misbehave with rules: throttle, reject, delay, fail a share of deliveries, drop
the connection.

## Configuration

| Variable | Default | What it does |
|---|---|---|
| `CELLOPHANE_SMPP_PORT` | `2775` | SMPP listen port |
| `CELLOPHANE_HTTP_PORT` | `8025` | Web UI + API port |
| `CELLOPHANE_ACCOUNTS` | `cellophane:cellophane` | Comma-separated `system_id:password[:window]` |
| `CELLOPHANE_RULES` | – | Path to a mounted rules YAML file |
| `CELLOPHANE_MAX_MESSAGES` | `10000` | In-memory inbox size |
| `CELLOPHANE_IDLE_TIMEOUT` | `2m` | Drop binds that stay silent this long; `0` disables |
| `CELLOPHANE_DB` | – | SQLite file to keep the inbox across restarts; mount a volume under `/home/cnb` for it |

## Tags

- `latest`: the most recent release
- `x.y.z`: a specific release

Every tag is a multi-arch image for `linux/amd64` and `linux/arm64`, so it runs natively on Apple Silicon and
Graviton.

Source, documentation and issues: https://github.com/tareqmy/cellophane (MIT licence).
