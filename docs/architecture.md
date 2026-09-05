# Architecture

Cellophane is one process with two listeners: Netty on the SMPP port, Tomcat on the HTTP port. Everything in between
is plain Java; Spring only wires it together.

```
 ESME (your app) ──SMPP 3.4──▶ Netty ──▶ PduFrameDecoder ─▶ PduDecoder ─▶ SmppSessionHandler
                                                                              │
                                                                              ▼
                                                                          Operator ──▶ RuleEngine (YAML rules)
                                                                              │
                                                                     ┌────────┴─────────┐
                                                                     ▼                  ▼
                                                                   Inbox           ReceiptDispatcher ──deliver_sm──▶ ESME
                                                                     │
                                                              MessageStore ──▶ REST API / SSE ──▶ browser UI
```

## Modules

| Module | What it is | Depends on |
|---|---|---|
| `smpp` | SMPP 3.4 codec, GSM 03.38, UDH, receipt text, PDU annotator. No Spring. Publishable as a library. | Netty |
| `server` | Spring Boot app: SMPP listener, inbox, rules, operator, REST/SSE API. Bundles the UI. | `smpp`, Spring Boot |
| `ui` | Vite + Svelte single page. Built by Gradle and copied into the jar under `static/`. | Node (downloaded by Gradle) |

## The codec (`smpp`)

`PduCodec` encodes and decodes the PDUs a fake operator needs: the three binds, `submit_sm`, `deliver_sm`,
`enquire_link`, `unbind`, `generic_nack` and their responses. Anything else decodes to an `UnknownPdu` with its body
kept verbatim, so it can still be shown. Decoding is deliberately lenient about spec field lengths (real operators
accept over-long passwords) but strict about framing; a malformed PDU raises a `PduException` that carries the status
to answer with and the sequence number to answer to.

`PduAnnotator` re-reads a PDU as byte ranges with meanings, for the hex view. It never throws: a truncated PDU is
annotated as far as it goes and the rest is marked.

`Gsm7`, `Udh`, `DataCoding`, `SmsText` and `Segmenter` handle text: the GSM default alphabet with its extension
table and 7-bit packing, user data headers with 8- and 16-bit concatenation, data_coding classification, and
splitting long texts into network-sized parts.

`DeliveryReceipt` formats and parses the appendix B receipt text (`id:... stat:DELIVRD ...`).

## The operator (`server`)

`SmppSessionHandler` is one Netty handler per connection. It authenticates binds against the configured accounts,
hands every `submit_sm` to the `Operator`, answers `enquire_link`, and nacks what it cannot decode.

`Operator.onSubmit` is the whole decision in one place:

1. decode the text and evaluate the `RuleEngine` with per-account throttle windows and per-session disconnect
   counters as the stateful gates;
2. store the message in the `Inbox` with the verdict as the first timeline entry;
3. for accepted submits that asked for a receipt, schedule one on the `DelayedExecutor`;
4. return what to answer, how long to hold the answer, and whether to drop the link afterwards.

Rules are a first-match list. `Accept` and `Reject` end evaluation; `Throttle`, `Latency` and `Disconnect` are gates
that add their effect and let evaluation continue, so "throttle prefix X" composes with whatever the later rules say
about accepted messages. Adding an action is one record implementing `RuleAction`, a parse branch in `RulesYaml`,
and a case in `RuleSet.decide` and `Operator`.

`ReceiptDispatcher` sends due receipts to a bound receiver of the right account, queues them while none is bound,
and flushes the queue when one binds. `deliver_sm_resp` sequence numbers are matched back to the part they
acknowledge.

## The inbox

A `Message` is what the application meant to send; it holds one `Segment` per `submit_sm`. Parts of a concatenated
message are matched on account, sender, recipient and concat reference (from the UDH or the SAR TLVs) and joined in
order. Each segment keeps its PDU, raw bytes, the message_id given to the ESME, its own status and timeline.

`MessageStore` is a bounded insertion-ordered map with an index from any part's message_id to its message. The
`MessageListener` hook feeds the SSE stream; the REST API is a thin view over the store.

## Threads

Netty event loops own the SMPP sockets; a handler never blocks. The `Inbox` is synchronised (it is a single
in-memory map; contention is not a concern at SMS rates). Receipts and latency are timed by one scheduler thread and
written back onto the owning channel. Tomcat threads only read the store or call the operator for HTTP sends.
