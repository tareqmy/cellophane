#!/bin/sh
# Tells Jasmin about Cellophane through jCli: an SMPP client connector, a user for the HTTP API, and a default
# route sending everything to that connector. Runs once from the jasmin-setup service; harmless to run again
# (Jasmin answers "already exists" and keeps the persisted config).
set -e
host=${JASMIN_HOST:-jasmin}

echo "waiting for Jasmin (jCli on $host:8990, HTTP API on $host:1401)"
until nc -z "$host" 8990 2>/dev/null && nc -z "$host" 1401 2>/dev/null; do sleep 2; done
sleep 5

# One command per line, sent a second apart so each jCli prompt is answered in turn.
printf '%s\n' \
  jcliadmin jclipwd \
  "smppccm -a" "cid cellophane" "host cellophane" "port 2775" "username jasmin" "password jasmin" \
      "bind transceiver" "submit_throughput 50" "ok" \
  "smppccm -1 cellophane" \
  "group -a" "gid demo" "ok" \
  "user -a" "uid demo" "gid demo" "username demo" "password demo" "ok" \
  "mtrouter -a" "type DefaultRoute" "connector smppc(cellophane)" "rate 0.0" "ok" \
  "persist" \
  "smppccm -l" \
  quit \
  | nc -i 1 -w 30 "$host" 8990

echo "Jasmin configured: connector cellophane -> cellophane:2775, HTTP user demo/demo"
