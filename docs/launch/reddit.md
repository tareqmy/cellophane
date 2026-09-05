# Reddit drafts

## r/java

**Title:** Cellophane: a fake SMPP operator with a live inbox, built with Java 21, Spring Boot and a hand-written
SMPP codec on Netty

**Body:**

If you've ever had to test SMS sending you've probably met SMPPSim, the ten-year-old simulator with the
properties file and the restart. I built a modern replacement and thought this sub might like the Java side of
it.

Cellophane is one container: you bind your SMPP client to it, messages appear in a live web inbox with decoded
text and an annotated hex dump of every PDU, and a YAML rule list lets you make the "operator" reject, throttle,
delay, drop connections, or send DELIVRD/UNDELIV receipts with whatever probabilities you want.

Java notes:

- Own SMPP 3.4 codec (~2k lines, no Spring dependency, separate module). cloudhopper is Netty 3 and jSMPP is
  blocking with a thread per session, so writing one on Netty 4 was less work than it sounds and makes the
  misbehaviour features possible. cloudhopper is used as the *client* in tests so the codec is checked against
  an independent implementation.
- Records and sealed interfaces everywhere: the PDU model is a sealed `Pdu` with a record per command, and the
  rule actions are a sealed `RuleAction`. Pattern-matching switches made the codec and the rule engine short.
- Spring Boot 4.1, Spring MVC with `SseEmitter` for the live inbox, no JPA, no reflection magic.
- Maven build that also builds the Svelte UI (frontend-maven-plugin) and the container image (buildpacks, no
  Dockerfile). A Testcontainers integration test runs the real image, which caught a module missing from the
  jlinked JRE.

Repo: https://github.com/tareqmy/cellophane

Happy to answer questions about the codec or the Netty side.

## r/telecom

**Title:** Open-source SMSC simulator with a searchable inbox and configurable operator misbehaviour (throttling,
DLR failure rates, dropped binds)

**Body:**

For anyone who tests A2P routing or SMS gateways: I've released Cellophane, a fake SMSC you run in Docker.

It speaks SMPP 3.4 (TX/RX/TRX binds, concatenation via UDH and SAR, GSM7/Latin-1/UCS-2, standard DLRs) and shows
everything in a web inbox: reassembled multi-part messages, every PDU field explained, hex dump, TLVs, and the
receipt timeline per message.

The part that matters for routing tests is the rule list. You describe operators by destination prefix:

```yaml
rules:
  - match: { to: "^88017" }
    accept: { dlr: DELIVRD, after: 2s }
  - match: { to: "^88019" }
    accept: { dlr: [ { DELIVRD: 80%, after: 3s }, { UNDELIV: 20%, after: 30s } ] }
  - match: { to: "^88015" }
    throttle: { tps: 5, then: ESME_RTHROTTLED }
  - match: { account: "chaos" }
    disconnect: { after: 10 }
```

Rules can be changed at runtime through the API, so a CI test can make "operator B" fall over and check that
your router fails over. There's also an endpoint to inject MO messages toward your receiver bind.

MIT licensed: https://github.com/tareqmy/cellophane

I'd genuinely like to collect operator quirks you've seen in production (odd DLR formats, weird error codes,
binds that half-die) to add as rules.
