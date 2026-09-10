# MoYoung Smart Glasses — Reverse-Engineering Toolkit

A reverse-engineering investigation into the **MoYoung "Altair" / V06 AI camera glasses** (the
"MY Glasses" / CRP BLE platform; cloud brand *altair.moyoung.com*) — hardware, dual-chip firmware,
the two OTA paths, and the BLE control protocol — plus a **React-Native (Expo) companion "tinker"
app** built to exercise the findings.

It is the sibling of the earlier
[**HeyCyan investigation**](https://github.com/mahi-v-v/heycyan-re-toolkit). Where the Cyan work
had to be pieced together almost entirely from decompiled binaries, MoYoung shipped a **real SDK
with developer documentation**, so this project starts from a stronger baseline and focuses its RE
energy on the parts the vendor *didn't* document (firmware internals, the Allwinner OTA surface,
the BLE wire format).

> **What this repo contains** — the author's own work: narrative RE documentation, analysis and
> tooling scripts (firmware/OTA/crypto/proxy), and the companion app source.
>
> **What it deliberately does not contain** — the vendor's proprietary SDKs, firmware images, and
> decompiled/decrypted firmware payloads. See [`VENDOR_ASSETS.md`](VENDOR_ASSETS.md).

> ### Why the history is a single commit
> This project was a **long, multi-month reverse-engineering effort**, and its full day-by-day
> commit history lives in a **private repository**. This public repo is a deliberately curated
> snapshot, rebuilt as one clean commit so the work can be shown as a portfolio piece **without
> publishing vendor-proprietary material or work-in-progress security details**. The flat history
> here is intentional — it's a showcase, not the development log.

> ### How to read the findings
> Claims are tagged **CONFIRMED** (verified from the shipped SDK/its binaries or vendor docs),
> **INFERRED** (strong deduction, not yet proven on hardware), or **UNVERIFIED / TODO** (needs a
> device, a BLE sniff, or a teardown).

---

## TL;DR — what these glasses are

| Aspect | Finding | Status |
|--------|---------|--------|
| Platform | MoYoung "MY Glasses" — a **CRP**-family BLE SDK (`com.moyoung.glasses`) | **Confirmed** (AAR) |
| Silicon | **Dual-chip**: a **Jieli** BLE + audio MCU (master) and an **Allwinner** SoC (slave) for camera / Wi-Fi / AI | **Confirmed** (SDK) |
| Two OTA paths | **Jieli OTA over BLE** (`startOta`, Jieli `jl_bt_ota` DFU) and **Allwinner OTA over Wi-Fi** (`startAllWinnerOta(File)`) | **Confirmed** (SDK + docs) |
| Firmware push | `startAllWinnerOta(java.io.File, listener)` takes an **arbitrary local file** — the primary firmware-tinkering surface | **Confirmed** (API), exploitation **TODO** |
| Audio | On-device audio (AI dialogue, translation, recordings) is **Opus**, decoded to PCM | **Confirmed** (bundled lib) |
| Wake word | Toggle + query over BLE (`sendVoiceWakeUpState` / `queryVoiceWakeUpState`) — on/off only, no custom phrase exposed | **Confirmed** (API) |
| Cloud | Firmware update check hits `https://altair.moyoung.com/api/v1/firmware/check-upgrade` | **Confirmed** (string in AAR) |
| Live video | Glasses stream out over **WebRTC** once joined to Wi-Fi in STA mode — endpoint fully app-supplied (`setStaInfo`) | **Confirmed** (iOS SDK 1.2.0); **absent from Android 0.0.7** |

The single most interesting surface for "do whatever we want with the firmware" is the
**Allwinner Wi-Fi OTA** (`startAllWinnerOta`) — it accepts a raw file, so the open questions are:
what does that file's format/signing look like, and does the Allwinner side verify it. See
[`docs/02_Firmware_and_OTA.md`](docs/02_Firmware_and_OTA.md).

---

## Repository layout

```
.
├── docs/          # Narrative documentation & reconciled findings (start here)
│   ├── README.md              # Doc index
│   ├── 01_Hardware_Architecture.md … 18_Button_Remap_and_Input_Ownership.md
│   └── reference/             # javap class/API dumps + Ghidra text dumps + capture evidence
├── firmware/      # Firmware manifest, index, and patched-rootfs build scripts (images not included)
├── tools/         # RE tooling: crypto, firmware-server, OTA MITM proxy, streaming probes
└── moyoung-app/   # The Expo companion / tinker app
    ├── modules/glass-sdk/     # Custom Expo native module wrapping the vendor AAR
    └── app/(tabs)/            # Tinker screens (scan / control / capture / ota / debug)
```

### `docs/` — the documentation hub
Numbered to read top-to-bottom; start at [`docs/README.md`](docs/README.md). The append-only
reverse-engineering journey log is [`docs/moyoung_reverse_engineering.md`](docs/moyoung_reverse_engineering.md).

### `firmware/` — manifest & build scripts
The curated firmware **index** (`MANIFEST.csv`, `index/`) and the patched-rootfs build scripts
(`vision-v821/_patched/`). The firmware **images themselves are not distributed** — see
[`VENDOR_ASSETS.md`](VENDOR_ASSETS.md).

### `tools/` — RE tooling
Crypto (`crypto/`, `core-firmware/`), a firmware-fetch/index server (`firmware-server/`), an
OTA man-in-the-middle proxy (`proxy-ota/`), and Wi-Fi/RTSP/WebRTC streaming probes (`streaming/`).
Several scripts take a firmware directory as an argument or a relative default path.

### `moyoung-app/` — the tinker app
An Expo Router app that talks to the glasses over BLE via a **custom Expo native module**
(`modules/glass-sdk/`, package `expo.modules.moyoung`) wrapping the vendor `my_glasses_sdk` AAR.
**Status: wired and building** once the vendor AAR is supplied — the module, JS bridge, all five
screens, and the vendor CRP calls are implemented. **Not yet run on real hardware.**

---

## Getting started

### Run the companion app (Android)
```bash
cd moyoung-app
npm install
# supply the vendor SDK AAR first — see ../VENDOR_ASSETS.md
npx expo prebuild          # generates android/ and links the custom native module
npx expo run:android       # builds & boots on a connected device
```

### Read the investigation
Start at [`docs/README.md`](docs/README.md); the deepest single narrative is the append-only
[`docs/moyoung_reverse_engineering.md`](docs/moyoung_reverse_engineering.md).

---

## Relationship to the HeyCyan investigation

The two projects target different vendors but a **strikingly parallel architecture** (Allwinner
main SoC + a separate BLE MCU, dual firmware, Wi-Fi for bulk transfer, BLE for control, an on/off
wake-word toggle, Opus audio). The [HeyCyan repo](https://github.com/mahi-v-v/heycyan-re-toolkit)'s
hardware-hacking playbook (binwalk the firmware, Ghidra the co-processor image, chase the OTA
packaging) transfers almost directly.
