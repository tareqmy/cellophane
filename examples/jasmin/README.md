# Cellophane behind Jasmin

[Jasmin](https://github.com/jookies/jasmin) is an open-source SMS gateway. This compose file runs Jasmin with
Cellophane as its upstream operator, so you can develop against Jasmin's HTTP API and see what Jasmin actually
sends over SMPP.

```bash
docker compose up -d
# wait for "Jasmin configured" in the setup service's output:
docker compose logs -f jasmin-setup
curl "http://localhost:1401/send?username=demo&password=demo&from=MyApp&to=8801711111111&content=Hello+via+Jasmin"
```

The message appears at [http://localhost:8025](http://localhost:8025) on account `jasmin`, with the exact
`submit_sm` Jasmin produced. Add `&dlr=yes&dlr-level=2&dlr-url=http://your-app/dlr` to make Jasmin ask for a
receipt: Cellophane sends the `deliver_sm` and the inbox shows it delivered. Assert from a script the same way:

```bash
curl -s "http://localhost:8025/api/v1/messages?to=8801711111111&text=Jasmin" | jq '.total'
```

What the setup service configures, through jCli (`telnet localhost 8990`, `jcliadmin` / `jclipwd`):

| jCli object | Setting |
|---|---|
| SMPP client connector `cellophane` | `cellophane:2775`, account `jasmin` / `jasmin`, transceiver bind |
| Group `demo`, user `demo` / `demo` | Credentials for Jasmin's HTTP API on port 1401 |
| Default MT route | Everything to `smppc(cellophane)` |

Jasmin persists this in the `jasmin-store` volume, so it survives `docker compose restart`. To make Cellophane
misbehave under Jasmin (throttle, reject, drop the bind) mount a rules file as in
[`../docker-compose`](../docker-compose) and watch how Jasmin's connector retries.
