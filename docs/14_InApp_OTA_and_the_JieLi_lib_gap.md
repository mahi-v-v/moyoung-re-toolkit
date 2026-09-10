# 14 — In-App OTA: implementation, the vendor flow, and the JieLi-lib blocker

Wiring the OTA *into our own tinker app* (so we don't depend on the official app + a proxy). Current
status (corrected 2026-08-08): **Core BLE OTA works and is verified** (§3); the **Vision OTA is blocked
device-side — the glasses report `SUCCESS` without flashing** (measured 12/12; the phone serves the whole file and
the Wi-Fi holds, so it is **not** transport/AP-reaping — **§9**, superseding the "transport truncation" (§7–§8) and
"device won't apply our repack" (§6) readings). Companion to
[13](13_OTA_via_Official_App_MITM.md) (the MITM route — now **parked**) and [03](03_Mobile_App_and_SDK.md).

## 1. The vendor-documented OTA flow (`vendor-sdk/.../dev-docs/guides/ota.md`)
Two paths, from the SDK's own guide:
- **Jieli / Core** — BLE DFU: `startOta(listener)` (server) or `startOta(File, listener)` (local).
- **Allwinner / Vision** — Wi-Fi:
  ```
  enableWifi(CRPWifiType.OTA)
    → onWifiStateChange(OTA, state==STATE_SUCCESS(=0))  → connectWifi()
    → onWifiConnectionStateChanged(connected==true)     → startAllWinnerOta(File, listener)
    → onCompleted                                       → disableWifi()
  ```
  **`connectWifi()` is the phone-side join** — the SDK does it; the official app's
  `AllwinnerUpdateManager` / `WifiConnectionHelper` are just thin wrappers over these same callbacks.
  Firmware selection mirrors the cloud: `firmwareType==1 → startOta`, `==2 → startAllWinnerOta`
  ([13](13_OTA_via_Official_App_MITM.md)).

## 2. What we implemented (Vision — complete)
- **`startVisionOtaAuto(path)`** in `MoyoungAdapter` arms an auto-chain: `enableWifi(OTA)` →
  (on `STATE_SUCCESS`) `connectWifi()` → (on `connected`) `startAllWinnerOta(file)` → `disableWifi()`
  on finish. Exposed via the `sendCommand` seam + `Glass.startVisionOtaAuto()`; one-tap
  **"Flash Vision (auto Wi-Fi)"** button in `app/(tabs)/ota.tsx` (manual 3-step kept as fallback).
- **Bundled builds** shipped as native assets + `resolveBundledFirmware()` → a real file path
  ([12 §4](12_Firmware_Patching_and_Flashing.md)).
- **Verbose `[OTA]` step logging** through the whole chain (each step + the `onWifiStateChange` /
  `onWifiConnectionStateChanged` / `CRPOtaListener` callbacks) → the app's Device-log panel, so a
  stall is pinpointed to a step. **This Vision path is implemented but not yet confirmed on hardware.**

## 3. The Core path — `jl_bt_ota` version skew, **RESOLVED (pin v1.10.0)** ✅
`startOta()` originally failed because the vendor **`my_glasses_sdk.aar` references `com.jieli.jl_bt_ota.*`
but does not bundle it** (its `classes.jar` has **zero** `com/jieli/*`) → runtime
`Failed resolution of: Lcom/jieli/jl_bt_ota/interfaces/BtEventCallback;`. `jl_bt_ota` is JieLi's
**open-source** BLE-OTA lib ([github.com/Jieli-Tech/Android-JL_OTA](https://github.com/Jieli-Tech/Android-JL_OTA),
Apache-2.0). Bundling the version the shipping Da Echo links (**v1.11.0_11015**) cleared the class error
but exposed a **method-signature mismatch** — v1.11.0 had migrated the API:

| Caller (`my_glasses_sdk.aar`, obfuscated `com.moyoung.u.*`) | needs | v1.11.0 (one release too new) |
|---|---|---|
| `BluetoothBase.registerBluetoothCallback(IBluetoothCallback)` | → **boolean (`Z`)** | `registerBluetoothCallback(BtEventCallback)` → **void** |

**FIX (2026-08-03) — pin `jl_bt_ota` to `v1.10.0_10932`.** Decompiling the SDK's wrapper
(`com.moyoung.u.a extends BluetoothOTAManager`, `e$c extends BtEventCallback`) gave the exact API surface
it calls; `javap` across JieLi's tags showed **v1.10.0 is the newest release that still has
`registerBluetoothCallback(IBluetoothCallback):Z` AND every other method the SDK uses** — all
`BluetoothOTAConfigure` setters (`setBleIntervalMs`/`setNeedChangeMtu`/`setUseAuthDevice`/`setUseReconnect`/
`setFirmwareFilePath`/…), `startOTA(IUpgradeCallback):void`, `BtEventCallback`, `IUpgradeCallback`. Swapped
`libs/jl_bt_ota_V1.11.0…` → `libs/jl_bt_ota_V1.10.0_10932-release.aar` and re-enabled Core in the UI.

**Result — CONFIRMED working.** Flashed genuine Core **`0.0.9`** over BLE (dual-bank): DFU ran clean (no
`registerBluetoothCallback` error), 99% → `completed`, the glasses **rebooted**, and the **Core version now
reads `MOY-A073-0.0.9`** — the project's **first verified firmware write** ([log §20](moyoung_reverse_engineering.md)).
(`jl_audio_decode`/Opus stays stubbed — unrelated to OTA.)

## 4. Practical upshot
- **Core BLE OTA is the proven, working flash path** (§3): dual-bank A/B (rollback-safe), verified
  end-to-end (0.0.8→0.0.9) with `jl_bt_ota` **v1.10.0**. Use it for the JieLi chip — and, once E3 is
  proven, for modified Core images.
- **Vision** (the bitrate goal) needs **none** of `jl_bt_ota` (verified: `startAllWinnerOta` /
  `AllwinnerUpdateManager` reference it nowhere). The transport is fine — the phone serves the whole file and the
  Wi-Fi holds — but the **device reports OTA `SUCCESS` without flashing** (a device-side masked no-op, 12/12 with a
  genuine `-ab` image; **§9**, correcting §6–§8). So the Vision path is **not a working flash**; the `swupdate` reason
  lives on `ttyS3` and needs a teardown (or the modified-Core route) — not the app.
- The **official app + MITM ([13](13_OTA_via_Official_App_MITM.md)) is PARKED** — its `check-upgrade` can
  no longer be intercepted (doc 13 decision). The in-app Core path supersedes it as the reliable channel.

## 5. Misc device notes
Device advertises as **"V06"**; the app's vendor advert-heuristic scan misses it, so it only appears
under the raw **"Show all devices"** sweep (connects fine — `F5:13:72:15:2C:31`). The AAR needs these
runtime deps the app must supply: `protobuf-java`, `okhttp` (present), and JieLi's `jl_bt_ota` +
`jl_audio_decode` (not on Maven — ship as AARs; version-match matters).

## 6. Field results (2026-08-03) — Vision OTA transport WORKS, device does NOT apply

> **⚠ SUPERSEDED (see §9, 2026-08-08).** This section's reading — "swupdate rejects our repack" / "~25% stall" — is
> historical. Corrected verdict: the device reports OTA **`SUCCESS` without flashing** (a masked no-op, 12/12 with a
> *genuine* `-ab` image); it is **not** our repack and **not** the transport. Kept for the investigative trail.
Ran the in-app Vision OTA on hardware. **Fixed the last missing lib:** `startAllWinnerOta` crashed with
`Failed resolution of: Lfi/iki/elonen/NanoHTTPD;` — the SDK stands up an embedded **NanoHTTPD** server on
the phone to serve the `.swu` to the glasses over the device AP, but the AAR references
`fi.iki.elonen.NanoHTTPD` without bundling it. Added `implementation 'org.nanohttpd:nanohttpd:2.3.1'`
(public, Maven Central, stable 2.x API → no version-skew, unlike `jl_bt_ota`) — same
referenced-but-not-bundled pattern as protobuf/okhttp.

**With that, the whole chain runs** (verbose `[OTA]` trace): `startVisionOtaAuto` → `enableWifi(OTA)` →
`onWifiStateChange(OTA, STATE_SUCCESS)` → `connectWifi()` → `onWifiConnectionStateChanged(true)` →
`startAllWinnerOta(File)` → progress → `onCompleted` → `disableWifi()` — **line-for-line the vendor guide**
(`dev-docs/guides/ota.md`). On Android 14 the connect step pops the `WifiNetworkSpecifier` "join this
network?" dialog (must be approved).

**BUT the device does NOT apply the image — VERIFIED negative (three runs: 2× `vision_noop`, 1× `vision_6mbps_adb`):**
- Progress climbs to **~25%, fires `onCompleted`, glasses never reboot** — identical every run.
- **RTSP bitrate unchanged:** `ffprobe rtsp://192.168.18.31:8554/ch0` (H.264 1600×1200@30) measured
  **1.92 Mbps** (12 s video-only capture) = stock. The 6 Mbps patch would read ~6.
- **adb-over-Wi-Fi absent:** `adb connect 192.168.18.31:5555` **refused** — the 6 Mbps build enables adbd
  on tcp:5555, so the patched rootfs plainly did not boot.
- ⇒ the SDK's `onCompleted` is **cosmetic**; it fires whether or not swupdate applies.

**Interpretation:** because even the **no-op** (the vendor's own rootfs, just repacked) fails identically,
the fault is **not** the bitrate patch — it's common to every image we build: either the transfer never
completes (device gets only ~25%) or swupdate **rejects our repacked `.swu`** (trimmed sw-description /
squashfs repack / cpio) and aborts. **Un-diagnosable from the phone side** — the SDK reports "completed"
regardless and we have no device log; the very adb-over-Wi-Fi that would show it is what isn't applying
(chicken-and-egg).

**Blocker = flashing blind.** Closing it needs **device-side visibility** — a teardown → **UART console**
to read swupdate's abort reason and iterate the `.swu` with feedback (or an exact stock `.swu` control).
Deferred to an exhaustive session. **Net:** the app can now *drive* Allwinner OTA exactly like Da Echo
(genuine progress); the device-*apply* is an unsolved device-side wall. Related: [12](12_Firmware_Patching_and_Flashing.md),
[paths_forward D2/D5](paths_forward.md).

## 7. ROOT CAUSE FOUND — 2026-08-05 (supersedes §6's "device-side wall" reading)

> **⚠ PARTLY SUPERSEDED by §9 (2026-08-07).** The **"phone-side transport truncation"** conclusion below
> (bullet 2) is **refuted** — later measurement showed the phone serves the full file, the Wi-Fi holds, and the
> device no-ops the flash. What **still stands:** the firmware has **no failure opcode** so `SUCCESS` is
> meaningless (judge by reboot + version only), and the unit is **dual-bank A/B**. Read §9 for the corrected verdict.

We got device-side visibility **without a teardown** (pulled the Vision's mode-3 OTA logs over BLE via
`downloadLogFile`, then off the device via adb `run-as`) and **decompiled `ai_glass_ota`** (RISC-V, Ghidra).
§6's "genuine progress, device won't apply our repack" was **wrong on both halves**:

- **The progress and the "success" are not trustworthy.** `ai_glass_ota` emits **`AG_VD_OTA_SUCCESS` and never
  `AG_VD_OTA_FAILED`** (verified: `a1,0xc` loaded once, `a1,0xe` never, across all 11 tx sites), and its download
  routine **returns "ok" on a truncated body** (EOF with `downloaded < total` → return 0), after which `swupdate`
  runs with its exit code discarded. So the SDK's "completed" proves nothing — **only a reboot + version change
  does.** (No captured session ever rebooted.)
- **The real fault is a phone-side transport truncation, not our `.swu`.** The BLE progress bar is the device's
  real `downloaded*100/total` on a 500 ms timer; it **stalls at 25 because the HTTP body ends at ~25%** of the
  advertised length. The phone URL is correct (`http://192.168.31.2:8182/<name>`, host resolved, NanoHTTPD serves
  any path with Range) — so swupdate simply never receives a complete file. This **matches the online NanoHTTPD /
  large-file-streaming research**, and **retires** the "swupdate rejects our repacked `.swu`" hypothesis.
- **This firmware DOES support A/B** — `ai_glass_ota` runs `swupdate … -e stable,{sdnand|nor|now_A_next_B|now_B_next_A}`.

**Fix direction (phone-side, no teardown):** control the file serving — deliver the full body from our **own**
robust HTTP server and route the device to it (the SDK owns its internal NanoHTTPD, so we bypass it). Cheapest
first step is to **measure the served byte-count** (own server or packet capture) / test a **pristine vendor
image** for a real reboot. Full decompiled write-up: [log §22](moyoung_reverse_engineering.md).

## 8. Transport truncation localised — the phone SERVES fine; the loss is at the Wi-Fi layer (2026-08-06/07)

> **⚠ SUPERSEDED by §9 (2026-08-07).** The framing below — "the loss is at the Wi-Fi layer / Android reaps the
> AP" — is **refuted**: the wired `[NET]` watch showed the Wi-Fi does **not** drop during the transfer, and the
> device masks a no-op as success. Still valid here: **the phone serves the whole file, and it is not our file**
> (genuine `-ab` fails too). Read §9.

We instrumented and controlled §7's "phone-side transport truncation". The refined verdict: **the phone hands off
the whole file every time; the glasses receive only ~40% before a clean EOF; and three in-app fixes could not stop
it.** Full journey in [log §23](moyoung_reverse_engineering.md). Key results:

- **The server is not the problem (VERIFIED).** We vendored the exact NanoHTTPD 2.3.1 source into the module
  (`.../fi/iki/elonen/NanoHTTPD.java`) and instrumented `sendBody`: every run logs
  `[HTTP] [sendBody] done sent=N/N reason=complete` (full 7.21 MB / 12.5 MB, status 200, not chunked, no
  exception). The truncation is downstream of our serving.
- **It is NOT our file (VERIFIED by control test).** A **genuine, unmodified `-ab` image** (bundled as
  `vision_stock_ab.swu`, the kind the official app flashes) truncates the same way (~36–45%, no reboot). So the
  visible failure is transport, not the repack. *(Separately, our repack is still structurally wrong for this A/B
  unit — see §7 / [doc 09 §1](09_Vision_Firmware_V821.md): it must carry `now_A_next_B`/`now_B_next_A`, not
  `sdnand`. But that only bites once a full file arrives.)*
- **The official app uses the identical transport** (decompiled): phone-AP + NanoHTTPD :8182, byte-identical BLE
  command, no keepalive. So there's no secret channel we're missing — the difference is environmental.
- **Root mechanism (INFERRED): Android reaps / routes away from the glasses' no-internet AP mid-transfer.**
  Evidence: turning **mobile data off + forgetting home Wi-Fi** stretched the transfer from ~22% → **45%** (fewer
  internet-bearing networks to prefer); and the **phone-as-client** log-pull completes 100% while the
  **phone-as-server** OTA truncates (an outgoing connection keeps the AP "in use"; serving doesn't).
- **In-app fixes that did NOT work:** `SO_LINGER(30s)`, `SO_SNDBUF=1 MB`, and `ConnectivityManager.bindProcessToNetwork`
  to the glasses' Wi-Fi (bind confirmed `ok=true`, still truncated at 37%). Binding sets routing but can't *hold* a
  network the OS is tearing down. A `[NET]` `NetworkCallback` watch (`onLosing`/`onLost`, elapsed-ms) is wired to
  confirm the teardown timing on the next run.

**⇒ Practical status.** Vision Wi-Fi OTA is **blocked by a phone/OS-level Wi-Fi truncation we could not fix from an
Android app.** Not the file; not the server. **Next:** confirm the teardown via `[NET]`, and — the likely path — a
**laptop Node harness** (real HTTP server + full network control + packet capture, sidestepping AP-reaping; needs a
CRP-BLE reimplementation, GATT UUIDs the only gap). The **Core BLE OTA remains the proven, working flash channel**
([§3](#3-the-core-path--jl_bt_ota-version-skew-resolved-pin-v1100-)).

## 9. CORRECTION — the block is device-side (masked no-op), NOT phone/transport (2026-08-07; supersedes §8)

Ran §8's wired-but-unexecuted `[NET]` watch and pulled the device OTA logs over BLE. Result: **§8's "phone/OS-level
Wi-Fi truncation" was wrong as the operative cause.** The phone delivers the whole file, the Wi-Fi holds, and the
**device declines to apply the update while reporting success.** What survives from §8: the phone serves fully, and
it is not our file. What is retired: the "Android reaps the no-internet AP mid-transfer" inference. Full record:
[log §24](moyoung_reverse_engineering.md).

- **Wi-Fi did not drop (new, decisive).** On a clean genuine `-ab` run the `[NET]` watch logged `onAvailable` +
  `validated=true` and then **no `onLosing`/`onLost`** for the whole 0→45% transfer — while `[HTTP] [sendBody] done
  sent=12514304/12514304 reason=complete`. (The one run that *did* log `onLost` also logged a full BLE
  `disconnect()` with **zero** HTTP transfer — a whole-link drop, not AP-reaping.)
- **Device masks success 12/12 (live confirmation of §7's decompile).** BLE-pulled device logs (all timestamped
  today, 2026-08-07): **`AG_AD_OTA_START` ×12 → `AG_VD_OTA_SUCCESS` ×12, `AG_VD_OTA_FAIL` ×0**, version never
  changed — with the genuine vendor image. Each OTA cycle ~9 s; the Core↔Vision `aglink` link logs
  `ackFailures:14 txedRate:0` throughout (unhealthy, observation only).
- **The swupdate accept/reject reason is NOT in the BLE-pullable logs** (kernel dmesg + aglink protocol only). It
  goes to **`console=ttyS3,1500000`** — i.e. only a **teardown** reveals it.
- **Honest bound:** `sendBody complete` proves the phone *sent* every byte, **not** that the device *read* every
  byte (TCP buffers can absorb the tail). Whether the device receives ~100% and no-ops, or stops reading at ~45%,
  is **undecidable teardown-free.** Either way the decision is the device's, not the phone's.

**⇒ Status.** Vision flash is **blocked by device behaviour we cannot see into without `ttyS3`; the user has
declined a teardown for now.** The §8 laptop harness is therefore **obviated** — it was built anyway as an in-app
**external-server mode** (`startVisionOtaExternal`/`continueVisionOtaExternal`, pause at `awaitingExternalServer`;
`tools/vision-ota-harness/`), but since Android was never reaping the transfer, relocating the server cannot change
the device's decision. The **Core BLE OTA remains the one proven flash channel.** Next step (user's direction):
**online research** on sibling/parent hardware before any teardown.
