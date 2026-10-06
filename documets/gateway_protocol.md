# Cat Tracker ↔ Heltec V3 Gateway Protocol (v8)

This document is the implementation contract for the point-to-point LoRa link
between the CatTracker T114 firmware and a future Heltec V3 gateway. It defines
radio configuration, byte encoding, frames, commands, acknowledgements,
timeouts, gateway behavior, and known limits in the current tracker firmware.
The gateway must implement protocol version 8 literally before adding any
gateway-to-network, phone, or cloud interface.

## 1. Scope and interoperability

The protocol supports multiple trackers and gateways sharing one radio
profile. Every frame carries a `trackerId`; there is no `gatewayId`:

* `trackerId` (32-bit) is the identity of one tracker. By default the firmware
  derives it from the nRF52 FICR device ID (`DEVICEID[0] ^ DEVICEID[1]`); it can
  be overridden at build time with `-DTRACKER_ID=0x...`. A resulting zero is
  replaced with `1`. The tracker prints the ID at boot (`[BOOT] Tracker ID =
  XXXXXXXX`). Register each tracker ID in the gateway configuration.
* A tracker only processes frames whose `trackerId` equals its own. Every
  command addresses one tracker; there is no group-command type. Gateways must
  still communicate with only one tracker at a time to avoid reply collisions
  on the half-duplex radio.
* Gateways are assumed not to be within radio range of the same tracker at the
  same time. The tracker replies on the shared radio channel, so its most
  recently communicating gateway is the implicit return path. A gateway
  accepts frames for its registered tracker IDs; this is radio addressing,
  not authentication.

**Gateway discipline:** run one command/transfer at a time (stop-and-wait). Do
not start a command for tracker B while a transaction with tracker A (e.g. a
FETCH) is in progress. The current firmware normally boots ACTIVE and listens
continuously, so WAKE is not required after every boot. After SLEEP or the
ACTIVE idle timeout, the tracker is DORMANT and WAKE must be retried across
receive windows. A typical policy is GET_CONFIG when needed, FETCH, then SLEEP
when continuous tracking is not desired; whether to keep a tracker ACTIVE is an
application decision.

Records are deduplicated by `(trackerId, recordSequence)`. A tracker keeps
unacknowledged records only in RAM during its current powered session; they are
lost on reset or power loss. The next gateway can fetch records only while the
tracker still has them in memory. Because the same tracker may be fetched by
different gateways, all gateways should forward their records to the same
store (or be merged later) using that key.

The transport is raw LoRa point-to-point. Do not use LoRaWAN or Meshtastic.
The V3 gateway and tracker must use matching modulation settings and sync word.
There is no separate network-ID field. The `trackerId` is a routing address,
not a secret or a security mechanism.

This contract describes the current tracker implementation. The gateway must
be tolerant of retransmitted `DATA` packets and repeated commands. Some
limitations and important consequences are collected in §11.

## 2. Radio configuration

Configure the V3 radio as follows:

| Parameter | Tracker default | Gateway requirement |
| --- | --- | --- |
| Modulation | LoRa, explicit header | LoRa, explicit header |
| Frequency | 868.1 MHz | Same frequency, subject to local regulations |
| Bandwidth | 125 kHz | Same |
| Spreading factor | SF7 | Same |
| Coding rate | 4/5 | Same |
| SX1262 sync word | `0x12` | Same private sync-word setting |
| TCXO | 1.8 V | Match tracker radio board setting |
| RF switch | SX1262 DIO2 controls RF switch | Use V3 board's supported RF-switch control |
| Preamble | 8 symbols | Same for ordinary packets |
| PHY CRC | Enabled | Enable |
| Maximum frame length | 255 bytes | Accept up to 255 bytes |
| Tracker TX power | Default 10 dBm; configurable 2–10 dBm | Gateway TX power is fixed at 10 dBm |

`SET_CONFIG(TX_POWER_DBM)` changes the tracker's conducted TX power. Firmware
caps both radios at 10 dBm and limits charged airtime to 0.8% in a rolling hour.
The airtime ledger is in RAM and resets on reboot. Conducted power does not
establish ERP: verify the installed antenna and final product against the
applicable national conditions and RED/ETSI requirements before deployment.

The tracker turns the SX1262 off between scheduled DORMANT receive windows. The
default radio-off interval is 900 seconds, followed by a 6-second receive
window. On ordinary entry to DORMANT, the first window begins after the
900-second off interval. Each later off interval begins when the previous
window ends, so window starts are approximately 906 seconds apart at defaults.
Both values can be changed through
`SET_CONFIG` and read back through `GET_CONFIG`. A command sent once can fall
between windows. To wake a dormant tracker, the gateway must repeat the same
`WAKE` frame, with the same sequence
number, across successive windows until it receives a valid command ACK or
reaches an application-level timeout. The gateway must observe regional airtime
limits and leave time for the tracker to transmit its ACK. At defaults, initial
WAKE latency after ordinary DORMANT entry can approach 15 minutes; actual timing
and current consumption must be verified on hardware. A fresh low-battery
lockout is an exception: the tracker opens its first radio window immediately
to transmit a charge request on the shared radio channel.

## 3. Binary encoding and frame layout

Frames are binary, not text. Multi-byte integers are **little-endian**. Signed
integers use two's-complement representation. There is no padding between
fields. Use explicit byte serialization in gateway code; do not send a C++
structure unless its layout, packing, endianness, and size are verified.

The LoRa PHY CRC is enabled. The application frame adds a second CRC:

| Offset | Length | Field | Encoding |
| ---: | ---: | --- | --- |
| 0 | 1 | `frameMarker` | Constant `0xC9`; combines frame magic and protocol version |
| 1 | 1 | `type` | Packet type from §4 |
| 2 | 1 | `payloadLength` | Number of payload bytes, 0–245 |
| 3 | 4 | `trackerId` | Non-zero tracker ID, little-endian |
| 7 | 1 | `sequence` | Sender's packet/transaction sequence; wraps modulo 256 |
| 8 | N | `payload` | Exact packet payload from §4 |
| 8 + N | 2 | `frameCrc` | CRC-16/CCITT-FALSE over bytes 0 through 7+N; little-endian |

The full frame length is `10 + payloadLength` bytes; maximum `payloadLength` is
245 so the full frame does not exceed 255 bytes. `frameMarker` identifies this
protocol layout; a future incompatible layout must use a different marker.
CRC-16/CCITT-FALSE parameters:
polynomial `0x1021`, initial value `0xFFFF`, no reflected input/output, xor-out
`0x0000`. For example, CRC over ASCII `"123456789"` is `0x29B1`, transmitted
as bytes `B1 29`.

Reject a frame unless the PHY CRC passed, its length is at least 10 bytes, its
`frameMarker` is `0xC9`, its `trackerId` is a registered tracker, its declared
length matches the received length exactly, and its application CRC matches.
Ignore unknown packet types. The tracker ignores frames that fail its
header/address/application-CRC validation.

Each sender owns its own 8-bit `sequence` counter. The gateway increments its
counter for each newly created outbound frame, including commands, DATA_ACK,
and CHARGE_REQUEST_ACK. It must **reuse the same frame and sequence** when
retrying the same command. The tracker increments its counter when constructing
each outgoing frame; the counter starts at 1 after boot and is not persistent.
A failed radio transmission may still consume a sequence. The counter wraps
modulo 256; zero is a valid sequence value. Responses echo the relevant
gateway request sequence in their payload. A frame sequence is not a log
record sequence or a DATA chunk ID.

## 4. Packet types and exact payload schemas

### 4.1 Packet type registry

| Value | Name | Direction | Payload length |
| ---: | --- | --- | ---: |
| 1 | `WAKE` | Gateway → tracker | 0 |
| 2 | `SLEEP` | Gateway → tracker | 0 |
| 3 | `FETCH` | Gateway → tracker | 0 |
| 4 | `SET_CONFIG` | Gateway → tracker | 2–5, depending on setting |
| 5 | `ACK` | Tracker → gateway | 2 |
| 6 | `BATTERY` | Tracker → gateway | 3 |
| 7 | `DATA` | Tracker → gateway | `5 + 16 × count`, count 1–4 |
| 8 | `DATA_ACK` | Gateway → tracker | 4 |
| 9 | `FETCH_DONE` | Tracker → gateway | 9 |
| 10 | `GET_CONFIG` | Gateway → tracker | 0 |
| 11 | `CONFIG_REPORT` | Tracker → gateway | 33 |
| 12 | `CHARGE_REQUEST` | Tracker → gateway | 5 |
| 13 | `CHARGE_REQUEST_ACK` | Gateway → tracker | 1 |

### 4.2 `WAKE`, `SLEEP`, and `FETCH`

These command types have no payload.

* `WAKE`: request transition to ACTIVE. The tracker measures battery before
  accepting the transition. When lockout is active, it sends ACK status 3 and
  stays DORMANT. Otherwise it sends status 0 before entering ACTIVE. If it is
  already ACTIVE, it remains ACTIVE.
* `SLEEP`: request transition to DORMANT. The tracker sends an `ACK` before
  applying the command. Receiving SLEEP again while DORMANT restarts the
  radio-off timer.
* `FETCH`: request a battery report and transfer of stored, unacknowledged
  location records. The tracker sends status-0 ACK before starting the
  transfer, then sends BATTERY, DATA chunks, and FETCH_DONE as described in §6.
  If it cannot send the initial BATTERY frame, it aborts without FETCH_DONE.

Any payload on these commands is invalid.

### 4.3 `SET_CONFIG` payload

Each command updates exactly one setting. The first byte is a `settingId`
enumerator; the remaining bytes are that setting's value. The setting ID
determines the value's type and exact length, so there is no separate type or
length byte. Multi-byte values are little-endian; signed values use two's
complement.

| ID | Setting | Value bytes | Units / accepted range | Default |
| ---: | --- | ---: | --- | --- |
| 1 | `SAMPLE_INTERVAL_MINUTES` | 1 | `uint8`, 1–127 minutes | 1 |
| 2 | `AUTO_SLEEP_MINUTES` | 1 | `uint8`, 1–127 minutes | 30 |
| 3 | `GPS_TIMEOUT_SECONDS` | 1 | `uint8`, 5–120 seconds after GPS standby wake | 45 |
| 4 | `BATTERY_INTERVAL_MINUTES` | 1 | `uint8`, 1–255 minutes | 60 |
| 5 | `DISTANCE_THRESHOLD_METERS` | 1 | `uint8`, 0–127 metres | 20 |
| 6 | `CRITICAL_BATTERY_MILLIVOLTS` | 2 | `uint16`, 3,000–4,200 millivolts | 3,300 |
| 7 | `TX_POWER_DBM` | 1 | signed `int8`, 2–10 dBm | 10 |
| 8 | `DORMANT_SLEEP_MINUTES` | 1 | `uint8`, 1–59 minutes | 15 |
| 9 | `RADIO_LISTEN_SECONDS` | 1 | `uint8`, 3–30 seconds; must be less than `DORMANT_SLEEP_MINUTES` in seconds | 6 |

The payload is 2–3 bytes and the full frame is 12–13 bytes including header
and CRC. Unknown IDs, incorrect value lengths, out-of-range values, or a
resulting invalid settings combination are rejected. Setting an already
configured value is valid and safe to retry. Setting IDs are stable and must
not be reused.

For example, setting the dormant sleep interval to 15 minutes is:

```text
08 0F
```

### 4.4 `GET_CONFIG` and `CONFIG_REPORT`

`GET_CONFIG` has an empty payload. It does not receive an ACK; the gateway
waits for the matching `CONFIG_REPORT`.

`CONFIG_REPORT` contains the request's one-byte gateway sequence followed by
the 10-byte runtime settings snapshot:

| Payload offset | Bytes | Field |
| ---: | ---: | --- |
| 0 | 1 | `requestSequence`, copied from the gateway's `GET_CONFIG` sequence |
| 1–10 | 10 | Settings snapshot, using the field layout below |

Within the settings snapshot, offsets and encodings are: 0
`sampleIntervalMinutes` (`uint8`); 1 `autoSleepMinutes` (`uint8`); 2
`gpsTimeoutSeconds` (`uint8`); 3 `batteryIntervalMinutes` (`uint8`); 4
`distanceThresholdMeters` (`uint8`); 5–6 `criticalBatteryMillivolts`
(`uint16`); 7 `txPowerDbm` (`int8`); 8 `dormantSleepMinutes` (`uint8`); and
9 `radioListenSeconds` (`uint8`). Multi-byte values are little-endian. The
units, ranges, and defaults are listed in §4.3. The fixed 300-second GPS
cold-start deadline is firmware-defined and is not a mutable setting in this
report.

The complete CONFIG_REPORT frame is 21 bytes including header and CRC. The
gateway matches reports by `requestSequence`; if no matching report arrives,
it may retry the same GET_CONFIG frame and sequence.

### 4.5 `ACK` payload (2 bytes)

| Offset | Bytes | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 1 | `acknowledgedSequence` | Gateway command's `sequence` |
| 1 | 1 | `status` | Result/status code below |

Status values:

| Value | Name | Meaning |
| ---: | --- | --- |
| 0 | `SUCCESS` | Command-specific success; `SET_CONFIG` has been applied and persisted |
| 1 | `INVALID` | Recognized command type with an invalid payload |
| 2 | `APPLY_FAILED` | Configuration could not be applied or persisted |
| 3 | `LOW_BATTERY_LOCKOUT` | WAKE refused while critical-battery lockout is active |

For WAKE, SLEEP, and FETCH, status 0 is sent before the command takes effect;
for FETCH it is not proof that the transfer completed. `SET_CONFIG` is
different: status 0 is sent only after the setting has been applied and saved,
and status 2 reports an apply or storage failure. A failed ACK transmission
does not undo a successful setting update, so a retry may apply the same value
again. `GET_CONFIG` is completed by its CONFIG_REPORT and does not use an ACK.
For a recognized command with an invalid payload it sends status 1.
The gateway must correlate ACKs by `acknowledgedSequence`, not by the ACK
frame's own header sequence, and must not treat status 0 as proof that a
long-running FETCH completed.

### 4.6 `BATTERY` payload (3 bytes)

| Offset | Bytes | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 2 | `millivolts` | Measured battery-divider voltage in mV, `uint16` |
| 2 | 1 | `lowBattery` | `0` normal; `1` low-battery lockout active |

The tracker sends a BATTERY frame only at the start of FETCH; it is not a
periodic unsolicited report. It also measures battery as part of most outgoing
transmissions, and CHARGE_REQUEST carries a measurement and the configured
critical threshold. The charge/recharge condition is inferred from battery
voltage; this board variant does not expose a separate charger-detect input.
Treat the reading as the tracker-reported battery voltage, not a reliable
VBUS-present bit.

### 4.7 `DATA` payload (21–69 bytes)

| Offset | Bytes | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | `chunkId` | Sequence number of the first record in this chunk |
| 4 | 1 | `count` | Number of records, 1–4 |
| 5 | 16 × count | `records` | Packed records in oldest-first order |

Each 16-byte record is:

| Record offset | Bytes | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | `recordSequence` | Tracker record ID from a persisted epoch and per-epoch counter, `uint32` |
| 4 | 4 | `utcSeconds` | UTC Unix time in seconds, `uint32` |
| 8 | 4 | `latitudeE7` | Signed latitude degrees × 10,000,000 |
| 12 | 4 | `longitudeE7` | Signed longitude degrees × 10,000,000 |

The `DATA` frame length is `15 + 16 × count` bytes, including the 8-byte
header and 2-byte CRC: 31 bytes with one record and 79 bytes with four records.
A valid latitude is -900,000,000 through 900,000,000 and longitude is
-1,800,000,000 through 1,800,000,000. Unix timestamps are UTC; the tracker
populates them from a valid GPS RMC time/date fix.

`chunkId` is the `recordSequence` of the first record in this transmitted
chunk. After the tracker receives a DATA_ACK and marks those records, a later
chunk or FETCH can begin with a different record and therefore have a
different ID. If DATA_ACK is lost before the tracker receives it, those records
remain unacknowledged and the same chunk ID can be transmitted again. Record
sequence IDs are the stable de-duplication keys. The tracker advances
the persisted sequence epoch at startup, so IDs remain distinct across reboots
even though the location records themselves are volatile.

### 4.8 `DATA_ACK` payload (4 bytes)

The payload is a little-endian `uint32 chunkId`, echoing the ID in the DATA
frame being acknowledged. Its frame sequence is a new gateway-owned sequence.

The gateway must first validate the DATA frame and durably commit **all**
records in it. Only then send DATA_ACK. If the same records arrive again,
deduplicate by `(trackerId, recordSequence)`, verify that their stored values
match, and ACK the received chunk ID again. If a record with the same key has
different data, do not silently overwrite it; record a gateway-side protocol
error and do not ACK that chunk.

The tracker waits up to 2 seconds for the exact chunk ID. An ACK for another ID
does not complete the wait. Missing/wrong ACK leaves the chunk eligible for
retransmission.

### 4.9 `FETCH_DONE` payload (9 bytes)

| Offset | Bytes | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 1 | `fetchRequestSequence` | Sequence from the gateway's FETCH command |
| 1 | 4 | `acknowledgedChunks` | Number of chunks the tracker marked acknowledged in this FETCH |
| 5 | 4 | `pendingRecords` | Number of tracker log records still unacknowledged |

The tracker sends FETCH_DONE after it stops sending DATA, including after a
chunk timeout. `pendingRecords == 0` means the tracker found no remaining
unacknowledged records at that time. FETCH_DONE itself is not acknowledged by
the current tracker protocol; a gateway can use the next FETCH to reconcile
state. After FETCH, the tracker returns to the state in which it received the
command. A FETCH received while DORMANT returns the tracker to DORMANT and
restarts its radio-off interval.

### 4.10 `CHARGE_REQUEST` and `CHARGE_REQUEST_ACK`

`CHARGE_REQUEST` is sent when the measured battery voltage falls below
`criticalBatteryMillivolts`. Its payload is:

| Offset | Bytes | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 1 | `attempt` | Attempt number, 1–4 |
| 1 | 2 | `batteryMillivolts` | Measured battery voltage, `uint16` |
| 3 | 2 | `criticalBatteryMillivolts` | Configured threshold, `uint16` |

The gateway acknowledges an alert with `CHARGE_REQUEST_ACK`. Its one-byte
payload is the `sequence` from the corresponding CHARGE_REQUEST frame header.
The tracker accepts an ACK from any gateway in range only for the currently
outstanding alert during the current receive window. The ACK's own header
`sequence` is a new gateway-owned packet sequence. A matching ACK tells the
tracker the gateway received the request; it does not confirm that charging has
started.

The tracker sends at most four requests. The first is sent when low-battery
lockout begins, without requiring a previously known gateway. Retries are sent
at the start of later receive windows. Since the configured off interval begins when a window ends,
the time between request starts is approximately
`radioListenSeconds + dormantSleepSeconds` (906 seconds at defaults), not
exactly `dormantSleepSeconds`. The attempt counter is incremented before each
transmit, so a radio transmit failure consumes an attempt. The gateway should
listen during the tracker window and ACK promptly. If acknowledged, the
tracker attempts charge-wait immediately without sending the remaining
requests. After four unacknowledged attempts, it attempts charge-wait when the
fourth receive window closes.

Charge-wait uses nRF52840 System OFF with LPCOMP configured to wake on rising
battery voltage. The battery divider remains enabled during this state; the
threshold is an approximate charging proxy and must be verified on the board.
On wake, the tracker reboots and qualifies the battery voltage before clearing
lockout. System OFF loses all volatile location records. Charge requests are
sent on the shared radio channel, so any in-range gateway may receive and
acknowledge them; when only one gateway is in range, it is the implicit return
path. If received during low-battery listening, the first request is sent in
that window.
If the ADC already reads at least 4,000 mV, LPCOMP reports the input above its
crossing threshold, or LPCOMP setup fails, the tracker remains in low-battery
DORMANT operation instead of entering System OFF. Recovery qualification
begins only after the ADC reaches `max(4,000 mV, criticalBatteryMillivolts)`.
The gateway should not expect an ACK of its `CHARGE_REQUEST_ACK`; receipt of
the alert is acknowledged by its matching request sequence, and a later
request indicates that the earlier ACK may not have reached the tracker.

## 5. Gateway command/response state machine

Implement one transaction at a time; both ends are half-duplex and the tracker
does not support simultaneous gateway commands and FETCH traffic.

### 5.1 Common command transaction

1. Construct and persist a gateway command frame, including its one-byte
   sequence number, before transmitting it.
2. Transmit the exact same frame for each retry; do not allocate a new
   sequence per retry.
3. Listen for `ACK` frames and validate the full frame before acting on them.
4. Match `ACK.acknowledgedSequence` to the outstanding command sequence.
   Ignore ACKs for other commands.
5. `status=0` completes WAKE and SLEEP. For `SET_CONFIG`, it confirms that the
   setting was applied and persisted. `GET_CONFIG` completes when the matching
   `CONFIG_REPORT` arrives. The current tracker boots ACTIVE unless its
   persisted low-battery lockout prevents activation; do not require a WAKE
   transaction merely because the tracker has rebooted.
6. Status 1, 2, or 3 is a terminal rejection/failure for that command. Surface
   it to the gateway application and do not report success.
7. Do not send another command while FETCH is active. The tracker will ignore
   ordinary commands received while waiting for a chunk DATA_ACK.

Recommended gateway defaults: allow up to 10 seconds for a response to an
active-mode command. For WAKE, retry every 5 seconds for up to 1 hour, using
the exact same frame and sequence number. The 1–59-minute DORMANT sleep range
ensures a normal receive window occurs during that attempt. Listen for the
tracker's ACK between transmissions and stop immediately when it arrives.
Retry spacing and total transmissions must still satisfy local regulatory
airtime restrictions.

### 5.2 Wake sequence

1. Persist a WAKE transaction and retransmit the identical frame every 5
   seconds, using the same sequence number, until its matching status-0 ACK
   arrives or 1 hour elapses.
2. The tracker listens for `radioListenSeconds` every
   `dormantSleepSeconds` while DORMANT. The gateway must listen for the ACK
   between retries and continue retries across windows; a sleeping tracker may
   receive a later retry rather than the first frame.
3. Once acknowledged, consider the tracker ACTIVE. It will sample on its
   configured interval and returns to DORMANT after `autoSleepSeconds` without
   a recognized command type; even a recognized type with an invalid payload
   resets the current idle timer before it is rejected. WAKE while already
   ACTIVE is safe and does not restart its sampling timer.
4. If status 3 arrives, show low-battery lockout. Do not keep issuing WAKE.
   Lockout is cleared locally after the battery qualifies; the tracker remains
   DORMANT, so issue a new WAKE after recovery is known. Recovery is not
   automatically reported. FETCH may still be used to request a BATTERY report
   while lockout is active.

The gateway and tracker independently enforce a rolling-hour airtime budget.
The limiter charges each packet at 110% of RadioLib's estimated airtime and
allows up to 0.8% charged airtime per rolling hour, leaving margin below a 1%
limit. It delays transmissions when the budget is exhausted. The in-memory
accounting resets on reboot, so it does not guarantee the limit across power
cycles. FETCH may therefore take several hours for a full tracker buffer; the
gateway allows up to eight hours for an accepted FETCH transaction.

After SLEEP is acknowledged, stop retrying it: a repeated SLEEP received in a
later DORMANT window restarts the off timer and can delay the next opportunity
to contact the tracker.

### 5.3 Set and read configuration

1. Persist the gateway's desired configuration as individual setting IDs and
   values.
2. To update a setting, send `SET_CONFIG` with its ID and value. Wait for the
   matching ACK: status 0 confirms application and persistence; status 1 means
   invalid ID/value/combination; status 2 means apply or persistence failed.
3. Retransmit the exact same frame and sequence if the ACK is lost. Setting a
   value is idempotent, but the tracker does not de-duplicate commands.
4. To read current values, send `GET_CONFIG` and wait for a `CONFIG_REPORT`
   whose payload `requestSequence` matches the request. If the report is lost,
   retry the same request frame and sequence.
5. On reconnect or reboot, read the tracker configuration and compare it with
   the gateway's desired values; send updates only for settings that differ.

### 5.4 Charge-request handling

1. Listen during each configured tracker receive window while the tracker is
   in low-battery lockout.
2. On `CHARGE_REQUEST`, validate the tracker ID and payload, then
   return `CHARGE_REQUEST_ACK` with the request frame's sequence in its
   payload. Send it promptly while the tracker is still listening.
3. Treat the alert as a request for charging attention, not proof that a
   charger is connected. Do not expect a tracker response to the ACK; the
   tracker may enter System OFF immediately.
4. Repeated requests are retries of the same low-battery condition. Deduplicate
   or update by tracker ID and alert state rather than creating a new charging
   incident for every attempt.

## 6. FETCH transfer algorithm

The gateway should implement FETCH as a durable, idempotent stop-and-wait
transfer:

1. Send FETCH and match its command ACK. Do not treat that ACK as transfer
   completion.
2. Receive and store the BATTERY report.
3. For each DATA frame:
   1. Validate frame and DATA payload length/count (`count` must be 1–4, length
      must equal `5 + 16 × count`).
   2. Validate coordinate ranges and record fields.
   3. In one durable database transaction, insert missing records keyed by
      `(trackerId, recordSequence)`. Do not release a DATA_ACK before commit.
   4. Send DATA_ACK echoing this packet's exact `chunkId`.
   5. If the same DATA packet is retransmitted, idempotently confirm the
      already committed records and send DATA_ACK again.
4. Continue until FETCH_DONE matching the FETCH command sequence is received.
5. Mark the FETCH operation complete only after FETCH_DONE. If its
   `pendingRecords` is nonzero, report a partial transfer; the gateway may
   issue another FETCH after the current session is closed.
6. On gateway restart, recover committed records and unfinished command state
   from persistent storage. Reissuing FETCH is safe when DATA insertion is
   idempotent; never discard tracker records based solely on receiving DATA.

The tracker sends at most four records in each packet and waits 2 seconds for
each DATA_ACK. A gateway should ACK promptly after its durable commit. Do not
perform slow cloud upload, UI work, or external API calls before acknowledging;
queue such work after local storage. On a timeout, the tracker stops the
current FETCH and retains unacknowledged records in RAM. The gateway should
leave the session available to receive FETCH_DONE, then start a new FETCH
rather than sending unsolicited DATA_ACKs later. A tracker reset or power loss
before a later FETCH discards those records.

An ACK is the gateway's confirmation that the records in that one packet are
durably held by the gateway. The tracker marks each record in that chunk
acknowledged only after receiving the chunk ACK. There is no whole-log erase
command and no whole-transfer atomic commit.

## 7. Gateway data model and behavior

At minimum, persist:

### Tracker configuration

* The list of registered tracker IDs and radio parameters.
* Desired configuration values keyed by setting ID and the last successful
  update time for each setting.
* Gateway's next packet sequence and any outstanding command frame/sequence.
* Last-seen time and latest valid battery report.

### Location record

Use a unique key `(trackerId, recordSequence)` and store:

* UTC timestamp from `utcSeconds`.
* Signed latitude/longitude E7 as integers (avoid floating-point keying).
* First and most recent receive time.
* Optional RSSI/SNR from the radio and associated FETCH/chunk metadata.

For a repeated record key, identical values mean a retry and are harmless;
different values mean a protocol/data-integrity error. Keep tracker data after
it has been imported: tracker-side ACK means the gateway durably accepted the
chunk, not that any later cloud upload succeeded.

The gateway can export coordinates as decimal degrees by dividing E7 by
10,000,000. Keep integer E7 and Unix seconds as the canonical persisted values
to avoid rounding and time-zone conversion issues.

## 8. Validation, sequencing, and error handling

Reject or ignore a received packet when any of these conditions holds:

* PHY CRC failure, bad application CRC, wrong `frameMarker`, or an
  unregistered `trackerId`.
* Truncated frame, payload-length mismatch, oversized frame, or unknown type.
* Wrong payload size, DATA count outside 1–4, or invalid coordinates.
* ACK sequence does not match the active command, or DATA_ACK chunk ID does
  not match the DATA currently being acknowledged.

For a received command with a known command type but invalid payload, the
tracker responds with status 1. The current tracker ignores unknown packet
types rather than sending an error ACK for them; the gateway should not send
unregistered type values.

Do not treat a tracker header sequence as a record ID or use it to deduplicate
DATA. Do not require strictly increasing packet sequences across tracker
reboots: the current firmware restarts its packet sequence after reboot.
Gateway sequence numbers are one byte and wrap modulo 256. Persist the gateway
counter and outstanding command across gateway restarts. Never allocate a new
sequence while a transaction is outstanding; on timeout, retry the exact frame
or explicitly abandon the transaction and discard any delayed response before
reusing its sequence value.

The tracker does not currently persist or de-duplicate gateway command
sequences. WAKE/SLEEP/SET_CONFIG are intended to be idempotent, but retries can
cause repeated ACKs and repeated command processing. FETCH retries can produce
duplicate DATA and must be handled with the durable record-key deduplication
above. ACK frames may repeat; ignore duplicate ACKs after the transaction is
already complete.

Log and report failures explicitly: radio timeout, invalid frame, rejected
configuration, storage commit failure, conflicting duplicate record, low
battery, partial FETCH, or tracker offline. A missing packet is not evidence
that the tracker has no new data.

## 9. Security

The current protocol provides filtering and accidental-corruption detection
only. The IDs, SX1262 sync word, and CRC are not authentication,
authorization, encryption, or replay protection. A third party with compatible
radio settings can forge WAKE/SET_CONFIG commands, observe coordinates, replay
commands, or forge DATA_ACKs. Do not use this protocol where that threat is
unacceptable or where location confidentiality is required.

Before field deployment beyond a trusted prototype, add authenticated
encryption and replay protection (for example a per-device key, authenticated
frame counter, and AEAD tag) in a protocol version change, then update both
tracker and gateway together. Do not add an authentication tag only at the
gateway: the tracker must verify it before acting on any command or accepting
any DATA_ACK.

## 10. Suggested gateway implementation order

1. Configure the V3 SX1262 radio for the §2 profile and log raw frame length,
   RSSI, and SNR.
2. Implement a byte-oriented frame parser/serializer and unit-test CRC with
   `"123456789" -> 0x29B1`, endianness, declared-length checks, and malformed
   frames.
3. Implement WAKE/SLEEP and command-ACK matching. Verify scheduled receive
   windows, retry timing, and wake latency with the tracker.
4. Implement setting-ID validation, `SET_CONFIG` persistence, and
   `GET_CONFIG`/`CONFIG_REPORT` matching.
5. Implement a local durable record store with unique record keys and atomic
   chunk commit.
6. Implement stop-and-wait FETCH, durable DATA commit, duplicate-safe
   DATA_ACK, and FETCH_DONE/partial-transfer handling.
7. Test tracker reset during each point of FETCH, lost DATA, lost DATA_ACK,
   gateway reboot, and full tracker ring before enabling any remote/cloud
   forwarding.

## 11. Known protocol and firmware limits

These points are important when designing the gateway and should be considered
for the next protocol/firmware iteration:

* **Shared channel / concurrent access:** the gateway must talk to one tracker
  at a time. Frames have no gateway address; every gateway in radio range can
  receive tracker transmissions. The deployment assumes no other gateway is
  within range of the same tracker at the same time.
* **No cryptographic security:** frames are forgeable and location data is
  plaintext.
* **No command de-duplication:** the tracker may execute a retried command
  more than once. DATA ingestion must be idempotent.
* **Tracker sequence restarts after reboot:** do not use it as a persistent
  identity.
* **Dormant wake latency:** the radio-off interval and receive-window duration
  are configurable (900 seconds and 6 seconds by default). A WAKE sent between
  windows may wait nearly the full interval; verify timing on-device and retry
  across windows within legal airtime limits.
* **Battery voltage is a recharge proxy:** no separate VBUS input is defined by
  the current variant. Recharge qualification requires voltage at or above the
  greater of 4.0 V and `criticalBatteryMillivolts` for more than 10 seconds.
  LPCOMP's nominal wake threshold is about 4.05 V with a 3.3 V MCU supply;
  measure it on the assembled hardware. Battery rebound can cause a false
  wake.
* **Low-voltage recovery is local:** once the voltage qualifies, the tracker
  clears its lockout but stays DORMANT until a later WAKE. There is no dedicated
  `RECHARGED` event; FETCH can request a BATTERY report while lockout is active.
  System OFF wake restarts the MCU and loses volatile location history.
* **ACK status 0 is an early command receipt for FETCH:** it is not transfer
  completion. For `SET_CONFIG`, status 0 is sent only after successful
  application and persistence; status 2 is the failure result.
* **Tracker ring behavior:** the volatile ring has 3200 × 24-byte records
  (75 KiB). Unacknowledged records are not overwritten during the current
  powered session. When no eligible slot is available, new location samples
  are dropped. All records are lost on tracker reset or power loss.

The tracker ID is derived from the chip (or `-DTRACKER_ID`). Frequency,
bandwidth, spreading factor, coding rate, sync word, and frame marker are
firmware build-time settings. TX power and the tracker timing/configuration
values are runtime settings. If any of those values or packet layouts change,
update this document and the gateway parser in the same release.
