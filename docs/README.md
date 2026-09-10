# MoYoung Glasses — Documentation Index

Reverse-engineering documentation for the **MoYoung "Altair" AI camera glasses** (unit **MOY-A073
"V06"**). This is the RE analysis layer on top of the vendor's own SDK docs. **New here? Start with
[00 — Executive Summary](00_Executive_Summary.md).**

## How this documentation is organised
- **`00`** — one-page orientation + current-status table.
- **`01`–`06`** — the reconciled, per-topic reference (kept current; edit freely).
- **`07`–`16`** — the detailed *findings* from the hands-on RE (firmware, decryption, cloud, streaming, patching, OTA, Core `app.bin`, offline voice / wake word).
- **[`moyoung_reverse_engineering.md`](moyoung_reverse_engineering.md)** — the **append-only journey
  log** (portfolio narrative; never rewritten — only appended, with dated corrections).
- **[`paths_forward.md`](paths_forward.md)** — the prioritised roadmap of what's next.
- **`reference/`** — machine-generated `javap` dumps. **`99_Archive/`** — superseded material (kept, not deleted).

| # | Doc | What's in it |
|---|-----|--------------|
| 00 | [Executive Summary](00_Executive_Summary.md) | Orientation, achievements, status table, headline findings |
| 01 | [Hardware Architecture](01_Hardware_Architecture.md) | Dual-chip **JieLi AC701N + Allwinner V821**, `aglink`, storage (CONFIRMED from firmware) |
| 02 | [Firmware & OTA](02_Firmware_and_OTA.md) | The two OTA paths, cloud endpoint, tinkering surface (overview → 07–10) |
| 03 | [Mobile App & SDK](03_Mobile_App_and_SDK.md) | CRP SDK + our tinker app; the two connect-crash fixes; on-hardware status |
| 04 | [BLE Protocol Reference](04_BLE_Protocol_Reference.md) | Full command / callback / listener / enum map + data shapes |
| 05 | [Reverse Engineering Findings](05_Reverse_Engineering_Findings.md) | Reconciled findings + the Cyan side-by-side (with 2026-07-30 status update) |
| 06 | [Live Video Streaming](06_Live_Video_Streaming.md) | STA live mode: **RTSP on this unit**; `setSTALiveMode`/WebRTC = other-branch/iOS |
| 07 | [Firmware Acquisition & Archive](07_Firmware_Acquisition.md) | Open firmware server, the 4077-file catalog, dedup, the local `firmware/` mirror |
| 08 | [Core Firmware — JieLi AC701N](08_Core_Firmware_Jieli.md) | Cryptanalysis + **software decryption** (chipkey `0x1607`), decrypted contents |
| 09 | [Vision Firmware — Allwinner V821](09_Vision_Firmware_V821.md) | `.swu` internals, `ai_glass_*`, mode dispatch, WebRTC branch, **OTA brick risk** |
| 10 | [Cloud & Device Auth](10_Cloud_and_Device_Auth.md) | mitm: check-upgrade, Allwinner device-auth, JWT, `device_license`; version relabel corrected (not Core-side; mechanism TODO) |
| 11 | [Streaming Bitrate Analysis](11_Streaming_Bitrate_Analysis.md) | ffprobe + Ghidra: the **1.5 Mbps** cap and the fixes |
| 12 | [Firmware Patching & Flashing](12_Firmware_Patching_and_Flashing.md) | The **6 Mbps + adb** rootfs-only `.swu`, in-app OTA + progress, and the brick-risk model |
| 13 | [OTA via the Official App + MITM](13_OTA_via_Official_App_MITM.md) | Forge `check-upgrade` to push our image via Da Echo — worked 2026-07-30; now **PARKED** (`check-upgrade` no longer interceptable, inferred pinning) |
| 14 | [In-App OTA & the JieLi-lib gap](14_InApp_OTA_and_the_JieLi_lib_gap.md) | **Core BLE OTA VERIFIED** (`jl_bt_ota` v1.10.0, 0.0.8→0.0.9); Vision Wi-Fi transport runs but device **does not apply** the image |
| 15 | [Core `app.bin` Analysis](15_Core_App_Analysis.md) | **pi32v2 disassembly** (quarkslab Ghidra module), memory map, string/table taxonomy, voice-command grammar, UART link, **version-relabel corrected** (not on Core; mechanism TODO) |
| 16 | [Wake Word & Offline Voice](16_WakeWord_and_Offline_Voice.md) | **JieLi `JL_KWS`/`batasr` fully RE'd** (WFST grammar format + acoustic model located); a **new spoken wake word can't be hand-authored** — needs JieLi's trained-model pipeline (OEM route); grammar-only edits (disable/relabel) are doable |
| 17 | [**Company Value & Capabilities**](17_Company_Value_and_Capabilities.md) | **Business-facing:** what we can ship *now* with the glasses **unmodified** (all firmware-mod paths are blocked) — "capture & ask" AI on full-res photos, branded app control, live AI + RTSP→RTMP distribution, voice flows — ranked by value + an honest BLOCKED list |
| ★ | [**MoYoung RE — append-only log**](moyoung_reverse_engineering.md) | Chronological journey (portfolio); §1–§26 |
| → | [Paths Forward (roadmap)](paths_forward.md) | Prioritised next steps (risk / needs-HW / value) |
| → | [Streaming to a URL (how-to)](streaming_glasses_to_a_url.md) | Grounded ffmpeg/MediaMTX restreaming recipe |

## Vendor source material (unmodified)
| Location | What |
|----------|------|
| ``../vendor-sdk/android/.../dev-docs/`` | MoYoung's own SDK guides + API reference (authoritative for the *documented* API) |
| ``../vendor-sdk/android/.../*.aar`` | The Android BLE SDK binary (`my_galsses_sdk_0.0.7`) |
| ``../vendor-sdk/ios/`` | iOS SDK 1.2.0: `CRPSmartGlasses.xcframework`, JieLi frameworks, two PDFs (EN + 中文) |
| [`reference/`](reference/) | `javap` dumps of the AAR's public API + protobufs + class inventory |
| [`../firmware/`](../firmware/) | Curated local firmware archive + `MANIFEST.csv` (see [07](07_Firmware_Acquisition.md)); `vision-v821/_patched/` = built mod images, `core-jieli-a073/_decrypted/` = decrypted Core |
| [`../tools/`](../tools/) | The RE scripts (bitrate scan, `.swu` build, cipher analysis, RTSP probe, server crawl) — see [tools/README](../tools/README.md) |
| [`reference/evidence/`](reference/evidence/) | Captured evidence (live-stream `frame.png` + `cap.mp4` at the stock 1.5 Mbps) |

## Conventions
- **CONFIRMED** — verified from a shipped artifact, the firmware, or an on-device/on-wire capture.
- **INFERRED** — a strong deduction, not yet directly proven.
- **UNVERIFIED / TODO** — needs a device, a capture, or a teardown.

When a vendor doc and an RE doc disagree, note it explicitly. The append-only log is the record of
*how* conclusions changed; docs 00–15 always hold the *latest* reconciled view.
