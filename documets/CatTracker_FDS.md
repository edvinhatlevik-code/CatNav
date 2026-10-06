# Functional Design Specification: CatTracker T114 (Firmware Protocol v5)

This document describes the behavior implemented by the current CatTracker
firmware. The protocol details needed to implement a compatible gateway are in
[gateway_protocol.md](./gateway_protocol.md). Where a future design goal differs
from the current code, this document identifies the current behavior.

## 1. System and hardware

The tracker is a Heltec T114 built around the nRF52840, an SX1262 LoRa radio,
and a Quectel L76K GPS module. The firmware is C++17 / Arduino, built with
PlatformIO and the Adafruit nRF52 framework. It uses RadioLib for LoRa and
Adafruit InternalFS/LittleFS for persistent settings.

Relevant T114 variant pins:

| Function | Pin / behavior |
| --- | --- |
| LoRa | SX1262; CS P0.24, DIO1 P0.20, BUSY P0.17, RESET P0.25 |
| LoRa RF switch | SX1262 DIO2 |
| LoRa TCXO | SX1262 DIO3 at 1.8 V |
| GPS UART | `Serial1`, 9600 baud; TX P1.07, RX P1.05 |
| GPS power | VEXT enable P0.21, active high |
| GPS standby | P1.02; high forces wake, low permits standby |
| Battery sense | P0.04 / AIN2 through the board divider |
| Battery divider enable | P0.06, active high |

The battery divider is documented by the board variant as 100 kOhm / 390 kOhm
with a multiplier of 4.916. The optional QSPI flash is not used by this
firmware. The location log is in RAM; settings are in internal flash.
Battery measurement enables P0.06, waits 3 ms, reads P0.04 with the 14-bit ADC
and its 3.0 V internal reference, then disables the divider. The firmware
calculates `round(raw / 16383 * 3.0 * 4.916 * 1000)` millivolts.

The radio uses raw point-to-point LoRa, not LoRaWAN or Meshtastic. The current
radio profile is 868.1 MHz, 125 kHz bandwidth, SF7, coding rate 4/5, sync word
`0x12`, explicit headers, 8-symbol preamble, and PHY CRC. There is no separate
network-ID or gateway-ID field. Frames carry the tracker ID; replies use the
shared channel, with the most recently communicating gateway as the implicit
return path. Gateways are assumed not to be within range of the same tracker
simultaneously. The sync word is only a radio filter. Check regional frequency,
airtime, and power rules before deployment.

Firmware limits conducted TX power to 10 dBm and charges each radio transmission
at 110% of RadioLib's calculated airtime against a rolling one-hour budget of
0.8%. The airtime ledger is in RAM and resets on reboot; it is not a substitute
for product-level RED/ETSI testing or a guarantee across power cycles. Confirm
ERP with the installed antenna and verify the final equipment against the
applicable Norwegian frequency conditions.

## 2. Startup, states, and watchdog

### 2.1 Startup behavior

On boot, the firmware derives `trackerId` from the XOR of the two nRF52840 FICR
device-ID words, unless `TRACKER_ID` is defined at build time. A resulting ID
of zero is replaced with 1. It initializes LittleFS, loads and validates
persistent settings, initializes the SX1262, and measures the battery.

**Current code starts ACTIVE after a normal boot when low-battery lockout is
not active.** It immediately attempts a GPS sample. This differs from an
earlier design intention that DORMANT be the boot state. If low-battery lockout
is active, `enterActive()` refuses the transition and the tracker remains
DORMANT; after the configured charge-alert attempts, charge-wait may enter
System OFF.

The watchdog timeout is 60 seconds. The firmware feeds it while running and
configures the watchdog to pause during CPU sleep. Normal DORMANT sleep is
FreeRTOS tickless System ON sleep with timed task notifications; the RTC wake
path services the next battery check or radio deadline.

On a storage or radio initialization failure, the current default
`kHaltOnFatal=false` enters a diagnostic loop that keeps USB/serial alive,
prints the failure once per second, and feeds the watchdog. If that compile-time
constant is changed to `true`, the failure path requests nRF52840 System OFF.
It does not retry initialization or automatically return to normal sleep.

### 2.2 ACTIVE

ACTIVE keeps the radio receiving for gateway commands and makes GPS sample
attempts at `sampleIntervalSeconds`. The first attempt is due immediately after
entering ACTIVE. Subsequent attempts are scheduled from completion of the
previous attempt, whether or not it obtained a fix.

The GPS parser accepts checksum-valid RMC sentences. It uses the RMC UTC
date/time and position for a fix; it does **not** set or synchronize a hardware
RTC from GPS. A cold GPS start has a fixed 300-second deadline. A wake from GPS
standby uses configurable `gpsTimeoutSeconds` (45 seconds by default).

Between ACTIVE samples, the firmware ends the GPS UART session and asserts the
standby pin low while retaining VEXT power. On a missed fix it also enters
standby if the tracker is still ACTIVE. Entering DORMANT or low-battery lockout
ends the GPS UART and turns VEXT off; the next activation is a cold start.

The first valid fix in a powered session is recorded. Later fixes are recorded
only when their distance from the last recorded fix is **greater than**
`distanceThresholdMeters`. The filter uses a local planar distance
approximation. A fix at exactly the configured threshold is skipped.

The ACTIVE idle timer is reset by recognized gateway command frames, including
recognized commands with invalid payloads. When
`autoSleepSeconds` elapses without such a command, the firmware enters
DORMANT. A charge-request ACK is not treated as an ordinary gateway command
for this timer.

### 2.3 DORMANT

DORMANT powers GPS off and sleeps the SX1262 between receive windows. On normal
entry, the first receive window starts after `dormantSleepSeconds`; after each
window closes, the radio is off for that interval before the next window.
Defaults are 900 seconds off and 6 seconds listening. A low-battery lockout
with zero previous alert attempts opens a listen window immediately and sends
a charge request on the shared channel. Subsequent windows use the normal
schedule.

The gateway must transmit commands during a receive window. The gateway should
repeat a command frame with the same one-byte sequence number across windows
until a matching response arrives, subject to local airtime limits. At defaults,
window starts are about 15 minutes and 6 seconds apart after the first window;
the initial wait from ordinary DORMANT entry is 15 minutes.

If a valid WAKE command is received, the tracker ACKs and enters ACTIVE unless
low-battery lockout is active. A valid SLEEP command ACKs and enters or
re-enters DORMANT, restarting its radio-off interval. Invalid frames are
ignored; a known command type with an invalid payload receives status 1.

## 3. Battery protection and charge wake

Battery voltage is sampled at `batteryIntervalMinutes` (default 60 minutes)
and generally before outgoing transmissions. This remains an independent
setting rather than being tied only to position samples: it detects a critical
battery while DORMANT (when no position samples occur) and between ACTIVE
position cycles, even if GPS acquisition fails. Sampling only on position
cycles would leave the tracker without that background battery guard whenever
it is dormant. A measured value below
`criticalBatteryMillivolts` (default 3,300 mV) sets a persistent low-battery
lockout, stops GPS, and prevents ACTIVE operation. Recovery requires voltage
at or above `max(4,000 mV, criticalBatteryMillivolts)` continuously for at
least 10 seconds of qualified measurements. While recovery qualification is
in progress, the firmware checks every 2 seconds. On qualified recovery it
clears and persists the lockout, but does not automatically enter ACTIVE; a
subsequent WAKE command is needed to resume tracking.

When lockout starts, the tracker sends up to four protocol-v8
`CHARGE_REQUEST` attempts on the shared channel. The first is
attempted at the start of the first low-battery receive window; later attempts
are made at the starts of later windows. Since the off interval begins after
the receive window closes, the request-start interval is approximately
`radioListenSeconds + dormantSleepSeconds` (906 seconds at defaults). The
attempt counter is persisted and a radio TX failure still consumes an attempt.
A matching `CHARGE_REQUEST_ACK` from any in-range gateway stops further
attempts. The tracker does not need a gateway ID to send an alert.

After a matching alert ACK, or after four unacknowledged attempts, the tracker
tries nRF52840 System OFF. LPCOMP watches battery-sense AIN2/P0.04 for a rising
voltage. The divider-enable output P0.06 must remain high while the comparator
is monitoring. A System OFF wake is a reset, not a resume; startup reloads
settings and the volatile location log is lost.

LPCOMP uses AIN2/P0.04, the `2/8 VDD` reference, and upward analog detection.
This gives an approximate battery threshold near 4.05 V at a 3.3 V MCU supply,
based on the nominal divider. The value is not calibrated and must be measured
on the assembled board. If the comparator
already reports that the threshold has been crossed, or the ADC reads at least
4,000 mV before LPCOMP is armed, the firmware does not enter System OFF and
stays in low-battery DORMANT operation. Recovery qualification begins only
when the ADC reaches `max(4,000 mV, criticalBatteryMillivolts)`. LPCOMP setup
failure has the same stay-awake outcome. Battery rebound can cause a false
wake. There is no dedicated charger-detect input, so battery voltage is only a
charging proxy.

## 4. Location and settings storage

The location log is a 3,200-slot RAM ring. Each internal record is 24 bytes
(75 KiB total); the wire record in DATA packets is 16 bytes. The ring is
volatile and starts empty after reset. A DATA_ACK marks the records in that
chunk acknowledged; acknowledged slots can be reused when the ring fills.
Unacknowledged records are preserved. If the next slot contains an
unacknowledged record, the new sample is dropped instead of overwriting it.
Acknowledged records are not erased immediately; they remain until their slots
are reused. System OFF, reset, or power loss discards all ring contents.

Settings and low-battery metadata are persisted in internal LittleFS using two
alternating settings files with generation numbers and CRCs. The firmware
loads the newest valid copy and migrates older 35-byte and 43-byte records to
the current 48-byte format. If no valid copy can be loaded, it restores
defaults and attempts to save them. It does not reformat the filesystem
automatically.

### 4.1 Persistence layout

The current packed 48-byte settings record is little-endian:

| Offset | Bytes | Field |
| ---: | ---: | --- |
| 0 | 4 | Magic `0x43545333` |
| 4 | 4 | Generation |
| 8 | 32 | Settings structure (layout in §5 and the gateway protocol) |
| 40 | 1 | Low-battery lock flag |
| 41 | 1 | Charge-request attempt count, 0–4 |
| 42 | 4 | Reserved; previously stored gateway ID, now ignored |
| 46 | 2 | CRC-16/CCITT-FALSE over bytes 0–45, little-endian |

The files alternate by generation parity: odd generations use
`/settings1.bin`, even generations use `/settings0.bin`. The firmware selects
the newest valid generation and retains the other copy as fallback. The prior
48-byte protocol-v7 record uses magic `0x43545332`; older 48-byte, 43-byte,
and 35-byte records use magic `0x43545331`. These records are migrated on
load. Migration rounds sample, auto-sleep, dormant, and battery intervals up
to whole minutes, then clamps them to the v8 ranges; distances above 127 m
are clamped to 127 m. A prior 3-second radio-listen setting is migrated to the
new 6-second default. The 43-byte format omitted attempt count and the
reserved field; the 35-byte format also had the legacy 24-byte settings
structure and receives the current 900-second sleep / 6-second listen
defaults.

Each 24-byte RAM log slot contains `sequence` (4), `utcSeconds` (4),
`latitudeE7` (4), `longitudeE7` (4), `acknowledged` (1), reserved bytes (3),
CRC (2), and reserved bytes (2). Its CRC-16/CCITT-FALSE covers the first 16
bytes, so setting the acknowledgement flag does not invalidate it. Record
sequences combine a persisted 16-bit epoch with a 16-bit counter; a new epoch
is reserved in settings before the counter wraps. The ring itself is never
persisted.

## 5. Runtime configuration

Setting IDs, wire encodings, validation, and GET_CONFIG response layout are
normative in [gateway_protocol.md](./gateway_protocol.md). Current values:

| Setting | Default | Accepted range |
| --- | ---: | --- |
| `sampleIntervalMinutes` | 1 min | 1–127 min |
| `autoSleepMinutes` | 30 min | 1–127 min |
| `gpsTimeoutSeconds` | 45 s | 5–120 s; standby wake only |
| `batteryIntervalMinutes` | 60 min | 1–255 min |
| `distanceThresholdMeters` | 20 m | 0–127 m |
| `criticalBatteryMillivolts` | 3,300 mV | 3,000–4,200 mV |
| `txPowerDbm` | 10 dBm | 2–10 dBm |
| `dormantSleepMinutes` | 15 min | 1–59 min |
| `radioListenSeconds` | 6 s | 3–30 s and less than `dormantSleepMinutes` in seconds |

The 300-second GPS cold-start deadline and 4,000 mV minimum recharge
qualification threshold are firmware constants, not gateway settings.

## 6. Gateway command behavior

The gateway protocol is version 8 and is fully specified in
[gateway_protocol.md](./gateway_protocol.md). In brief:

* WAKE and SLEEP have empty payloads and receive an ACK correlated by the
  one-byte command sequence in the ACK payload. The sequence wraps modulo 256.
* FETCH sends a BATTERY report, then oldest-first DATA chunks of up to four
  records. The gateway must durably commit a complete chunk before sending
  DATA_ACK. The tracker waits 2 seconds for the matching chunk ID, retains
  unacknowledged records on timeout, and ends a successful or partial transfer
  with FETCH_DONE.
* SET_CONFIG updates one setting ID/value and persists it before status-0 ACK.
  GET_CONFIG returns a complete CONFIG_REPORT and does not use an ACK.
* Low-battery WAKE is rejected with ACK status 3. The one-byte
  CHARGE_REQUEST_ACK payload echoes the tracker CHARGE_REQUEST frame sequence;
  the tracker does not send a separate acknowledgment of this ACK.

The tracker does not authenticate frames, encrypt location data, deduplicate
gateway command sequences, or provide replay protection. IDs, sync word, and
CRC are not security controls. See the protocol document before implementing
the gateway.

## 7. Power and validation notes

Normal DORMANT uses System ON tickless sleep, not System OFF, so RTC deadlines
can continue to schedule battery checks and radio windows. System OFF is used
only for low-battery charge-wait when LPCOMP can be armed. The divider remains
powered in that state; using the nominal 490 kOhm total resistance at about
4 V implies roughly 8 uA through the divider, in addition to comparator and
board current.

An illustrative normal-DORMANT estimate is about 52 uA average if the board
draws 22 uA while radio-off and 9 mA during each 6-second listen window every
900 seconds. This is not a measured product guarantee and excludes battery
derating, self-discharge, transmissions, active GPS, and board variation.
Measure current on the assembled tracker.

Before relying on low-battery charge wake, verify the battery ADC calibration,
LPCOMP crossing threshold, System OFF wake on the exact T114 revision, and
current draw with the divider enabled. Also test dropped alert ACKs and
recovery qualification with real battery behavior. Firmware compilation alone
does not validate those hardware assumptions.
