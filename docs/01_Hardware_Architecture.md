# 01 — Hardware Architecture

> **Grounding:** originally derived from the shipped Android SDK; since **2026-07-30** the exact
> parts and internals are **CONFIRMED from the firmware itself** (the decrypted Jieli Core image
> names its chip `AC701N`; the Allwinner `.swu` is `openwrt_v821_aiglass`). No teardown was needed.
>
> ### ✅ 2026-07-30 — exact silicon CONFIRMED (was "family only")
> - **Core MCU = JieLi AC701N** (BR28 series). Proven: the decrypted `.ufw` reports chip name
>   `AC701N`; `jl-uboot-tool` lists AC701N under BR28. See [08 — Core Firmware](08_Core_Firmware_Jieli.md).
> - **Vision SoC = Allwinner V821**, running **OpenWRT/Tina Linux** on **RISC-V** app cores. Proven:
>   `.swu` = `openwrt_v821_aiglass-*`, `sw-description` says "Tina Project", binaries are `riscv32`.
>   Same SoC as the Cyan project. See [09 — Vision Firmware](09_Vision_Firmware_V821.md).
> - **Inter-chip link = `aglink`** (a MoYoung IPC library, `libaglink.so` on the Vision side; carries
>   media + status + the relayed version between Vision and Core).
> - This unit: **MOY-A073 "V06"**, MAC `F5:13:72:15:2C:31`, Core `MOY-A073-0.0.8` (git `C363B807`),
>   Vision **display** version `2.4.0.22.3.2603302218` vs the Vision image's internal
>   `1.4.0.20.3.2603302218` — the `1.4.0.20→2.4.0.22` relabel is **NOT synthesised on the Core**
>   (corrected; exact mechanism still open — see [10 §3](10_Cloud_and_Device_Auth.md) / [15 §7](15_Core_App_Analysis.md)).

## 1. The big picture — a two-processor design

The MoYoung glasses are a **dual-SoC** device. The SDK makes this unambiguous even without
opening the hardware, because it exposes *two independent OTA mechanisms* and a feature-state
message that names both processors:

- **Jieli (杰理 / "JL")** — the **BLE + audio MCU**. This is the *master* the phone talks to.
  Evidence: the AAR bundles Jieli's official libraries — `com.jieli.jl_bt_ota.*` (Bluetooth
  DFU/OTA) and `com.jieli.jl_audio_decode.opus.OpusManager` (Opus audio codec). The
  BLE-transport OTA path (`startOta`) is a Jieli DFU. **CONFIRMED.**
- **Allwinner (全志 / "AW")** — the **application SoC**: camera pipeline, Wi-Fi, on-device AI,
  video encode, live streaming. This is the *slave* co-processor. Evidence: a dedicated
  `startAllWinnerOta(File)` path that runs **over Wi-Fi**, and the `RunningStatus` protobuf
  fields `slaveOta` / `slaveActive` (the "slave" is the Allwinner side) alongside `jieliOta`.
  **CONFIRMED (family); part number UNVERIFIED.**

```
                 ┌──────────────────────────────────────────────┐
   Phone  ◀─BLE─▶│  Jieli MCU  (MASTER)                          │
   (app)         │   • GATT server, control protocol            │
                 │   • Opus audio in/out (AI dialogue, calls)   │
                 │   • Jieli DFU OTA over BLE                    │
                 │   • bonded BT classic (audio) likely         │
                 │                ▲                             │
                 │                │ internal link (UART/SPI?)   │
                 │                ▼                             │
                 │  Allwinner SoC  (SLAVE)                      │
                 │   • Camera ISP + photo/video capture         │
   Phone  ◀─WiFi─│   • Wi-Fi AP (file download, OTA, live)      │
   (app)         │   • On-device AI / AI-vision                 │
                 │   • Allwinner OTA over Wi-Fi (arbitrary file)│
                 └──────────────────────────────────────────────┘
```

The **internal link** between the two chips is not exposed by the SDK and is **UNVERIFIED**
(UART or SPI are the usual choices for this class of device; a teardown would confirm).

### Why this matters for RE
This is the *same shape* as the Cyan glasses (Allwinner V821 main SoC + a separate BLE MCU).
The division of labor — **BLE MCU owns control & audio, Allwinner owns camera/Wi-Fi/AI** — means:
- Control-plane RE happens over BLE against the Jieli MCU.
- Bulk data (media, firmware, live video) happens over the Allwinner's Wi-Fi AP.
- The two have **separate firmware images and separate update paths**, so "own the firmware"
  is really two problems. See [02 — Firmware & OTA](02_Firmware_and_OTA.md).

## 2. What the SDK tells us about each processor

### Jieli MCU (master)
| Signal | Source | Meaning |
|--------|--------|---------|
| `com.jieli.jl_bt_ota.impl.BluetoothOTAManager` | AAR strings | Jieli's standard BLE DFU stack is embedded |
| `com.jieli.jl_bt_ota.model.BluetoothOTAConfigure` | AAR strings | DFU is configured/driven from the phone side |
| `CRPOtaType { GR_A, GR_B }` | `com.moyoung.glasses.conn.type.CRPOtaType` | Dual-bank ("GR_A"/"GR_B") DFU — a classic Jieli A/B image layout |
| `com.jieli.jl_audio_decode.opus.OpusManager` | AAR strings | Audio (AI dialogue / translation) is **Opus**, decoded on the phone to PCM |
| `startOta(...)` over BLE | `CRPBleConnection` | Confirms the control MCU is the BLE/DFU endpoint |

The `GR_A` / `GR_B` OTA types strongly imply a **dual-bank (A/B) flash layout** on the Jieli
side — the updater writes the inactive bank and swaps. That's good news for bricking safety on
the Jieli side (a bad image can fall back), and a known Jieli DFU packaging to study.

### Allwinner SoC (slave)
| Signal | Source | Meaning |
|--------|--------|---------|
| `startAllWinnerOta(File, listener)` | `CRPBleConnection` | Allwinner image is pushed as a **file over Wi-Fi** |
| `enableWifi(CRPWifiType.OTA)` → `connectWifi()` | dev-docs `ota.md` | Allwinner OTA requires bringing up the device's Wi-Fi AP first |
| `CRPWifiType { FILE, OTA, LIVE }` | `com.moyoung.glasses.conn.type.CRPWifiType` | The Wi-Fi AP serves three roles: media download, OTA, and live streaming |
| `RunningStatus.slaveOta` / `slaveActive` | `protos.RunningStatus` | The Allwinner ("slave") runs its own OTA and has an active/idle state |
| `takePhoto`, `queryVideoConfig`, AI-vision (`aiVisual`), `livingMode` | API + `RunningStatus` | Camera/AI/live all live on the Allwinner side |
| `downloadMediaFile` / `downloadLogFile` over Wi-Fi | `CRPBleConnection` | Bulk transfer (photos, videos, logs) is HTTP over the device AP |

**Media & OTA are HTTP over the device's own Wi-Fi AP**, not BLE. The `CRPFileDownloadCallback`
error codes (`CODE_HTTP_FAIL`, `CODE_RESPONSE_DATA_FAIL`, `CODE_URL_NULL`) confirm an HTTP client
(okhttp is a declared SDK dependency) pulling from a URL served by the Allwinner side. That HTTP
server on the device AP is an **untested attack/RE surface** (what else does it serve? directory
listing? log files? see [05](05_Reverse_Engineering_Findings.md)).

## 3. `RunningStatus` — the device's concurrent-feature map

The `queryFeatureActiveState()` → `CRPFeatureStateListener.onFeatureStateChanged(RunningStatus)`
callback returns a protobuf that enumerates *everything the device can be doing at once*. This is
effectively a live map of the firmware's subsystems:

| Field | Subsystem | Runs on |
|-------|-----------|---------|
| `takePicture` | Still capture | Allwinner |
| `aiVisual` | AI vision / recognition | Allwinner |
| `audioRecording` | Voice recorder | Jieli (mic) → stored |
| `videoRecording` | Video capture | Allwinner |
| `fileSync` | Media file transfer (Wi-Fi) | Allwinner |
| `livingMode` | Live streaming | Allwinner |
| `slaveActive` | Allwinner co-processor awake | Allwinner |
| `simuInterpretation` | Simultaneous interpretation / translation | Jieli (audio) + cloud |
| `aiDialogue` | AI voice assistant | Jieli (audio) + cloud |
| `slaveOta` | Allwinner OTA in progress | Allwinner |
| `jieliOta` | Jieli OTA in progress | Jieli |

This one message is the best single artifact for understanding the firmware's feature
decomposition. `slaveActive` is notable: the Allwinner is powered down when idle (to save
battery) and woken for camera/Wi-Fi/AI — so many operations have an implicit "wake the slave"
step with latency, which explains the 5–10s Wi-Fi bring-up the vendor docs warn about.

## 4. Storage & memory (inferred)

Not directly exposed, but inferable:
- Jieli side: internal flash with an **A/B (GR_A/GR_B) layout** for DFU (see §2).
- Allwinner side: larger storage for the OS + captured media (photos/videos/audio, counted by
  `FileCount { pictureCount, videoCount, audioCount, fileCount }`). Media served over HTTP.
- The Cyan parallel (Allwinner V821 + SPI-NOR, Tina Linux) is the most likely reference point
  for what the Allwinner side looks like — **UNVERIFIED** here but a strong starting hypothesis.

## 5. Open hardware questions (worklist) — several now ANSWERED

1. ~~**Exact part numbers**~~ ✅ **ANSWERED**: JieLi **AC701N** (Core) + Allwinner **V821** (Vision).
2. **Inter-chip bus** — the IPC library is **`aglink`** (name confirmed, `libaglink.so` on the Vision).
   The physical bus is now **UART (strongly INFERRED)**: the decrypted Core `app.bin` names it plainly —
   tasks `UseUartRecvTask`/`UseUartSendTask`, helpers `checkSlave`/`CheckUart`, `UART_UPDATE_CUSTOM`, and
   the 8 `jlstream_0..7` channels that drive the Vision's mode dispatch ([15 §6](15_Core_App_Analysis.md)).
   The exact **pins/wiring** still want a teardown + logic capture to fully confirm.
3. **Debug interfaces** — still *UNVERIFIED* physically, but the software routes are now known:
   the Core exposes JieLi **UBOOT** (USB/UART) — dumpable with `jl-uboot-tool` (AC701N "seems to
   work"); the Vision (V821) has `adbd` in its init and Allwinner **FEL** over USB (Cyan used
   `xfel`). All require exposing a USB-data/UART pad — the teardown.
4. **Flash readout protection** — Core `.ufw` is now **decrypted in software** (no readout needed —
   the cipher is a known JieLi LFSR keyed by a chipkey stored *in* the firmware; see
   [08](08_Core_Firmware_Jieli.md)). Vision `.swu` was **never encrypted** (unpacks freely). So we
   already have both images to diff — the "dump to diff" need is largely satisfied.
5. **Mic/wake path** — the decrypted Core `app.bin` contains **`jl_kws`** (JieLi Keyword Spotting) —
   i.e. the wake word runs on the **Core (AC701N)**, confirming the Cyan-parallel hypothesis
   (wake word on the always-on BLE/audio MCU, not the big SoC).

See [05 — Reverse Engineering Findings](05_Reverse_Engineering_Findings.md) for how these map to
concrete next steps and the Cyan comparison.
