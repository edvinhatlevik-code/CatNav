# Gateway API v1

The gateway exposes an asynchronous HTTP API on the local Wi-Fi network at
`http://cat-gateway.local`. If mDNS is unavailable, use the IP shown in the
serial monitor. Requests use JSON and must include:

```http
Authorization: Bearer <API token>
Content-Type: application/json
```

Set the Wi-Fi SSID, password, and a private API token in
`include/user_config.h` before connecting the gateway. The API does not start
while the token is left at its placeholder value. Keep the gateway on a trusted
local network; this HTTP API is not encrypted and should not be exposed to the
public internet.

The PlatformIO environment uses `heltec_wifi_lora_32_V3` for the
HTIT-WSL_V3's ESP32-S3/SX1262 hardware. The configured SX1262 pins are NSS 8,
SCK 9, MISO 11, MOSI 10, reset 12, BUSY 13, and DIO1 14. Radio parameters are
868.1 MHz, 125 kHz bandwidth, SF7, coding rate 4/5, sync word `0x12`, 8-symbol
preamble, and PHY CRC enabled. Confirm that this frequency and transmit power
are legal for the installation region before enabling transmissions.

## Routes

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/status` | Gateway ID, Wi-Fi/IP, uptime, and registered tracker count |
| `GET` | `/api/v1/trackers` | Registered tracker state and latest RSSI/SNR |
| `POST` | `/api/v1/trackers` | Register a tracker ID |
| `GET` | `/api/v1/trackers/{id}/battery` | Latest persisted battery and lockout state |
| `GET` | `/api/v1/trackers/{id}/config` | Latest received configuration and desired values |
| `GET` | `/api/v1/trackers/{id}/locations?offset=0&limit=100` | Location records, insertion order; limit up to 100 per page |
| `GET` | `/api/v1/alerts` | Current low-battery lockouts |
| `GET` | `/api/v1/events` | Authenticated Server-Sent Events (SSE), including charge requests |
| `POST` | `/api/v1/jobs` | Queue a tracker command; returns `202 Accepted` |
| `GET` | `/api/v1/jobs` | Recent asynchronous command jobs |
| `GET` | `/api/v1/jobs/{id}` | Status and result for one job |

### Registering a tracker

Use the non-zero 32-bit ID printed by the tracker firmware at boot:

```json
{"trackerId": 123456789}
```

Registration is stored in NVS and survives a gateway restart. The gateway
accepts frames only from registered tracker IDs. The `gatewayId` reported by
the status route identifies this gateway to API clients; it is not present in
the LoRa protocol.

### Queuing commands

```json
{"trackerId": 123456789, "command": "FETCH"}
```

Supported commands are `WAKE`, `SLEEP`, `FETCH`, `GET_CONFIG`, and
`SET_CONFIG`. For example:

```json
{"trackerId": 123456789, "command": "SET_CONFIG", "settingId": 8, "value": 900}
```

`SET_CONFIG` uses the setting IDs, value types, and ranges in
`gateway_protocol.md`. API values for setting IDs 1, 2, 4, and 8 are in
seconds (and must be whole minutes); the gateway converts them to the protocol's
minute-valued wire representation. Other API setting values use the units in
the protocol document. Jobs are processed one at a time. A sleeping tracker
may take multiple 15-minute receive windows to respond, so poll its job
endpoint; do not assume `202 Accepted` means the radio command completed.

Job status values are `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, and
`TIMED_OUT`. A completed FETCH can include `partial: true` and a non-zero
`pendingRecords` count if the tracker still has untransferred records.
`acknowledgedChunks` reports the tracker's FETCH_DONE count.

## First-time setup

1. Edit the API `gatewayId`, Wi-Fi credentials, and `apiToken` in
   `include/user_config.h`. Keep the gateway ID non-zero and unique; it is not
   transmitted over LoRa.
2. Build and upload `heltec_wifi_lora_32_V3`. The USB serial monitor runs at
   115200 baud and reports radio initialization, Wi-Fi status, and tracker IDs.
3. Register each tracker using its boot-printed ID and the tracker route above.
4. Queue `GET_CONFIG` or `FETCH` for a registered tracker. A sleeping tracker
   may need several 900-second sleep/6-second listen windows to receive a job.

### Charge alerts

The gateway acknowledges a valid `CHARGE_REQUEST` promptly, then persists the
tracker's low-battery state. It emits an SSE `charge_request` event:

```text
event: charge_request
data: {"trackerId":123456789,"requestSequence":12,"attempt":1,...}
```

SSE clients must send the same `Authorization` header. Poll `/api/v1/alerts`
to recover current lockouts after reconnecting.

### Location retention

Per the selected functional-design behavior, location history is held only in
RAM. Up to 1024 records are retained for the current gateway uptime. The
gateway validates and atomically accepts each DATA chunk before sending
`DATA_ACK`, but an acknowledged record can be lost if the gateway reboots.
When RAM capacity is reached, the gateway does not acknowledge new chunks.
Battery/lockout state, tracker IDs, settings, packet sequence, and an
outstanding command are stored in NVS.

## Local test

From PowerShell:

```powershell
curl.exe -H "Authorization: Bearer YOUR_CONFIGURED_TOKEN" `
  http://cat-gateway.local/api/v1/status
```

To queue a FETCH:

```powershell
curl.exe -X POST `
  -H "Authorization: Bearer YOUR_CONFIGURED_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"trackerId":123456789,"command":"FETCH"}' `
  http://cat-gateway.local/api/v1/jobs
```

## Hardware and library references

- [Heltec Wireless Stick Lite / HTIT-WSL documentation](https://docs.heltec.org/en/node/esp32/wireless_stick_lite/index.html)
- [PlatformIO Heltec WiFi LoRa 32 V3 board profile](https://docs.platformio.org/en/latest/boards/espressif32/heltec_wifi_lora_32_V3.html)
- [RadioLib SX126x settings example](https://github.com/jgromes/RadioLib/blob/master/examples/SX126x/SX126x_Settings/SX126x_Settings.ino)
