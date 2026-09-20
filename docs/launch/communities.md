# Jasmin and Kannel communities

Short, useful, not salesy. These are people who need a target for integration tests.

## Jasmin (GitHub Discussions / Discord)

**Title:** A fake SMSC for testing Jasmin connectors: Cellophane

If you need an SMPP server to point a Jasmin SMPPClientConnector at during development or CI, I've released
Cellophane: https://github.com/tareqmy/cellophane

`docker run -p 2775:2775 -p 8025:8025 ghcr.io/tareqmy/cellophane`, bind with system_id/password
`cellophane`/`cellophane`, and every message Jasmin sends shows up in a web inbox with the decoded PDU. It
sends standard DLRs (DELIVRD/UNDELIV etc. with configurable delays and probabilities), can throttle or reject
by destination prefix, and can drop the bind after N messages, which is handy for testing Jasmin's reconnect
and retry behaviour. There's a REST API to assert on what arrived and to push MO messages back toward Jasmin.

There's a compose file in the repo that runs Jasmin with Cellophane as its upstream operator and configures the
connector, user and route through jCli automatically:
https://github.com/tareqmy/cellophane/tree/master/examples/jasmin. If your setup differs, I'd gladly take a PR
or a pointer.

## Kannel users list

**Subject:** Fake SMSC with a web inbox for testing bearerbox SMPP connections

Hi all,

For testing Kannel's SMPP `smsc` connections without a real operator I've released an open-source simulator,
Cellophane (https://github.com/tareqmy/cellophane). It runs in Docker, accepts SMPP 3.4 binds, shows every
submit_sm in a web inbox with the decoded and annotated PDU, sends configurable delivery receipts, and can
simulate throttling (ESME_RTHROTTLED), rejections and dropped links via a YAML rule list that can be changed
at runtime. It also has a REST API for assertions in automated tests and for injecting MO messages.

Feedback and reports of operator quirks worth simulating are very welcome.

Tareq
