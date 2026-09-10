# 03 — Mobile App & SDK

Two things live here: (A) the **vendor CRP SDK** — how a phone actually talks to the glasses —
and (B) our **tinker app's native module**, which wraps that SDK so React Native can drive it.

---

## A. The vendor SDK (`com.moyoung.glasses`)

### Shape
- Android: `my_galsses_sdk_0.0.7_20260403_release.aar`, package root `com.moyoung.glasses`.
  Public API is a clean set of `CRP*` classes; the implementation is obfuscated into
  `com.moyoung.a … com.moyoung.z`. Manifest package: `com.moyoung.glasses.sdk`.
- Declared deps: `com.google.protobuf:protobuf-java:4.29.3`, `com.squareup.okhttp3:okhttp:4.12.0`.
  Bundled inside the AAR: Jieli's `jl_bt_ota` (BLE DFU) and `jl_audio_decode` (Opus).
- iOS: `CRPSmartGlasses.xcframework` + the same Jieli frameworks (`JL_OTALib`, `JLAudioUnitKit`,
  `JL_AdvParse`, `JL_HashPair`, `MZEncryptSDK`, `openssl`). Same CRP surface, Swift-side.

### The three core classes
```
CRPBleClient        ── singleton entry: scan + get device
   └─ CRPBleDevice  ── one device: connect / disconnect / isConnected / name / address
        └─ CRPBleConnection ── the command surface (≈45 methods) + all listeners
```

### Connection lifecycle (the golden path)
```java
CRPBleClient client = CRPBleClient.create(appContext);   // once, in Application.onCreate
client.scanDevice(scanCallback, 10_000);                  // → onScanning(CRPScanDevice) / onScanComplete(list)
client.cancelScan();
CRPBleDevice device = client.getBleDevice(mac);
CRPBleConnection conn = device.connect();                 // returns the object; does NOT connect yet
conn.setConnectionStateListener(state -> { ... });        // STATE_CONNECTING/CONNECTED/DISCONNECTING/DISCONNECTED
conn.connect();                                           // actually initiates; result via the listener
// on STATE_CONNECTED: conn.syncTime(); conn.queryBattery(); ...
device.disconnect();
```
Gotchas (from the vendor docs): `device.connect()` returns the connection but does **not** dial;
you must call `conn.connect()`. `CRPBleClient` is a singleton; `getBleDevice(mac)` returns the same
object per MAC. Re-set listeners after a disconnect.

The full method / callback / listener / enum catalog is in
[04 — BLE Protocol Reference](04_BLE_Protocol_Reference.md). The machine-generated `javap` dump is
in [`reference/moyoung_public_api_signatures.txt`](reference/moyoung_public_api_signatures.txt).

### Data model note: protobuf on the wire
Several payloads are **protobuf** messages (`com.moyoung.glasses.conn.protos.*`): `VersionInfo`,
`BatteryInfo`, `VideoConfig`, `FileCount`, `TakePhoto`, `FlowStatus`, `RunningStatus`. That means
the underlying BLE "large data" channel carries protobuf-encoded structures, not just raw opcodes —
useful to know when sniffing (you'll see protobuf field tags). `RunningStatus` in particular is the
device's whole feature-state map (see [01 §3](01_Hardware_Architecture.md)).

---

## B. The tinker app's native module

The app is Expo (SDK 54, expo-router, RN 0.81, React 19). React Native can't call the Java AAR
directly, so we wrap it in a **custom Expo native module**, modeled 1:1 on the Cyan app's proven
architecture. The design intentionally separates a **reusable vendor-agnostic framework** from a
**thin MoYoung-specific adapter**.

### Layered architecture
```
JS screen
   │  Glass.ts  (requireNativeModule + typed wrappers + event subscription)
   ▼
GlassModule.kt         ── Expo ModuleDefinition: AsyncFunction(down) + Events(up)
   │  down: coroutine → GlassManager        ▲ up: greenrobot EventBus → sendEvent
   ▼                                         │
GlassManager.kt        ── router / singleton; owns GlassScanner + the adapter
   ▼
BaseGlassAdapter.kt    ── shared state machine, event hooks, IO scope   (REUSE)
   ▼
MoyoungAdapter.kt      ── MoYoung-specific: maps GlassAdapter ⇄ CRP SDK  (REPLACE)
   │
   ▼
MoyoungBleConnection.kt ── isolates every com.moyoung.glasses.* call
   ▼
com.moyoung.glasses (the vendor AAR)
```

- **Commands flow down** as coroutine `AsyncFunction`s that resolve/reject a `Promise`.
- **Events flow up** over a single flat envelope `GlassEventMsg(cmd, n1, n2, s1, s2)` posted on
  greenrobot EventBus (native→native), demuxed in `GlassModule` into typed Bundles, and emitted to
  JS via Expo `sendEvent`. This flat envelope is the key trick — one data class carries every event
  type.

### Reuse vs replace (what's MoYoung-specific)
| Layer | File | Status in this repo |
|-------|------|----------------------|
| Expo module (JS↔native boundary) | `glass/GlassModule.kt` | **Reused** framework |
| Event model | `glass/GlassEvent.kt` | **Reused** verbatim |
| Adapter interface + base | `glass/GlassAdapter.kt`, `glass/BaseGlassAdapter.kt` | **Reused** verbatim |
| Router + generic BLE scanner + fg service | `glass/GlassManager.kt`, `GlassScanner.kt`, `GlassService.kt` | **Reused** framework |
| **Vendor wrapper** | `glass/vendors/moyoung/MoyoungAdapter.kt`, `MoyoungBleConnection.kt` | **New / MoYoung-specific** — the CRP port |
| JS bridge | `src/native/Glass.ts` | **Reused** shape |
| Permissions | `src/util/permissions.ts` | **Reused** verbatim |
| Screens | `app/(tabs)/*` | **New** — tinker UI |

### CRP → GlassAdapter mapping (how the port lines up)
| `GlassAdapter` concept | CRP SDK call | Notes |
|------------------------|--------------|-------|
| `init(context)` | `CRPBleClient.create(ctx)` | once; SDK is a singleton |
| `startScan()` / `stopScan()` | `client.scanDevice(cb, timeout)` / `client.cancelScan()` | or use the generic raw-BLE `GlassScanner` |
| `connect(deviceId)` | `client.getBleDevice(mac).connect()` then `conn.connect()` | strip a `moyoung:` id prefix |
| ready signal | `CRPBleConnectionStateListener` → `STATE_CONNECTED` | mark connected here; then `syncTime`, `queryBattery` |
| battery push | `setBatteryListener` + `queryBattery` | `BatteryInfo{lvl,volt,charging,...}` → `GLASS_BATTERY` |
| device info | `queryDeviceVersion(type, cb)`, `queryDeviceId(cb)` | protobuf `VersionInfo` |
| photo/video | `takePhoto(PhotoMode)`, `queryVideoConfig`, `sendVideoConfig` | request/response via `CompletableDeferred` |
| audio record | `startAudio(sec, cmdCb)` / `stopAudio` / `queryAudioState` | |
| media download | `enableWifi(FILE)`→`connectWifi`→`downloadMediaFile(cb)` | Wi-Fi, not BLE |
| OTA (Jieli) | `startOta(listener)` / `startOta(File, listener)` | BLE DFU |
| OTA (Allwinner) | `enableWifi(OTA)`→`connectWifi`→`startAllWinnerOta(File, listener)` | Wi-Fi |
| wake word | `sendVoiceWakeUpState(bool)` / `queryVoiceWakeUpState(cb)` | the Cyan-parallel toggle |
| wear check | `sendWearCheckState(bool)` / `queryWearCheckState(cb)` | |
| feature map | `queryFeatureActiveState()` + `setFeatureActiveStateListener` | `RunningStatus` |
| device mgmt | `restart` / `reset` / `shutdown` / `removeBond` | all take `CRPCommandCallback` |
| AI dialogue | `setAiDialogueListener` + `sendAIDialogueState` + `exitAIDialogue` | Opus audio in |
| translation | `setTranslationListener` + `startTranslation` / `pause` / `stop` | Opus audio in |

### Async bridging pattern
The CRP SDK is **callback-based**; the `GlassAdapter` interface is `suspend`. Bridge them the same
way the Cyan adapter does:
- **request/response** (e.g. `takePhoto`, `queryBattery`) → wrap in a `CompletableDeferred` +
  `withTimeout`, complete it from the CRP callback.
- **device pushes** (battery change, wear status, AI audio, wake-word) → `EventBus.post(GlassEventMsg(...))`.
- a diagnostic `otaPost(msg)` helper reuses the `GLASS_ERROR` event as a **generic on-device trace
  channel**, so every native log line shows up in the app's debug panel — the tinker workflow's
  superpower.

### Current status — RUN ON HARDWARE (2026-07-30); connects after two crash fixes
The framework, JS bridge, all five screens, and the MoYoung adapter are implemented (all 45
`GlassAdapter` methods call the real CRP SDK) and it now **runs on a real device and connects**
(unit `F5:13:72:15:2C:31`). Two hard crashes had to be fixed first — both **missing vendor classes
the AAR references but never ships**, thrown on binder/dispatcher threads our code can't wrap:

1. **`NoClassDefFoundError: com/android/mltcode/paycertificationapi/IWrite`** — the vendor SDK
   (`com.moyoung.b.a`) calls a proprietary "PayCertification" **device auth handshake** from inside
   its own `onCharacteristicChanged`, killing the process the instant the glasses sent a
   notification. Fixed with compatible **stub classes** (`PayCertificationApi` / `IWrite` /
   `VerificationListener`) under the exact packages — signatures recovered via `javap`. (iOS ships
   the analogue `MZEncryptSDK.framework`; MoYoung just never bundled the Android side.)
2. **`NoClassDefFoundError: com/jieli/jl_audio_decode/opus/OpusManager`** — thrown from **both**
   `setAiDialogueListener` and `setTranslationListener`. The real class is a JNI wrapper
   (`System.loadLibrary("jl_opus")`) that isn't shipped; fixed with a no-op **`OpusManager` stub**
   (+ `OnDecodeStreamCallback`, `OpusException`). Consequence: AI-dialogue/translation **audio is not
   decoded**, everything else works.

Plus **`VendorCrashGuard`** (a narrow last-resort handler: swallows missing-vendor-class errors on
non-main threads only) and **connection hardening** ported from the sibling CRP app —
warm-scan-before-connect, a 5-attempt retry loop (Android's first GATT connect after idle often drops
instantly), a superseded-attempt guard, and reliance on the state listener rather than
`connect()`'s unreliable boolean. Full chronology in the append-only log
[`moyoung_reverse_engineering.md` §2](moyoung_reverse_engineering.md). Build/run **only** via
`npx expo run:android` (never manual APK installs).

> The stub sources were independently cross-validated against the sibling MADRIMs/Cyan app, which had
> solved the identical gaps ("signatures verified against the decompiled official app").

Two scan paths exist: the vendor scanner (default — decodes MoYoung's advertisement) and a generic
raw-BLE sweep (`startGenericScan`) as a fallback.

> **iOS:** scaffolded/​documented only. The `CRPSmartGlasses.xcframework` exposes the same CRP
> surface (see the two PDFs in `vendor-sdk/ios/`); an iOS adapter would mirror `MoyoungAdapter`
> against the Swift API. Android is the tested target, matching the Cyan project.
