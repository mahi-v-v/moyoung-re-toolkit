# 00 — Executive Summary

**Project:** reverse-engineering the **MoYoung "Altair" AI camera glasses** (unit **MOY-A073 "V06"**,
companion app **Da Echo**, cloud **altair.moyoung.com**) — hardware, firmware, cloud, and a companion
"tinker" app. Sibling of the [Cyan/HeyCyan investigation](../../heycyan-re-toolkit), with which it
shares the Allwinner V821 camera SoC.

This is the one-page orientation. The **chronological journey** (every attempt, dead-end, and
correction — portfolio narrative) is the append-only log
[`moyoung_reverse_engineering.md`](moyoung_reverse_engineering.md). The **per-topic reconciled detail**
is docs 01–15.

## The device (all CONFIRMED from firmware)
A **dual-chip** design:
- **Core = JieLi AC701N** (BR28) — BLE + audio MCU, always-on, owns control/wake-word. Firmware = an
  encrypted JieLi `.ufw`.
- **Vision = Allwinner V821** — camera/Wi-Fi/AI, OpenWRT-Tina on RISC-V. Firmware = an unencrypted
  swupdate `.swu`.
- Linked internally by **`aglink`**. Two independent OTA paths (JieLi DFU over BLE; Allwinner swupdate
  over Wi-Fi).

## What was achieved this investigation
| Result | Status | Where |
|---|---|---|
| Device identity & exact silicon | ✅ AC701N + V821 | [01](01_Hardware_Architecture.md) |
| Tinker app runs & connects on hardware (after 2 crash fixes) | ✅ | [03](03_Mobile_App_and_SDK.md) |
| Cloud/OTA captured; firmware server found wide open | ✅ | [10](10_Cloud_and_Device_Auth.md), [07](07_Firmware_Acquisition.md) |
| Full firmware set acquired + curated local archive (~1.25 GB) | ✅ | [07](07_Firmware_Acquisition.md), [`../firmware/`](../firmware/) |
| **Core `.ufw` decrypted in software** (chipkey `0x1607`) | ✅ | [08](08_Core_Firmware_Jieli.md) |
| Vision `.swu` unpacked; OTA safety model mapped | ✅ | [09](09_Vision_Firmware_V821.md) |
| Live-stream quality root-caused (bitrate, not resolution) | ✅ | [11](11_Streaming_Bitrate_Analysis.md), [06](06_Live_Video_Streaming.md) |
| **In-app Core (JieLi) BLE OTA — VERIFIED flash** (0.0.8→0.0.9, dual-bank A/B) | ✅ **first verified firmware write** (needs `jl_bt_ota` v1.10.0) | [14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md) |
| Core `app.bin` disassembled (pi32v2, Ghidra) — voice grammar, UART link, RCSP surface | ✅ first pass | [15](15_Core_App_Analysis.md) |
| Custom wake word ("glass")? — offline-voice engine (`JL_KWS`/`batasr`) fully RE'd | ✅ answered — **a new spoken word can't be hand-authored** (needs JieLi's trained-model pipeline / OEM route); WFST grammar format + 8 KB acoustic model located; grammar-only edits (disable/relabel) doable; **verdict confirmed against JieLi's own `smart_voice`/`jl_kws` source** (2026-08-10) | [16](16_WakeWord_and_Offline_Voice.md), [log §26](moyoung_reverse_engineering.md) |
| Remap the physical buttons (power / AI / touch)? — input ownership RE'd across BOTH chips + runtime-confirmed | ✅ answered — **Allwinner owns NO input HW; all inputs are JieLi-Core**; real key handler `FUN_0601ee90` + keycode→action map + patch points; **runtime E5 (2026-08-11): power + AI button CONFIRMED Core-owned live** (AI button = audio/voice-query, correcting the static vision guess), touch still pending behavioral; sensor = Sony IMX681; "dynamic firmware" (flash-once, app-configurable) plausible but code-injection + E3-gated | [18](18_Button_Remap_and_Input_Ownership.md), [log §27](moyoung_reverse_engineering.md) |
| Raise stream quality — 6 Mbps rootfs-only `.swu` built + app-wired | ⏳ blocked at the **device** (Vision OTA masked no-op, see finding 5), not the file/phone/transport | [12](12_Firmware_Patching_and_Flashing.md), [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md) |
| Vision OTA root cause — decompiled + measured (2026-08-07) | ✅ **device-side masked no-op** — phone serves the full file, Wi-Fi holds, device reports OTA `SUCCESS` 12/12 today without flashing (genuine `-ab`); exact swupdate reason needs `ttyS3` (teardown, declined) | [14 §7,§9](14_InApp_OTA_and_the_JieLi_lib_gap.md), [log §22,§24](moyoung_reverse_engineering.md) |
| Teardown-free device access via BLE/`aglink` (use the Core→Vision link) | ✅ investigated — **no phone-reachable exec vector** (Ghidra: `libaglink` 571 + `ai_glass_normal` 39 funcs; no injection, no shell/adb spawn, factory=HW self-test); remaining = modified-Core bridge (E3-gated) | [09 §2.1](09_Vision_Firmware_V821.md), [log §25](moyoung_reverse_engineering.md) |
| Prior art — any public V821-glasses flash / same OTA symptom? | ✅ researched — **none exists**; sibling HeyCyan/Cyan stuck at the same wall; OEM = CRREPA/Shenzhen Kunpeng | [log §24](moyoung_reverse_engineering.md), [paths_forward R1](paths_forward.md) |
| Glasses → server streaming (relay works today) | ✅ relay / ⏳ native | [streaming_glasses_to_a_url.md](streaming_glasses_to_a_url.md) |
| Root / safe-flash / on-chip access | ⏳ needs UART/USB teardown | [paths_forward.md](paths_forward.md) |

## Headline findings
1. **The Core encryption is weak and now broken** — a 16-bit-keyed JieLi LFSR whose key is stored in
   the firmware itself; decrypted with `kagaimiq/jl-misctools`, no hardware. `app.bin` (incl. the
   `jl_kws` wake word) is now readable.
2. **The vendor's firmware distribution is wide open** — an unauthenticated `check-upgrade` API plus a
   world-readable `/static/firmware/` **directory listing** (every OEM's images, ~46 GB).
3. **The low stream quality is a bitrate cap** — 1600×1200@30 crushed to ~1.5 Mbps by a hardcoded
   constant in the Vision `ai_glass_livestream`; the resolution was never the problem.
4. **Vision OTA is unsigned but risky** — per-item md5 only, no signature, no hardware-compat gate,
   in-place (incl. bootloader), no rollback → a brick with only FEL/USB recovery.
5. **In-app OTA works for the Core; the Vision OTA is blocked at the *device*, which reports success without
   flashing (NOT the file, NOT the phone/Android/transport).** The **Core BLE OTA is verified** (dual-bank A/B;
   flashed genuine `0.0.9`, device rebooted, version confirmed — the first real firmware write of the project).
   For the **Vision**, the measured picture (corrected 2026-08-07 — an earlier "phone-side transport truncation"
   reading was **refuted**): the phone **serves the whole `.swu`** (`sendBody sent=12514304/12514304 complete`)
   and Android **does not tear down** the glasses Wi-Fi during the transfer (a wired `NetworkCallback` watch logs
   **no** `onLost` across the run) — yet the device's OTA progress caps (~45% this run) and it fires `completed`
   **without rebooting or changing version.** Device logs pulled teardown-free over BLE show this **12 times
   today**: `AG_AD_OTA_START ×12 → AG_VD_OTA_SUCCESS ×12 → AG_VD_OTA_FAIL ×0`, version unchanged — with the
   **genuine, unmodified `-ab` image.** The firmware has **no failure opcode** (decompiled `ai_glass_ota`), so
   `SUCCESS` proves nothing; **only a reboot + version change counts.** What we *cannot* see teardown-free is the
   `swupdate` accept/reject reason (it goes to `ttyS3`, not the BLE-pullable dmesg/aglink logs) and whether the
   device received ~100% or stopped reading at ~45% (TCP buffers make `sendBody complete` non-decisive on that
   point). Separately, our repack is *also* structurally wrong for this **dual-bank A/B** unit (needs
   `now_A_next_B`/`now_B_next_A`, not `sdnand`) — a downstream defect, moot while the device no-ops. The user has
   **declined a teardown** for now, so the swupdate reason stays open and the streaming-quality fix is blocked at
   the device. See [14 §7,§9](14_InApp_OTA_and_the_JieLi_lib_gap.md), [log §22,§24](moyoung_reverse_engineering.md).

## Where to go next
See [`paths_forward.md`](paths_forward.md) — the prioritised roadmap. The one step that would reveal *why*
the device no-ops the OTA (and unlock safe flashing + a root shell) is a **UART teardown** to read `ttyS3` —
but **the user has declined a teardown for now**. Two teardown-free avenues were then exhausted (2026-08-08):
**online prior-art research** (no public V821-glasses flash exists — R1/[log §24](moyoung_reverse_engineering.md))
and the **BLE/`aglink` attack surface** (no phone-reachable exec vector into the Vision — R2/[§2.1](09_Vision_Firmware_V821.md)/[log §25](moyoung_reverse_engineering.md)).
**Open decision:** (a) **E3 / modified-Core bridge** — the remaining teardown-free-*ish* lever (flash a re-encrypted
modified Core via the proven BLE OTA — safe-ish via Core dual-bank A/B — to prove modified images boot, then patch
the pi32v2 Core to reach the Vision); (b) **pivot** to what works — the **Core BLE OTA** (proven write channel) +
relay-based glasses→server streaming (runs today); or (c) the `ttyS3` **teardown**.
