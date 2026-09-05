# smpp.org listing request

The testing tools page: https://smpp.org/smpp-testing-development.html. Use the contact address shown on the
site.

**Subject:** Listing request: Cellophane, an open-source SMPP 3.4 SMSC simulator

Hello,

I'd like to suggest Cellophane for the SMPP testing and development tools page.

Cellophane is an open-source (MIT) SMSC simulator distributed as a Docker image. It implements SMPP 3.4
(bind_transmitter/receiver/transceiver, submit_sm with UDH and SAR concatenation, deliver_sm delivery receipts in
the appendix B format, enquire_link, unbind, generic_nack; GSM 03.38, Latin-1 and UCS-2 data codings) and adds
what developers need around it: a live web inbox that decodes and annotates every PDU, reassembles concatenated
messages and shows the receipt timeline; a YAML rule engine to simulate operator behaviour (delivery receipt
outcomes and delays, error codes, throttling, latency, dropped binds); and a REST API for test automation,
including mobile-originated message injection.

Project page: https://github.com/tareqmy/cellophane
Licence: MIT
Maintainer: Tareq Mohammad Yousuf

Suggested one-line description: "Open-source SMPP 3.4 SMSC simulator in Docker with a live web inbox, PDU
decoding, configurable operator behaviour and a REST API for test automation."

Thank you for maintaining the resource.

Tareq
