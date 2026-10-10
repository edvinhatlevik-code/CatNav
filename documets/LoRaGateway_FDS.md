# Functional Design Specification: Cat Tracker Gateway

This document specifies the operational, state, and architectural requirements for an autonomous coding agent implementing the gateway software on the Heltec Wireless Stick Lite (V3) (HTIT-WSL_V3).

The gateway bridges point-to-point LoRa communication from CatTracker nodes to an asynchronous network API serving an Android application over Wi-Fi.

---

## 1. Single Source of Truth
The file `gateway_protocol.md` is the normative reference for the physical layer and binary wire protocol. The coding agent must strictly extract the following parameters from `gateway_protocol.md`:
* Radio parameters (868.1 MHz, BW 125 kHz, SF7, CR 4/5, Sync Word 0x12, 8 preamble symbols).
* Binary struct encodings, endianness (little-endian), and CCITT-FALSE CRC-16 calculations.
* Frame headers, packet type enumerations, payload schemas, and timeouts.

---

## 2. Gateway Hardware & Setup Parameters

### 2.1 Target Hardware
* **Board:** Heltec Wireless Stick Lite (V3) (HTIT-WSL_V3)
* **MCU / Wireless:** Espressif ESP32-S3 (2.4 GHz Wi-Fi 802.11 b/g/n, Bluetooth 5 LE)
* **LoRa Radio:** Semtech SX1262 (controlled via SPI)

### 2.2 System Configuration Placeholders
The agent must provide clear configuration parameters (e.g., in `config.h` or persistent NVS storage) for the user to populate:
* `GATEWAY_ID` (32-bit unique identifier, e.g., `0x00000001`)
* `WIFI_SSID` (Placeholder: `"YOUR_WIFI_SSID"`)
* `WIFI_PASSWORD` (Placeholder: `"YOUR_WIFI_PASSWORD"`)
* `MDNS_HOSTNAME` (Default: `"cat-gateway"`, resolves to `cat-gateway.local`)

---

## 3. Network Architecture & Wi-Fi Management

### 3.1 Connectivity & Provisioning
* The gateway operates connected to a continuous power supply and maintains an active 2.4 GHz Wi-Fi connection (802.11 b/g/n).
* **Automatic Reconnection:** The gateway must monitor network link status and automatically re-establish the Wi-Fi connection if dropped.
* **Local Service Discovery:** The gateway must initialize mDNS to allow the Android application to discover the REST API endpoint at `http://cat-gateway.local` without needing static IP assignments.

### 3.2 FreeRTOS Task Isolation
* **LoRa Core Task:** Dedicated execution thread for high-priority LoRa RX/TX handling and timer deadlines.
* **Network & API Task:** Dedicated execution thread running the HTTP/REST server for Android application requests.
* **Non-Blocking Execution:** Network latency, Wi-Fi client disconnects, or HTTP processing must never block the LoRa task or cause dropped `CHARGE_REQUEST` frames.

---

## 4. State & Task Management Architecture

### 4.1 Communication Controller Rules
1. **Single-Threaded LoRa Transactions:** The gateway operates as a half-duplex, stop-and-wait controller and processes commands for only ONE tracker at a time. An unacknowledged non-WAKE command must time out after the active-response window rather than holding the radio while guessing the tracker's sleep schedule. WAKE jobs take priority over queued commands. Once FETCH is acknowledged, it remains exclusive until the transfer completes or times out.
2. **Passive Listening Mode:** When not transmitting or executing a queued command, the gateway radio must remain in continuous receive mode (RX) to capture unsolicited frames (e.g., `CHARGE_REQUEST`).
3. **Idempotent Retries:** Command retries must reuse the exact same gateway sequence number and payload until acknowledged or timed out.

### 4.2 Dormant Tracker Handling
* A missing response does not reveal whether the tracker is dormant, out of range, or experiencing packet loss. The gateway must not infer a receive-window deadline from silence.
* Non-WAKE commands are retried at the active retry cadence for up to the 10-second active-response timeout, then reported as timed out. To command a dormant tracker, the client should WAKE it and resubmit the failed command after WAKE succeeds.
* WAKE is retried every 5 seconds for up to 1 hour, subject to the rolling-hour regional airtime limiter. WAKE jobs are placed ahead of other queued jobs.

### 4.3 Unsolicited Alert Interception (`CHARGE_REQUEST`)
* On receiving `CHARGE_REQUEST` (type 12):
  1. Validate the v8 frame marker and registered `trackerId`.
  2. Immediately transmit `CHARGE_REQUEST_ACK` (type 13) containing the request's header sequence number.
  3. Mark the tracker state as `LOW_BATTERY_LOCKOUT` and persist this critical state to non-volatile storage so it survives gateway reboots.
  4. Trigger an asynchronous application alert/notification.

---

## 5. Data Storage & Volatility Architecture

### 5.1 Data Retention Policy
* **Location Data Volatility:** Location records received during `FETCH` do **not** require non-volatile flash storage. Storing location records in ordinary RAM is fully acceptable. Data loss upon gateway power cycling is acceptable for location history.
* **Critical State Persistence Exception:** Battery state, active low-battery lockout conditions, and `CHARGE_REQUEST` events are critical and **must** be persisted to non-volatile storage (e.g., ESP32 Preferences/NVS).

### 5.2 Validation Pipeline
Before processing any received frame, the gateway must sequentially validate:
1. PHY CRC pass.
2. Frame length is 10–255 bytes and `payloadLength` exactly matches the received length.
3. Header `frameMarker == 0xC9`, which identifies the v8 wire format.
4. Header `trackerId` matches a registered tracker ID; frames have no `gatewayId`.
5. Calculated application CRC-16 matches `frameCrc`.

The v8 header is 8 bytes: marker, packet type, payload length, 32-bit
little-endian tracker ID, and 8-bit sequence. Do not parse or respond to the
previous v5 header format.

---

## 6. Asynchronous Network API Architecture

The REST API exposed over Wi-Fi to the Android app must be asynchronous. Radio jobs return promptly with a job ID; their progress is polled independently.

### 6.1 Functional Requirements
1. **Command Queuing:** The app can push intent jobs (e.g., `FETCH`, `SET_CONFIG`, `WAKE`, `SLEEP`, `POWER_SAVE`) to a queue on the gateway.
2. **Asynchronous Dispatcher:** An independent worker task picks up queued jobs and executes LoRa sequences according to `gateway_protocol.md`, applying the active-response timeout to non-WAKE commands and scheduled retries to WAKE jobs.
3. **Job Status & State Tracking:** The API must expose job status tracking (e.g., `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `TIMED_OUT`).
4. **Decoupled Data Retrieval:** Location records, tracker battery status, and runtime configurations must be served directly from the gateway's memory/state, independent of real-time LoRa activity.

---

## 7. Domain Realities & System Resiliency

* **Hardware Watchdog Timer (WDT):** The agent must configure the ESP32-S3 Hardware Watchdog Timer. If either the LoRa execution thread or the Wi-Fi/Network thread starves or deadlocks for more than 30 seconds, the system must automatically reset.
* **Tracker Reboot & Unsynchronized Sequences:** The coding agent must be aware that the tracker resets its packet header `sequence` counter to 1 upon reboot, while advancing its persistent `recordSequenceEpoch`. The agent must design frame parsing and deduplication logic with this behavior in mind without relying on header sequences as absolute global sequence identifiers.
* **Timestamp Handling:** Canonical record timestamps rely on `utcSeconds` from the GPS payload. The agent should anticipate edge cases such as missing or unset GPS time (e.g., early startup records) and tag incoming records with a local gateway receipt timestamp (`received_at`) for tracking and auditing.