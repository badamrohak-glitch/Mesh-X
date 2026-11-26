# MESH-X Native Bridge Specification (Layer C, v1)

## 1. Purpose & Security Model
The Native Bridge connects the Web UI (`src/` inside `WebView`) to Android Kotlin services (`com.meshx.bridge`) through the React Native shell (`android-app/App.tsx`).

Security rules:
- **No unrestricted Android access**: JavaScript can only invoke commands explicitly registered in `BridgeDispatcher`'s allowlist.
- **No `@JavascriptInterface` exposure**: Uses `react-native-webview`'s `window.ReactNativeWebView.postMessage` (WebView -> RN) and double-JSON-encoded `injectJavaScript` (RN -> WebView).
- **Strict schema & size validation**: Every incoming message is validated in Kotlin (`BridgeEnvelopeValidator`) before any handler runs. Maximum payload size is 64 KB.
- **No silent fake data**: When the Web UI runs in a regular browser outside the Android WebView, `nativeBridge.ts` reports `BRIDGE_UNAVAILABLE` honestly.

---

## 2. Message Envelopes (Protocol v1)

### 2.1 Request (Web UI -> RN -> Kotlin)
```json
{
  "version": 1,
  "kind": "request",
  "requestId": "req_1732612000000_1",
  "type": "BRIDGE_PING",
  "payload": {
    "echo": "hello-meshx"
  },
  "timestamp": 1732612000000
}
```

Validation rules:
- Must be a valid JSON object (<= 65,536 characters).
- `version`: integer, must equal `1` (otherwise `UNSUPPORTED_VERSION`).
- `kind`: string, must equal `"request"` (otherwise `INVALID_ENVELOPE`).
- `requestId`: non-empty string, 1–128 chars (otherwise `INVALID_ENVELOPE`).
- `type`: string matching `^[A-Z0-9_]{2,64}$` (otherwise `INVALID_ENVELOPE`).
- `payload`: JSON object (otherwise `INVALID_ENVELOPE`).
- `timestamp`: positive number (otherwise `INVALID_ENVELOPE`).

### 2.2 Response (Kotlin -> RN -> Web UI)
Success:
```json
{
  "version": 1,
  "kind": "response",
  "requestId": "req_1732612000000_1",
  "type": "BRIDGE_PING",
  "ok": true,
  "data": {
    "pong": true,
    "echo": "hello-meshx",
    "receivedAtMs": 1732612000000,
    "nativeTimestampMs": 1732612000012,
    "uptimeMs": 452190
  },
  "timestamp": 1732612000012
}
```

Error:
```json
{
  "version": 1,
  "kind": "response",
  "requestId": "req_1732612000000_2",
  "type": "UNKNOWN_OP",
  "ok": false,
  "error": {
    "code": "UNKNOWN_COMMAND",
    "message": "Command 'UNKNOWN_OP' is not registered on this bridge."
  },
  "timestamp": 1732612000015
}
```

Error codes:
- `INVALID_JSON` — Input is not valid JSON or exceeds size limit.
- `INVALID_ENVELOPE` — Missing or malformed envelope fields (`kind`, `requestId`, `type`, `payload`, `timestamp`).
- `UNSUPPORTED_VERSION` — `version` is not `1`.
- `UNKNOWN_COMMAND` — `type` is not registered in the Kotlin handler allowlist.
- `INVALID_PAYLOAD` — Command-specific payload validation failed.
- `INTERNAL_ERROR` — Unexpected error inside native layer or RN relay.
- `BRIDGE_UNAVAILABLE` — Client-side code when running outside Android WebView.
- `TIMEOUT` — Client-side timeout waiting for native response.

### 2.3 Native Event (Kotlin -> RN -> Web UI)
```json
{
  "version": 1,
  "kind": "event",
  "eventId": "evt_1732612000050_1",
  "type": "BRIDGE_READY",
  "payload": {
    "protocolVersion": 1,
    "platform": "android"
  },
  "timestamp": 1732612000050
}
```

---

## 3. Stage 1 Commands

| Command | Payload | Response `data` |
|---------|---------|-----------------|
| `BRIDGE_PING` | `{ echo?: string }` (max 256 chars) | `{ pong, echo, receivedAtMs, nativeTimestampMs, uptimeMs }` |
| `GET_RUNTIME_INFO` | `{}` | `{ protocolVersion, platform, manufacturer, model, androidRelease, sdkInt, appId, appVersionName, appVersionCode, isDebugBuild, availableCommands }` |
