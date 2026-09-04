# Cellophane — v1 product spec

*"The Mailpit for SMS": a single Docker container that pretends to be a mobile operator so you can develop and test SMS sending without touching a real network. The name: a fake cell you can see straight through.*

Status: draft v0.2 · Author: Tareq · Date: 2026-09-04

---

## 1. Why this should exist

Every team that sends SMS (A2P aggregators, banks, OTP providers, SaaS notification stacks, SMS gateways like Jasmin/Kannel) needs a fake SMSC to develop against. What they use today is SMPPSim, a Java tool whose last release was around 2014, configured through a properties file that requires a restart, with a 1990s-style HTML console. Every Docker image of it is a third-party wrapper that is 4–9 years stale, and one of them (`jookies/smppsim`) has 10k+ pulls with no README at all. Demand is proven; the tooling is abandoned.

The email world had the same problem and solved it twice: MailHog (~16k stars, now dead) and Mailpit (~10k stars, very active). Mailpit's formula was simple: one binary, an official Docker image, a polished web inbox, and a REST API for test assertions. Nobody has applied that formula to SMS. The entire open-source SMPP simulator category combined has under ~200 GitHub stars, and every project is single-maintainer with most dormant.

The two most recent entrants (2026) each hold one piece of the puzzle: `rust-smpp-sim` has a web dashboard and a DLR lifecycle but no fault injection; `go-smsc-simulator` has excellent YAML fault profiles (flaky, throttling, dead carrier) but no UI and a read-only API. Neither has a searchable message inbox, decoded PDUs, or a runtime control API. Combining all of that in one tool is the gap.

## 2. Positioning

> **Cellophane** — catch, inspect and script SMS traffic in development. Run one container, point your app's SMPP or HTTP client at it, watch messages arrive in a live inbox, and make the fake operator misbehave on demand.

One-line pitch for the README: *"Mailpit for SMS. The see-through SMSC."*

## 3. Who it is for

Primary: backend developers and QA engineers at companies that send SMS via SMPP (aggregators, telcos, fintech, OTP/2FA vendors). They hit this pain weekly and have no good option.

Secondary: developers of SMS gateways and libraries (Jasmin, Kannel, cloudhopper, node-smpp, gosmpp users) who need a target for integration tests and CI.

Tertiary: platform/SRE teams who want to load-test routing engines against simulated operator behaviour (throttling, outages) without paying per message.

## 4. v1 scope — what ships

The rule for v1: everything needed for the screenshot and the `docker run` to be impressive, nothing else.

**SMPP 3.4 server (the "fake operator").**
Bind as transmitter, receiver or transceiver; `submit_sm`, `deliver_sm`, `enquire_link`, `unbind`, `generic_nack`; multiple ESME accounts (system_id/password) with per-account window size; `submit_sm` with UDH and SAR concatenation; `registered_delivery` honoured with DLR `deliver_sm` in the standard receipt text format. Data codings GSM 7-bit (packed and unpacked), Latin-1, UCS-2. Plaintext TCP only.

**HTTP send endpoint.**
`POST /api/v1/send` with `{from, to, text}` so non-SMPP apps (and curl demos) can use the same inbox. This is cheap and makes the README's first example a one-liner.

**Live inbox (web UI).**
Real-time list of received messages via server-sent events; full-text search over sender, recipient, text; filter by account, status, date; message detail with decoded text, encoding, concatenation reassembly (segments grouped into one logical message with a "3/3 parts" badge), raw PDU hex dump with field annotations, TLVs, and the DLR timeline for that message. Clear inbox button. Dark mode, because screenshots.

**Behaviour rules ("make the operator misbehave").**
An ordered list of rules loaded from YAML at start and editable at runtime. Each rule has a match (destination prefix or regex, sender, account, text regex, or `default`) and an action:

- `accept` with DLR status (`DELIVRD`, `UNDELIV`, `EXPIRED`, `REJECTD`, `ACCEPTD`, `UNKNOWN`) and a delay or delay range, optionally a weighted distribution (e.g. 90% DELIVRD after 2s, 10% UNDELIV after 30s);
- `reject` with an SMPP error code (`ESME_RINVDSTADR`, `ESME_RMSGQFUL`, `ESME_RTHROTTLED`, `ESME_RINVSRCADR`, any numeric code);
- `throttle` at N submits per second per account, returning `ESME_RTHROTTLED` above the limit;
- `latency` adding a fixed or random delay before the `submit_sm_resp`;
- `disconnect` closing the bind after the message (simulate an operator dropping the link).

Rules are the feature that lets an A2P routing engine be tested for "prefix 01711 goes to operator A which is throttling today". This is what nobody else has and what your day job makes you uniquely able to design well.

**Control REST API.**
Everything in the UI is also an endpoint, so CI can drive it: list/search messages, get one message, delete all, assert helpers (`GET /api/v1/messages?to=017...&text=OTP&since=...`), inject an MO message toward a bound receiver, get/put rules, stats (bound sessions, TPS, counts by status). OpenAPI spec generated and served at `/api`.

**Packaging.**
Official Docker image on GHCR and Docker Hub, `docker run -p 2775:2775 -p 8025:8025 ghcr.io/<you>/cellophane`. Config via env vars and an optional mounted YAML. In-memory storage by default with an optional SQLite file for persistence across restarts.

## 5. Explicitly out of scope for v1

SMPP 5.0 and 3.3, TLS, UCP/CIMD2/HTTP operator APIs, outbind, `data_sm`/`broadcast_sm`, message priority and scheduling semantics, authentication on the web UI, clustering, Kubernetes manifests, Prometheus metrics, and a Testcontainers module. Several of these are good v1.1/v2 items and are listed on the README roadmap so people know they are coming, but none are required for the launch to land.

## 6. Architecture and stack

**SMPP layer: our own SMPP 3.4 codec on Netty 4.** The two established Java libraries were checked and both are legacy: cloudhopper-smpp's stable 5.0.9 runs on Netty 3 with its Netty 4 line stuck at `6.0.0-netty4-beta-3`, and jSMPP's last release was 2.3.11 (August 2021) on blocking sockets with a thread per session. SMPP 3.4 is small (16-byte header, ~15 PDU types we need, C-octet strings, a handful of TLVs), so a clean `ByteToMessageDecoder`/`MessageToByteEncoder` pair plus PDU records is roughly two thousand lines and one to two weeks of evenings. Owning the codec is what allows the interesting misbehaviour (truncated PDUs, garbage bytes, disconnect mid-write, bad sequence numbers), makes SMPP 5.0 a later branch rather than a rewrite, and is the most impressive code in the repo. It lives in a `cellophane-smpp` module with zero Spring dependencies so it can be published as a library on its own. GSM 03.38 7-bit packing/unpacking and UDH parsing live in the same module. cloudhopper is used only as the *client* in integration tests, so the codec is verified against an independent implementation.

**Runtime: Java 21 and Spring Boot 3.5.** Spring MVC (not WebFlux) with `SseEmitter` for the live inbox, springdoc-openapi for the API page, Micrometer for later metrics, and Boot's built-in GraalVM native support so "instant start, tiny image" is a Gradle flag later, not a rewrite. Netty owns the SMPP port; Spring owns HTTP. Netty's `HashedWheelTimer` schedules delayed DLRs and latency injection. This is a deliberate choice against Go: your expertise is Java, the project signals Java competence to the people you want reading your profile, and the Java SMS ecosystem is the largest.

**Storage:** bounded in-memory deque (configurable, default 10k messages) with a simple index; when `CELLOPHANE_DB` is set, persist through `sqlite-jdbc` using Spring's `JdbcClient` and hand-written SQL from a `schema.sql`. No JPA. SQLite FTS5 is the upgrade path for search.

**Rules engine:** plain Java. Jackson YAML for loading, a sealed `RuleAction` interface with the five actions above, first-match evaluation, regexes compiled at load. Boring on purpose so a contributor can add an action in one class.

**Frontend:** Vite + TypeScript with Svelte 5 (smallest thing to learn from a Java background) or React if already familiar; Tailwind so dark mode is a `dark:` prefix. SSE for live updates. One page, three panes: message list, message detail, rules. The Gradle build runs `npm run build` via the `node-gradle` plugin and copies `dist/` into the jar's `static/`, so the deliverable stays one jar and one image.

**Build and delivery:** Gradle with Kotlin DSL, three modules (`smpp`, `server`, `ui`). Docker image via `bootBuildImage` (Paketo buildpacks, no Dockerfile) published to GHCR on every tag by GitHub Actions; Dependabot; JReleaser or release-please for changelogs. Tests: JUnit 5, cloudhopper-client conformance tests against the codec, one Testcontainers smoke test that runs the real image. Licence MIT.

Repo layout: `smpp/` (codec, no Spring), `server/` (Spring Boot), `ui/` (SPA), `docs/`, `examples/` (docker-compose with Jasmin, a Spring Boot client sample, a node-smpp sample), `.github/workflows/` (build, test, publish image on tag).

## 7. Milestone plan (evenings, ~9 weeks)

Each milestone ends with something you can screenshot or run, because that is what keeps an evening project alive. Writing the codec adds about a week compared with using a library; it is worth it for the reasons in section 6.

Weeks 1–2, *it speaks SMPP*: the `smpp` module — PDU header, `bind_*`/`unbind`/`enquire_link`/`submit_sm`/`deliver_sm`/`generic_nack` and their responses, TLVs, GSM 03.38 and UCS-2 codecs, UDH parsing — with unit tests and a cloudhopper-client round-trip test. No UI yet; the milestone is a green test suite and a hex dump in the log.

Week 3, *it binds*: Spring server hosting the Netty listener, accounts, messages stored in memory, a bare UI that lists them live over SSE.

Week 4, *it decodes*: GSM7/UCS-2 decoding, UDH concatenation reassembly, PDU hex view, message detail pane. The first "wow" screenshot.

Week 5, *it lies*: YAML rules with accept/DLR lifecycle and reject; DLR timeline in the detail pane.

Week 6, *it is scriptable*: throttle, latency, disconnect actions; control REST API with OpenAPI; MO injection; HTTP send endpoint.

Week 7, *it ships*: `bootBuildImage` + GHCR publish workflow, `examples/`, README with GIF, docs site (a single `docs/` folder rendered by GitHub is fine).

Week 8, *it is trustworthy*: integration tests using cloudhopper client against the server, a GitHub Actions matrix, a `v0.1.0` tag, and a CHANGELOG.

Week 9, *launch*: Show HN, r/java, r/telecom, the smpp.org testing-tools page (ask to be listed), Jasmin and Kannel mailing lists/Discord, a LinkedIn post with the GIF, and a Docker Hub description. Then respond to every issue for the first month; early responsiveness is what converts stars into contributors.

## 8. Success criteria for the first 90 days after launch

500 GitHub stars, 5k image pulls, at least three external contributors, listed on smpp.org, and at least one company you don't work at saying they use it in CI. Modest numbers, but they would already make it the most-used tool in its category.

## 9. Open decisions

Name: **Cellophane** (decided). Before the first commit, claim the GitHub org/user, the Docker Hub namespace, a `.dev`/`.io` domain, and use `io.cellophane` (or the domain you get) as the Maven group id. Licence: MIT (matches Mailpit and lowers adoption friction; Apache-2.0 if you care about the patent clause). UI framework: Svelte unless you already know React; the UI is a thin client.

## Sources for the landscape claims

SMPPSim ([seleniumsoftware.com](https://seleniumsoftware.com/), README mirror at [haifzhan/SMPPSim](https://github.com/haifzhan/SMPPSim)), Docker wrappers [jookies/smppsim](https://hub.docker.com/r/jookies/smppsim), [bitsensedev/smpp-sim](https://hub.docker.com/r/bitsensedev/smpp-sim), [eagafonov/smppsim](https://hub.docker.com/r/eagafonov/smppsim); [rust-smpp-sim](https://github.com/TheGU/rust-smpp-sim); [go-smsc-simulator](https://github.com/martialanouman/go-smsc-simulator); [ukarim/smscsim](https://github.com/ukarim/smscsim); [melroselabs/smpp-smsc-simulator](https://github.com/melroselabs/smpp-smsc-simulator); [MikeSafonov/smpp-server-mock](https://github.com/MikeSafonov/smpp-server-mock); [Auron SMPP Simulator](https://github.com/Auron-Software/AuSmppSimulator); [smppsink](https://github.com/ten0s/smppsink); [cloudhopper-smpp](https://github.com/fizzed/cloudhopper-smpp); [Mailpit](https://github.com/axllent/mailpit); [MailHog](https://github.com/mailhog/MailHog); [smpp.org testing tools](https://smpp.org/smpp-testing-development.html).
