# 05 — Reverse Engineering Findings

The reconciled, grounded view. When an earlier note and this doc disagree, this doc wins. Claims
are tagged **CONFIRMED** / **INFERRED** / **UNVERIFIED (TODO)**.

> ## ✅ 2026-07-30 — major status change (this section supersedes the "nothing analyzed yet" tone below)
> A full hands-on session moved most of the worklist from TODO → DONE. The §3 worklist and the
> §4 "no device / no firmware analyzed yet" note are **historical** — current reality:
> - **Device connected & driven on hardware** (crash fixes — see [03](03_Mobile_App_and_SDK.md)).
> - **Cloud `check-upgrade` captured** (mitm) → **firmware server found wide open** (dir listing,
>   4077 files) → **local archive** of the A073 Core + V821 Vision builds. See
>   [07 — Firmware Acquisition](07_Firmware_Acquisition.md) and [10 — Cloud & Auth](10_Cloud_and_Device_Auth.md).
> - **Silicon CONFIRMED:** JieLi **AC701N** (Core) + Allwinner **V821** (Vision).
> - **Core `.ufw` DECRYPTED in software** (JieLi LFSR cipher, chipkey `0x1607` from `isd_config.ini`);
>   `jl_kws` wake word confirmed on the Core. See [08 — Core Firmware](08_Core_Firmware_Jieli.md).
> - **Vision `.swu` = swupdate, unencrypted, per-item md5, no signature, no HW-compat gate**;
>   `ai_glass_*` daemons, mode-dispatch. See [09 — Vision Firmware](09_Vision_Firmware_V821.md).
> - **Live video = RTSP** on this unit (not WebRTC); quality ceiling = **encoder bitrate ~1.5 Mbps**.
>   See [06](06_Live_Video_Streaming.md) / [11](11_Streaming_Bitrate_Analysis.md).
>
> The chronological journey (with dead-ends and corrections) is the append-only log
> [`moyoung_reverse_engineering.md`](moyoung_reverse_engineering.md). The table below is kept as the
> original SDK-only baseline; treat its "UNVERIFIED" rows as since-resolved per the docs above.

---

## 1. Findings at a glance

| Claim | Status | Basis |
|-------|--------|-------|
| Platform is MoYoung "MY Glasses", a CRP-family BLE SDK | **Confirmed** | AAR package `com.moyoung.glasses`, class prefix `CRP*` |
| Dual-chip: Jieli MCU (master, BLE+audio) + Allwinner SoC (slave, camera/Wi-Fi/AI) | **Confirmed** | Bundled `jl_bt_ota`/`jl_audio_decode`; `startAllWinnerOta`; `RunningStatus.{jieliOta,slaveOta,slaveActive}` |
| Jieli side uses dual-bank A/B DFU | **Inferred** | `CRPOtaType {GR_A, GR_B}` + stock Jieli DFU lib |
| Two firmware images, two OTA paths (BLE-DFU vs Wi-Fi file) | **Confirmed** | `startOta` vs `startAllWinnerOta`; `FIRMWARE_TYPE_{JIELI,ALLWINNER}` |
| `startAllWinnerOta(File)` / `startOta(File)` accept **arbitrary local files** | **Confirmed** | Method signatures take `java.io.File` |
| App-visible integrity gate is an **MD5**, not a signature | **Confirmed (app side)** | `CRPNewFirmwareVersionInfo.md5`; device-side enforcement **UNVERIFIED** |
| Official firmware images are downloadable (plaintext `fileUrl`) | **Confirmed** | `check-upgrade` returns `fileUrl`; captured on-device via mitm (2026-07-30) + whole archive mirrored |
| Cloud: `https://altair.moyoung.com/api/v1/firmware/check-upgrade` | **Confirmed** | Hardcoded string in AAR |
| On-device audio (AI/translation/calls) is Opus → PCM | **Confirmed** | Bundled `com.jieli.jl_audio_decode.opus.OpusManager`; `onDialogueAudioChange(byte[])` PCM |
| Wake word toggled/queried over BLE (on/off only, no custom phrase) | **Confirmed** | `sendVoiceWakeUpState(bool)` / `queryVoiceWakeUpState` |
| Wear detection toggled/queried over BLE | **Confirmed** | `sendWearCheckState` / `queryWearCheckState` |
| GATT service/characteristic UUIDs | **Unverified** | Built in obfuscated code; only CCCD `0x2902` hardcoded — needs a sniff |
| Device Wi-Fi AP runs an HTTP server (media/OTA/logs/live) | **Confirmed (exists)** | okhttp + `CRPFileDownloadCallback` HTTP error codes; contents **UNVERIFIED** |
| Exact silicon part numbers, debug pads, flash readout protection | **Unverified** | No teardown |
| Allwinner OTA container is swupdate **`.swu`** (same as Cyan V821) | **Confirmed** | iOS guide §4.10 |
| Cloud API base URL is **runtime-settable** (`setApiBaseUrl`) | **Confirmed** | iOS 1.2.0 swiftinterface / guide §4.9 |
| **Live video = WebRTC on the glasses** (+ TURN), configured via BLE `setSTALiveMode`; phone has no WebRTC/signaling code | **Confirmed (iOS 1.2.0)** | `CRPLiveState` `webrtc_*` enum + `CRPStaLiveMode.turn_*`; no WebRTC lib linked. Signaling wire format **UNKNOWN**. See [06](06_Live_Video_Streaming.md) |
| Live streaming absent from Android SDK 0.0.7 | **Confirmed** | No `StaLiveMode`/`webrtc` symbols in AAR; iOS changelog: landed in 1.1.9 |

**One-line takeaway:** the interesting firmware surface is the **Allwinner Wi-Fi OTA** — it accepts
a raw file and the only integrity gate the *app* knows about is an MD5. Whether the *device*
enforces a signature is the pivotal open question, and it's answerable without a teardown (capture
the OTA, modify a byte, observe accept/reject).

---

## 2. Side-by-side: MoYoung vs Cyan

These are two different vendors, but the architecture rhymes hard. The Cyan playbook transfers.

| Dimension | **Cyan / HeyCyan (prior project)** | **MoYoung (this project)** |
|-----------|-----------------------------------|----------------------------|
| Vendor SDK naming | `com.oudmon.*` (QC/Oudmon) | `com.moyoung.glasses` (CRP family) |
| Main SoC | **Allwinner V821** (RISC-V + ARM, Tina Linux) | **Allwinner** (family confirmed, part TBD) |
| Second MCU | **BlueX AM01CY** BLE MCU (encrypted fw) | **Jieli** BLE+audio MCU (stock Jieli DFU) |
| Who owns BLE control | the BLE MCU | the Jieli MCU |
| Bulk transport | Wi-Fi (K900 hotspot / file sync) | Wi-Fi AP (`enableWifi` FILE/OTA/LIVE) |
| OTA packaging | swupdate `.swu`, per-CPIO-item md5, **no secure boot / no dm-verity** | Jieli `.ufw` (A/B) + Allwinner **`.swu`** (same swupdate format — confirmed, iOS guide §4.10) |
| Integrity gate | md5 per item (weak) | app-side md5; device-side **TBD** |
| Wake word | branded "Hey Cyan"; toggle over BLE (`aiVoiceWake`, opcode `0x44`, on/off only) | toggle over BLE (`sendVoiceWakeUpState`, on/off only) |
| Wake word location | **NOT** in the Allwinner RISC-V image (refuted early hypothesis); likely the BLE MCU | **UNVERIFIED** — likely the always-on Jieli audio MCU (test the same hypothesis) |
| Audio codec | Opus (OpusOgg) | Opus (`jl_audio_decode`) |
| Docs available | none (decompile-only) | **vendor shipped dev-docs** ← big head start |
| Companion app | Expo + `expo.modules.madrims.glass` native module, multi-vendor | same pattern, MoYoung adapter |

### What we can lift directly from the Cyan work
- **Firmware harvesting via the update endpoint** — Cyan's `fw_AM01CY_*` images and V821 `.swu`
  were obtained by watching the update path. Same move here with `altair.moyoung.com`.
- **`binwalk` + Ghidra methodology** — Cyan's `hardware_research/` scripts (entropy scan, XOR
  descramble, key search, headless Ghidra) are a template for the Allwinner image and, if needed,
  the Jieli image.
- **The "modify a byte, re-OTA, observe" integrity probe** — Cyan proved the V821 side had only
  md5 gating. Run the same probe on both MoYoung chips.
- **BLE control probing** — Cyan's `cyan_ble_control_probes.md` (poking opcodes and logging
  responses) maps onto MoYoung's `sendCommand("rawControl", {bytes})` seam + the shared-CRP extra
  opcodes noted in [04](04_BLE_Protocol_Reference.md).

### Where MoYoung is *easier* than Cyan
- The Jieli side uses a **stock, publicly-documented DFU** (`.ufw`, CRC), vs Cyan's **encrypted**
  BlueX AM01CY image that required XOR-descramble + key search.
- **Vendor dev-docs exist**, so the control protocol is documented rather than fully reverse-engineered.
- Dual-bank A/B on the Jieli side lowers brick risk for control-MCU experiments.

### Where it might be *harder*
- Two *active* application processors to understand (Cyan's wake-word turned out to live on the
  MCU, not the big SoC — here the split of responsibilities needs its own confirmation).
- Jieli MCUs *can* enforce signed DFU; need to confirm these don't.

---

## 3. Open-questions worklist (ordered by value / effort)

Software-only (no hardware needed):
1. **Capture `check-upgrade`** — proxy the app's firmware check; record request body, auth, and the
   `fileUrl` + `md5` for both chips. → download official images. *(tinker app: OTA screen)*
2. **`binwalk` the images** — characterize the Jieli `.ufw` and the Allwinner container. Look for a
   swupdate `sw-description` / md5 manifest like Cyan's.
3. **Sniff the BLE control channel** — nRF Connect/​Wireshark during a normal session; recover the
   service/char UUIDs and the protobuf-over-GATT framing. Fills the gap in [04](04_BLE_Protocol_Reference.md).
4. **Sniff the Allwinner Wi-Fi OTA** — join the device AP, run a stock update with capture; learn
   the on-device HTTP receiver's contract + file format. Enumerate what else the AP's HTTP server
   serves (media list, logs, config, directory listing).
5. **Integrity probe** — flip a benign byte and re-flash via `startOta(File)` /
   `startAllWinnerOta(File)`; accept ⇒ CRC-only, reject ⇒ signed. Do the Jieli A/B side first.
6. **Opcode fuzzing** — via `sendCommand("rawControl", {bytes})`, probe the shared-CRP opcodes not
   exposed by the glasses API (alarms, hardware self-test, SOS, user-info) and log responses.

Hardware (if OTA is signature-gated):
7. **Teardown** — identify exact Jieli + Allwinner parts; find UART console pads, SWD (Jieli),
   FEL/USB (Allwinner). Reuse Cyan's UART3 / `xfel` approach as a starting hypothesis.
8. **Dump current firmware** — via the debug interface, to diff against OTA images and to enable
   offline analysis independent of the cloud.

---

## 4. Provenance / how to reproduce

- **SDK surface**: `javap -public` over the AAR's `classes.jar`; full dump in
  [`reference/moyoung_public_api_signatures.txt`](reference/moyoung_public_api_signatures.txt),
  protobuf shapes in [`reference/moyoung_protos_signatures.txt`](reference/moyoung_protos_signatures.txt),
  class list in [`reference/moyoung_class_inventory.txt`](reference/moyoung_class_inventory.txt).
- **Bundled libs / cloud endpoint**: string search over the extracted `.class` files
  (`grep -rhoaE 'https?://…'`, UUID regex, `jieli`/`allwinner` tokens).
- **Documented behavior**: the vendor's own `dev-docs/` under
  ``../vendor-sdk/android/``.

The table above was the **SDK-only baseline (pre-2026-07-30)**. Since then a physical device *was*
driven, the cloud/OTA *was* captured, and both firmware images *were* obtained and analyzed (Core
decrypted, Vision unpacked) — see the top banner and docs 06–11. This §4 is retained verbatim as the
"how we started" provenance; for reproduction of the *new* work see the per-topic docs and the
append-only log.
