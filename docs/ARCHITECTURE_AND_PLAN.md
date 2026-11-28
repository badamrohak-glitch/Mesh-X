# MESH-X — Stage 0 Architecture Report & Implementation Plan

## 1. Repository Inspection Summary

### 1.1 Entry Points & Execution Flow
1. **Web Application (`E:\Mesh-X`)**:
   - HTML entry: `index.html` mounts `<div id="root">` and loads `/src/main.tsx`.
   - React entry: `src/main.tsx` renders `<App />` (`src/App.tsx`) with `src/styles.css`.
   - Build config: `vite.config.ts` sets `base: './'` so all JS/CSS/asset paths work when loaded from local Android assets (`file:///android_asset/meshx/`).
   - Output: `dist/` (`index.html`, `assets/index-*.js`, `assets/index-*.css`, `meshx-logo.svg`, `manus-routes.json`).
2. **Android / React Native Shell (`E:\Mesh-X\android-app`)**:
   - JS entry: `android-app/index.js` registers `"MeshX"` (`android-app/app.json`) pointing to `android-app/App.tsx`.
   - WebView container: `android-app/App.tsx` renders `react-native-webview` (`^14.0.1`) loading `file:///android_asset/meshx/index.html`, wrapped in `SafeAreaProvider` / `SafeAreaView`, handling Android hardware `BackHandler` (`webViewRef.current?.goBack()`), and restricting navigation to `file:///android_asset/` and `about:blank`.
   - Bundled web assets: `android-app/android/app/src/main/assets/meshx/` contains a copy of the Vite `dist/` output.
3. **Android Native Project (`E:\Mesh-X\android-app\android`)**:
   - Application class: `com.meshx.MainApplication` (`MainApplication.kt`) initializes React Native (`PackageList(this).packages` + `loadReactNative(this)`).
   - Activity: `com.meshx.MainActivity` (`MainActivity.kt`) extends `ReactActivity`.
   - Manifest (`AndroidManifest.xml`): currently declares only `android.permission.INTERNET` and `MainActivity` (`singleTask`, `adjustResize`).
   - Gradle (`build.gradle` / `gradle.properties`):
     - `minSdkVersion = 24` (Android 7.0)
     - `targetSdkVersion = 36`
     - `compileSdkVersion = 37`
     - `kotlinVersion = "2.2.0"`
     - `newArchEnabled = false`, `hermesEnabled = true`, `edgeToEdgeEnabled = true`
   - Existing APK outputs: `app-debug.apk` and `app-release.apk` exist in `android-app/android/app/build/outputs/apk/`.

### 1.2 Existing Dependencies & Backups
- **Web (`package.json`)**: `react`, `react-dom`, `lucide-react`, `vite`, `@vitejs/plugin-react`, `typescript`.
- **Android Shell (`android-app/package.json`)**: `react-native 0.88.1`, `react-native-webview ^14.0.1`, `react-native-safe-area-context ^5.5.2`, `react-native-svg ^15.16.1`, `lucide-react-native ^1.80.0`.
- **Preserved Backups**: `MESHX-original-App.tsx`, `MESHX-original-styles.css`, `vite.config.ts.backup`, `README-backup.md`, `android-app/App.backup.tsx`, `android-app/App.tsx.before-webview.bak`. All backups remain untouched.

### 1.3 Backend Evaluation & Decision
- Currently, there is **no cloud backend** configured in the repository (`public/manus-routes.json` and `app.config.ts` contain only static route metadata and logo URL).
- **Selected Cloud Backend (for Stage 8)**: **Firebase** (Firebase Authentication + Cloud Firestore + Firebase Cloud Messaging + Cloud Functions in TypeScript).
  - *Why*: Integrates natively with Android, handles offline-queue/reconnect synchronization cleanly via Auth + FCM wakeups, avoids maintaining two competing backends, and separates user-initiated cloud sync cleanly from local-only mesh relaying.

---

## 2. Mapping Existing UI Surfaces to Real Functionality

| UI Page | Current State | Target Real Functionality (by Stage) |
|---------|---------------|--------------------------------------|
| **Home** (`/`) | 6 layout directions with simulated health, topology, stats, and activity | Real device & mesh state summary from Kotlin (`GET_NETWORK_STATUS`, real event feed, active peer count); clear simulation markers until wired |
| **Chats** (`/chats`) | Hardcoded 3 conversations, static message bubbles | Persisted Room SQLite conversations, real message sending, verified `pending`/`sent`/`delivered`/`read` states (Stages 5, 8) |
| **Network** (`/network`) | Static 5-node topology diagram | Real transport & discovery status (BLE/Wi-Fi Direct/Internet), real discovered/connected nodes only, relay opt-in status, pending queue counts (Stages 2–6) |
| **Transfers** (`/transfers`) | Hardcoded upload/queue/history rows | Android system file picker, chunked Wi-Fi Direct / relay transfers, SHA-256 verification, real progress, retry/cancel/resume (Stage 7) |
| **Nearby Devices** (`/nearby`) | Static peer list with simulated connect | Runtime permission checks, BLE advertising/scanning controls, real MESH-X peer list, connection accept/reject (Stages 2–4) |
| **Gateway** (`/gateway`) | Offline gateway card with simulated request | Explicit separation of user's own internet sync (Stage 8) vs. local-only relay rule (never silently relaying others' traffic over mobile data) |
| **Settings** (`/settings`) | Static identity card (`MX-4A7C-91B2`), local React toggle state | Android Keystore identity (Stage 1/4+), persisted Room/DataStore prefs: discovery, relay opt-in, background service, storage quota, notifications (Stages 2–10) |
| **Debug** (`/debug`) | Static 6-row packet table | Real native bridge diagnostics (Stage 1), permission matrix, active transports, queue counts, routing events, storage stats (no private keys or message plaintext) |

---

## 3. Layered Architecture

- **Layer A — Web UI (`E:\Mesh-X\src`)**: React + TypeScript + Vite. Renders screens and communicates with Android via `src/bridge/nativeBridge.ts`.
- **Layer B — React Native Shell (`E:\Mesh-X\android-app`)**: Hosts the `WebView` (`App.tsx`) and relays versioned JSON messages (`src/bridgeRelay.ts`) between `WebView` and Kotlin `MeshXBridgeModule`, plus forwards native `DeviceEventEmitter` events to the WebView.
- **Layer C — Native Bridge (`com.meshx.bridge`)**: Versioned envelope validation (`BridgeContract.kt`), command routing (`BridgeDispatcher`), and modular `BridgeCommandHandler` implementations.
- **Layer D — Android Native Services (`com.meshx.*`)**: Modular Kotlin packages added stage-by-stage:
  - `com.meshx.bridge` (Stage 1)
  - `com.meshx.permissions` & `com.meshx.capabilities` (Stage 2)
  - `com.meshx.discovery` (Stage 3)
  - `com.meshx.transport` & `com.meshx.identity` (Stage 4)
  - `com.meshx.messaging` & `com.meshx.storage` (Stage 5)
  - `com.meshx.routing` (Stage 6)
  - `com.meshx.transfers` (Stage 7)
  - `com.meshx.sync` & `com.meshx.notifications` (Stage 8)
  - `com.meshx.background` (Stage 9)
  - `com.meshx.security` (Cross-cutting, hardened in Stage 10)
- **Layer E — Local Database**: SQLite via Android Room (introduced in Stage 5 with versioned migrations).
- **Layer F — Cloud Backend**: Firebase (Auth, Firestore, FCM, TypeScript Cloud Functions) introduced in Stage 8.

---

## 4. Staged Implementation Plan

- **Stage 1 — Native Bridge**: `BridgeContract.kt`, `BridgeCommandHandler.kt`, `PingHandler.kt`, `RuntimeInfoHandler.kt`, `MeshXBridgeModule.kt`, `MeshXBridgePackage.kt`, `bridgeRelay.ts`, `src/bridge/nativeBridge.ts`, Debug panel integration, unit + Jest tests.
- **Stage 2 — Capabilities & Permissions**: Hardware checks (BLE, Wi-Fi, Wi-Fi Direct, Location/Bluetooth runtime permissions for Android 7–16), `CHECK_CAPABILITIES` / `REQUEST_PERMISSIONS` bridge commands, real capability/permission UI in Nearby & Debug.
- **Stage 3 — Nearby Discovery**: `MeshTransport` & `DiscoveryProvider` abstractions, BLE GATT/Advertising with MESH-X service UUID and privacy-preserving rotating/short identifier, start/stop discovery, real peer stream to Nearby UI.
- **Stage 4 — Direct Connection**: Authenticated local session handshake, connection request/accept/reject UX, disconnect/reconnect handling, Android Keystore device keypair.
- **Stage 5 — Direct Messaging**: Room SQLite database (`conversations`, `messages`, `acks`, `devices`), versioned message envelope, deduplication by message ID, real delivery acknowledgements.
- **Stage 6 — Offline Store-and-Forward**: Relay opt-in setting, encrypted relay packet table, TTL + max hop count, storage quota enforcement, local-only relay forwarding (strictly zero mobile-data relaying).
- **Stage 7 — File Transfers**: Android SAF file picker, Wi-Fi Direct high-bandwidth transport, chunked transfer + SHA-256 verification + resume/cancel/retry.
- **Stage 8 — Internet Sync**: Firebase Auth + Firestore + FCM, sync user's own pending messages on internet restore, reconcile delivery states without violating relay data rule.
- **Stage 9 — Background Operation**: Foreground service (user-controlled) for active mesh sessions + WorkManager for deferred cleanup/sync within Android background limits.
- **Stage 10 — Security, Testing & Release**: End-to-end encryption review, malformed packet fuzzing, multi-device physical verification, standalone release APK.

## C3 — Foreground Service Startup Error Handling

**Status:** Code corrected — Android debug build successful.

### Changes
- Added exception handling around `MeshForegroundService.start()`.
- Handles `SecurityException` when Android denies foreground-service startup.
- Handles `IllegalStateException` when Android restricts foreground-service startup.
- Returns descriptive bridge errors instead of allowing startup exceptions to escape.

### Verification
- Android debug build: `assembleDebug` — BUILD SUCCESSFUL.
- Physical-device foreground-service startup and BLE operation tests: pending.

### Limitation
Foreground-service startup is asynchronous. The immediate bridge response does not guarantee that the service has completed startup or that BLE scanning and advertising are active.

