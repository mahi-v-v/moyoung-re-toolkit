# MoYoung Tinker App

An Expo (SDK 54) app for poking at MoYoung smart glasses over BLE, built around a custom Expo
native module that wraps the vendor's `my_galsses_sdk` AAR.

**Status: wired, and it compiles.** The framework, the JS bridge, all five screens, **and the vendor
CRP calls** are implemented. Every command in `MoyoungAdapter` calls the real SDK; every vendor
callback is mapped back to a typed JS event. All 45 `GlassAdapter` methods are implemented against
the exact AAR signatures (see [`../docs/reference/`](../docs/reference/)).

Verified here: `npx tsc --noEmit` clean, `expo prebuild` succeeds, and
`./gradlew :glass-sdk:compileDebugKotlin` builds with **zero Kotlin errors** against the vendor AAR.

**Not yet validated on hardware.** Nothing has touched a real pair of glasses — the wiring follows
the SDK's published signatures and the vendor guides, not a live session. Expect to shake out timing
and ordering issues on first contact; [First contact](#first-contact) is a plan that fails safely.

Two scan paths: the **vendor scanner** (default — decodes MoYoung's advertisement, so you get
`firmwareType` + `battery` + `isCharging` before connecting) and a **generic raw-BLE sweep**
(`Glass.startGenericScan()`) showing every advertising device, for when the vendor scan filters out
something you expect to see.

---

## Build & run

```bash
npm install
npx expo prebuild        # generates android/ and autolinks modules/glass-sdk
npx expo run:android     # builds & installs on a connected device
```

Requires JDK 17+ and the Android SDK. Android is the target; iOS is documented but not implemented
(see [`../docs/03_Mobile_App_and_SDK.md`](../docs/03_Mobile_App_and_SDK.md)).

> `npx expo start` alone (Expo Go) will **not** work — the native module needs a dev build.

Typecheck with `npx tsc --noEmit`.

---

## Layout

```
app/(tabs)/
  index.tsx      Connect  — scan, connect, live status, quick session probes
  control.tsx    Control  — feature state, wake word, wear check, version probes, danger zone
  capture.tsx    Capture  — photo/audio/video config, Wi-Fi media download, device logs
  ota.tsx        OTA      — cloud firmware check, Jieli BLE DFU, Allwinner Wi-Fi OTA
  debug.tsx      Debug    — arbitrary sendCommand probing + raw trace stream
components/GlassKit.tsx   Shared UI primitives + the global device-trace buffer
modules/glass-sdk/        The native module (see below)
```

### The native module
```
modules/glass-sdk/
  expo-module.config.json          registers expo.modules.moyoung.glass.GlassModule
  index.ts                         JS barrel
  src/native/Glass.ts              typed bridge + event subscriptions
  src/util/permissions.ts          runtime BLE/Wi-Fi permission requests
  src/util/detectVendor.ts         scan-result classification (heuristic — needs a real device)
  android/
    build.gradle                   AAR wiring + transitive deps
    libs/my_glasses_sdk.aar        the vendor SDK
    src/main/java/expo/modules/moyoung/glass/
      GlassEvent.kt                 ← framework: flat event envelope
      GlassAdapter.kt               ← framework: vendor-agnostic contract
      BaseGlassAdapter.kt           ← framework: state machine, hooks, safe defaults
      GlassScanner.kt               ← framework: raw-BLE scanner (works today)
      GlassManager.kt               ← framework: router/singleton
      GlassService.kt               ← framework: foreground service
      GlassModule.kt                ← framework: the Expo JS↔native boundary
      vendors/moyoung/
        MoyoungBleConnection.kt     ← vendor: the ONLY file importing com.moyoung.glasses.*
        MoyoungAdapter.kt           ← vendor: maps the contract onto CRP calls + push listeners
```

Only the two files under `vendors/moyoung/` are MoYoung-specific. Everything above them is reusable
framework, lifted from the Cyan project's proven architecture. To add a second vendor, write a
sibling of those two files and add a branch to `GlassManager.createAdapter`.

### How it flows
```
Screen → Glass.ts → GlassModule.kt (AsyncFunction) → GlassManager → MoyoungAdapter → CRP SDK
Screen ← Glass.ts ← GlassModule.kt (sendEvent)     ← EventBus     ← MoyoungAdapter ← CRP callback
```
Commands go down as coroutines resolving a Promise. Events come up as a flat
`GlassEventMsg(cmd, n1, n2, s1, s2)` on greenrobot EventBus, demuxed into typed Bundles in
`GlassModule.emitEvent`.

**The trace channel is your friend:** `trace("...")` in any adapter method rides the `GLASS_ERROR`
event into the on-screen log panel on every tab. Use it liberally while wiring.

---
## First contact

Nothing in this app has run against real hardware yet. Work up in this order — each step proves one
more layer, and the destructive commands come last. **Keep the Debug tab open the whole time**: every
native call emits a `[CRP]` trace there, so you can see exactly which SDK call fired and what came back.

### 0. Before the glasses arrive
```bash
npm install
npx expo prebuild --platform android
npx expo run:android      # must build & boot; Expo Go will NOT work
```
Confirm the app launches and the Debug tab shows `[CRP] CRPBleClient created`. That single line
proves the AAR is linked and the module autolinked correctly — worth confirming *before* you have a
device to blame.

### 1. Scan
Grant permissions when prompted (on API 31+ a missing `BLUETOOTH_SCAN` makes the scanner **silently**
no-op — no error, no results).

Hit **Scan** on the Connect tab. You should see the glasses with `firmwareType`, `battery`, and
`isCharging` decoded from the advertisement. If nothing appears, try **Generic scan**
(`Glass.startGenericScan()`) — it shows every advertising device, unfiltered. If the device shows up
there but not in the vendor scan, the advert parser is rejecting it, and that's worth noting.

### 2. Connect
Tap the device. Watch for the state sequence `CONNECTING → CONNECTED` and then `[CRP] connection
state -> 2`.

This is the step most likely to need adjustment — the SDK's two-step connect (`device.connect()`
returns the object, `connection.connect()` actually dials) is wired, but real-world timing may need a
delay before the follow-up queries. On connect the app automatically runs `syncTime`, `queryBattery`,
`queryDeviceId`, and `queryFeatureActiveState`.

### 3. Read-only probes (safe)
On the **Control** tab, in rough order of harmlessness:
- `queryAllVersions` — pulls all six version types. **Do this first**: `VerFirmware` (Jieli) and
  `VerFirmware1` (Allwinner) are exactly the `fw1_ver`/`fw2_ver` you need for the OTA check, and
  `VerGithash` pins the exact build.
- Battery, device ID, feature state (`RunningStatus` — the live map of what the device is doing).
- Wake word / wear check **queries** (read the current value before setting anything).

If these return sane data, the whole stack is proven end to end.

### 4. Toggles (reversible)
Wake word on/off, wear check on/off. Read the value back after each write to confirm it stuck.

### 5. Capture
Photo (`ModeNormal` first), then audio record, then video config read/write. Then the Wi-Fi media
flow on the **Capture** tab: `enableWifi(FILE)` → wait for the connected callback → `downloadMediaFile`.
**Wait for the callback** — the device needs 5–10 s to bring Wi-Fi up, and the app auto-disables Wi-Fi
after a transfer so the battery doesn't drain.

### 6. OTA — last, and read the docs first
Read [`../docs/02_Firmware_and_OTA.md`](../docs/02_Firmware_and_OTA.md) before touching this tab.

Start with **`checkFirmware`** (read-only): it hits `altair.moyoung.com` and returns `fileUrl` +
`md5` for both chips. That alone is a research win — it's how you obtain official images.

If you flash: **do the Jieli side first.** It's dual-bank A/B, so a rejected image should fall back to
the good bank. The Allwinner side is a `.swu` over Wi-Fi with no bank-swap safety net.

> You already have four real Jieli images to work with (2.0.3 / 2.0.4 / 2.0.5 / 2.0.5-test) in
> ``../vendor-sdk/ios/.../swift-SdkDemo/TestSdk/`` — diff them before flashing.

### ⚠️ Commands that can ruin your day
The **danger zone** on the Control tab is deliberately last:

| Command | Effect |
|---------|--------|
| `factoryReset` | **Wipes the device.** Irreversible. |
| `removeBond` | Unpairs — you'll need to re-pair, possibly from the vendor app. |
| `shutdown` | Powers off; you may need physical access to power back on. |
| `restart` | Reboots and drops the link (the mildest of the four). |
| `startJieliOta` / `startAllwinnerOta` | Flashes firmware. Bricking risk on the Allwinner path. |

Don't run any of these until steps 1–5 work, and don't run them on your only pair of glasses first.

---

## If something doesn't work

| Symptom | Likely cause |
|---------|--------------|
| No `CRPBleClient created` trace | AAR not linked / module not autolinked. Re-run `expo prebuild`. |
| Scan returns nothing | Runtime permission not granted (silent no-op on API 31+), or Bluetooth off. Try `isBluetoothEnabled`. |
| Device found but connect never reaches CONNECTED | The two-step connect timing; check the Debug tab for which step logged last. |
| Connects, then every command is ignored | `[CRP] no active connection` means `connection` is null — the link dropped. Listeners must be re-registered after reconnect. |
| `NoClassDefFoundError` at runtime | A transitive dep is missing. The AAR bundles none — `protobuf-java` and `okhttp` are declared in `modules/glass-sdk/android/build.gradle`. |
| Commands succeed but no events arrive | The push listener wasn't registered — it happens in `onDeviceConnected()`, only after CONNECTED. |

Add `trace("...")` anywhere in `MoyoungAdapter` and it appears live in the Debug tab — that's the
fastest way to bisect a problem.

---
## Gotchas worth knowing up front

| Gotcha | Why it bites |
|--------|--------------|
| Two-step connect | `device.connect()` **then** `connection.connect()`. Miss the second and nothing happens. |
| Listeners reset on reconnect | Re-register after a disconnect, or events go silent. |
| `CRPBleClient` is a singleton | `create()` once; `getBleDevice(mac)` returns the same object per MAC. |
| Permissions before scan | Without `BLUETOOTH_SCAN` (API 31+) the scanner **silently no-ops**. |
| Wi-Fi ordering | enable → wait for the connected callback → transfer → disable. Don't skip the wait. |
| AAR has no transitive deps | protobuf-java and okhttp must be declared manually or you get `NoClassDefFoundError` at runtime. |
| Everything crosses the bridge as primitives | Structured data goes as a JSON string in `s1`/`s2`. |

---

## Where to go next

- Protocol catalog: [`../docs/04_BLE_Protocol_Reference.md`](../docs/04_BLE_Protocol_Reference.md)
- Firmware/OTA analysis: [`../docs/02_Firmware_and_OTA.md`](../docs/02_Firmware_and_OTA.md)
- Open RE worklist: [`../docs/05_Reverse_Engineering_Findings.md`](../docs/05_Reverse_Engineering_Findings.md)
- Vendor's own guides: ``../vendor-sdk/android/``
