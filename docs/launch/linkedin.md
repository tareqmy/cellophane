# LinkedIn draft

Attach `docs/screenshot-inbox.png`.

---

I've spent a good part of my career sending SMS through SMPP, and every project starts the same way: someone
digs out SMPPSim, a simulator last released around 2014, edits a properties file, restarts it, and squints at a
1990s HTML page.

So I built the tool I wished existed. Cellophane is "Mailpit for SMS": one Docker container that pretends to be a
mobile operator.

• Point your SMPP (or HTTP) client at it and watch messages arrive in a live inbox
• See the decoded text, the reassembled multi-part message, every PDU field explained next to the hex
• Tell the fake operator to misbehave: throttle, reject, delay, fail 20% of deliveries, drop the connection
• Drive all of it from CI through a REST API

It's open source (MIT), Java 21 and Spring Boot with its own SMPP codec on Netty.

If you build anything that sends SMS, I'd love your feedback, and especially the strange operator behaviours
you've had to code around. Those become rules.

https://github.com/tareqmy/cellophane

#sms #smpp #a2p #telecom #java #opensource #testing
