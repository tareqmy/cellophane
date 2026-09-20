Cellophane is a fake mobile operator for developing and testing SMS sending: one container that speaks SMPP 3.4,
catches everything in a live web inbox, and misbehaves on command.

```bash
docker run -p 2775:2775 -p 8025:8025 ghcr.io/tareqmy/cellophane
```

**In this release** (0.4.1 is the launch build: 0.4.0 with a multi-arch image; earlier tags predate the launch)

- SMPP 3.4 binds (TX, RX, TRX), `submit_sm` with UDH and SAR concatenation, GSM 7-bit, Latin-1 and UCS-2, delivery
  receipts as standard `deliver_sm` DLRs, multiple ESME accounts.
- A live inbox over SSE: search, reassembled multi-part messages, decoded text, every PDU field explained, a hex
  dump, TLVs, and the receipt timeline for each message.
- Rules in YAML to make the operator lie: accept with weighted receipt outcomes and delays, reject with any SMPP
  status, throttle per account, add latency, drop the link after N messages. Editable at runtime from the UI or
  the API.
- A REST API for CI: search and assert on messages, clear the inbox, swap rules, inject mobile-originated
  messages toward your receiver bind, read stats. OpenAPI at `/api`.
- `POST /api/v1/send` so non-SMPP apps and curl demos share the inbox.
- Operator realism outside the rules: per-account windows answered with `ESME_RMSGQFUL`, idle binds dropped
  after `CELLOPHANE_IDLE_TIMEOUT`, and a `status=` filter to find what failed.
- `CELLOPHANE_DB` keeps the inbox in a SQLite file so it survives restarts.
- One image for `linux/amd64` and `linux/arm64`, so it runs natively on Apple Silicon and Graviton.
- A Sessions panel: who is bound, how full each window is, live counters, and a form to inject
  mobile-originated messages.
- Examples: docker-compose with rules, a CI assertion script, cloudhopper and node-smpp clients, and Cellophane
  as the operator behind a Jasmin gateway.

See the [README](https://github.com/tareqmy/cellophane#readme) and [docs/rules.md](https://github.com/tareqmy/cellophane/blob/master/docs/rules.md).
