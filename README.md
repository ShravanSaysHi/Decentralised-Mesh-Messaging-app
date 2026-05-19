# Hop — Bluetooth Mesh Messaging (MANET)

![App Mockup](MiniMockUp.jpg.jpeg)

Production-ready Android app implementing a **Bluetooth-based mesh messaging network** with no internet or SIM usage.

## Architecture Overview

- **Language:** Kotlin  
- **Architecture:** MVVM  
- **Concurrency:** Coroutines / Flow  
- **Layers:** Bluetooth → Routing → Encryption → Messaging → UI  

## Phase 1: Bluetooth Communication Layer ✅

- **Device discovery** via Bluetooth Classic
- **Server mode:** listen for incoming connections
- **Client mode:** connect to a bonded device
- **Basic data transfer:** send/receive text over RFCOMM

## Phase 2: Node Identity + Routing Table ✅

- **Node ID:** UUID-based, persisted in SharedPreferences (generated once per install)
- **Routing table:** Room DB with `NodeID | NextHop | Cost | LastSeen | HopCount`
- **Neighbor tracking:** discovered and connected Bluetooth devices upserted as direct neighbors (cost=1, hopCount=1)
- **Stale pruning:** entries not seen in 5 minutes removed

## Phase 3: Routing Algorithm ✅

- **DVR-style:** merge updates from neighbors (cost + 1, hopCount + 1)
- **Loop prevention:** sequence numbers; prefer newer route info
- **TTL / freshness:** routes expire after 5 minutes (stale pruning)

## Phase 4: Messaging + Multi-hop ✅

- **Packet format:** MessageID, SourceID, DestID, TTL, SeqNum, EncryptedPayload, Checksum
- **Forwarding:** if destination ≠ local, decrement TTL and forward to connected peer
- **Length-prefixed frames** over Bluetooth; ROUTING / MESSAGE_FULL / BLOCK / ACK / MESSAGE_HEADER

## Phase 5: Encryption ✅

- **AES-256-GCM** for payloads; pre-shared key (KeyStore, generated once)
- End-to-end: encrypt before send, decrypt on receive; checksum over plaintext

## Phase 6: Fragmentation + Reassembly ✅

- **Blocks:** 512-byte payload; BlockID, MessageID, TotalBlocks, checksum per block
- **Reassembler** by MessageID; verify checksums; deliver when complete

## Phase 7: Reliability ✅

- **ACK** per block; receiver sends ACK(MessageID, BlockID)
- **Checksum** (CRC32) on payloads for corruption detection

## Phase 8: Power Optimization ✅

- **Adaptive discovery:** throttled to 30s interval to reduce scan frequency
- Routing broadcast every 30s when connected (controlled flooding)

## Phase 9: UI Integration ✅

- **Node ID** display; **routing table** list; **nearby devices**; **Send** (destination Node ID + message)
- **Inbox** list (decrypted messages); **network status** (connected count, route count); raw received

### Project structure (all phases)

```
app/src/main/kotlin/com/hop/mesh/
├── MeshApplication.kt
├── bluetooth/          # Discovery, connect, send/receive, raw bytes callback
├── identity/           # Node UUID
├── routing/            # Room table, DVR merge, sequence numbers
├── encryption/         # KeyStore, MessageCrypto AES-256-GCM
├── messaging/          # WireProtocol, MessagePacket, Fragmentation, ReceiveBuffer, MessagingLayer
└── ui/                 # MainActivity, ViewModel, adapters (devices, routing, inbox)
```

## Setup

1. **Open in Android Studio** (or use CLI with JDK 17+ and Android SDK).
2. **SDK:** Set `ANDROID_HOME` to your Android SDK path (or use Android Studio’s SDK).
3. **Build:**
   ```bash
   ./gradlew assembleDebug
   ```
4. **Unit tests:**
   ```bash
   ./gradlew test
   ```
5. **Run:** Install on a device or emulator with Bluetooth support.  
   For two-device testing: on one device tap **Start server**; on the other tap **Discover devices**, then **Connect** next to the first device. **Pairing is done in-app**—if the device isn’t paired yet, a system pairing dialog may appear; accept on both devices, then the app connects. No need to open system Bluetooth settings first.

## Permissions

- `BLUETOOTH` / `BLUETOOTH_ADMIN` (API ≤ 30)
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+)
- `ACCESS_FINE_LOCATION` (required for discovery on many devices)
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTIVITY` (for future foreground mesh service)

## Constraints

- No internet APIs
- No SIM/network services
- Bluetooth-only communication

## License

Part of the Hop mesh project.
