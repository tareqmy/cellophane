# Changelog

All notable changes to Cellophane are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [SemVer](https://semver.org/).

## [Unreleased]

### Added

- `CELLOPHANE_DB=/path/inbox.db` keeps the inbox in a SQLite file so messages, parts, timelines and statuses
  survive a restart. The file is trimmed to `CELLOPHANE_MAX_MESSAGES` like the in-memory store, and `DELETE
  /api/v1/messages` empties it too.

## [0.3.0] - 2026-09-06

### Added

- The account window size is enforced: a session with more unanswered submits than its window gets
  `ESME_RMSGQFUL`, and the overflow shows in the inbox as a rejected message.
- Idle binds are dropped after `CELLOPHANE_IDLE_TIMEOUT` (default two minutes) of silence, like a real operator
  without keepalives; `enquire_link` keeps a session alive.
- `status=` filter on `GET /api/v1/messages` (comma-separated, case-insensitive) and a status dropdown in the UI.
- Logo: header mark, favicon and README image.

### Changed

- `throttle` now uses a sliding one-second window instead of wall-clock seconds, so a burst straddling a second
  boundary can no longer pass twice the limit.

## [0.2.0] - 2026-09-05

### Changed

- The build moved from Gradle to Maven (`./mvnw verify`). Same modules and artifacts; the container image is
  built by the `image` profile and the image smoke test is a failsafe integration test. Nothing changes for
  users of the image.

### Added

- Issue forms for bug reports and operator quirks, and launch notes under `docs/launch`.

## [0.1.0] - 2026-09-05

First release: a fake mobile operator with a real inbox.

### Added

- SMPP 3.4 server on Netty: transmitter, receiver and transceiver binds; `submit_sm`, `deliver_sm`,
  `enquire_link`, `unbind`, `generic_nack`; multiple ESME accounts from `CELLOPHANE_ACCOUNTS`.
- Own SMPP codec (`smpp` module, no Spring): GSM 03.38 with the extension table and 7-bit packing, Latin-1,
  UCS-2, UDH parsing and building, SAR TLVs, standard delivery receipt text, and a byte-range PDU annotator.
- Live inbox: messages stream over SSE; search by sender, recipient, text; concatenated messages reassembled
  into one entry with a parts badge; detail pane with decoded text, every PDU field explained, TLVs, UDH, a hex
  dump with hover highlighting, and the delivery receipt timeline. Dark mode and deep links.
- Rules in YAML, loaded from `CELLOPHANE_RULES` or changed at runtime through the API and the UI: `accept` with
  receipt state, delay or range and weighted outcomes; `reject` with any SMPP status; `throttle` per account
  per second; `latency` before `submit_sm_resp`; `disconnect` after N messages. First match wins; gates fall
  through.
- Delivery receipts as standard `deliver_sm` DLRs, one per part, honouring `registered_delivery`, queued while
  no receiver is bound for the account.
- REST API with OpenAPI at `/api`: list and search messages (`to`, `from`, `text`, `q`, `account`,
  `since=30s`), fetch one with full detail, clear, sessions, stats, rules, `POST /api/v1/send` for non-SMPP
  clients, `POST /api/v1/mo` to inject a mobile-originated message toward a receiver bind.
- Container image built with Paketo buildpacks (`ghcr.io/<owner>/cellophane`), GitHub Actions for build and
  publish, examples for docker-compose, CI assertions, node-smpp and cloudhopper.

[Unreleased]: https://github.com/tareqmy/cellophane/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/tareqmy/cellophane/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/tareqmy/cellophane/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/tareqmy/cellophane/releases/tag/v0.1.0
