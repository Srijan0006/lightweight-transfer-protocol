# LWSP — Lightweight Wire Streaming Protocol

UDP-style transport: fire-and-forget, no delivery guarantees (MQTT QoS 0 analogue).
Goal is minimum header bytes — only flags, TTL, topic, and reassembly hints.
Includes a tiny HELLO/WELCOME handshake so the sender knows the receiver is ready before streaming.
Optional shared-secret mode encrypts control packets and authenticates the handshake.
Optional broker mode fans out published frames to multiple receivers.

## Wire format

### Single-packet message (4 bytes + payload)

```
Byte 0: [ver:2][type:4][more_frags:1][frag_msg:1]
Byte 1: [ttl:4][topic_id:3][retain:1]
Byte 2-3: msg_id (uint16, big-endian)
```

### Fragmented message (6 bytes + payload)

Same as above, plus:

```
Byte 4: frag_index (0-based)
Byte 5: total_frags
```

| Field | Bits | Notes |
|-------|------|-------|
| ver | 2 | Protocol version (currently 0) |
| type | 4 | Message type (see below) |
| more_frags | 1 | Set if more fragments follow |
| frag_msg | 1 | Set if bytes 4–5 are present |
| ttl | 4 | 0 = no limit; 1–15 = reassembly window in 200 ms units |
| topic_id | 3 | 0–7 numeric channel (no string on wire) |
| retain | 1 | Receiver keeps last payload for topic (MQTT retain) |
| msg_id | 16 | Groups fragments; dedup key |

**Overhead:** 4 bytes (small messages) or 6 bytes (fragments) — vs 24 bytes in the first draft, ~4–10+ for MQTT PUBLISH.

## Message types

| Type | Value | Purpose |
|------|-------|---------|
| PUBLISH | 1 | Payload on a topic |
| SUBSCRIBE | 2 | Register interest in a topic_id |
| PING | 3 | Liveness check |
| PONG | 4 | Reply to PING |
| HELLO | 5 | Session preflight from sender |
| WELCOME | 6 | Receiver acknowledges readiness |

## MQTT-inspired ideas used here

- **Topics as channels** — 3-bit topic IDs on the wire; names are local (`LWSPTopics`)
- **SUBSCRIBE filter** — receiver ignores unpublished topics
- **Retain** — last frame cached for late joiners
- **QoS 0 only** — fits UDP; no ACKs, no retries
- **Fragmentation** — large payloads split at MTU with `msg_id` reassembly
- **Handshake** — sender checks receiver readiness before streaming
- **Secret control channel** — HELLO/WELCOME/PING/PONG/SUBSCRIBE can be AES-GCM protected
- **Broker relay** — one sender can fan out to many subscribers

## TTL semantics

TTL is measured on the **receiver** from the first fragment of a `msg_id`.
If reassembly takes longer than `ttl × 200 ms`, the partial message is dropped.
Stale partial buffers are evicted after 3 s of inactivity.

## Run the screen-stream demo

```bash
# Terminal 1
javac -d out src/lwsp/*.java
java -cp out lwsp.Receiver

# Terminal 2 (optional IP, defaults to 127.0.0.1)
java -cp out lwsp.Sender
java -cp out lwsp.Sender 192.168.1.10
```

## Broker mode

```bash
# Terminal 1
java -cp out lwsp.Broker

# Terminal 2
java -cp out lwsp.Receiver 127.0.0.1

# Terminal 3
java -cp out lwsp.Receiver 127.0.0.1

# Terminal 4
java -cp out lwsp.Sender 127.0.0.1
```

Optional shared secret:

```bash
set LWSP_SECRET=my-secret
java -cp out lwsp.Broker
java -cp out lwsp.Receiver 127.0.0.1 my-secret
java -cp out lwsp.Sender 127.0.0.1 my-secret
```

## Project layout

```
src/lwsp/
  LWSPPacket.java   — encode/decode
  LWSPCrypto.java    — AES-GCM control packet protection
  LWSPWire.java      — optional wire encode/decode helpers
  LWSPTopics.java   — topic IDs + subscription filter
  Sender.java       — screen capture → PUBLISH fragments
  Receiver.java     — reassemble, TTL drop, retain, display
  Broker.java       — UDP relay for fan-out
```

## Possible next steps

- Variable-length msg_id for >64k in-flight messages
- Topic registry handshake (name → id once at connect)
- Optional 1-byte checksum for corrupted UDP payloads
- Broker/relay for fan-out between publishers and subscribers
