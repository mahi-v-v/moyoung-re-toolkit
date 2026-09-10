# 02 — Firmware & OTA

> This is the doc most relevant to "do whatever we want with the firmware." It maps the two
> update paths, the cloud that feeds them, and where the tinkering surface actually is.

> ## ✅ 2026-07-30 — this overview is now backed by hands-on results (see the deep-dive docs)
> The mechanisms below (two OTA paths, cloud endpoint, "arbitrary File" surfaces) are all confirmed,
> and the open questions this doc raised are largely answered in dedicated docs:
> - **How firmware is obtained & what exists** → [07 — Firmware Acquisition & Archive](07_Firmware_Acquisition.md)
>   (the `altair.moyoung.com` server had **directory listing on** — 4077 files pulled/deduped locally).
> - **Core `.ufw` internals & the encryption** → [08 — Core Firmware (JieLi AC701N)](08_Core_Firmware_Jieli.md)
>   — **decrypted in software**; the "does the device enforce more than md5" question for the Core is moot
>   (it's a plain JieLi LFSR + CRC, chipkey `0x1607`).
> - **Vision `.swu` internals & OTA safety** → [09 — Vision Firmware (Allwinner V821)](09_Vision_Firmware_V821.md)
>   — swupdate, **per-item md5, NO signature, NO hardware-compat gate**; in-place on the single-system `-ai`/NOR
>   profile — **but the live unit is dual-bank A/B** (SD-NAND, writes the *inactive* slot → rollback-safer; 09 §1).
>   Worst-case recovery is still FEL/USB → treat brick risk as real. So the "modify a byte and reflash" probe is
>   understood on paper; **do not run it without a UART/USB recovery net**.
> - **The cloud/auth path** → [10 — Cloud & Device Auth](10_Cloud_and_Device_Auth.md) (check-upgrade with no
>   auth; Allwinner device-license/activation; JWT).

## 1. Two firmware images, two update paths

Because the device is dual-chip ([01](01_Hardware_Architecture.md)), there are **two separate
firmware images** and the SDK exposes **two separate OTA mechanisms**:

| | Jieli OTA | Allwinner OTA |
|---|-----------|---------------|
| Chip | Jieli MCU (master) | Allwinner SoC (slave) |
| Transport | **BLE** (Jieli DFU) | **Wi-Fi** (device AP + HTTP) |
| SDK entry | `startOta(listener)` or `startOta(File, listener)` | `startAllWinnerOta(File, listener)` |
| Resume | reconnect + retry | `resumeAllWinnerOta(listener)` |
| Abort | `abortOta()` | `abortAllWinnerOta()` |
| Underlying lib | `com.jieli.jl_bt_ota` (bundled in AAR) | vendor's own HTTP push |
| Image layout | dual-bank A/B (`CRPOtaType.GR_A`/`GR_B`) | single image file (inferred) |
| Firmware-type const | `CRPNewFirmwareVersionInfo.FIRMWARE_TYPE_JIELI = 1` | `FIRMWARE_TYPE_ALLWINNER = 2` |

Both are driven by `CRPBleConnection` and report through the same `CRPOtaListener`:
`onDownloadStarting → onDownloadComplete → onProgressStarting → onProgressChanged(percent) →
onCompleted | onAborted | onError(code, msg)`.

### OTA error codes (from `CRPOtaListener`)
| Constant | Value | Meaning |
|----------|-------|---------|
| `FIRMWARE_DOWNLOAD_FAILED` | `0x11` | Server download of the firmware failed |
| `NO_FOUND_DEVICE` | `0x13` | DFU target not found (Jieli reconnect step) |
| `FIRMWARE_VERSION_NULL` | `0x14` | No version info supplied |
| `NO_NEW_FIRMWARE_VERSION` | `0x15` | Already up to date |
| `DFU_PROCESS_FAILED` | `0x17` | The DFU transfer itself failed |

## 2. The Jieli path (BLE DFU)

`startOta(listener)` — the SDK downloads the firmware from the cloud and runs a **Jieli DFU** over
BLE. `startOta(File, listener)` — same DFU but with a **local file you provide**.

Flow (from `dev-docs/guides/ota.md`):
1. (SDK) download firmware (or use your `File`)
2. Disconnect the normal connection, enter DFU
3. Transfer + verify, device reboots into the new bank
4. Reconnect

Key facts:
- The `com.jieli.jl_bt_ota` stack is the **stock Jieli OTA library** — its packaging, the `.ufw`
  container format, and its CRC/verification are **publicly documented by Jieli** and widely
  reverse-engineered. This is a big advantage: the Jieli image format is a known quantity.
- `CRPOtaType.GR_A` / `GR_B` ⇒ **dual-bank**. A bad/rejected image should fall back to the good
  bank, which lowers brick risk while experimenting on the Jieli side.
- **`startOta(File)` accepts an arbitrary local file** — so you can feed a modified/hand-built
  Jieli image *if* it passes the DFU container's integrity checks (CRC + any signature the Jieli
  bootloader enforces — **UNVERIFIED** whether these glasses enable Jieli's signature option).

**Tinkering questions (Jieli):**
- Does the Jieli bootloader require a signed `.ufw`, or only CRC? (Jieli supports both; many
  low-cost devices ship CRC-only.) → try a trivially-modified stock image and observe.
- Can we dump the current Jieli firmware to diff? (Depends on flash readout protection — [01 §5].)

## 3. The Allwinner path (Wi-Fi) — the primary tinkering surface

This is the one to focus on. `startAllWinnerOta(File, listener)` pushes a **raw local file** to the
Allwinner over the device's own Wi-Fi AP.

Flow (from `dev-docs/guides/ota.md` + `media.md`):
```
setWifiListener(...)                       // observe state
enableWifi(CRPWifiType.OTA)                // bring up the device Wi-Fi AP (OTA role)
  → onWifiStateChange(OTA, STATE_SUCCESS)  // AP is up
connectWifi()                              // phone joins the device AP
  → onWifiConnectionStateChanged(true)     // joined
startAllWinnerOta(otaFile, otaListener)    // HTTP push the file to the Allwinner
  → onProgressChanged(%) → onCompleted     // device reboots into new image
disableWifi()                              // tear down AP
```

Why this is the interesting surface:
- **It takes an arbitrary `File`.** The SDK does not (as far as the API shows) constrain the
  contents beyond whatever the *device-side* updater validates. So the entire question reduces to:
  **what does the Allwinner-side OTA receiver accept, and does it verify signatures/hashes?**
- The transfer is **plain HTTP over a device-hosted AP** — trivially observable and replayable.
  You can capture exactly what the SDK POSTs, then craft your own requests without the SDK at all.
- The Cyan investigation found the analogous Allwinner (V821) device had **no secure boot and no
  dm-verity — the only integrity gate was a per-CPIO-item md5**. If MoYoung's Allwinner side is
  similar, a modified rootfs that keeps the md5 manifest consistent could flash. **UNVERIFIED here**
  but the single most valuable thing to check.

**Tinkering questions (Allwinner) — the worklist:**
1. Capture the OTA HTTP exchange (join the device AP, run the stock update, sniff). What's the
   endpoint, method, headers, and the file's container format?
2. What's *inside* the `.swu`? The container format is **confirmed as swupdate `.swu`** — the same
   format as the Cyan V821 (iOS guide §4.10, verbatim: *"Start Allwinner OTA (.swu format …)"*).
   `binwalk` it: does it carry an `sw-description` + md5 manifest like Cyan's, and what's the
   payload (SquashFS/CPIO/ext)? The vendor even ships a parser — iOS `analysisAllwinnerFile(url:…)`
   (SDK 1.2.0) — worth reversing for the exact layout. See [06 §9](06_Live_Video_Streaming.md).
3. Is there signature verification, or only hash/CRC? Modify one byte and see if it's rejected.
4. Does `resumeAllWinnerOta` expose partial-write / rollback behavior we can abuse?
5. What else does the device AP's HTTP server expose besides the OTA endpoint? (media list, logs,
   config, a busybox `httpd` with directory listing?)

## 4. The cloud: `altair.moyoung.com`

`checkFirmwareVersion(CRPFirmwareRequestInfo, callback)` calls out to:

```
GET/POST https://altair.moyoung.com/api/v1/firmware/check-upgrade
```
(String is hardcoded in the AAR — **CONFIRMED**.) The request is built from
`CRPFirmwareRequestInfo(mac, fw1_ver, fw2_ver)` (MAC + the two firmware versions — Jieli + Allwinner),
and the response deserializes into `CRPNewFirmwareVersionInfo`:

| Field | Meaning |
|-------|---------|
| `newVersion` | version string of the available update |
| `fileUrl` | **URL to download the firmware image** |
| `md5` | expected MD5 of the downloaded file |
| `otaType` | UI hint: `CLOSE_POPUP(1)`, `FORCE(2)`, `PROGRESS(3)`, `RED_POINT(4)` |
| `firmwareType` | `JIELI(1)` or `ALLWINNER(2)` — which path to use |
| `has_upgrade` | boolean |

**RE value of the cloud path:**
- `fileUrl` + `md5` means **the vendor's own firmware images are downloadable** (the URL is
  returned in plaintext to the app). Capturing a `check-upgrade` response yields a direct link to
  official firmware — the best possible source to `binwalk`/diff, and to build modified images
  from.
- The integrity contract the *app* sees is just an **MD5** — that's a tamper check, not a
  signature. Whether the *device* enforces more is the open question (§3.3).
- Requests are keyed by **MAC + current versions**, so you can query for *any* version/region by
  forging `CRPFirmwareRequestInfo` — useful for pulling older images or forcing a downgrade URL.
  (`CRPFirmwareRequestInfo(mac, deviceModel, region)` is the 3-arg form the docs show; the bean's
  real fields are `mac, fw1_ver, fw2_ver`.)

> **TODO / UNVERIFIED:** the exact request body, auth (if any), and whether the endpoint is
> rate-limited or signed. Capture it with the tinker app's OTA screen + a proxy.

## 5. A practical firmware-RE plan for these glasses

Ordered by value-for-effort, and reusing the Cyan playbook:

1. **Harvest official firmware** — use the app's update-check to capture `check-upgrade` responses;
   download the `fileUrl` images for both Jieli and Allwinner. Zero hardware needed.
2. **`binwalk` both images** — identify container formats. Jieli `.ufw` is known; the Allwinner
   image is the unknown to characterize (swupdate? raw partitions? md5 manifest?).
3. **Sniff the Allwinner Wi-Fi OTA** — run a stock update over the device AP with a capture, to
   learn the on-device HTTP receiver's contract and file format independent of the SDK.
4. **Test integrity gates** — modify a benign byte and re-flash via `startOta(File)` /
   `startAllWinnerOta(File)`; observe accept/reject to learn CRC-only vs signed.
5. **Build a modified image** — once the container + integrity model is known, rebuild with a
   change (e.g., flip a feature default) and flash. Prefer the Jieli **A/B** side first (safer
   fallback) before touching the Allwinner rootfs.
6. **Teardown for the hard cases** — if OTA is signature-gated, fall back to hardware
   (UART console, flash dump, FEL on Allwinner / SWD on Jieli) — the Cyan repo's methodology.

The tinker app ([03](03_Mobile_App_and_SDK.md)) gives you buttons for steps 1, 3, 4, and 5:
`checkFirmwareVersion`, the Wi-Fi bring-up, and both `startOta(File)` / `startAllWinnerOta(File)`
with a file you pick — so you can push arbitrary images without writing an Android app from scratch.

## 6. Safety notes (from vendor docs + prudence)
- OTA wants **>50% battery**; low battery may refuse Wi-Fi (`STATE_LOW_BATTERY`).
- Don't disconnect mid-flash. The Jieli A/B layout protects the Jieli side; the Allwinner side's
  recovery behavior is **UNVERIFIED** — assume a bad Allwinner flash can brick until proven
  otherwise, and keep the first Allwinner experiments to known-good stock images + `resume`.
- Keep a copy of every stock image you pull (they're the recovery path).
