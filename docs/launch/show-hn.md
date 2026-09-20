# Show HN draft

**Title** (80 chars max; HN strips "Show HN:" formatting variations, keep it plain):

Show HN: Cellophane – Mailpit for SMS, a fake SMPP operator with a live inbox

**URL:** https://github.com/tareqmy/cellophane

**Text** (posted as the first comment, in your own voice):

Hi HN. I work on SMS routing (A2P aggregation) and every team I've been on has needed a fake SMSC to develop
against. What we all use is SMPPSim, which last shipped around 2014: a properties file that needs a restart, a
1990s console, and Docker images that are unofficial wrappers several years old.

The email world solved this problem with MailHog and then Mailpit: one binary, an official image, a nice inbox,
an API for test assertions. Cellophane is that idea for SMS.

You point your app's SMPP client (or curl) at it and every message lands in a web inbox: decoded text,
concatenated parts reassembled, every PDU field annotated next to a hex dump, and the delivery receipt timeline.
Then you tell the fake operator to misbehave with a YAML rule list: prefix 88019 delivers 80% and fails 20% after
30 seconds, prefix 88015 throttles you at 5 TPS, the "chaos" account gets its connection dropped every ten
messages. Rules can be swapped mid-test from a CI step, and there's a REST API to assert on what arrived.

Technical notes for the curious: it's Java 21 / Spring Boot with its own SMPP 3.4 codec on Netty (the existing
Java SMPP libraries are Netty 3 era or blocking), about 140 tests including conformance checks against
cloudhopper as an independent client, and a Testcontainers test that runs the real image on both amd64 and arm64
(the image is multi-arch). That last one caught a bug the unit tests couldn't: the buildpack's minimal JRE lacks
the `jdk.random` module.

Things it doesn't do yet: SMPP 5.0, TLS, `data_sm`. Those are next if people want them.

I'd love to hear what operator behaviours have bitten you in production; the rules engine is designed so a new
action is one class.
