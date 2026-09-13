# Examples

Ready-to-run setups against a local Cellophane. Start one first:

```bash
docker run -p 2775:2775 -p 8025:8025 ghcr.io/tareqmy/cellophane
# or, from a checkout:
./mvnw -DskipTests package && java -jar server/target/cellophane-server-*.jar
```

| Folder | What it shows |
|---|---|
| [`docker-compose/`](docker-compose) | Cellophane with two accounts, a rules file mounted in, and a volume so the inbox survives restarts |
| [`jasmin/`](jasmin) | Cellophane as the upstream operator behind a [Jasmin](https://github.com/jookies/jasmin) gateway, configured automatically |
| [`ci/`](ci) | A shell script that sends a message and asserts on the inbox, the way a CI step would |
| [`node-smpp/`](node-smpp) | A Node client binding, sending, and receiving the delivery receipt |
| [`java-cloudhopper/`](java-cloudhopper) | The same in Java with cloudhopper, as a tiny Maven project |
