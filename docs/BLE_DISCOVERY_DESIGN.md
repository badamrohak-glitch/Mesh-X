# MESH-X BLE Discovery Design

## 1. Purpose

This document describes the current Android BLE discovery and peer identity implementation.

The BLE layer is intended to let nearby MESH-X devices discover one another, establish a BLE GATT connection, and exchange public device identity information.

This is a discovery and identity foundation. It is not yet a complete messaging or mesh-routing implementation.

## 2. Current Architecture

Main implementation:

- `com.meshx.ble.BleScanner`
- `com.meshx.bridge.handlers.DiscoveryHandler`
- `com.meshx.bridge.handlers.IdentityHandler`
- `com.meshx.storage.MeshLocalStore`
- `com.meshx.bridge.BridgeEventEmitter`

The React frontend sends commands through the native bridge. Kotlin handlers validate command-specific input and call `BleScanner`.

`BleScanner` manages BLE advertising, scanning, discovered-device records, GATT connections, and Bluetooth adapter state changes.

## 3. BLE Identifiers

The current implementation uses:

| Purpose | UUID / Value |
|---|---|
| BLE service UUID | `0000c0de-0000-1000-8000-00805f9b34fb` |
| Identity characteristic UUID | `0000c0df-0000-1000-8000-00805f9b34fb` |
| Manufacturer ID | `0x1234` |

The advertised service UUID is used by scanners to identify MESH-X-compatible advertisements.

The manufacturer data contains a four-byte prefix derived from the local public-key fingerprint.

This short value is a discovery hint, not a complete cryptographic identity or authentication proof.

## 4. Discovery Lifecycle

### Initialization

`BleScanner.initialize(appContext)` stores the application context and registers a Bluetooth adapter state receiver.

### Capability checks

`GET_DISCOVERY_CAPABILITIES` reports:

- BLE hardware support
- Bluetooth enabled state
- Advertising support
- Permission status
- Whether location is required
- Whether location services are enabled
- Whether scanning is active

### Starting discovery

`START_DISCOVERY` accepts:

- `lowPowerMode`: optional Boolean, default `false`

The implementation checks BLE hardware, runtime permissions, Bluetooth state, and legacy location requirements.

It then attempts to start:

1. A GATT server exposing the identity characteristic.
2. BLE advertising.
3. BLE scanning using the MESH-X service UUID filter.

Scanning must start successfully for the overall operation to report success. Advertising can fail independently, so callers should inspect both `advertising` and `scanning` in the response.

The current scan modes are:

- Normal mode: low-latency scanning.
- Low-power mode: balanced scanning.

### Stopping discovery

`STOP_DISCOVERY` stops scanning and advertising and closes the local GATT server.

The current implementation does not automatically disconnect every active GATT client as part of this command.

## 5. Nearby Device Records

Each discovered device is represented by a `DeviceRecord` containing:

- Bluetooth address
- Device name
- RSSI
- Last-seen timestamp
- Advertised fingerprint hint
- Device ID, when received
- Public key, when received
- Connection state

The records are held in a concurrent in-memory map.

A cleanup task checks for devices that have not been seen for 15 seconds. Devices that are no longer seen are removed and a `DEVICE_LOST` event is emitted, unless their connection state is `connected`.

## 6. GATT Identity Exchange

The local GATT server exposes a readable identity characteristic.

When a peer reads it, the server obtains the local identity through `AndroidDeviceIdentityProvider` and returns JSON containing:

- `deviceId`
- `publicKey`
- `algorithm`

The connecting device reads the characteristic after discovering the MESH-X GATT service.

The client parses the received identity, derives a short SHA-256-based fingerprint, updates its in-memory device record, and stores the peer using `MeshLocalStore.upsertPeer`.

### Important security limitation

The current implementation stores the received peer with `trusted = true`.

However, the current identity exchange does not demonstrate cryptographic proof of private-key ownership, a challenge-response protocol, or user-approved peer verification.

Consequently:

- A received public key is not proof that the remote device controls its corresponding private key.
- The four-byte advertising fingerprint is not sufficient for secure identity verification.
- The current `trusted` value must not be treated as a verified trust decision.

Before production use, replace automatic trust with an explicit verification process and cryptographic authentication.

## 7. Native Bridge Commands

| Command | Purpose |
|---|---|
| `GET_DISCOVERY_CAPABILITIES` | Read BLE capabilities and permission state |
| `START_DISCOVERY` | Start advertising and scanning |
| `STOP_DISCOVERY` | Stop discovery and local advertising |
| `GET_NEARBY_DEVICES` | Return currently tracked devices |
| `CONNECT_PEER` | Initiate a GATT connection using a Bluetooth address |
| `DISCONNECT_PEER` | Disconnect an active GATT client |

`CONNECT_PEER` and `DISCONNECT_PEER` require a non-empty `address` field.

## 8. Native Events

The BLE layer emits these events through `BridgeEventEmitter`:

| Event | Meaning |
|---|---|
| `DEVICE_FOUND` | A previously unseen device was discovered |
| `DEVICE_LOST` | A device timed out and was removed |
| `DISCOVERY_STATE_CHANGED` | Discovery state changed |
| `BLUETOOTH_STATE_CHANGED` | Bluetooth adapter state changed |
| `PEER_CONNECTION_CHANGED` | A peer connection or identity state changed |

Consumers should use these events to keep the frontend's discovery and connection UI synchronized with native state.

## 9. Android Permissions

The manifest declares:

- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`
- `ACCESS_FINE_LOCATION`

The BLE scanner checks the Android version:

- Android 12 and newer: scan, connect, and advertise runtime permissions.
- Earlier Android versions: fine-location permission.
- Earlier Android versions also require location services to be enabled for BLE scanning.

The manifest declares BLE hardware as optional, so devices without BLE hardware can still install the application.

## 10. Current Limitations

The current implementation does not yet establish a complete mesh network.

Outstanding work includes:

1. Cryptographic peer authentication and explicit trust management.
2. Reliable GATT connection lifecycle handling and reconnection.
3. A defined GATT message-transfer protocol.
4. Message delivery acknowledgements and retry handling.
5. Store-and-forward relay processing.
6. Multi-hop routing and TTL enforcement.
7. Duplicate packet detection.
8. Background discovery and delivery reliability testing.
9. Privacy review of device identifiers and Bluetooth addresses.
10. Testing across multiple physical Android devices and Android versions.

The current GATT identity exchange must not be described as encrypted messaging or authenticated communication.

## 11. Physical Device Validation

Use two Android devices with MESH-X installed.

Suggested validation sequence:

1. Check BLE capabilities on both devices.
2. Grant the required runtime permissions.
3. Enable Bluetooth.
4. Start discovery on both devices.
5. Confirm that each device can discover the other.
6. Inspect RSSI, last-seen time, and advertised fingerprint.
7. Initiate a connection to the discovered peer.
8. Observe GATT connection state events.
9. Confirm whether identity information is received and stored.
10. Stop discovery and inspect the resulting state.

Record Android versions, device models, test results, and any failures.

A successful discovery or GATT connection does not prove that application-level messaging or secure peer authentication works.

## 12. Completion Criteria for the Next BLE Milestone

The next milestone should be considered complete only when:

- Two physical devices can discover one another consistently.
- Discovery can be started and stopped without leaving stale callbacks.
- GATT connection and disconnection states are reported correctly.
- Peer identity is parsed and stored consistently.
- Identity verification is implemented before peers are marked trusted.
- Permission denial and Bluetooth-off cases are handled cleanly.
- Tests cover connection failures and repeated discovery sessions.

## BLE Advertising Startup Order — C2 Correction

**Status: Corrected — Android build successful**

Updated `BleScanner.startAdvertising()` to check whether
`bluetoothLeAdvertiser` is available before starting the GATT server.

The GATT server is still started when advertising is supported.

### Verification
- Confirmed the corrected startup order in `BleScanner.kt`.
- Android debug build completed successfully.
- Build result: `BUILD SUCCESSFUL`.

### Remaining verification
- Physical-device BLE advertising and GATT connection tests are
  still required.
- A successful build does not prove runtime BLE compatibility.

