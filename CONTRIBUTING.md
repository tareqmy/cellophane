# Contributing

Thanks for looking. Bug reports, operator quirks you have suffered in production, and pull requests are all
welcome.

## Build

You need JDK 21 or newer (the code targets 21) and Docker only for the container image. Maven and Node are
downloaded by the build: `./mvnw` fetches Maven, and the `ui` module fetches Node.

```bash
./mvnw verify                                  # everything, with tests
./mvnw -DskipTests package                     # just the jar ...
java -jar server/target/cellophane-server-*.jar   # ... run on :2775 (SMPP) and :8025 (HTTP)
./mvnw -Pimage -DskipTests verify              # container image via buildpacks
./mvnw -Pimage verify                          # image plus the smoke test against it (needs Docker)
cd ui && npm run dev                           # UI with hot reload, proxied to :8025
```

Tests include real-client checks: cloudhopper (an independent SMPP implementation) binds to the running server in
`server/src/test`, and the codec is round-tripped against it in `smpp/src/test`. Please keep new wire-level
behaviour covered that way.

## Adding a rule action

1. Add a record implementing `RuleAction` in `server/.../rules`.
2. Parse and format it in `RulesYaml`, with a message that names the rule and the mistake for bad input.
3. Handle it in `RuleSet.decide` (terminal or gate) and, if it needs state or side effects, in `Operator`.
4. Document it in `docs/rules.md` and add a `RulesYamlTest` and an end-to-end test.

## Code style

Plain Java records and sealed interfaces, no Lombok, no reflection magic. The `smpp` module must not depend on
Spring. Compile warnings are errors in `smpp`.

## Commits

Small, single-purpose commits with a message that says why. The first line is a sentence under 72 characters.
