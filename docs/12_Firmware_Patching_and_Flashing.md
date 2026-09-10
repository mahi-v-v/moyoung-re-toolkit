# 12 — Firmware Patching & Flashing (bitrate mod, safe .swu, in-app OTA)

How we turned the streaming diagnosis ([11](11_Streaming_Bitrate_Analysis.md)) into a **ready-to-flash
Vision image** and wired it into the tinker app with live progress — built to keep **brick risk as low
as software alone allows**. Done 2026-07-30.

> **Status (updated 2026-08-03):** artifacts **built + validated offline**; app **wired**. **Attempted on
> hardware:** the in-app **Core** dual-bank BLE OTA is **VERIFIED** (flashed `0.0.9`, device rebooted,
> version confirmed — needs `jl_bt_ota` v1.10.0, [14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md)); the
> **Vision `.swu` transport completes but the device reports `SUCCESS` without flashing** (device-side masked
> no-op, 12/12 — [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md); see the CORRECTION banner below). There is **no USB data line** on
> this unit (confirmed), so there is no hardware recovery net (FEL) for the Vision path without a teardown
> — see [§6](#6-brick-risk-model--honest).
>
> **⚠ CORRECTION (2026-08-07) — two things below are wrong for THIS unit; see
> [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md) + [09 §1](09_Vision_Firmware_V821.md) + [log §24](moyoung_reverse_engineering.md):**
> (1) The Vision failure is **device-side: the glasses report OTA `SUCCESS` without flashing.** Measured
> 2026-08-07 with the genuine `-ab` image: the phone serves the whole file (`sendBody sent=12514304/12514304
> complete`), Android does **not** drop the Wi-Fi (a `NetworkCallback` watch logs no `onLost`), yet the device
> caps progress (~45%), fires `completed`, and does **not** reboot or change version — `AG_AD_OTA_START ×12 →
> AG_VD_OTA_SUCCESS ×12 → AG_VD_OTA_FAIL ×0` in one day. So it is **not the file and not a phone/transport
> truncation** (an earlier "phone-side transport" reading here is **refuted**). The `swupdate` reason lives on
> `ttyS3` and needs a teardown (declined for now). *(This also means the offline "brick-risk" model below is
> academic until the device actually applies anything.)*
> (2) This unit is **dual-bank A/B** and runs `swupdate -e stable,now_A_next_B` (NOT `-e stable,sdnand`), so
> a working `.swu` must ship the **`now_A_next_B`/`now_B_next_A`** groups, **not** the single
> `sdnand → mmcblk0p9` group the recipe below builds. The rootfs-only `sdnand` recipe stays valid tooling,
> but retarget it to the A/B `/dev/by-name/*` devices before it can flash this unit. *(Downstream of (1).)*

## 1. The changes (minimal by design)
Both changes live in the **SD-NAND rootfs** (`rootfs_sdnand`, the device's `/`), nothing else:

1. **Live-stream bitrate 1.5 → 6.0 Mbps.** One RISC-V `lui` immediate in `/bin/ai_glass_livestream`
   at **file offset `0xBA66`**: `37 E7 16 00` (`lui x14,0x16E` → 1,500,000 with the trailing
   `addi x14,x14,864`) → `37 97 5B 00` (`lui x14,0x5B9` → **6,001,504**). A **2-byte** change; the
   only site in the binary that materialises 1,500,000 (`scan_bitrate.py` confirms uniqueness).
   8 Mbps = `37 37 A1 00` (`lui x14,0x7A1`).
2. **adb over Wi-Fi (recovery/verify channel).** Uncomment `#ADB_TRANSPORT_PORT=5555` in
   `/etc/init.d/adbd`. The vendor already ships the plumbing
   (`procd_set_param env ADB_TRANSPORT_PORT="$ADB_TRANSPORT_PORT"`) and starts adbd at boot
   (`rc.d/S80adbd`). Result: `adb connect <glasses-ip>:5555` → **root shell over Wi-Fi, no teardown**,
   *if the rootfs boots*. This is the user's "flash adb into the swu" idea, grounded.

## 2. The safety lever: the `.swu` decides what gets written
swupdate installs **only** the images listed in the `sw-description` **inside the `.swu` we build**.
The stock vendor image writes 5–9 partitions **including `boot0`/`uboot`** (the bootloader — a bad
write there is a hard, FEL-only brick). Our trimmed manifest declares **only**:

```
stable.sdnand.images = ( { filename="rootfs_sdnand"; device="/dev/mmcblk0p9"; installed-directly=true; } )
stable.sdnand.scripts = ( { filename="preinstall_sdnand.sh"; type="preinstall"; } )   # vendor's; writes boot_type=2
```

⇒ it writes **only `/dev/mmcblk0p9`**, never the bootloader/kernel/riscv/user. Container is
newc-**CRC** CPIO (`070702`), so swupdate validates each member's checksum and **aborts before
writing on a corrupt transfer**. There is no signature check in this swupdate build (unsigned `.swu`s
are accepted — see [10](10_Cloud_and_Device_Auth.md)).

**Why `sdnand`:** our unit is the `-ai` single-system build, boots `root=/dev/mmcblk*` (SD-NAND), and
its `ai_glass_ota` runs `swupdate -i … -e stable,sdnand` for `-ai` images. (The firmware *family*
also has a true A/B path — see [09 §5](09_Vision_Firmware_V821.md) — but that's the `-ab` line, not
this unit.)

## 3. Build recipe (offline, no root)
Tools: WSL + `squashfs-tools` (lzo) + `cpio`. Scripts + outputs live in
[`../firmware/vision-v821/_patched/`](../firmware/vision-v821/_patched/).

1. `cpio -idm < <base -ai .swu>` → extract members; pull `rootfs_sdnand`.
2. `unsquashfs` it (non-root; the lone `/dev/console` node warns, ignored).
3. Patch `bin/ai_glass_livestream` (the 2 bytes) + uncomment the adbd line.
4. `mksquashfs … -comp lzo -b 32768 -all-root -p 'dev/console c 600 0 0 5 1'` (the pseudo-file
   recreates the device node without root). Rebuilt image is **7.08 MB ≤ 7.21 MB** original → fits.
5. Author the trimmed `sw-description`, recompute `cpio_item_md5`, repack with `cpio -o -H crc`
   (`sw-description` first, `cpio_item_md5` last).
6. **Self-validation** (in `build_rootfs.sh`): re-extract and `diff` the tree vs the original — only
   `ai_glass_livestream` + `init.d/adbd` differ; confirm the patched bytes and the adbd line survive.

Outputs: `vision_noop.swu` (unchanged rootfs, pipeline test) and `vision_6mbps_adb.swu` (the mod).

## 4. In-app OTA (wired, with progress)
The native + JS layers were already complete (`startAllwinnerOta`/`startJieliOta` → `CRPOtaListener`
→ `GLASS_OTA_PROGRESS`/`GLASS_OTA_STATE`). New in this pass:

- **Bundled artifacts** shipped in the APK at
  `moyoung-app/modules/glass-sdk/android/src/main/assets/firmware/` (git-ignored; see its README).
- Native **`resolveBundledFirmware(name)`** / **`listBundledFirmware()`** (in `GlassModule.kt`) copy a
  bundled build to `filesDir` and return an absolute path for `start*Ota`.
- **`app/(tabs)/ota.tsx`** rewritten: a **bundled-build picker** (recommended order), a **progress
  bar** + OTA-state + Wi-Fi-ready row, a one-tap **Core dual-bank** flash, a staged **Vision Wi-Fi**
  flow (Enable Wi-Fi → Connect → Start, gated on `connected`), plus the manual/cloud paths.

## 5. Recommended flash sequence (escalating confidence)
1. **Core dry-run** (`core_0.0.9_dryrun.ufw`, BLE, **dual-bank A/B → auto-rollback**) — proves the whole
   app→SDK→device OTA pipeline + progress UI on the recoverable chip. Reversible.
2. **Vision no-op** (`vision_noop.swu`) — same rootfs-only Wi-Fi path, **zero functional change**;
   isolates "does the Vision transfer/write/reboot work" from "is my patch good."
3. **Vision 6 Mbps** (`vision_6mbps_adb.swu`) — the real mod. Verify afterward by
   `adb connect <ip>:5555` and re-measuring the RTSP bitrate ([11 §1](11_Streaming_Bitrate_Analysis.md)).

## 6. Brick-risk model — honest
| Mitigation | Effect |
|---|---|
| rootfs-only `.swu` (never `boot0`/`uboot`) | removes the hard-brick (FEL-only) vector |
| newc-CRC container | corrupt transfer → swupdate aborts **before** writing |
| minimal 2-byte + 1-line change | won't affect squashfs mountability; 6 Mbps is a valid encoder value |
| Core dry-run + Vision no-op first | pipeline proven before the real mod |
| adb-over-Wi-Fi in the image | root shell to verify/repair **if it boots** |
| well-charged, still, don't power off | avoids the one uncontrolled failure (power loss mid-write) |

**Residual risk (not zero):** the flash overwrites the *running* rootfs in place (single-system unit,
no A/B). If the new rootfs failed to mount at all, auto-fallback to the NOR recovery is **unverified**,
and with **no USB data line** the only recovery would be a **teardown → Allwinner FEL** over the
V821's USB pads. This is why "near-zero" was not promised for the in-place Vision flash; it is *low*,
and every software lever above is pulled. A genuine near-zero path needs the teardown (UART/USB) or a
confirmed-A/B unit — see [paths_forward.md](paths_forward.md) C1/C3.

## 7. Hardware access (recorded for later)
- **No USB data line** on the charging port (user-confirmed) → ADB/FEL both need a **teardown**.
- Alternative unlocked here: **adb over Wi-Fi** via the OTA (§1.2). If `vision_6mbps_adb.swu` boots,
  we get a root shell over Wi-Fi anyway — enabling live, reversible edits (drop a patched binary into
  the `/overlay` upperdir instead of ever reflashing again).
- Decrypted Core `app.bin` (for the E2 Ghidra track) preserved at
  `firmware/core-jieli-a073/_decrypted/app.bin`.
