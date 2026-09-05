# Rules

Rules make the fake operator misbehave on purpose. They are an ordered list; for every `submit_sm` the list is
walked from the top, and the first rule that matches and *decides* (accepts or rejects) wins. Rules that only add an
effect (throttle, latency, disconnect) apply it and let the walk continue.

```yaml
rules:
  - match: { to: "^88017" }          # one operator delivers fine
    accept: { dlr: DELIVRD, after: 2s }

  - match: { to: "^88019" }          # another one is flaky today
    accept:
      dlr: [ { DELIVRD: 80%, after: 3s }, { UNDELIV: 20%, after: 30s } ]

  - match: { to: "^88015" }          # this one is throttling you
    throttle: { tps: 5, then: ESME_RTHROTTLED }

  - match: { text: "(?i)spam" }
    reject: ESME_RINVDSTADR

  - match: { account: "chaos" }
    disconnect: { after: 10 }         # drops the bind after 10 messages

  - match: { account: "slow" }
    latency: 1s-3s                    # holds submit_sm_resp for a random 1 to 3 seconds

  - default:
      accept: { dlr: DELIVRD, after: 500ms }
```

Load a file at start with `CELLOPHANE_RULES=/rules.yaml`, or change rules at runtime:

```bash
curl -X PUT localhost:8025/api/v1/rules -H 'content-type: application/yaml' --data-binary @rules.yaml
curl localhost:8025/api/v1/rules
```

A document that does not parse is refused with a 400 whose `detail` says which rule and why; the previous rules
stay in force. The UI's **Rules** button opens the same document in an editor.

## Matching

| Key | Matches against | How |
|---|---|---|
| `to` | destination address | regex, searched anywhere (`^` anchors a prefix) |
| `from` | source address | regex, searched anywhere |
| `text` | decoded text | regex, searched anywhere; never matches binary payloads |
| `account` | the bound system_id | regex that must match the whole id |

Every key given must match. A rule without `match` matches everything. `name:` gives the rule a label for the
timeline; otherwise it is called `rule N`.

## Actions

Exactly one per rule.

### `accept`

Answer `ESME_ROK` and, if the submit asked for a receipt (`registered_delivery`), send a `deliver_sm` delivery
receipt later.

```yaml
accept:                                   # accept, no receipt
accept: { dlr: DELIVRD }                  # receipt right away
accept: { dlr: UNDELIV, after: 30s }      # failed after half a minute
accept: { dlr: DELIVRD, after: 1s-5s }    # random delay in a range
accept:
  dlr: [ { DELIVRD: 90%, after: 2s }, { EXPIRED: 10%, after: 1m } ]
```

States: `DELIVRD`, `UNDELIV`, `EXPIRED`, `REJECTD`, `ACCEPTD`, `UNKNOWN`, `DELETED`, `ENROUTE`, or `none`.
Weights are relative; they need not sum to 100. A `registered_delivery` of 2 (failure receipts only) suppresses
`DELIVRD` receipts, as an operator would. Delays count from the response, so a latency rule never lets a receipt
overtake its `submit_sm_resp`. Each part of a concatenated message gets its own receipt with its own message_id.

### `reject`

Answer with an SMPP error instead of accepting. The message still lands in the inbox, marked rejected.

```yaml
reject: ESME_RINVDSTADR
reject: RMSGQFUL          # the ESME_ prefix is optional
reject: 0x58
reject: { status: 88 }
```

### `throttle`

Allow `tps` matching submits per account per wall-clock second; answer the rest with `then` (default
`ESME_RTHROTTLED`). Submits under the limit continue to the following rules.

```yaml
throttle: { tps: 5 }
throttle: { tps: 5, then: ESME_RMSGQFUL }
```

### `latency`

Hold the `submit_sm_resp` back. Several matching latency rules add up. The following rules still decide the answer.

```yaml
latency: 2s
latency: 500ms-3s
```

### `disconnect`

Drop the TCP connection right after responding to the `after`-th matching submit on a session (default 1). The
count is per session and per rule; a fresh bind starts again.

```yaml
disconnect:
disconnect: { after: 10 }
```

## What you see

Every decision is written to the message's timeline in the UI and in `GET /api/v1/messages/{id}`: which rule
answered, when the receipt was scheduled and sent, whether the response was held or the link dropped. Rejected and
undeliverable messages get red badges in the list, so the effect of a rule is visible without reading logs.
