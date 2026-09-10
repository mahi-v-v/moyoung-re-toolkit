# MoYoung "Altair" AI Smart Glasses — Firmware Reverse Engineering & Security Analysis

> ## 📌 This file is an APPEND-ONLY LOG — do not delete or overwrite
> **Purpose:** this file is the author personally documenting their reverse-engineering **journey** on the
> **MoYoung / "Altair" AI camera glasses** (marketed via the *Da Echo* app; cloud brand *altair.moyoung.com*),
> written to be included in their **cybersecurity portfolio**. It is a chronological log kept precisely so
> that no prior work is ever lost — it must retain **every** finding, attempt, tool tried, and dead-end,
> because the visible reasoning and course-corrections are exactly what make it portfolio-worthy. It is the
> sibling of `../../heycyan-re-toolkit/docs/06_Reference_Materials/cyan_reverse_engineering.md`.
> **Rules for anyone (human or AI) editing this file:**
> - Only ever **ADD** — append new dated `## N. Session <date>` sections in the existing numbered style.
> - **Never delete or rewrite** existing text, even when it's wrong.
> - Correct earlier claims **additively**: leave the original verbatim and annotate it inline
>   (`[<date> correction — see §N: …]`) and/or add a `> ⚠️ SUPERSEDED …` banner under the section header,
>   with details in the new session's "Corrections to prior sections" subsection.
> - This rule is **ONLY for this file.** Other docs under `docs/**` are normal working docs — update them freely.

This document is a detailed technical breakdown of the reverse-engineering performed on the **MoYoung
MOY-A073 ("V06") AI camera glasses**. It covers the dual-processor architecture, the companion-app/SDK
crash analysis, the live-video (RTSP) quality investigation, full cloud/OTA reconnaissance via an
intercepting proxy, complete firmware acquisition from an exposed server, static analysis of the
Allwinner "Vision" firmware, and the Ghidra work that located the exact video-encoder bitrate constant.

> ### Relationship to the Cyan project
> These glasses share the **Allwinner V821** camera SoC with the earlier
> [Cyan/HeyCyan investigation](../../heycyan-re-toolkit). The V821 Tina/OpenWRT playbook (`.swu` = newc
> CPIO, `cpio_item_md5`, no signatures, `ai_glass_*` daemons, `rtc_init.sh` mode dispatch) transfers almost
> verbatim. Where Cyan pairs the V821 with a **BlueX/JieLi** BLE MCU, MoYoung pairs it with a **JieLi AC701N**
> "Core" MCU. Cross-references to the Cyan log are noted throughout.

---

## 1. Device Identity & Hardware Architecture

### A. Confirmed identity (official *Da Echo* app "About/Upgrade" screens + BLE logs)
| Field | Value | Source |
|:---|:---|:---|
| Device name | **V06** | app device-info screen |
| Model code | **MOY-A073** | firmware version prefix |
| BLE MAC | **F5:13:72:15:2C:31** | app + our own scan/logcat (`MoyoungAdapter` connect trace) |
| **Core firmware** | **`MOY-A073-0.0.8`** (git hash `C363B807`) | app; **update to `0.1.0` offered** |
| **Vision firmware** | **`2.4.0.22.3.2603302218`** (build 2026-03-30 22:18) | app; reports **"already up to date"** |
| TP (touch) version | `000096C3` | app |
| Device serial | `22806c006c00482000c30388549b2113` | mitm capture (§4) |

### B. Dual-chip architecture (Asymmetric Multi-Processing)
```
 ┌──────────────────────────────────────────────────────────────┐
 │                       MoYoung V06 GLASSES                    │
 │  ┌───────────────────────┐   BLE / aglink   ┌──────────────┐ │
 │  │  JieLi AC701N  (Core) │◄────────────────►│ Allwinner    │ │
 │  │  BLE + audio MCU      │                  │ V821 (Vision)│ │
 │  │  ".ufw" OTA over BLE  │                  │ Tina/OpenWRT │ │
 │  │  ENCRYPTED firmware   │                  │ camera/WiFi  │ │
 │  └───────────────────────┘                  │ RISC-V app   │ │
 │                                             │ ".swu" OTA   │ │
 │                                             └──────────────┘ │
 └──────────────────────────────────────────────────────────────┘
```
* **Core = JieLi AC701N.** Proven by the downloaded Core image (§4/§5): the `.ufw` ends with the tail magic
  `4a 4c 55 46 57` = **`JLUFW`** (JieLi UFW container) and carries an `AC701` string. Firmware is encrypted.
* **Vision = Allwinner V821.** Proven by the `.swu` internals (§6): `sw-description` says *"Firmware update for
  Tina Project"*, the RTSP SDP self-identifies as `AW RTSP Streaming`, the filenames are
  `openwrt_v821_aiglass-*.swu`, and the application binaries are **RISC-V 32** ELFs (`ld-musl-riscv32`).
* Two independent OTA paths mirror Cyan: **JieLi DFU over BLE** (`.ufw`, dual-bank) and **Allwinner swupdate
  over Wi-Fi** (`.swu`, in-place — see §7).

### C. The `MOY-TTT3` red herring (dead-end, retained for the record)
The vendor iOS SDK bundle (`vendor-sdk/ios/.../swift-SdkDemo/TestSdk/`) ships four `MOY-TTT3-2.0.x.bin`
firmware images (~4.8 MB each, header magic `c8 b5 97 96`). Initial hypothesis: these are the glasses' Core
firmware. **Investigation refuted this:**
- Two-time-pad / encryption feasibility test (Python): XOR of version pairs showed up to **95 % byte-identical
  regions** and 5 KB+ zero-runs, with only 437-ish distinct sizes across pairs → *not* per-image encryption.
- Byte-histogram of the lowest-entropy window: 66.5 % `0x00` (padding) — not ciphertext.
- **String extraction was decisive:** `RTL8763EFL`, `RTL8773E`, `Welcome to Realtek BBPRO Terminal`,
  `get_band`/`wristband_mtu`, `gsensor`/`psensor`, `SC7A20` accelerometer — **and zero camera/ISP/H264/resolution
  strings**. Bootloader strings: `FSBL`, `secure_boot_image_id`, `image_check_bitmap`, `iap_signature`, A/B banks.

⇒ **`MOY-TTT3` is a Realtek RTL8763-based MoYoung fitness band ("MOYOUNG-V2"), a different product entirely** —
a demo leftover in the SDK. It taught us the vendor's Realtek band firmware is *plaintext with an FSBL
secure-boot check*, but it is **not** the glasses. Also corrected a working assumption: the SDK's `jl_*`
libraries (esp. `jl_audio_decode`) are a **phone-side Opus codec**, not evidence of the glasses' Core chip; the
Core chip was only confirmed as JieLi later, from the actual `.ufw` (§4).

---

## 2. Companion App / SDK — Crash Analysis & Fixes

Building a custom Expo "tinker" app (`moyoung-app/`, a native module wrapping the vendor `my_glasses_sdk.aar`)
surfaced two hard crashes on connect. Both were **missing vendor classes** the AAR references but never ships,
thrown on threads our code can't wrap.

### A. Crash 1 — `NoClassDefFoundError: com/android/mltcode/paycertificationapi/IWrite`
- Fired from the vendor's own `BluetoothGattCallback.onCharacteristicChanged` (`com.moyoung.f.a`) on a **binder
  thread** — uncatchable from our Kotlin — killing the process the instant the glasses sent a notification.
- `javap` on the AAR recovered the exact contract used by `com.moyoung.b.a`:
  `PayCertificationApi.init(Context,String,IWrite)` + `.distributionData(byte[],VerificationListener)`, where
  `IWrite.onWrite(byte[]):boolean` and `VerificationListener{onSuccess/onError(int)/onUnknown(int)}`.
- This is a **device challenge/response auth handshake** (the Android twin of the iOS SDK's
  `MZEncryptSDK.framework` / `MZPayAuth`), which MoYoung does not distribute.
- **Fix:** compatible stub classes under the exact packages (inert: log + no-op). The sibling MADRIMs/Cyan app
  had solved the identical gap ("signatures verified against the decompiled official app"), independently
  validating our recovered signatures.

### B. Crash 2 — `NoClassDefFoundError: com/jieli/jl_audio_decode/opus/OpusManager`
- Fired from `setAiDialogueListener` **and** `setTranslationListener` (`com.moyoung.g.a` / `com.moyoung.g.c` —
  two consumers, which is why both failed). The real class is a JNI wrapper doing
  `System.loadLibrary("jl_opus")`; without the `.so` it can't function, so a stub is the only option.
- **Fix:** stub `OpusManager` (+ `OnDecodeStreamCallback`, `OpusException`), ported from the Cyan repo's
  decompiled originals; verified via `javap` that the MoYoung AAR calls exactly the 5 stubbed members.
- Added `VendorCrashGuard` — a narrow default-uncaught-exception handler that swallows *missing-vendor-class*
  errors on non-main threads only, so any further unshipped component degrades to a log line, not a crash.

### C. Connection hardening (ported from the sibling CRP app)
The CRP SDK's two-step connect (`device.connect()` returns a connection object but does **not** dial; you must
then call `connection.connect()`) is unreliable: `connect()`'s boolean can return `false` while the link still
comes up asynchronously. Ported: warm-scan-before-connect, a 5-attempt retry loop (Android's first GATT connect
after idle often drops instantly), a `conn !== connection` superseded-attempt guard, and reliance on the state
listener rather than the boolean. **Consequence:** the app connects cleanly, reads both firmware versions, and
reaches the Wi-Fi/OTA paths.

---

## 3. Live Video Streaming — RTSP Quality Diagnosis

**Question:** why is the live video so low-quality? User hypothesis: the resolution is set very low.

### A. The transport (corrected the project's earlier WebRTC assumption)
The stream is **RTSP**, not WebRTC. In STA mode the glasses + phone join the same Wi-Fi router; the glasses run
an RTSP **server** and the phone is the client. The SDK hands the phone the URL over BLE via
`onLiveUrlChanged(String)` after `enableWifi(LIVE)` + `connectWifi()`. (Docs guide `audio.md` states verbatim:
*"video stream format is RTSP".*)

### B. Method — read the stream directly (no sniffing needed)
Since the stream lives on the shared LAN, a Python RTSP scanner swept the `/24`, found the server, pulled the
SDP, and `ffprobe` read the exact media. A 12 s `ffmpeg -c copy` capture measured the true bitrate; one keyframe
was extracted for a visual check.

```
rtsp://10.136.210.76:8554/ch0
  s=Session streamed by "testH264VideoStreamer"   a=tool:AW RTSP Streaming v20170726
  m=video H264/90000   b=AS:1048576 (bogus 2^20 placeholder)   m=audio MPEG4-GENERIC/8000 (AAC)
  measured: 1600x1200, ~30 fps, H.264 Main, yuvj420p — VIDEO ≈ 1,524,012 bps
```

### C. Verdict — it is a **bitrate cap, not a resolution cap**
The resolution is a genuine **1600×1200 (2 MP)** at ~30 fps — perfectly fine. The problem is the encoder is
**starved to ~1.5 Mbps**, i.e. ~**0.026 bits/pixel/frame** (good H.264 wants ~0.1–0.15). At that ratio the
encoder discards high-frequency detail → the soft, smeared "watercolor" frame we captured. Only `/ch0` exists
(no hidden high-quality channel), and a port scan of the glasses in STA mode showed **only TCP 8554 open** — no
telnet/adb/http/ssh, so no over-Wi-Fi shell shortcut. ⇒ the fix must change the Vision firmware's encoder config
(pursued in §7/§8). Full working notes: `docs/06_Live_Video_Streaming.md`.

---

## 4. Cloud & OTA Reconnaissance (mitmproxy on the *Da Echo* app)

The user proxied the official app with mitmproxy (TLS decrypted). Two connections were decisive.

### A. `POST https://altair.moyoung.com/api/v1/firmware/check-upgrade` — no auth, plaintext firmware URL
Request body: `{"fw1_ver":"MOY-A073-0.0.8","fw2_ver":"2.4.0.22.3.2603302218","mac":"F5:13:72:15:2C:31"}`.
Response (verbatim):
```json
{"data":{"has_upgrade":true,"firmware_ver":"MOY-A073-0.1.0",
"firmware_file":"https://altair.moyoung.com/static/firmware/20260714183832_MOY-A073-0.1.0-BIN-DAD63A87-ENCRYPTED.ufw",
"firmware_md5":"17b83173c38e0920e0a50cff3fb09017","firmware_size":1703232,"type":1,"firmware_num":1},"status":"ok"}
```
Findings: the endpoint takes **no Authorization header**; the `firmware_file` is a direct, unauthenticated
download link; `type:1` = the JieLi **Core** (the API never returns the Allwinner Vision — that is
Allwinner-cloud-managed). Forging the request (older/empty `fw2_ver`; claiming Core already latest) confirmed the
server **only** dispenses Core and applies **no version/anti-downgrade gate at this endpoint**.

### B. `deviceauth.allwinnertech.com` + `altair.moyoung.com` auth/activation chain (Vision-side DRM)
The Vision/AI features are gated by an Allwinner-cloud device-authorization + a per-device license:
- `POST deviceauth.allwinnertech.com/algorithm/api/authDeviceCode` — `{activationCode, customerIndex:"4",
  deviceSerial, timestamp}`, signed with an RSA-ish `signature` header; response `"activationCode is used"`.
- `POST altair.moyoung.com/api/v2/oauth/auth` → issues an **`altair-ai-hub` JWT** (`device_id:"230673"`, scope
  `device_access`, `aud:["altair-devices"]`).
- `.../api/v3/activation-code/api/tool-other/{get,bind}-activation-code` → returns a **335-byte
  `device_license`** blob that embeds the device serial (byte-swapped). This is the Allwinner secure-boot / SDK
  device certificate keyed to `customer_index 4` (the MoYoung OEM).

⇒ The AI/translation/dialogue features ride on Allwinner's cloud licensing; **the video/OTA path does not** — the
firmware itself is served in the clear (§5).

---

## 5. Firmware Server Exposure & Bulk Acquisition

### A. Download + verify the Core image
`curl --ssl-no-revoke` (Windows schannel throws `CRYPT_E_REVOCATION_OFFLINE` without it) fetched the Core
`.ufw`; md5 matched `17b83173…` exactly (1,703,232 bytes). Analysis: overall entropy **7.97** (encrypted, as the
`-ENCRYPTED` filename admits), tail magic `JLUFW`, `AC701` string ⇒ **JieLi AC701N, encrypted `.ufw`**. Decrypt
is a side quest (JieLi's format; likely diffable across the many versions on the server — a future session).

### B. The whole factory is exposed — directory listing is ON
`GET https://altair.moyoung.com/static/firmware/` returns a **full Apache-style directory index**:
**4077 files, ~46.5 GB** — every model, every version, `.ufw` (JieLi) + `.swu` (Allwinner).

**De-duplication (Python, via `Range: bytes=0-0` → `Content-Range` sizing, no full downloads):** the 46.5 GB is
mostly identical rebuilds re-uploaded under new timestamps — only **437 distinct file sizes**. Deduped to distinct
builds ≈ **15 GB**. Breakdown of the MoYoung V821 Vision lines: `aiglass-ai` 444 files → **26 unique** (610 MB);
`aiglass-ab` 878 → **34** (421 MB); `aiglass_imx681-ab` 227 → **6** (75 MB); plus per-ODM lines
(`YX-`, `ZL-`, `MLB-`, `JDF-`, `SS-`, `ZKHS-` … all V821).

### C. Curated local archive (kept, with manifest)
Downloaded a curated **~1.25 GB / 108-file** set = *every A073 Core build* + *one copy of every distinct MoYoung
V821 Vision build*, into `firmware/` with a `MANIFEST.csv` (filename, size, md5, sha256, and the internal
`ag_user_version.conf` per `.swu`) + full provenance (`fwindex.html`, `fwsizes.txt`, `sel.tsv`) + a `README.md`.
The archive makes us independent of the server if it ever locks down. Tooling gotcha logged: `sel.tsv` written by
Windows-Python had **CRLF** line endings → trailing `\r` made every `curl` URL malformed (exit 3); fixed with
`tr -d '\r'`.

---

## 6. Vision Firmware Internals (Allwinner V821 / Tina-OpenWRT)

### A. `.swu` container & install rules (`unsquashfs`/`cpio` in WSL Ubuntu — Windows has neither)
The `.swu` is a **newc-CRC CPIO** (magic `070702`). First member `sw-description` (plaintext swupdate manifest)
declares two install profiles (**NOR** = `mtdblock*`, **SD-NAND** = `mmcblk0p*`) and these images, all
`installed-directly = true`:

| Image | NOR device | Notes |
|:---|:---|:---|
| `kernel` | `/dev/mtdblock3` | Android bootimg |
| **`rootfs`** | `/dev/mtdblock5` | squashfs 4.0 / **lzo** / 32K block — holds the `ai_glass_*` apps |
| `riscv` | `/dev/mtdblock4` | RISC-V ELF (camera/AI co-core) |
| `user` | `/dev/mmcblk0p13` | squashfs — ships `etc/ag_user_version.conf` (`-ai` packaging only) |
| **`boot0`** | `/dev/mtdblock0` | **the bootloader** |

Integrity = a plaintext **`cpio_item_md5`** member only; **no per-image `sha256` in `sw-description`, no signature,
no hardware-compatibility gate** (see §7). Same weak model as Cyan's V821.

### B. The application layer (`rootfs`/`user`)
`/bin/ai_glass_*` suite: `ai_glass_normal`, `_audio`, `_photo`, `_video`, `_download`, `_ota`, **`_livestream`**,
plus Allwinner's `eyesee-mpp` media stack. The device is **mode-driven** — `/etc/media/rtc_init.sh` launches one
app per `$mode` (0=photo, 1=video, 2=download, 3=ota, 5/7=normal, 6=audio, **8=livestream**, 15=ETF test), each
`&`-backgrounded with **no arguments**. `adbd` is a real init.d service (USB-gadget FFS) → an ADB root shell is
available **only over USB**, which this unit does not physically expose without a teardown.

### C. Version identification — the app "transforms" the displayed version
The app shows Vision `2.4.0.22.3.2603302218`, but **no such string exists on the server**. Matching by the
unforgeable **build stamp `2603302218`** (2026-03-30 22:18) found `ag_user_version.conf = 1.4.0.20.3.2603302218`
in the **`-ai`** line ⇒ the app relabels `1.4.0.20` → `2.4.0.22` for display; the `.3.2603302218` suffix is
identical. The `imx681` sensor variant was **ruled out** (it's explicitly labelled `imx681` in
`etc/openwrt_release`; our build has no imx681 anywhere). ⇒ **your exact Vision image =
`firmware/vision-v821/aiglass-ai/20260427195936_openwrt_v821_aiglass-ai.swu`** (md5 `cfb419c8…`, internal
`1.4.0.20.3.2603302218`).

---

## 7. Allwinner OTA — Brick-Safety Analysis

Before attempting any flash, the stock image's own updater rules were audited. **This is the one operation in the
project that can permanently brick the glasses.**

- **9 partitions written in-place** (`installed-directly=true`), **including `boot0` (the bootloader)**.
- **No A/B rollback** — the `sw-description` `bootenv` `swu_next="reboot"` blocks are **commented out**.
- **No pre-write verification** — no per-image `sha256`; only the weak `cpio_item_md5`.
- **No hardware-compatibility gate** — swupdate writes whatever it is handed; it will not refuse a wrong variant.
- The install scripts (`preinstall_nor.sh`/`postinstall_nor.sh`) manage a **dual NOR/SD-NAND boot source** and
  reference **`efex`** (Allwinner FEL/FES USB recovery) — so a brick is *recoverable only via FEL over USB*.

⇒ **Risk assessment:** an interrupted or wrong-variant flash overwrites the bootloader with no rollback and only
FEL recovery — which is **unavailable without opening the device** (no exposed USB). Our app *does* wire the push
path (`MoyoungAdapter.startAllwinnerOta(filePath)` → `conn.startAllWinnerOta(File, listener)`, needs
`enableWifi(OTA)`+`connectWifi()` first) but it has **never been run on hardware**. **Decision: hold the Allwinner
in-place flash until a UART/USB teardown gives a recovery net or a root shell.** The safe pipeline-validation
alternative is the **JieLi Core OTA** (dual-bank A/B, vendor-offered `0.1.0`) — not the camera SoC. Mirrors Cyan
§5.B/§9 (in-place-NOR contention, unverified rollback).

---

## 8. Ghidra — Locating the Encoder Bitrate Constant

**Goal:** find the compiled `c0` bitrate default in `ai_glass_livestream` and whether `--bitrate` overrides it.

### A. Tooling & method
Binary: `ai_glass_livestream` — **RISC-V 32, stripped, musl** (extracted from the exact device image). WSL
`objdump` lacks a RISC-V target; used **Ghidra 12.1.2 headless** (`analyzeHeadless`). Dead-ends logged: (1) a
`.py` post-script failed — *"Ghidra was not started with PyGhidra"* → rewrote as **Java**; (2) `import
ghidra.program.model.data.Data` is wrong — `Data` lives in `…model.listing`. The Java script located the
`*demo param: c0` format string, walked its xref to the setup function, and decompiled it.

### B. Result — the bitrate is a hardcoded constant
`take_video_livestream_start` (`FUN_ram_0001b99a`):
```c
iStack_154 = 1500000;                     // ← c0 (channel 0) bitrate, HARDCODED  (= our measured ~1.5 Mbps)
iStack_558 = iStack_154;                  // → encoder target bitrate
iStack_478 = iStack_474 + (iStack_154/8)*4;   // VBV buffer sized from it
...
if (encode_format == 1) {                 // H.265/HEVC branch
    iStack_558 = 0xC00000;                //   = 12,582,912  → 12 Mbps target
    uStack_4e8 = 0xA00000;  uStack_4ec = 0xE00000;   //   10 Mbps / 14 Mbps VBR bounds
}
```
- **c0 bitrate = `1500000` (0x16E360), compiled in.** Width/height come from a "PresetConfig", but the **bitrate
  is overwritten in code** regardless of preset.
- **`--bitrate` does NOT override the live path** — the `--bitrate`/`c0_bitrate` strings have **no code xref**
  into this function, and `rtc_init.sh` launches the daemon arg-less. So a launch-script edit cannot work.
- **Hidden 12 Mbps path:** H.265 (`encode_format==1`) auto-selects a 12 Mbps target. Our stream is H.264
  (`format 0`) so it's pinned to 1.5.

### C. Conclusion — the fix requires modifying the binary/firmware
Two levers, both needing a Vision-firmware reflash or a root shell (i.e. the §7 teardown):
1. **Binary-patch** the `1500000` `lui`/`addi` immediate → 6–8 Mbps (guaranteed win, keeps H.264), or
2. **Force H.265** (`encode_format=1`) for the built-in 12 Mbps — bigger jump + better compression, *iff* the
   phone app decodes HEVC (unverified).
Either way: patch → `mksquashfs` (lzo/32K) → refresh `cpio_item_md5` → repack CPIO → flash. Cyan's
`repackage_swu.py` (§7 there) is the template.

---

## 9. Current Status & Roadmap

| Goal | Path | State (2026-07-29) |
|:---|:---|:---|
| Identify device & chips | app + firmware internals | ✅ JieLi AC701N (Core) + Allwinner V821 (Vision) |
| Get the glasses' firmware | exposed `static/firmware/` server | ✅ Core `.ufw` + exact Vision `.swu` + 1.25 GB archive |
| Tinker app connects & reads versions | stubs + connect hardening | ✅ done |
| Diagnose low stream quality | ffprobe + Ghidra | ✅ **encoder bitrate cap (1.5 Mbps), not resolution** |
| Raise stream quality | patch `ai_glass_livestream` (1.5→6–8 Mbps) or force H.265 (12 Mbps) | **image-build pending; flash needs teardown** |
| Glasses → server (RTMP/HLS) | **Route A:** ffmpeg/MediaMTX relay off `rtsp://…:8554/ch0` (works today) · **Route B:** native push = firmware mod | Route A ready; Route B needs flash |
| Decrypt JieLi Core `.ufw` | diff many versions / JieLi tooling | not started |
| Root / recovery net | UART or USB (adbd/FEL) teardown | **hardware step — unlocks safe flashing** |

**Tools used this session:** `javap` (AAR surface), Python (entropy/XOR/crypto tests, RTSP scanner, size/dedup,
JWT decode), `ffprobe`/`ffmpeg` (stream measurement), WSL Ubuntu `cpio`/`unsquashfs`/`file`/`binwalk`, **Ghidra
12.1.2 headless** (RISC-V decompile), `curl` (`--ssl-no-revoke`), mitmproxy (user-driven). Working docs kept in
sync: `01_Hardware_Architecture.md`, `02_Firmware_and_OTA.md`, `06_Live_Video_Streaming.md`; archive in
`firmware/`.

---

## 10. Session 2026-07-29 (cont.) — Vendor STA-WebRTC push API vs. this unit's firmware

**Question:** does the vendor SDK expose a way for the glasses to **push** video to a server/URL (instead of
hosting an RTSP server the phone joins)? Prompted by the user recalling such a method.

### A. Method
Searched the whole `vendor-sdk/` (not just the Android `dev-docs/`). The Android BLE SDK 0.0.7 only documents
the RTSP pull model. The **iOS SDK 1.2.0** is newer — read its `CRPSmartGlasses.framework`
`arm64-apple-ios.swiftinterface` and extracted the `IOS-SDK Development Guide.pdf` with `pdftotext`.

### B. Finding — the API exists (iOS 1.2.0), and it is WebRTC *[verified from SDK + manual §4.6]*
```
setLiveStreamEnter(wifiCtrl: CRPWifiCtrl)   setSTALiveMode(staInfo: CRPStaLiveMode?)   setLiveStreamExit()
```
`CRPStaLiveMode { wifi_ssid, wifi_pwd, token, web, user_id, user_pwd, turn_urls, turn_id, turn_pwm }` — the
glasses **join a Wi-Fi you specify** and push to **your signaling (`web`) + TURN** infrastructure. The
`CRPStaLiveState` enum (`webrtc_start_success`, `webrtc_new_connection`, `webrtc_connect_success`,
`webrtc_data_transfer`, `webrtc_disconnected`, `high_temperature_warning`) confirms it is **WebRTC**. This is
exactly the "push to a given server" model — and it corroborates the project's earlier note that WebRTC live is
"iOS-SDK-1.2.0 only".

### C. But it does NOT apply to the A073/V06 — the firmware has no WebRTC *[verified — decisive]*
- **Not in the Android SDK 0.0.7** (our app's base): grep for `setSTALiveMode`/`CRPStaLiveMode`/`webrtc`/`turn`
  across the AAR + dev-docs = empty. Not callable from our current app.
- **Not in this unit's firmware:** grep of the exact device Vision image
  (`firmware/vision-v821/aiglass-ai/20260427195936_…ai.swu`, unpacked rootfs) for
  `webrtc|PeerConnection|stun:|turn:|ice_ufrag|libwebrtc|createOffer|dtls-srtp` → **nothing** (only GLib
  `libgio`, a false positive). `ai_glass_livestream`: **47 `rtsp`, 0 `webrtc`, 0 `turn:`/`stun:`.**

⇒ The STA-WebRTC push is a **CRP-platform SDK feature for models whose firmware bundles a WebRTC stack; the
A073 is not one of them** — its Vision firmware implements only the RTSP server path (§3). This is an important
**correction of scope** for [`streaming_glasses_to_a_url.md`](streaming_glasses_to_a_url.md): the vendor *does*
have a push API, but pushing from *this* device still requires either a relay (works today) or a firmware mod
that adds a pusher — not merely calling the SDK.

### D. Caveat on §10.C's "decisive" (append-only correction, same session)
§10.C called the no-WebRTC result "decisive." Tightening that for honesty: it is **strong but scoped**, not
absolute. Verified only for **the build currently on the device** (`1.4.0.20.3.2603302218`), via **static
string analysis of the squashfs rootfs/user partitions only**. It does NOT establish: (a) that other/newer
server builds lack WebRTC — only this one version was unpacked (older/newer builds are checkable offline, but
future ones can't be); (b) the `riscv` co-processor blob or the kernel image (not grepped — WebRTC there would
be unusual but wasn't ruled out); (c) runtime behaviour — strings show present code, not how the device acts.
**The only definitive confirmation is to call `setSTALiveMode` on the device and watch `receiveSTALiveState`.**
That test is **low-risk** (BLE command + a Wi-Fi/WebRTC attempt; worst case `webrtc_start_fail`; cannot brick),
so it — not the grep — is the real settle. The static result remains a strong prior, nothing more.

### E. Session 2026-07-30 — Da Echo APK decompile: the version relabel is DEVICE-side, not app-side
Pulled the exact companion app from the phone via adb (`com.moyoung.glasses`, versionName `2.4.31_20260712` —
matches the mitm capture) and decompiled `classes3.dex`/`classes6.dex` with jadx (not obfuscated). Traced the
version display to `DeviceVersionCallback.onDeviceVersion()`:
```java
String ver = versionInfo.getVer();
if (i == 1)      DeviceInfoProvider.setFirmwareVersion(ver);          // VerFirmware  = Core, verbatim
else if (i == 2) DeviceInfoProvider.setAllwinnerFirmwareVersion(ver); // VerFirmware1 = Vision, verbatim
```
`get/setAllwinnerFirmwareVersion` are pure SharedPreferences storage — **no transform**. Only the TP version is
reformatted (`%08X`). **⇒ The app does NOT relabel the version; the glasses report `2.4.0.22.3.2603302218` over
BLE verbatim.** This corrects the earlier guess (§6-doc) that the app transformed `1.4.0.20`→`2.4.0.22`. The
relabel is **device-side** — the running `.swu`'s `ag_user_version.conf` says `1.4.0.20.3.2603302218`, but the
firmware reports `2.4.0.22.3.2603302218`; same unique build-stamp `2603302218`, different version numbers. So
the exact-image identification still rests only on that build-stamp, and the definitive answer needs the live
device (glasses adb/UART), not the APK.

**Follow-up (same session) — RESOLVED by elimination to the Core MCU.** `VersionInfo` is a plain protobuf: field
2 `ver` is one UTF-8 string read straight off the wire (`readBytes()`) — no client-side assembly, the device
sends the whole string. Grepping the exact Vision `.swu`: its own version is **`1.4.0.20.3.2603302218`**, baked
into `libaglink.so` **and** `ag_user_version.conf`; **`2.4.0.22` appears nowhere** in the Vision rootfs/user (no
file, no binary, no runtime assembly). Report chain = Vision → `libaglink` → **Core (Jieli) MCU** → BLE → app.
App = verbatim; Vision = `1.4.0.20`, no `2.4`. ⇒ **By elimination the `1.4.0.20`→`2.4.0.22` relabel is applied by
the Core/Jieli MCU** (the BLE endpoint) — whose `.ufw` is encrypted, so the exact rule is unreadable. The
`.3.2603302218` build stamp is preserved through the relabel and is unique ⇒ **the glasses are confirmed running
the `1.4.0.20.3.2603302218` Vision build**; `2.4.0.22` is a Core-side display label present in neither the
Vision firmware nor the app. This retroactively validates the build-stamp identification. Bonus:
`DeviceVersionCallback.FORCED_AP_VERSIONS` =
`{MOY-A073-0.1.0, -0.0.7, -0.0.6, -0.0.3, MOY-A253-…}` — the app forces Wi-Fi **AP** mode for those Core
versions; our Core `0.0.8` is absent ⇒ **STA** (consistent with the observed RTSP-on-shared-LAN); taking the
offered `0.1.0` Core update would flip streaming to **AP** mode.


---

## 11. Session 2026-07-30 — Core `.ufw` cryptanalysis (Jieli reused GLOBAL keystream)

**Task:** attempt to decrypt the Jieli AC701N Core `.ufw`. We hold ~15 distinct A073 builds (0.0.1→0.1.2).

### A. Classification (empirical, `firmware/core-jieli-a073/`)
- **Deterministic:** same version+CRC builds are byte-identical (three `0.0.8-C363B807` copies → one md5; `0.0.7-C0CD42A8` pair → one md5). No per-build IV/nonce.
- **Reused GLOBAL positional keystream (two-time-pad):**
  - same-size different-CRC builds share 20–78 % of bytes with 400 KB+ contiguous identical runs (0.0.7 pair: 78 % identical, XOR entropy 2.46);
  - same-size **different-version** builds also share hugely (`0.0.8 ^ 0.0.9` = **76 %** identical); across 10 same-size builds, **26 % of offsets are identical in ALL 10**;
  - cross-*size* pairs drop to ~5 % purely from offset misalignment.
  ⇒ one ~1.7 MB **non-repeating** pad is XORed into *every* build at the same offsets. No short period (periodicity test flat ~1 %). Header bytes 8–15 (`f9c1a767 cebf5b97`) fixed across versions; container footer is plaintext `JLUFW` (Jieli UFW).
- **Verdict:** NOT strong crypto — a catastrophic one-time-pad reuse; breakable **in principle**.

### B. But ciphertext-only recovery is blocked (matches the sibling Cyan finding)
The pad is long & non-repeating (no key to brute), and the plaintext **difference** `P1⊕P2` in changed regions is high-entropy (~7.89, 33 % printable) — i.e. compressed or heavily address-shifted code, **not** cleanly crib-draggable. The Cyan project hit the **identical** Jieli reused-keystream scheme (`docs/05_.../am01cy_ota_crypto_analysis.md`): its two-time-pad/consensus attacks yielded only **~33–65 % printable "smudged" garbage**, never a clean decrypt, for the same reason. So multi-version differencing gives `Pi⊕Pj` but not clean `P`.

### C. Clean-decrypt paths (need known-plaintext or the on-chip key)
Because the pad is **global**, **any one** known-plaintext AC70x image → XOR → recovers the whole pad → decrypts **all** our builds at once. Options:
1. **Jieli on-chip dump** via `jl-uboot-tool` (kagaimiq) / the JieLi USB-uboot method over USB/UART → pull the running **decrypted** image or the key directly (needs hardware access — the teardown). Cyan's docs point here for JieLi.
2. **A reference decrypted AC70x/AC701 image** (community/SDK) as known-plaintext.
3. Filename CRC (e.g. `C363B807`) is the plaintext BIN's checksum → a **validation oracle** for any candidate decrypt.
Ciphertext-only crib/consensus is worth an attempt (we have more samples than Cyan, ~15 vs ~1) but expected to be partial/smudged, per §11.B.

### D. Result of the ciphertext-only crib-drag attempt (2026-07-30) — NEGATIVE (honest)
Attempted the crib-drag independently (largest aligned group: 7 distinct builds @ 1691968 B). It **initially
looked like a clean win** — the crib `MOY-A073-0.0.` "decrypted" printable in all 7 builds at offset 0x4 — but
that was a **FALSE POSITIVE**, caught via a red flag (every crib, incl. nonsense like `ZZZZ-QQQQ-9.9.`, got
~440k "hits" = exactly the 26% identical-region size). Verified: offsets 0x4–0x11 are byte-identical across all
builds (`D_g=0`), so decrypting with `K=C0⊕crib` trivially reproduces *any* ASCII crib → validates nothing.
Root cause (measured): **26% of the file is identical across builds** (no differential signal → guesses
unverifiable) and the **74% that varies is recompiled code** (`Pi⊕Pj` entropy ~7.9, not guessable ASCII → no
crib to anchor). ⇒ **Ciphertext-only crib-drag is exhausted; it cannot recover the keystream here.** The
reused global pad still means **one** known-plaintext (a reference decrypted AC70x image, or a `jl-uboot-tool`
on-chip dump over USB/UART) recovers the whole pad and decrypts all 15 builds at once — that hardware/known-PT
route is the only remaining path. (Independently reproduces the Cyan project's conclusion on the same scheme.)

---

## 12. Session 2026-07-30 — Core `.ufw` DECRYPTED in software (JieLi cipher + embedded chipkey)

**Result: SOLVED.** The Core firmware decrypts with **no hardware and no brute-force** — superseding §11's
"needs known-plaintext / chip dump" conclusion (that was true only for the blind ciphertext-only route).

**How (online research → the known JieLi scheme):** `kagaimiq/jl-misctools` implements the JieLi firmware
cipher. It is a **16-bit LFSR** (CRC-CCITT poly `0x1021`): keystream byte = `key & 0xFF`, then
`key = ((key<<1) ^ (0x1021 if key&0x8000 else 0)) & 0xFFFF`, applied in **32-byte blocks**, each block seeded
with `chipkey ^ ((blockaddr) >> 2)`. This exactly matches our measured **global positional reused pad**
(§11.A): deterministic, non-repeating, same for all builds because `chipkey` is fixed. The keyspace is only
16 bits, but brute-force wasn't even needed — **the chipkey is stored in the firmware's own `isd_config.ini`**
JLFS entry (`chipkeybin_decode`). For our device: **chipkey = `0x1607`**.

**Run:** `fwunpack_newfw.py <our .ufw>` on `MOY-A073-0.0.8-BIN-C363B807` →
- chip name **AC701N**, chipkey `0x1607`, JLFS parsed cleanly (CRCs valid).
- Extracted: `uboot.boot`, **`app.bin` (1,065,100 B, entropy 7.97→7.0)**, `cfg_tool.bin`, `config.dat`,
  `p11_code.bin`, `stream.bin`, `isd_config.ini`, and the `tone_en/` voice prompts (`awake.wts`,
  `take_pic.wts`, `call.wts`, `low_battery.wts`, …).
- `app.bin` has **1,894 plaintext strings**: `MOY-A073-0.0.8`, `VersionInfo: Invalid type`, `jl_kws` (wake
  word / KWS), `ai_voice`, `moy_recorder`, `Take_a_photo/Take_a_video/Start_recording/Photo_Recognition`,
  RCSP BLE (`jl_rcsp_ble_test`, `JL_SPP`, `Zble_ota.bin`), BT profiles `JL_A2DP/HFP/HID`, `DBG_CPU0..3`.

**Unlocks:** (a) full Core RE — Ghidra `app.bin` for the BLE/RCSP protocol, the `jl_kws` wake word, the
device-side version relabel that emits `2.4.0.22` (§10.E), and the photo/video/AI command handlers;
(b) decrypt **all 15** A073 builds (same tool, chipkey auto-read); (c) **modify + re-encrypt**
(`recrypt.py` with chipkey `0x1607`) → custom Core firmware, flashable over BLE via the **dual-bank** Jieli
DFU (`startOta(File)`) — lower brick risk than the in-place Allwinner path. Tools cloned to
`scratchpad/jlmt`; decrypt output in `scratchpad/coredec/`. (Note: the Vision/Allwinner `.swu` was never
encrypted — it unpacks freely — so this is specifically the Core chip.)

---

## 13. Session 2026-07-30 — Documentation reorganisation pass

Brought the whole `docs/` set up to date and organised it as a portfolio (nothing deleted; superseded
claims corrected in place with dated banners, or slated for `99_Archive/`).

**Updated for accuracy (working docs 01–06):**
- **01 Hardware** — silicon CONFIRMED (JieLi **AC701N** + Allwinner **V821**), `aglink` IPC, wake word
  on the Core (`jl_kws`); the old "part numbers unverified / teardown needed" worklist marked answered.
- **02 Firmware & OTA** — added a banner routing to the new deep-dive docs (07–10) and flagging what's
  now resolved.
- **03 App & SDK** — replaced "not yet run on hardware" with the real result: runs & connects after the
  two missing-vendor-class crash fixes (mltcode PayCertification, JieLi Opus) + `VendorCrashGuard` +
  connection hardening.
- **05 Findings** — top banner supersedes the "no device / no firmware analyzed yet" framing; the SDK-only
  table kept as the baseline.
- **06 Streaming** — banner: this unit streams **RTSP** (not WebRTC); the quality issue is the **bitrate**;
  `setSTALiveMode`/WebRTC applies to the iOS SDK / the `1.0.0.x` Vision branch only.

**New detailed findings docs:**
- **07 Firmware Acquisition** — open `check-upgrade` + world-readable `/static/firmware/`, dedup, the local `../firmware/` archive.
- **08 Core Firmware (JieLi)** — the full cryptanalysis → software decryption (chipkey `0x1607`), decrypted contents.
- **09 Vision Firmware (V821)** — `.swu` internals, `ai_glass_*`, mode dispatch, the WebRTC branch, OTA brick-risk.
- **10 Cloud & Device Auth** — mitm: check-upgrade / deviceauth / JWT / `device_license`; the `2.4.0.22` relabel resolved.
- **11 Streaming Bitrate Analysis** — ffprobe + Ghidra `1500000` constant + the ranked fixes.

**Structure:** new **00 Executive Summary** (orientation + status table); **README** rewritten as a full
index (00–11 + log + roadmap + how-to + reference + firmware); **99_Archive/** created with a convention
note (empty — everything was updated in place this pass). `paths_forward.md` E1 marked DONE, E2/E3 added.

## 14. Session 2026-07-30 — Bitrate mod BUILT + flashed pipeline wired (safe, rootfs-only)

Goal: turn the streaming diagnosis into a ready-to-flash Vision image and wire it into the app, with
**brick risk as low as software allows**. User chose the "safest `.swu` + Core dual-bank dry-run"
strategy; **no USB data line** exists (user-confirmed) so there is no FEL recovery net without a teardown.

### A. Ground truth from the real manifests (corrects earlier over-generalisation)
- The `.swu` is a **swupdate** archive; **swupdate writes only the images listed in the `sw-description`
  we ship**. ⇒ we can author a **rootfs-only** `.swu` that writes `/dev/mmcblk0p9` and **never touches
  `boot0`/`uboot`** — removing the hard-brick (FEL-only) vector even on the in-place path.
- The firmware **family DOES have true A/B** — the **`-ab` line**: `ai_glass_ota` runs
  `swupdate … -e stable,now_A_next_B` / `now_B_next_A`, writing the *inactive* slot
  (`bootB`/`rootfsB`/`riscv0-r`) then `systemAB_next=B`. Our unit is the **`-ai` single-system** build
  (boots `root=/dev/mmcblk*` = SD-NAND) → its OTA is `-e stable,sdnand` (in-place). Dual-system layout:
  NOR = minimal recovery rootfs (no `ai_glass_*`, no swupdate); SD-NAND = full system + `/sbin/swupdate`.
  (This refines doc 09 §5's blanket "no A/B" — corrected there with a dated banner.)

### B. The patch (pinned exactly)
- `scan_bitrate.py` (pure-Python RISC-V) found the **single** site that materialises 1,500,000 in
  `/bin/ai_glass_livestream`: **file offset `0xBA66`**, `lui x14,0x16E` (`37 E7 16 00`) + `addi x14,x14,864`.
  Patch the `lui` immediate → `0x5B9` (`37 97 5B 00`) = **6,001,504 bps** (`0x7A1` = 8.0 Mbps). 2 bytes.
- adb-over-Wi-Fi: uncomment `#ADB_TRANSPORT_PORT=5555` in `/etc/init.d/adbd` (vendor already wired
  `procd_set_param env ADB_TRANSPORT_PORT`; adbd starts at boot via `rc.d/S80adbd`). → `adb connect
  <ip>:5555` gives a root shell over Wi-Fi *if the rootfs boots* — a recovery/verify channel + the
  user's "flash adb into the swu" idea, grounded.

### C. Build (offline, no root — no passwordless sudo)
Only special file in the rootfs is `/dev/console` (char 5,1) — so `mksquashfs -all-root
-p 'dev/console c 600 0 0 5 1'` rebuilds faithfully without root. `-comp lzo -b 32768` to match.
Rebuilt image 7.08 MB ≤ 7.21 MB original → fits `mmcblk0p9`. Trimmed `sw-description` = `sdnand` group,
`rootfs_sdnand`→`/dev/mmcblk0p9` + vendor `preinstall_sdnand.sh` (writes `boot_type=2`), no bootloader.
Repacked with `cpio -o -H crc` (newc-CRC `070702` → swupdate CRC-validates, aborts on a bad transfer).
**Self-validated:** re-extract + tree-diff shows only `ai_glass_livestream` (2 bytes) + `init.d/adbd`
changed; patched bytes + adbd line survive the rebuild; inner squashfs valid. Two outputs:
`vision_noop.swu` (unchanged rootfs — pure pipeline test) and `vision_6mbps_adb.swu`. Scripts + images:
`firmware/vision-v821/_patched/` and `tools/vision-firmware/`.

### D. App wiring (native + JS were already complete)
`MoyoungAdapter`/`GlassModule`/`Glass.ts` already exposed `startAllwinnerOta`/`startJieliOta`,
`enableWifi`/`connectWifi`, and `GLASS_OTA_PROGRESS`/`GLASS_OTA_STATE`. Added: firmware **bundled as
native Android assets** (`modules/glass-sdk/android/src/main/assets/firmware/`, git-ignored) + native
**`resolveBundledFirmware`/`listBundledFirmware`** (copy asset → `filesDir` → absolute path), JS
wrappers, and a rewritten **`app/(tabs)/ota.tsx`**: bundled-build picker (recommended order), progress
bar + OTA-state + Wi-Fi-ready row, one-tap **Core dual-bank** flash, staged **Vision Wi-Fi** flow.
`tsc --noEmit` clean.

### E. Recommended sequence + honest risk
Core dry-run (BLE, A/B → auto-rollback) → Vision no-op (rootfs-only, zero change) → Vision 6 Mbps.
Residual risk is **low, not zero**: in-place rootfs write, unverified NOR auto-fallback, no FEL without
a teardown. Full model in **docs/12_Firmware_Patching_and_Flashing.md** (new).

### F. Housekeeping
Decrypted Core artifacts preserved to `firmware/core-jieli-a073/_decrypted/` (app.bin, uboot.boot,
isd_config.ini, config.dat, voice prompts). Stream-quality evidence → `docs/reference/evidence/`
(`frame.png`, `cap.mp4`). All working RE scripts organised under `tools/` with an index. Large/
reproducible scratchpad items (official APK, jadx sources, 125 MB logcats, duplicate images) left out
with provenance noted in `tools/README.md`.

## 15. Session 2026-07-30 — Flash path pivot: official app + MITM (BLE-flaky workaround)

Our tinker app's BLE keeps dropping mid-OTA. User's idea: use the **official Da Echo app** to flash and
just **intercept its firmware check** — offloading the reliability-critical BLE/Wi-Fi OTA to the vendor's
proven stack while keeping our (rootfs-only, safe) image. Confirmed viable by decompiling Da Echo:
- `FirmwarePresenter` posts `{baseUrl}/api/v1/firmware/check-upgrade`; response
  `data = {firmware_ver, firmware_file (URL), firmware_md5, firmware_num (1=Jieli/2=Allwinner), type
  (otaType), has_upgrade}` (`FirmwareVersionInfo` / `CRPNewFirmwareVersionInfo`; `FIRMWARE_TYPE_JIELI=1`,
  `FIRMWARE_TYPE_ALLWINNER=2`).
- `FirmwareUpdateFragment`: downloads `firmware_file` → `HexUtils.checkFileOfMd5(firmware_md5, file)` →
  `firmware_num==1 ? startOta(file) : startAllWinnerOta(file)`. Client does **no** version compare
  (`has_upgrade:true` is enough), and there is **no cert pinning** in the app (only okhttp's own unused
  class). ⇒ forging the response lets us set both the URL and the md5, so the integrity gate passes on
  our file, and `firmware_num:2` routes straight to the Vision Wi-Fi flash.
- Built md5s: `vision_6mbps_adb.swu`=`7f63ffd08b481db1d0a73a77bfbcc7f9`,
  `vision_noop.swu`=`7934a6b93efd97ebbd7d411e7c256dd8`.
- Deliverables: `tools/proxy-ota/moy_ota_mitm.py` (mitmproxy addon: rewrites check-upgrade + serves the
  image, no separate backend) and **doc 13**. Same rootfs-only brick model as doc 12; no-op image first.

## 16. Session 2026-07-30 — OTA execution: MITM path works, custom-app path blocked by SDK/lib skew

Tried to actually FLASH the patched Vision image two ways.

### A. Official Da Echo app + MITM (Reqable) — the WORKING path
- **mitmproxy's CA is NOT trusted by Da Echo.** Android 7+ apps ignore *user*-installed CAs and the
  phone is **not rooted**, so every HTTPS to `altair.moyoung.com` failed "client does not trust the
  proxy's certificate." The user's own **Reqable / PCAPdroid** setup DOES decrypt Da Echo (its CA is
  system-trusted on their phone) — so that is the tool, not our mitmproxy.
- Captured the exact `check-upgrade` response schema live:
  `{"status":"ok","data":{firmware_ver, firmware_file(URL), firmware_md5, firmware_size,
  firmware_num(1=Jieli/2=Allwinner), type(otaType), has_upgrade}}`. The real server was offering a
  Core update (`firmware_num:1`, `MOY-A073-0.1.0`).
- **Forged the response via a Reqable breakpoint** → the app accepted it, **downloaded our `.swu`
  over plain HTTP** (UA `okhttp/4.12.0`, served by `python serve_firmware.py`), md5-checked it (we set
  the md5), and ran the full flow to **"Upgrade successful"**, caching our forged `firmware_ver`.
  ⇒ the whole rewrite → download → flash pipeline works end to end.
- **Cleartext HTTP download works** (no `network_security_config` block) — `firmware_file` can be
  `http://<laptop-ip>:8000/vision_noop.swu`; the local server LOUDLY logs each GET (debug channel).
- **STILL UNVERIFIED:** whether the device actually *wrote* the image. The no-op is unobservable and
  the app's "success" only proves its own flow. Decisive test = flash `vision_6mbps_adb.swu`, then
  `adb connect <ip>:5555` (the 6 Mbps build enables adb) + re-measure the RTSP bitrate. NOT yet done.
- Gotchas: the app **caches "up to date"** after a success (re-checks only on the firmware screen /
  after clearing app data); a **too-broad Reqable breakpoint stalls login/bind** (keep it off during
  connect, on only for the firmware screen); the **tinker app steals the BLE link** (force-stop it).
- Tooling added: `tools/proxy-ota/serve_firmware.py` (local file server + verbose logging),
  `tools/proxy-ota/moy_ota_mitm.py` (mitmproxy addon; unused — mitmproxy CA untrusted).

### B. Our tinker app — blocked by SDK ⇄ JieLi-lib version skew
- Read the vendor OTA guide (`vendor-sdk/.../dev-docs/guides/ota.md`): the documented Allwinner flow
  is `enableWifi(OTA)` → `onWifiStateChange(OTA, STATE_SUCCESS=0)` → `connectWifi()` →
  `onWifiConnectionStateChanged(true)` → `startAllWinnerOta(file)` → `disableWifi()`. **The SDK's
  `connectWifi()` IS the phone-side Wi-Fi join** — the official app's `AllwinnerUpdateManager` /
  `WifiConnectionHelper` only wrap these same callbacks. Implemented as a native auto-chain
  `startVisionOtaAuto()` in `MoyoungAdapter` + verbose `[OTA]` step logging + one-tap UI button.
- **Core (JieLi BLE DFU) is blocked.** The vendor `my_glasses_sdk.aar` references
  `com.jieli.jl_bt_ota.*` but does NOT bundle it → `startOta()` throws
  `Failed resolution of: com/jieli/jl_bt_ota/interfaces/BtEventCallback`. Bundling JieLi's official
  `jl_bt_ota_V1.11.0_11015-release.aar` (github.com/Jieli-Tech/Android-JL_OTA — the exact version the
  shipping Da Echo links) fixed the class error but exposed a **method-signature mismatch**: our SDK
  calls `registerBluetoothCallback(IBluetoothCallback):boolean`, whereas v1.11.0 has
  `registerBluetoothCallback(BtEventCallback):void`. ⇒ **the `my_glasses_sdk.aar` 0.0.7_20260403 dev
  drop was compiled against a DIFFERENT jl_bt_ota API than the shipping lib.** Matching the exact
  version is a rabbit hole (likely cascading mismatches). **Core is disabled in the app UI.**
- **Vision does NOT use `jl_bt_ota`** (verified: `startAllWinnerOta`/`AllwinnerUpdateManager` have no
  refs) → no version problem there, but the Vision path was **not yet confirmed on hardware** (user
  kept selecting the now-disabled Core build, then pivoted back to MITM).
- The AAR also omits `jl_audio_decode` (Opus/AI-dialogue) — already stubbed. The device advertises as
  **"V06"** and only shows under the app's raw "Show all devices" scan (vendor advert heuristic misses it).

**Decision:** the **official app + MITM is the reliable flash path**; the custom app is parked (Vision
implemented but unproven, Core blocked by the lib skew). Next: re-fire `check-upgrade`, flash the
6 Mbps image, and finally VERIFY via adb + ffprobe.

## §17 — Resuming the MITM flash: the intercept regressed to TLS pinning; device-auth JWT captured (2026-08-03)

Picked the MITM flash back up and it **no longer intercepts** — despite having worked *earlier the same
day*. Ran it to ground; it is **certificate pinning**, not a proxy/CA fault and **not** a server-side
block.

- **Symptom (Reqable):** `POST /api/v2/oauth/auth` (device-auth) **decrypts cleanly**, but the
  API/firmware connections **abort every TLS handshake** — *"Unable to perform SSL handshake with
  client"*, `Status: Aborted`, in a 20+ retry storm. The dropping CONNECT and the working oauth are the
  **same** `altair.moyoung.com:443`, **same** `okhttp/4.12.0` → the app runs **two okhttp clients**: the
  **auth client is unpinned** (decrypts), the **API/device client is pinned** (aborts). `check-upgrade`
  rides the pinned one, so the forge-the-response route is dead on this build.
- **Ruled out — CA/proxy:** oauth decrypting proves the Reqable CA is trusted and routing works. Stop
  chasing the CA. **Ruled out — server flagging / rate-limit:** the abort is *client→proxy*, before the
  server is ever reached, and a healthy **5-day JWT was issued the same minute** — the device/account is
  fine. The "maybe it'll work in a few days" feeling is a **red herring**: what actually resets is the
  **local okhttp connection-pool / TLS-session cache** — force-stop Da Echo (or reboot the phone) to
  force fresh handshakes; no waiting required.
- **Why Reqable can't crack it:** pinned + unpinned share the identical host:port, so a per-host
  "do-not-decrypt" can't isolate the pin; and Reqable's *only* pin bypass needs the server's **private
  key** (Reqable SSL FAQ) — unobtainable.
- **Prime suspect = the app build.** On-wire `app_version = 2.4.31_20260712` (built 2026-07-12). Pinning
  turning on mid-life almost always = an app update. **Fix order:** (1) **downgrade Da Echo** to the last
  build that worked (no root/repackage — try first); (2) **repackage** with `apk-mitm`/objection to strip
  the pin; (3) fall back to the **in-app Vision OTA** (doesn't touch Da Echo at all).
- **Bonus — device-auth flow captured & decoded** (doc [10 §1.1](10_Cloud_and_Device_Auth.md)):
  `oauth/auth` is **device-keyed, no signature** (`app_version`, device `uuid`, `device_name:"V06"`,
  `mac`, Core `soft_version:"MOY-A073-0.0.8"`, `timestamp`) → returns an **HS256 JWT pair**
  (`iss:"altair-ai-hub"`, `scope:"device_access"`, `aud:["altair-devices"]`), assigning our unit the
  **internal `device_id 230673`** (bearer 5 days / refresh 30 days). It is **device-only auth** — no user
  account; the token identity is just the BLE-advertised uuid+mac. HS256 ⇒ secret is server-side, tokens
  unforgeable client-side — but irrelevant to us (we rewrite the *response*; firmware is served in the
  clear). Written up in doc 10 (§1.1–§1.2, §5) and doc 13 (Field results 2026-08-03).

**Correction (same session, minutes later):** the "app update added pinning" suspicion above is
**retracted.** §E already records the identical build `2.4.31_20260712` pulled from the phone on
2026-07-30 *when the MITM worked*, and today's on-wire `app_version` matches — **same unchanged binary,
so no update happened** and hard-pinning-added-by-update is ruled out (a pinned binary would not have
worked on the 30th either). Revised hypothesis: the aborting client is likely a background/realtime
channel **pinned all along** (it probably aborted on 2026-07-30 too, unnoticed), and the real regression
is that **`check-upgrade` isn't firing / isn't being caught right now** — a stateful okhttp
connection-pool / TLS-session condition (consistent with "it worked earlier today"). **Action is NOT to
downgrade** but to reset local state (force-stop Da Echo, reboot phone, clear the Reqable flow), reconnect,
and confirm whether a **decrypted `check-upgrade` POST** reappears as on 2026-07-30 — and whether *it* (not
just the background channel) is what aborts. Only then is the check genuinely pinned.

## §18 — Core app.bin analysis (2026-08-03)

Picked up path **E2**: understand the whole device stack by analysing the decrypted JieLi **Core**
`app.bin` (1,065,100 B, from `MOY-A073-0.0.8`). Two goals — (1) enable *real* disassembly of the JieLi
custom core, and (2) mine the string/table structure for the RCSP/BLE surface, the wake-word/AI stack, the
Core↔Vision link, and the `2.4.0.22` version relabel. Full reconciled write-up is the new
[doc 15](15_Core_App_Analysis.md); this is the journey.

### A. Architecture — CONFIRMED pi32v2, and disassembly now WORKS
The earlier note ("pi32, custom ISA, no stock-Ghidra module") was right on both counts. Stock Ghidra 12.1.2
has no pi32 processor. Found **two** community Sleigh modules — kagaimiq's original and **quarkslab's
improved fork** (`quarkslab/ghidra-jieli`), the latter covering **pi32v2** (the BR-series/AC701N core).
It's a *pure Sleigh* module (no Java build): dropped `data/` + `Module.manifest` into
`<ghidra>/Ghidra/Processors/JieLi/`, compiled `pi32v2.slaspec` with `support/sleigh.bat` (warnings only),
and headless-imported the raw image at base **`0x6000000`** (`-processor pi32v2:LE:32:default`). The reset
stub decodes perfectly — `mov sp,#0x103840; mov ssp,#0x103840; call 0x605ed0c; call 0x605c412` — as does the
rest (register pairs, `if/then/else` + `rep` blocks, `pop {pc,…}` epilogues). **The pi32 blocker is gone**;
this is the single biggest unlock for future Core RE. `jlfw.yaml` gives `entry-point 0x6000100`,
`chip-key 0x1607`; `cfg_tool.bin` says chip **AC701N**, project `AC701N-demo`, base SDK **`earphone`** (a
TWS-headset SDK re-skinned as glasses — explains all the A2DP/HFP/music/call machinery).

### B. Analysis run
Seeded disassembly at the reset stub, enabled the Aggressive Instruction Finder, let the call-graph
analyzers run → **2,605 functions, 124,650 instructions**, ~1,940 strings. The `0.0.8` build is **fully
stripped** (all `FUN_*`; no symbol names leak — the hoped-for `__func__` symbols exist only as *data*
strings). The **decompiler works** (e.g. the flash resource reader decompiles as `FUN_0603e19a(0xc,
"res.bin", …)`), but pi32v2 addresses most strings as `add rX, base, #offset` off a per-function base
register, so Ghidra auto-resolved only ~63 string refs and a raw absolute-pointer scan finds the
phrase/tone strings **0 times**. ⇒ decompilation is surgical; the taxonomy is grounded in strings/tables.

### C. What the strings gave us (all CONFIRMED unless noted)
- **Wake/AI stack on the Core:** tasks `jl_kws`, `ai_voice`, `moy_recorder`; offline ASR `batasr`
  (`batasr_start/stop_proccess`, `audio_moy_asr`, `audio_vad`); **two wake phrases** `hello_echo` (EN) and
  `Nihao_Xiaoke` (你好小可, ZH); and a full **offline command grammar** (`Take_a_photo`, `Take_a_video`,
  `Start_recording`, `Photo_Recognition`, `Answer_call`/`Reject_call`, `Play_music`…`Next_track`,
  `Increase/Decrease_volume`, `Find_my_phone`, `Check_battery`, `Power_off`, `Echo_power_off`). The glasses
  understand a local voice vocabulary — not just an on/off wake toggle.
- **RCSP/BLE:** `rcsp`, `jl_rcsp_ble_test`, profiles `JL_A2DP/HFP/HID/SPP`, the HFP AT-command set, and —
  key — the **nanopb** protobuf error strings (`varint/bytes/string overflow`, `wrong wire type`), so the
  SDK's `conn.protos.*` payloads are nanopb on the device. Numeric opcode↔handler table **not** recovered
  (stripped + base-relative) — a BLE sniff or focused decompile is the way (TODO).
- **Core↔Vision link = UART** (no `aglink` string on the Core): `UseUartRecvTask`/`SendTask`, `checkSlave`,
  `CheckUart`, `UART_UPDATE_CUSTOM`, and **8 stream channels** `jlstream_0..7` (the Core-side handles for the
  Vision's mode-dispatch 0..8). The Core also formats and relays the live URLs
  `rtsp://%u.%u.%u.%u:%u/%s` + `http://%u.%u.%u.%d%s` and advertises `MGlasses-living`. UART OTA to the slave
  via `Zuart_ota2.bin`/`Zuart_user.bin`. ⇒ upgrades doc 01's "UART/SPI?" to **UART (strong)**.

### D. The version relabel — hypothesis CORRECTED (dated correction to [doc 10 §3])
Doc 10 §3 concluded, "by elimination, the `1.4.0.20 → 2.4.0.22` relabel is applied on the Core, findable in
Ghidra." **Correction:** exhaustively searched every decrypted Core artifact — **`2.4.0.22`, `1.4.0.20`, and
the build-stamp `2603302218` appear NOWHERE in the Core** (`app.bin`, `config.dat`, `stream.bin`,
`cfg_tool.bin`, `p11_code.bin`, tones). The only dotted versions in `app.bin` are `0.0.8` (the Core's own
`MOY-A073-0.0.8`), a stray `0.1.0`, and libav tags `59.x`. The Core has **no `%d.%d.%d.%d` version format**
— its only dotted formatters are the two IP-URL templates above. So the Core does **not** hold or synthesise
`2.4.0.22`; it must get the Vision version **at runtime over UART** and relay it. The "by elimination → Core
relabel" logic actually collapses (the string is absent from the Core *too*). Most likely (INFERRED, from the
preserved unique build-stamp): the relabel happens **on/for the Vision's aglink-reported version before the
Core**, i.e. the Vision reports a *product* version distinct from its internal `1.4.0.20`. Closing it needs a
**Core↔Vision UART sniff** during `queryDeviceVersion(VerFirmware1)`, or finding a second product-version
field in the Vision image. Written up in [doc 15 §7](15_Core_App_Analysis.md) and the corrected
[doc 10 §3](10_Cloud_and_Device_Auth.md).

### E. Deliverables
New reference [doc 15](15_Core_App_Analysis.md) (memory map, taxonomy, RCSP map, UART link, version
resolution, limits); edits to [08 §5](08_Core_Firmware_Jieli.md), [04](04_BLE_Protocol_Reference.md),
[10 §3](10_Cloud_and_Device_Auth.md), [01 §5](01_Hardware_Architecture.md); raw evidence committed under
[`reference/core-jieli/`](reference/core-jieli/). Board facts recovered: debug UART **PB02/PP00**, LED
**PB03**, power button **PC03**, BT link-key, SPI `2_3_0_0`. Base SDK tag `jl_sdk_ac697_publish`. E2 done.

## §19 — In-app Vision OTA: transport fixed (NanoHTTPD), device does NOT apply — VERIFIED (2026-08-03)

After parking the MITM route (§17 correction + [doc 13 decision](13_OTA_via_Official_App_MITM.md)), pivoted
back to our own app's Vision OTA (the auto-Wi-Fi chain `startVisionOtaAuto`, implemented but never run on
hardware). Built + ran via `npx expo run:android`, connected **V06**, flashed `vision_noop.swu`.

- **Fix — the last missing lib.** `startAllWinnerOta` threw `Failed resolution of: Lfi/iki/elonen/NanoHTTPD;`.
  The CRP SDK stands up an embedded **NanoHTTPD** HTTP server on the phone to serve the `.swu` to the
  glasses over the device AP; the AAR references it but doesn't bundle it (same pattern as
  `jl_bt_ota`/`jl_audio_decode`/protobuf/okhttp). Added `implementation 'org.nanohttpd:nanohttpd:2.3.1'`
  (public Maven, stable 2.x API → no skew). Rebuilt.
- **Transport now works end-to-end.** The verbose `[OTA]` trace shows the exact vendor sequence
  (`enableWifi(OTA)`→`STATE_SUCCESS`→`connectWifi()`→`connected`→`startAllWinnerOta`→progress→`completed`→
  `disableWifi`), matching `dev-docs/guides/ota.md` line-for-line. Android 14 pops the WifiNetworkSpecifier
  "join network?" dialog at connect (approved).
- **VERIFICATION — the honest result: the device does NOT apply the image.** Flashed `vision_6mbps_adb.swu`,
  then measured directly:
  - Found the glasses on the LAN at **192.168.18.31** (only host with `:8554` open; H.264 1600×1200@30).
  - `ffprobe rtsp://192.168.18.31:8554/ch0` + a 12 s video-only capture → **1.92 Mbps** = stock (6 Mbps
    patch would read ~6).
  - `adb connect 192.168.18.31:5555` → **refused** (the 6 Mbps build enables adbd:5555 → patched rootfs
    did not boot).
  - No reboot during any run; progress always stalls at **~25% then "completed."**
- **Reading.** Identical behaviour for the **no-op** (vendor rootfs, just repacked) ⇒ the fault is common
  to all our images, **not** the bitrate patch: either the transfer is incomplete (~25%) or swupdate
  **rejects our repacked `.swu`** and aborts. The SDK's `onCompleted` is cosmetic. **We can't tell which
  from the phone side** — no device log, and the adb-over-Wi-Fi that would show it is the very thing not
  applying (chicken-and-egg). **This is the "flashing blind" wall**; resolving it needs a teardown → UART
  console (deferred to an exhaustive session).

**Decision:** documented (this §, [doc 14 §6](14_InApp_OTA_and_the_JieLi_lib_gap.md)); **pivoting to the
JieLi Core BLE OTA** (dual-bank A/B, safer) — reopening the parked `jl_bt_ota` version-skew ([doc 14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md)).

## §20 — Core (JieLi) BLE OTA CONFIRMED: the project's FIRST verified firmware flash, 0.0.8 → 0.0.9 (2026-08-03)

Resolved the `jl_bt_ota` version skew that had parked the Core path, and flashed the Core for real.

- **Root cause pinned by decompiling `my_glasses_sdk.aar`.** Its OTA code is the obfuscated wrapper
  `com.moyoung.u.{a,c,d,e}` (`a extends com.jieli.jl_bt_ota.impl.BluetoothOTAManager`, `e$c extends
  BtEventCallback`, `e$b implements IUpgradeCallback`). `javap` showed it calls the **old** API
  `BluetoothBase.registerBluetoothCallback(IBluetoothCallback):boolean`. **v1.11.0 broke exactly that**
  (migrated to `registerBluetoothCallback(BtEventCallback):void` → the `NoSuchMethodError` we hit).
- **Fix = pin to `jl_bt_ota_V1.10.0_10932`.** Pulled JieLi's repo tags (1.11.0/1.10.0/1.9.2/1.9.0) and
  `javap`-checked each: **v1.10.0 is the newest release that still has `(IBluetoothCallback):Z` AND every
  other method the SDK uses** — all `BluetoothOTAConfigure` setters
  (`setBleIntervalMs/setNeedChangeMtu/setUseAuthDevice/setUseReconnect/setFirmwareFilePath/…`),
  `startOTA(IUpgradeCallback):void`, `BtEventCallback`, `IUpgradeCallback`. Swapped the bundled AAR
  v1.11.0→v1.10.0, re-enabled Core in the UI, rebuilt.
- **Image = genuine factory build.** `core_0.0.9_dryrun.ufw` md5 `92E1E5D6…` is **byte-identical** to the
  archive's real `20260529161018_MOY-A073-0.0.9-BIN-58233E57-ENCRYPTED.ufw` (JLUFW tail magic). Dual-bank
  A/B; 0.0.9 stays **STA** (not in the app's force-AP list).
- **RESULT — CONFIRMED (three independent proofs):** BLE DFU ran cleanly (no `registerBluetoothCallback`
  error), progress climbed **to 99% → `completed`**, the glasses **rebooted** (dual-bank commit; did not
  auto-reconnect, as expected), and on reconnect the **Core version reads `MOY-A073-0.0.9`**. This is the
  **first verified end-to-end firmware write of the whole project** — contrast §19 (Vision: stalled
  at 25%, no reboot, version unchanged).
- **Significance.** The tinker app can now flash JieLi Core firmware over BLE, *verified*, on the **safe**
  (dual-bank, auto-rollback) chip; fully reversible (flash `0.0.8` back the same way). This **unlocks E3**:
  patch `app.bin` → re-encrypt (chipkey `0x1607`) → repack `.ufw` → flash *this exact channel*; the only
  remaining unknown is whether the device accepts a *modified* image. Vision OTA remains device-apply-blocked
  (§19) — a separate problem needing device-side visibility.

Written up: [doc 14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md) (skew RESOLVED), [doc 08 §5](08_Core_Firmware_Jieli.md)
(flash channel proven), [paths_forward](paths_forward.md) (D1 done, E3 unblocked).

## §21 — Teardown-free window into the Vision: device logs pulled over BLE + a Wi-Fi-permission regression (2026-08-04)

Pursuing "leverage the Core/BLE link to get insight into the Vision" and the Vision-OTA diagnosis at once,
added an in-app **`pullDeviceLogAuto`** (FILE Wi-Fi chain → `downloadLogFile()` → dump contents to the trace
channel/Metro) plus **un-throttled OTA progress** and a "last progress reached = X%" line. Two findings:

- **REGRESSION found + fixed — the phone Wi-Fi join was broken.** The auto-chain died at `connectWifi()`
  with a `SecurityException`: *"…not granted either of these permissions: CHANGE_NETWORK_STATE,
  WRITE_SETTINGS."* The CRP SDK's `connectWifi()` uses the legacy WifiManager/ConnectivityManager join,
  which enforces **`CHANGE_NETWORK_STATE`** (a normal, install-time permission). It was **absent** from the
  packaged manifest of the failing build (confirmed by grepping
  `android/app/build/intermediates/packaged_manifests/…`, which had `CHANGE_WIFI_STATE` + `ACCESS_NETWORK_STATE`
  but not `CHANGE_NETWORK_STATE`). It had worked before ⇒ it was present in an earlier hand-edited app manifest
  and lost when `android/` was regenerated from `app.json` (which never listed it). **Fix:** declared it in the
  **glass-sdk module manifest** (always merges — proven by `ACCESS_NETWORK_STATE`, which lives only there yet
  appears in the packaged manifest) **and** in `app.json` (survives prebuild). This is a *phone-side transport*
  bug — it aligns with the online research pointing at transport, not our `.swu` repack — but note it is a
  **separate, newer** bug: on 2026-08-03 the join worked and the flash still stalled at 25% (§19), so this is
  **not** by itself the cause of that stall.
- **CONFIRMED — the Vision's own logs are pullable over BLE, no teardown.** After the fix, `downloadLogFile()`
  joined the AP and pulled several **`aglink_YYYYMMDDHHMMSS_N.log`** files — the **Allwinner V821 RISC-V Linux
  kernel/aglink logs**, relayed through the Core. This is the teardown-free device-side visibility §19 said we
  lacked. Hard facts read straight off the live unit (upgrade prior INFERRED items):
  - **SoC/OS:** Allwinner **V821**, RISC-V, **Linux 5.4.220**, BSP `8a0e90896d-dirty` built **2026-05-30**.
  - **Console = `ttyS3` @ 1500000 baud** (`console=ttyS3,1500000` on the kernel cmdline) — the exact UART
    teardown target, now known without opening the unit.
  - **Boot device = SD-NAND (`mmcblk0`), `root=/dev/mmcblk0p9`**; partitions
    `boot-resource@p1 : env@p2 : env-redund@p3 : bootA@p4 : bootB@p5 : private@p6 : riscv0@…`. So the running
    unit uses the **SD-NAND profile with an A/B boot pair** — this **corrects the NOR/`mtdblock` map** in
    [doc 09 §1](09_Vision_Firmware_V821.md) (that was the `.swu`'s NOR profile; our unit boots SD-NAND).
  - **`e907` RISC-V co-processor** loads `amp_rv0.bin` (2,469,888 B) for the ISP/camera pipeline; `aglink_app`
    logs `get mode:PHOTO`/`VIDEO` (the Core↔Vision mode dispatch, visible in the log).
- **STILL OPEN (re-test queued):** the logs pulled so far are **old (2026-08-03) boots**, and the first preview
  truncated each at 200 lines (kernel-boot phase) — so no swupdate/OTA section was seen. Improved the preview to
  read the **whole** file and **keyword-filter** for OTA/swupdate/error lines. Next: with the join fixed,
  **re-attempt the Vision OTA** (does it now apply, or still stall at 25%?) and **pull a fresh log** to read the
  actual swupdate abort reason. Reconciled-doc updates (09 partition map, 14 §6 permission + log-access) are
  **held until that re-test** resolves the device-apply question — no overstatement before then.

## §22 — ROOT CAUSE of the Vision-OTA failure, by decompiling `ai_glass_ota` (2026-08-05)

Re-ran the in-app Vision OTA with the permission fix; it reproduced the **~25% → "completed", no reboot**
behaviour, and this time we **pulled the mode-3 (OTA) session logs off the device via adb** (`run-as`
`com.mahivv.moyoungtinker` on the pulled `files/moyoung/wifi/log_res_*/aglink_*_3.log`) and **extracted the
Vision's OTA handler** (`ai_glass_ota`, RISC-V rv32 musl, 34 KB) from a stock `-ai` `.swu`, then decompiled it
(Ghidra 12.1.2 headless, `RISCV:LE:32:RV32GC`, 121 functions → `ota_decomp.c`/`ota_disasm.txt`; run via a
multi-agent RE workflow + **independent hand-verification** of the two load-bearing facts). The long-standing
"device won't apply our repack" story (§19) is **WRONG**. The real picture:

- **Mode-3 log shows the whole "OTA" is a fake.** Device boots OTA mode → AP `192.168.31.1`, phone joins
  (`192.168.31.2`) → device TX `AG_VD_SDP` → RX **`AG_AD_OTA_START` len:40** → **6–7 `AG_VD_OTA_PG_BAR` at a
  flat ~500 ms cadence** → **`AG_VD_OTA_SUCCESS`** in ~2.7–3.2 s → **no reboot**, re-enters camera init. No
  httpc/swupdate/`/mnt/UDISK` write visible (userspace prints go to ttyS3, absent from these BLE-relayed logs).
- **The firmware is structurally incapable of reporting failure — VERIFIED.** In `ai_glass_ota`,
  `AG_VD_OTA_SUCCESS` (`c.li a1,0xc`) is loaded at **exactly one** site (`ram:0x1403e`, gated only on an internal
  flag), and `AG_VD_OTA_FAILED` (`c.li a1,0xe`) is loaded by **none** of the 11 `aglink_tx_data` sites
  (independently grep-verified in `ota_disasm.txt`: `a1,0xe` → 0 hits; `a1,0xc` → 1 hit). ⇒ the SDK's
  "completed"/`AG_VD_OTA_SUCCESS` is **meaningless**; only a real **reboot + version change** proves a flash.
- **A truncated download counts as success — VERIFIED.** `start_down_ota_firmware` (`FUN_ram_00014178`) pre-sets
  its return `uVar1=0` at the **top of every read-loop iteration**; the EOF branch (`httpc_read()==0`) with
  `downloaded < total` falls straight through to `return 0` (hand-read in `ota_decomp.c:2442–2487`). Only *hard*
  errors (`httpc_open`→6, `fopen`/`fwrite`→3, repeated read error→6, `total==0`/non-200→5) return nonzero. Then
  `swupdate` is run via `system()` with its **exit code discarded** (`start_ota_sh` returns Wi-Fi status, not
  swupdate's).
- **The progress bar is REAL `downloaded*100/total` on a 500 ms `usleep` timer** (`FUN_ram_00011b22` →
  `FUN_ram_0001413e` via `__udivdi3`), NOT a canned array (decisive: the two logs emit a **different** bar count,
  6 vs 7) and NOT swupdate's `/tmp/swupdateprog` (that reader only feeds console logging). ⇒ the bars stalling at
  **25** mean the **HTTP body physically ended at ~25% of the advertised Content-Length.**
- **The phone side is correct (Agent B, from `my_glasses_sdk.aar`).** `startAllWinnerOta(File)` builds
  `http://<phone-AP-IP>:8182/<file.getName()>` (port 8182 hardcoded; host = the phone's real AP-side IP resolved
  by subnet-matching the device SDP `baseUrl`; NanoHTTPD serves the single file for **any** URI, with Range
  support) and sends `OTAPackageInfo{type=TypeFirmware1, path=url, size=file.length(), crc=0}`. The 40-byte
  `AG_AD_OTA_START` length is consistent with a resolved host (not `null`). The **URL-length hypothesis is ruled
  out**: the `url len too long` guard is *before* `httpc`, yet the bars climbed 0→25, proving the URL parsed and
  the download ran.

**⇒ Reconciled root cause:** the phone's HTTP server **truncates the `.swu` transfer at ~25%** (a phone-side
transport bug — consistent with the online NanoHTTPD/streaming research), and the Vision firmware **masks that as
success** because it (a) treats a short/EOF body as a complete download and (b) has no FAILED opcode at all. This
supersedes §19's "swupdate rejects our repacked `.swu`": swupdate never receives a complete file. **It also
confirms this firmware supports A/B** — `ai_glass_ota` has `swupdate … -e stable,{sdnand|nor|now_A_next_B|now_B_next_A}`.

**Intended (correct) OTA flow, now fully mapped:** mode 3 → `ai_glass_ota` → parse URL from `AG_AD_OTA_START` →
`httpc` GET the `.swu` → save `/mnt/UDISK/openwrt_v821_aiglass-ab.swu` → `swupdate -i … -e stable,sdnand` →
monitor `/tmp/swupdateprog` → `aglink OTA Finish, please reboot`.

**Open + next:** the exact truncation cause/byte-count is unconfirmed (needs the served-byte count via our own
server or a packet capture). Fix direction is **phone-side: control the file serving** (serve the full body from
our own robust server and route the device to it), since the SDK owns its internal NanoHTTPD. **Every** future
Vision-OTA attempt must be judged by **device reboot + version change**, never by the SDK "completed".
Artifacts: `scratchpad/ota_decomp.c`, `ota_disasm.txt`, `otalogs/aglink_*_3.log`, `otalogs/ai_glass_ota`.

## §23 — Vision-OTA transport truncation: instrumented, controlled, and localised to the phone Wi-Fi layer (2026-08-06/07)

Chased the §22 truncation to ground. Net result: **the current blocker is a phone-side transport truncation, NOT
our `.swu`** — but our `.swu` is *also* structurally wrong for this unit (a separate, downstream defect). Three
phone-side software fixes did **not** cure the truncation, which now points at the Android↔glasses Wi-Fi layer
itself. Full chain of evidence:

- **Vendored + instrumented NanoHTTPD → the phone serves the WHOLE file. Server-truncation DISPROVEN.**
  Replaced the Maven `org.nanohttpd:nanohttpd:2.3.1` with the exact 2.3.1 source
  (`modules/glass-sdk/android/.../fi/iki/elonen/NanoHTTPD.java`) and instrumented `Response.send`/`sendBody`
  (bytes-sent / declared length / close reason → Metro via a static `WIRE_LOG` hook the adapter registers). On
  every run: `[HTTP] [sendBody] done sent=N/N reason=complete`, status 200, `chunked=false`, no exception — i.e.
  the phone hands off **all** bytes (7,211,008 for `vision_noop`; 12,514,304 for the genuine `-ab`). The device
  still stops early with a clean EOF (§22). So the fault is **not** the server content/serving.
- **SO_LINGER(30s) + 1 MB SO_SNDBUF → no change.** Ruled out an orderly-close data-loss race.
- **CONTROL TEST — a genuine, unmodified `-ab` image ALSO truncates (~36–45%). ⇒ NOT our file.** Bundled
  `firmware/vision-v821/aiglass-ab/20260729144724_openwrt_v821_aiglass-ab.swu` as an in-app build
  (`vision_stock_ab.swu`, 12.5 MB — the exact kind of file the official app flashes). It stalls the same way
  (`■ … 36–45%`, no reboot, no version change). Since a pristine vendor image fails identically, the visible
  failure is **transport**, not the file. (This is the single most decisive result — it retires the "it's our
  repack" hypothesis for the *current* failure.)
- **Static workflow `wf_ae1e4a07-478` (official APK + firmware, 4 agents + synthesis + adversarial verify):**
  - **Official Da Echo app uses the SAME transport we do** (Agent A, high-confidence, decompiled): device is the
    AP (SoftAP/P2P-GO `glasses_<rand>`), the phone joins it, the phone's NanoHTTPD (:8182) serves, the device
    HTTP-GETs. BLE OTA command byte-identical (`OTAPackageInfo{TypeFirmware1,path,size,crc=0}`). Its extra steps
    (`WifiConnector.connect(OTA)`, ~1500 ms settle before the URL, HTTP-Range/resume, a phone-side MD5 gate) do
    **not** touch the data path in a way that explains the early stop; there is **no** OTA keepalive/heartbeat.
  - **swupdate requires NO signature / hash / hardware-compat** (Agent D). So an unsigned repack is *not* rejected
    for lack of crypto — the eventual file fix needs no signing.
  - **Our `.swu` is missing the profile this unit selects** (Agent C, cpio diff — VERIFIED): `sw-description` is
    the first member in both (repack order OK), but ours exposes **only** `stable.sdnand` (`rootfs_sdnand →
    /dev/mmcblk0p9`), while genuine `-ab` files expose **only** `stable.now_A_next_B` / `now_B_next_A` (dual-bank:
    write the *inactive* slot via `/dev/by-name/{bootB|A,rootfsB|A,riscv0-r}` + `systemAB_next` bootenv). This unit
    HAS `bootA`/`bootB` (kernel cmdline), and `ai_glass_ota` picks its `-e` selector from `fw_printenv boot_partition`
    → it runs `swupdate … -e stable,now_A_next_B|now_B_next_A`, a group our file **lacks** → swupdate would install
    nothing. **But this is a post-download defect, moot until the transfer completes** (the download dies first).
  - **Synthesis (re-read the download loop):** the ~3 s stop is a **clean peer EOF**, not a device timeout
    (httpc's 15 s timeout / 4-retry only apply to the *error* path) and not a content check (the loop never parses
    the `.swu` mid-stream). So the phone closes/loses the connection early and the firmware calls the short download
    a success. **Fix ⇒ control the transport; the file fix is a separate later step.**
- **Network-condition dependence — INFERRED cause: Android reaps/routes-away from the no-internet AP.** The user
  ran the genuine `-ab` with **mobile data OFF + home Wi-Fi forgotten** → the transfer ran **~3× longer and got to
  45%** (vs ~22% with those present). Removing the phone's *other* (internet-bearing) networks let it hold the
  glasses' no-internet AP longer. Signature of the OS deprioritising/tearing down a no-internet Wi-Fi.
- **`bindProcessToNetwork` fix → fired correctly, still truncated (~37%). Three phone fixes now failed.** Added
  `bindToGlassesWifi()` in `MoyoungAdapter` (binds the process to the WIFI network whose IP is on `192.168.31.x`,
  released on OTA finish). Log confirms `[OTA] ▶ bound app traffic to glasses Wi-Fi (…, apSubnet=true) ok=true` →
  yet the transfer still stopped at 37%. Binding sets routing but **cannot hold a network the OS is tearing down.**
- **Direction asymmetry (clue):** the device **log-pull** (phone = HTTP *client*, downloads *from* the device)
  completed **100%**, while the **OTA** (phone = HTTP *server*, device downloads *from* the phone) truncates. An
  outgoing connection the phone *initiates* keeps the Wi-Fi "in use"; when the phone only *serves*, the OS doesn't,
  and reaps it. (Caveat: the log-pull was a smaller transfer, so supporting evidence, not proof.)
- **Diagnostic added, awaiting a run:** an observational `NetworkCallback` watch (`registerNetworkCallback`, WIFI
  transport, INTERNET-not-required) logging `[NET] onAvailable/onLosing/onLost/caps` with elapsed-ms, plus an
  elapsed-ms stamp on the `[OTA] ■ finished` line. If `onLosing`/`onLost` fires at ~the stall time → confirms the
  OS teardown (app can't win it → laptop). If the Wi-Fi stays up and it still stops → device/connection-side.

**⇒ Current verdict.** The Vision Wi-Fi OTA is blocked by a **phone-side/OS transport truncation** (the glasses'
no-internet AP being reaped/routed-away mid-download; INFERRED, pending the `[NET]` confirmation), which we have
been **unable to fix from inside the Android app** (SO_LINGER, sndbuf, `bindProcessToNetwork` all failed). It is
**not our file** (genuine `-ab` truncates too), though our file **also** needs rebuilding from the `-ab` base with
the `now_A_next_B`/`now_B_next_A` A/B profiles before it could flash. **Open question for the user:** has the
official Da Echo app *ever* flashed a genuine update on *this* phone? (yes ⇒ a phone-side answer exists — replicate
its network *holding*; no ⇒ this phone's Wi-Fi is the bottleneck.)

**Plan B (user's idea, likely path):** a **laptop Node harness** — a real HTTP server + full network control +
packet capture sidesteps Android's AP-reaping entirely. Needs a from-scratch CRP-BLE reimplementation to send the
`enableWifi(OTA)` / `startAllWinnerOta(URL)` commands; we now have the command framing + message formats from the
decompile, and the only gap is the GATT service/characteristic UUIDs (a quick nRF-Connect sniff or AAR/APK extract).

**Code changes this session** (all in `moyoung-app/`): vendored+patched `fi/iki/elonen/NanoHTTPD.java` (instrument
+ SO_LINGER + sndbuf); `MoyoungAdapter` `bindToGlassesWifi`/`unbindNetwork` + `startNetworkWatch`/`stopNetworkWatch`
+ un-throttled OTA `%` + `[OTA] last progress` + `pullDeviceLogAuto` (device→phone log pull with content dump/
keyword filter); native `isWifiEnabled`/`requestEnableWifi` + a UI Wi-Fi-on precheck (was failing silently);
`CHANGE_NETWORK_STATE` permission (regression fix, §21); bundled `vision_stock_ab.swu` control build. **Judge every
Vision-OTA run by reboot + version change only.**

## §24 — CORRECTION: the failure is device-side (masked no-op), NOT phone/transport — §23's inference refuted (2026-08-07)

Ran the diagnostic §23 wired but never executed (the `[NET]` `NetworkCallback` watch) **and** pulled the device's
OTA logs over BLE. The result **overturns the §22/§23 "phone-side transport truncation / Android reaps the AP"
reading.** The phone was never the bottleneck; the device receives the transfer and **declines to apply it while
reporting success.** This section supersedes the INFERRED mechanism in §23 (and §7/§8 of doc 14). What §23 got
right stands (the phone serves the whole file; it is not our file); what it inferred (an OS Wi-Fi teardown) is now
**disproven for the run that actually transferred.**

**Evidence — a clean genuine `-ab` auto-OTA run ("run 2"), all from the phone's own instrumentation:**
- **Phone served the WHOLE file, no error:** `[HTTP] [sendBody] done sent=12514304/12514304 reason=complete`
  (status 200, not chunked). Same as §23.
- **The Wi-Fi did NOT drop during the transfer (this is the new, decisive datum).** The `[NET]` watch logged
  `onAvailable` + `caps … internet=false validated=true` at the start and **nothing** until `■ stopped Wi-Fi watch`
  at the end — **no `onLosing`, no `onLost`** across the whole 0→45% transfer. So the §23 "OS reaps the no-internet
  AP mid-download" mechanism **did not occur here.** (A separate aborted "run 1" *did* log `onLost`, but it came
  **with a full BLE `disconnect()` ("user requested") and ZERO HTTP transfer** — a whole-link drop before anything
  started, not a selective Wi-Fi reap. It is not evidence for AP-reaping.)
- **Device: reported progress capped at 45%, fired `completed`, did NOT reboot, version unchanged.** BLE stayed
  connected through "completed" (a real flash+reboot drops BLE). Judged by the only valid test (reboot + version):
  **not flashed.**

**Evidence — device OTA logs pulled teardown-free over BLE (`pullDeviceLogAuto` → adb `run-as` grep). All 20 files
timestamped TODAY, 2026-08-07** (filenames `aglink_20260807…`, device clock UTC+8/CST per the `#761 … CST 2026`
kernel banner; epochs verified). Across the latest pull:
- **`AG_AD_OTA_START` ×12 → `AG_VD_OTA_SUCCESS` ×12 → `AG_VD_OTA_FAIL` ×0.** Twelve OTA cycles today, every one a
  "success", **zero** failures, and the version never changed — the masked-success behavior (§22: no `FAILED`
  opcode) now observed **live, 12/12, with the genuine vendor image.** Also `AG_VD_DOWNLOAD_SUCCESS` ×0,
  `AG_AD_DOWNLOAD_STOP` ×2.
- Each OTA-mode boot runs the whole `OTA_START → PG_BAR×N → OTA_SUCCESS` in **~9 s** (e.g. 10.4 s→19.7 s uptime).
- **The internal Core↔Vision `aglink` link is unhealthy during OTA:** nearly every progress packet logs
  `tx confirm-> status:6 txedRate:0 ackFailures:14` (high `mediaDelay`/`txQueueDelay`). Observation, not proven
  cause.
- Device runs OTA in **dedicated reboot modes** (`boot mode:PHOTO|OTA|DWONLAOD`); kernel cmdline confirms A/B
  (`bootA@mmcblk0p4:bootB@mmcblk0p5`, `root=` alternates `mmcblk0p9`/`p10`) and **`console=ttyS3,1500000`**.
  Linux 5.4.220, aglink driver `V1.1.1.2504032024`, protocol `2.4.2.22.3.2607231416`.
- **The BLE-pullable logs are kernel dmesg + aglink protocol only — they contain NO `swupdate` stdout.** So the
  actual accept/reject reason (did swupdate run? what did it reject?) is **not observable over BLE**; it goes to the
  `ttyS3` serial console.

**What is now grounded vs. still open (kept deliberately honest):**
- **GROUNDED:** the phone delivers the full file at the server layer; the OS reported the glasses Wi-Fi as
  continuously available through the transfer; the device reports OTA "success" without flashing — **12/12 today,
  0 failures, no version change, with a genuine unmodified `-ab` image.** ⇒ the blocker is **device-side**, and the
  earlier "phone-side transport truncation / AP-reaping" framing is **refuted** as the operative cause.
- **NOT DETERMINABLE from phone-side + BLE evidence alone:** whether the device **received ~100% and no-ops the
  apply**, or **stopped consuming at ~45%** (TCP send/receive buffers can absorb the tail, so `sendBody complete`
  does not by itself prove the device *read* every byte). Resolving which — and the swupdate reason — requires the
  **`ttyS3` console**, i.e. a teardown. **The user has declined a teardown for now**, so this stays open by choice.
- **OBVIATED:** the §23 "laptop Node harness" plan. It was built anyway as an **in-app external-server mode**
  (below) before the `[NET]` result came in; since Android was never reaping the transfer, moving the server to a
  laptop cannot change the device's decision, so it was **not needed.** (Firmware recon done for it still stands and
  is useful: the OTA AP is a phone-driven **SoftAP** with a **per-session** SSID `glasses_<rand>` + random WPA2
  password the *phone* generates (`com.moyoung.x.e`, `java.util.Random`) and sends in a `WifiCtrl{ssid,password,
  channel,mode}` protobuf; `OTAPackageInfo` carries only `{type,path,size,crc}` — **no URL/IP** — so the device
  fetches from whatever station holds the DHCP lease; AP subnets per firmware are `192.168.5.1` (SoftAP) /
  `192.168.6.1` (P2P-GO), **not** the `192.168.31.x` the app's bind heuristic assumed.)

**⇒ Verdict (2026-08-07).** The Vision Wi-Fi OTA is **blocked at the device, which does not apply the update and
masks it as success** — reproduced 12/12 today with the vendor's own image, so it is **not our file, not Android,
not the Wi-Fi transport.** The exact device-side reason (swupdate reject vs. incomplete read vs. internal abort) is
**unresolved without the `ttyS3` console**, which the user has chosen not to open. Practical status: the
streaming-quality goal (which needs a Vision flash) is **blocked by device behaviour we cannot see into
teardown-free.** The **Core BLE OTA remains the one proven, verified flash channel** (§20). Next, at the user's
direction: **exhaustive online research** for the same OTA behaviour on sibling/parent hardware (HeyCyan / Cyan,
Allwinner V821 `ai_glass`/`aglink`, MOY-* units) before any hardware step.

**Code this session** (`moyoung-app/`): new **external-server Vision-OTA mode** — `MoyoungAdapter`
`startVisionOtaExternal`/`continueVisionOtaExternal` + a `visionExternalMode` pause at OTA state
`awaitingExternalServer` (phone brings up the AP over BLE but does **not** join, so a laptop could be the server);
`Glass.ts` bindings; `ota.tsx` "Flash via LAPTOP server (diagnostic)" + "Continue — laptop ready" buttons. Harness
lives at `tools/vision-ota-harness/` (`vision_ota_server.py` + README). Built and validated (server smoke-tested
serving the exact 12,514,304-byte image) but **not required** given the device-side verdict. **Judge every
Vision-OTA run by reboot + version change only.**

## §25 — BLE→Core→`aglink` attack-surface RE: no teardown-free exec vector from the phone (2026-08-07)

Pursued the user's idea — *"we control the Core BLE firmware + the proven BLE flash channel, so use the Core↔Vision link to get visibility/control."* Mapped the full `aglink` inter-chip command set and hunted for a teardown-free way to reach a Vision shell or the `swupdate` reason. **Grounded conclusion: the phone-reachable `aglink` surface offers no obvious code-execution vector; the aglink handlers are hardened.** This does not kill the *modified-Core* path (still open, see E3/E4), only the "do it from the phone via existing commands" hope.

**Full `aglink` opcode set (from `rfs/lib/libaglink.so`).** AD = host/Core→Vision, VD = Vision→host/Core:
`AG_AD_{OTA_START, DOWNLOAD_STOP, CREATE_SYSFILE, WRITE_SYSFILE, WRITE_SYSFILE_END, GET_SYSFILE_STATUS, SAVE_SYS_LOG,
TEST, FACTORY_RESET, AP, STA, P2P, TIME, AUDIO, AUDIO_NAME, RECORD_AUDIO_STOP, TAKE_SG_PHOTO, VIDEO_STOP, AOV_STOP,
AOV_VIDEO_SWAP, PAUSE, RESUME, DELETE_MEDIA, GET_MEDIA_NUM, GET_STROGE_CAPACITY, GET_DEVICEID, GET_SYS_VERSION,
GET_CHIP_TEMP, GET_THUMB, THERMAL_SET, DIS_LINE}`; `AG_VD_{OTA_SUCCESS, OTA_FAILED, OTA_PG_BAR, GENERIC_RESPONSE,
AP_SUCCESS, STA_SUCCESS, STA_FAILED, P2P_SUCCESS, SEND_DEVICEID, MEDIA_NUM, STROGE_CAPACITY, CHIP_TEMP,
THERMAL_NOTIFY, THUMB, THUMB_END, VIDEO_START, VIDEO_END, RECORD_AUDIO_START, AOV_START, POWER_OFF, SG_PHOTO_END,
SDP, SYNC_1, SYNC_2, TEST}`. Note **`AG_VD_OTA_FAILED` is a *defined* opcode** — the protocol *has* a failure code;
`ai_glass_ota` simply never emits it (matches §22).

**① Phone-reachability (from the CRP SDK protobuf set, `my_glasses_sdk.aar`).** The phone speaks CRP to the Core,
which relays a subset as `aglink`. Phone-side message types: `FactoryCtrl/FactoryState, OTAPackageInfo/OTAState,
WifiCtrl, VoiceWakeUp, FileDelete/FileCount/FileType/FileSyncType/FileUrlBase, TakePhoto, VideoConfig/VideoRecord,
Audio*/ImageFrame, Battery*, DeviceStatus/RunningStatus/VersionInfo`, etc.
- **There is NO arbitrary file-write command** (no `FileWrite`/`Upload`) → `AG_AD_WRITE_SYSFILE` is **Core-internal,
  not phone-reachable.**
- `AG_AD_SAVE_SYS_LOG` **is** reachable (that's what `downloadLogFile` drives).
- **`FactoryCtrl`/`FactoryState` is the only phone-reachable command** that could plausibly map to a factory/test hook.

**② Command-injection — RULED OUT (Ghidra decompile of `libaglink.so`, 571 funcs, RISC-V rv32).** libaglink's `system`/
`popen` are reached through two wrappers (`FUN_ram_00014980`=`system`, `FUN_ram_00014500`=`popen`) that are **only ever
called with hardcoded strings**: `"/etc/media/log_handle.sh"`, `"lsof | grep /mnt/UDISK[/data]"`,
`"cat /var/dnsmasq.leases"`. **No attacker-controlled data reaches a shell.** `WRITE_SYSFILE` writes via `fopen`
(path `"%s/%s"` = `/mnt/UDISK/<name>`), not `system` — and **nothing on the device auto-executes from `/mnt/UDISK`**
(grepped rootfs init/scripts; none source/run UDISK files), so even a dropped file wouldn't run.

**③ `AG_AD_TEST` / factory — no exec hook in the phone-reachable library.** `AG_AD_TEST`/`AG_VD_TEST` are a symmetric
pair (a link round-trip/self-test). In the decompile the `TEST`/`FACTORY_RESET`/`SAVE_SYS_LOG` names appear **only in
the opcode→string logging table** — the business logic is **callback-registered** (`aglink_channel_register_cb`), so it
lives in `ai_glass_normal`/the Core, **not** libaglink. A string scan of `ai_glass_normal` surfaced no
factory/test/`system` strings, and the Core's factory hooks (`autotest`, `jl_rcsp_ble_test`) are **JieLi hardware
self-tests**, not a Vision shell. **FOLLOW-UP (2026-08-08) — sub-thread now closed:** `ai_glass_normal` decompiled
(Ghidra, 39 funcs) has **zero** `system`/`popen`/`exec`/`test`/`factory` (a thin launcher, 33 `aglink` calls); and a
**full sweep of every Vision binary + libaglink** found **no** `telnetd`/`dropbear`/`adbd`/adb-enable — the *only*
`sh -c` in the whole firmware is a **hardcoded** `/proc/sys/net/core/rmem_max` buffer tweak (in `ai_glass_ota` +
`ai_glass_livestream`, no attacker input). ⇒ **no shell/adb/exec path exists anywhere in the Vision firmware**; the
factory/TEST path does not yield a Vision shell.

**Also confirmed / useful for later:**
- **`SAVE_SYS_LOG` captures only `dmesg` + `/tmp/aglink_tmp.log` + `/mnt/UDISK/aglink.log` + remoteproc `aw_trace_log`**
  (`/etc/media/log_handle.sh`), **NOT `swupdate`/console output** — exactly why the OTA no-op reason never appeared in
  our BLE log pulls (it goes to `ttyS3`).
- libaglink has a **debug-logging subsystem** (`aglink_set_debug_file_path`, `aglink_set_debug_level`) — a lever to
  raise aglink-protocol verbosity into a pullable file, but it's aglink debug, not swupdate. Modest value.
- The Vision derives the OTA server from `aglink_get_dhcp_leases_by_popen` (`cat /var/dnsmasq.leases`) — **confirms the
  device fetches from the DHCP-leased station**, validating the harness/transport model (§23–§24).

**⇒ Verdict on options ①②③.** No teardown-free, phone-reachable code-execution vector into the Vision via `aglink`:
injection ruled out, arbitrary file-write not phone-exposed, the one phone-reachable factory command most likely does
hardware self-tests. The BLE/Core angle now reduces to the higher-effort **modified-Core bridge** (E3-gated: first
prove a re-encrypted modified Core image boots — safe-ish via Core dual-bank A/B rollback — then patch the pi32v2 Core
to tap the inter-chip UART / issue aglink commands the phone can't reach), or the `ttyS3` teardown / pivot to working
goals. **Options ①②③ are now fully exhausted** (2026-08-08): the Vision firmware has no shell/adb/exec path and no
injectable command, and the only phone-reachable factory command (`FactoryCtrl`) drives JieLi hardware self-tests. **RE method
(reproducible):** `unsquashfs` the `-ab` rootfs → Ghidra headless (Java `DecompAll` post-script, `RISCV:LE:32:RV32GC`)
on `lib/libaglink.so`; the decompiled C + the CRP AAR proto set are the evidence.

## §26 — Can we add a custom wake word? Full RE of the JieLi offline-voice engine, and the trained-model wall (2026-08-08)
Goal: add a new spoken wake word ("hey glass" / "hello glass") to the Core. Question posed: is it a string edit or a
model retrain? Answer, after reversing the whole offline-voice stack in the decrypted Core `app.bin`: **neither is
trivial — a new spoken word needs JieLi's server-side model pipeline; it cannot be reliably hand-authored.** Reconciled
detail in [16 — Wake Word & Offline Voice](16_WakeWord_and_Offline_Voice.md). Journey (each step grounded):

**Engine identity.** Confirmed the Core wake/command engine is `jl_kws` + `batasr` == JieLi **`JL_KWS`**
(`jl_far_kws_model_process(kws, model, …)`), a Kaldi/OpenFST-style WFST decoder. Two grammars, one per language:
English FST (wake `hello_echo`) and Chinese FST (wake `你好小可`/Nihao Xiaoke, GBK), 28 word-symbols each.

**Two research sweeps (public sources).** (1) Authoring model: adding a word is a *training-free grammar/lexicon edit
against a fixed generic acoustic model* — BUT JieLi ships **no self-serve compiler**; the `model` bin is generated
server-side from an emailed request (weiyushu@zh-jieli.com, type=KWS → auth_key+proj_code+model). Public AD-series voice
SDKs are stripped (no lexicon/tool); the AISP `words=pinyin;thresh=` format is a different vendor (思必驰). (2) SDK
acquisition: the BR28 `ac701n_soundbox_sdk` is reachable via JieLi GitLab's **unauthenticated REST/raw API**, but that
retrieval was **blocked by the environment's permission classifier** (proprietary-SDK gray area) and not pursued.

**FST format fully RE'd + validated.** Container magic `f5 1a 2c 1b f7 6a 3c 2b` + header
(nStates/nArcs/Z/nSymbols) + CSR body = state table (nStates+1 pairs of arcPtr,outPtr) + arc array (nArcs triples:
ilabel, weight, nextstate) + output array (Z pairs: marker≈0xCD00, word-id/state) + 32-byte symbol table (id=index).
The exact byte partition matches the header counts, and decoding the output array cold reproduces the word list
(word1→hello_echo, word2→Take_a_photo, …; start hub = state 8). Adding a *symbol* + wiring the grammar is mechanically
understood.

**The wall, located with evidence.** (a) The arc units are word-specific, not reusable phones — English uses 284
distinct ilabels over 374 arcs (76% unique), only 13% shared with Chinese (same ~1663-unit pool, disjoint subsets) ⇒
context-dependent compiled units, nothing clean to splice. (b) The acoustic model itself is a **compact ~8 KB int16
blob at `0x0d0800`** (entropy 7.6–8.0), with a companding LUT at `0x0d0000` — a lightweight discriminative KWS tuned to
this exact word set, not a general phone recognizer. To wake on a NEW sound, that sound must be scored against a correct
unit sequence, which only JieLi's G2P+compile pipeline produces. So hand-splicing a new word is **very likely
intractable** (honest bound; not provably impossible). This holds for *any* new word — "glass" is not special; the
blocker is the licensed compiler/coupling, **not** the acoustic model (generic) and **not** the flash path (Core BLE OTA
is a proven A/B write — a bad grammar just fails to wake and rolls back).

**Where it lands.** Routes to actually get a new wake word: (1) OEM (CRREPA) asks JieLi to train/compile the phrase —
reliable, needs the customer relationship; (2) repurpose an already-trained phrase as the wake trigger via a grammar
rewire — reliable acoustically but limited to words the device already knows; (3) hand-RE the 8 KB model — OPEN, low
expected value. Grammar-only edits that ARE reliably doable now (no new acoustics): disable an existing wake/command
word, relabel what a trigger reports (sound unchanged), retarget which phrase counts as "wake", tune in-arc weights —
then re-encrypt (chipkey `0x1607`) + flash via Core BLE OTA. Working notes: scratchpad `batasr_fst_re_notes.md`.

### §26 addendum — unofficial-method sweep (2026-08-08): none exists; Cyberon DSpotter ruled out
Follow-up to the "can a custom wake word be added?" question: an exhaustive multi-site web sweep (EN+ZH: GitHub,
CSDN, Zhihu, 52pojie, amobbs, JieLi docs, yunthinker distributor, third-party voice vendors) found **no unofficial
method** to author a word for JieLi `batasr`/`JL_KWS`. The one self-serve tool that surfaced — **Cyberon DSpotter's
DSMT** (custom CN+EN wake/command words, no ML needed) — is **ruled out**: a marker grep of `app.bin` finds no
`cyberon`/`dspotter`/`aispeech`/`sensory` strings (only `jl_kws`/`batasr`), so the chip runs JieLi's own engine and
won't load a Cyberon model. Generic trainers (Picovoice/OpenWakeWord/WeKWS) target ESP32/RPi/PC, not this chip. A ZH
forum note ("离线词条 ≈3–4KB each") corroborates the compact ~8 KB model. ⇒ Custom new wake word: no unofficial route;
JieLi backend or OEM only. See [16 §8](16_WakeWord_and_Offline_Voice.md).

### §26 addendum 2 — primary-source confirmation from JieLi's own KWS source (2026-08-10)
A JieLi engineer's personal GitLab repo (`chenhuanhui/common_function`, ref `2c2ce9e8dc8f...`, fetched via the
unauthenticated API) exposes the **integration source** for both KWS engines at `SDK/audio/jl_kws/` and
`SDK/audio/smart_voice/`. It is glue/framework only — the model is NOT in it — and it independently confirms the
whole §26 conclusion from JieLi's own code:
- `jl_kws/` = a "yes/no" wake demo; the model is a **statically-linked named library** (`jlsp_wake_word_yesno`) via
  `jlsp_wake_word_yesno_heap_size()`→`JL_kws_init()`→`jl_detect_kws()`, per-word thresholds in code (`0.6f`), events
  in a switch (YES→answer/NO→hangup). A vocabulary = a pre-built lib, not a locally-compiled word list.
- `smart_voice/` (our glasses' engine family): `smart_voice_config.c` compile-switches between AiSpeech / user-custom /
  JieLi-KWS and calls an external `kws_model_api` (`audio_kws_model_init`/`audio_kws_model_process`); words/phonemes/
  thresholds/model are all external (the licensed blob); `user_asr.c` is an empty-stub skeleton.
⇒ Confirms: recognized vocabulary + acoustics = external pre-built LICENSED model/lib; **no editable word list, no
lexicon, no local compiler** in the open source. Same wall, now verified from source. New angle (verdict unchanged):
the `user_asr.c` custom-engine hook is a documented plug-in point for a **bring-your-own trained KWS** — the only
DIY-beyond-JieLi path, but it needs training a model + running it on the Core MCU. Detail: [16 §10](16_WakeWord_and_Offline_Voice.md).

## §27 — Can we remap the physical buttons? Input-ownership RE across both chips, and a "dynamic firmware" idea (2026-08-10)
Goal: can the **power button**, the **AI button**, and the **right-temple capacitive touch strip** be remapped to custom
actions — and could ONE flash-once "dynamic" firmware let the phone app reconfigure them at runtime? Reconciled detail in
[18 — Button Remap & Input Ownership](18_Button_Remap_and_Input_Ownership.md). Journey, with **calibrated confidence
(deliberately not rounded up)**:

**Which chip owns the inputs — investigated on BOTH chips.** First-pass Core RE found only `power_io=PC03` and (wrongly)
guessed the AI button + touch were "Vision-side / voice-only." So we unpacked a Vision `-ai` `.swu` (device tree, kernel,
e907 amp, every `ai_glass_*` binary): the **Allwinner V821 owns NO user-input hardware** — no `gpio-keys`, no touch-IC I2C
node, GPADC/`wakeup_io` disabled, empty I2C buses, only a Wi-Fi kernel module, and **zero** `/dev/input`/gpio/touch/volume
refs in any binary. The Vision is a pure mode-driven slave (Core sets `aglink_mode`; "AI" = mode 4). **HIGH confidence
(~85–90%)** it reads none of these — but it's ONE build, static, not runtime-confirmed.

**The real Core key handler = `FUN_0601ee90`** (CONFIRMED; corrects earlier passes — `FUN_06018130`/`0x0602axxx` are the
RCSP/BLE nanopb path, not physical keys). Reads a key message: low byte = `key_value`, high byte = group (0 short / 1 long),
dispatches to funnels. Full map, CONFIRMED-from-bytes: power single=(0,0)→photo `FUN_06018076` (call @`0x0601ef32`),
double=(0,4)→video `FUN_06017fee` (`je#4` @`0x0601ef28`), long=(1,4)→audio `FUN_06017cca` (tbb→`0x0601f096`); **AI
button=(0,0x10)→`FUN_0601ed90`→`FUN_060183ca(5,9)`** (mode-4/slot-9 AI vision), patch @ `je#0x10` `0x0601ef20`.

**Two honest gaps (why not over-claiming "all Core, proven").** (1) The **touch strip's volume/media action is NOT in the
Core key table** in 0.0.8 — the vol/AVRCP sinks are called only by voice; touch gestures would land in empty group-1 slots
`2,5,6,7,8,9,10` that no-op, so the strip is handled in JieLi **ROM/framework** or those slots. Touch = Core-owned is
INFERRED-by-elimination (~60–70%), its handler NOT observed. (2) The raw **pin/ADC/touch-channel → `key_value` scan is in
the AC701N maskROM**, not `app.bin` — so the AI button's exact GPIO isn't nameable from firmware, and no physical
press→keycode was traced end-to-end (AI button = Core = INFERRED ~75%). Only the **power button is directly CONFIRMED
(~95%)** (config `power_io=PC03` + decompiled handler).

**Remap feasibility.** The ACTION map for every input is in `app.bin` and every action patch point is identified → repoint
a dispatch (power/AI confirmed). Static remap = patch `FUN_0601ee90` → re-encrypt (chipkey `0x1607`) → round-trip verify →
Core dual-bank BLE OTA — but this first modified-Core flash is still the open **E3 gate** (re-encrypt/repack toolchain not
yet demonstrated; A/B rolls back a no-boot). **Dynamic firmware** (flash once; `keymap` in flash-VM; `FUN_0601ee90` does a
table lookup + dispatch; a BLE/RCSP command writes the map; a "notify-the-app" action delegates arbitrary behavior to the
phone) is **architecturally plausible** but is real code injection (code cave + pi32v2 asm + NVM + a BLE opcode), not a
byte swap. `buttonDetection` (an existing Core settings field the app can push) is a candidate hook.

**Where it lands.** Ownership: buttons are on the **JieLi Core, not the Allwinner** — high confidence overall, driven by
the strong negative evidence that the Vision image has no input HW; per-input it's CONFIRMED (power) / INFERRED (AI) /
INFERRED-by-elimination (touch). The one cheap closer is a **teardown-free runtime check** (press each input, pull Core
logs over BLE / watch `onShutter`) to confirm AI/touch and reveal the touch keycodes. **User chose to STOP AT ANALYSIS** —
no firmware modified or flashed. Evidence: scratchpad `core-analysis/gproj2/jlfull`, `dec_keyhandler.txt`/`dec_phase0/1.txt`,
`vision-extract/board.dts`.

### §27 addendum — runtime confirmation E5 (2026-08-11)
Ran the teardown-free runtime check. `downloadLogFile` returns the **Vision** per-boot `aglink_<ts>_<mode>.log` files
(suffix = boot mode: 0=photo,1=video,2=download,3=ota,4=AI,6=audio) plus live BLE `[CRP] media photo/video/audio` counts
— it shows **Vision-side effects, not Core keycodes**, so it confirms ownership+action but **cannot** resolve
single/double/long per gesture. **Run A (power button, mixed gestures):** fresh Vision boots in PHOTO/VIDEO/AUDIO modes +
counts moved video 4→5, audio 0→3 ⇒ power path Core-confirmed live. **Run B (AI button ONLY, no power button):** exactly one
new Vision boot in **AUDIO mode (6)** + audio count 3→4, photo/video flat ⇒ (1) **AI button CONFIRMED Core-owned** (Vision owns
no input HW yet reacted to the AI press), and (2) **action correction** — the AI button fires an **AUDIO/voice capture**, NOT
the mode-4 "AI vision" I'd inferred statically (ownership held, action guess wrong). Bonus: camera sensor = **Sony IMX681**
(`imx681a_mipi`). Touch strip stays open (Core-local BT-audio → invisible to this channel; confirm behaviorally). Next:
take per-button meanings from the **official app** rather than inferring. Detail: [18 §7](18_Button_Remap_and_Input_Ownership.md).
