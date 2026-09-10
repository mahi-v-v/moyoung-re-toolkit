# 09 — Vision Firmware (Allwinner V821 / OpenWRT-Tina)

The camera/Wi-Fi/AI SoC's firmware — obtained, unpacked, and analyzed 2026-07-30. Unlike the Core, the
Vision `.swu` is **not encrypted**; it unpacks freely. This is where the **RTSP streamer + encoder
bitrate** live ([11](11_Streaming_Bitrate_Analysis.md)) and where the OTA **brick risk** is.

## 1. Container: swupdate `.swu` (newc-CRC CPIO)
`.swu` magic `070702` (SVR4/newc CPIO with CRC). Unpack in **WSL** (`cpio -idm`; Windows lacks it).
First member `sw-description` is plaintext (an Allwinner **"Tina Project"** swupdate manifest) and
declares two install profiles (**NOR** = `mtdblock*`, **SD-NAND** = `mmcblk0p*`), all
`installed-directly = true`:

| Image | NOR device | Notes |
|---|---|---|
| `kernel` | /dev/mtdblock3 | Android bootimg |
| **`rootfs`** | /dev/mtdblock5 | **squashfs 4.0 / lzo / 32 KB block** — the `ai_glass_*` apps |
| `riscv` | /dev/mtdblock4 | RISC-V ELF (camera/AI co-core) |
| `user` | /dev/mmcblk0p13 | squashfs — carries `etc/ag_user_version.conf` (`-ai` packaging only) |
| **`boot0`** | /dev/mtdblock0 | **the bootloader** |

**Integrity = a plaintext `cpio_item_md5` member only. NO per-image sha256, NO signature, NO
hardware-compatibility gate.** (Same weak model as the Cyan V821. CONFIRMED again 2026-08-06 by
inspecting the on-device swupdate — no `CONFIG_SIGNED_IMAGES`, no hwrevision match.)

> **✅ Live-device correction (2026-08-04/06) — this unit is SD-NAND + dual-bank A/B.** The table above is the
> NOR profile; the *running* unit boots **SD-NAND** (`mmcblk0`), from BLE-pulled kernel logs
> ([log §21–§23](moyoung_reverse_engineering.md)): `console=ttyS3,1500000` (the UART teardown target),
> `root=/dev/mmcblk0p9`, partitions `boot-resource@p1 : env@p2 : env-redund@p3 : bootA@p4 : bootB@p5 :
> private@p6 : riscv0@…`; an **`e907` RISC-V co-processor** loads `amp_rv0.bin`. Because it has **bootA/bootB**,
> the OTA is **true dual-bank A/B**: `ai_glass_ota` reads `fw_printenv boot_partition` and runs
> `swupdate … -e stable,now_A_next_B` (or `now_B_next_A`), which writes the **inactive** slot
> (`/dev/by-name/{bootB|A, rootfsB|A, riscv0-r}`) then flips `systemAB_next`. A genuine `-ab` `.swu` carries
> **only** `now_A_next_B`/`now_B_next_A` selections; a `.swu` we build for this unit **must** provide those (not
> a single `sdnand → mmcblk0p9` group) or swupdate installs nothing.

## 2. The application layer (`rootfs` / `user`)
`/bin/ai_glass_*`: `ai_glass_normal`, `_audio`, `_photo`, `_video`, `_download`, `_ota`,
**`_livestream`**, plus Allwinner's `eyesee-mpp` media stack and `libaglink.so` (the Core↔Vision IPC).
The device is **mode-driven** — `/etc/media/rtc_init.sh` launches one app per `$mode`, arg-less:
`0`=photo, `1/10`=video, `2`=download, `3`=ota, `4`=AI, `5/7`=normal, `6`=audio, **`8`=livestream**,
`15`=ETF test. `adbd` is an init.d service (USB-gadget FFS) — **but that root shell is NOT reachable on
the assembled unit.** The charging port exposes **no USB data line** (user-confirmed), so USB ADB needs a
**teardown**; and the `ADB_TRANSPORT_PORT=5555` **adb-over-Wi-Fi** path only comes up *after* a Vision flash
actually applies — which it doesn't yet ([14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)). ⇒ **no ADB of any
kind on this unit without a teardown** (noted 2026-08-04).

### 2.1 The `aglink` Core↔Vision command surface — no teardown-free exec vector (RE 2026-08-08)
`libaglink.so` implements the JieLi-Core↔V821 IPC. Full opcode set (AD = Core→Vision, VD = Vision→Core) incl.
`AG_AD_{OTA_START, DOWNLOAD_STOP, CREATE_SYSFILE/WRITE_SYSFILE/WRITE_SYSFILE_END, GET_SYSFILE_STATUS, SAVE_SYS_LOG,
TEST, FACTORY_RESET, AP/STA/P2P, TIME, media ops, GET_SYS_VERSION/DEVICEID/…}` and `AG_VD_{OTA_SUCCESS,
**OTA_FAILED** (defined but never emitted by `ai_glass_ota`), OTA_PG_BAR, GENERIC_RESPONSE, TEST, …}`. We RE'd this
to see whether the **Core (our proven BLE-flash channel) could reach a Vision shell / the swupdate reason without a
teardown** — **it can't** (Ghidra decompile: `libaglink.so` 571 funcs, `ai_glass_normal` 39 funcs; full log [§25](moyoung_reverse_engineering.md)):
- **`SAVE_SYS_LOG`** (what the app's `downloadLogFile` triggers) runs `/etc/media/log_handle.sh` → collects **`dmesg`
  + aglink logs + remoteproc `aw_trace_log`**, **NOT `swupdate`/console output** — which is on `ttyS3`. That's why the
  OTA no-op reason never appears in BLE log pulls.
- **`WRITE_SYSFILE`** = a chunked **`fopen` write into `/mnt/UDISK`** (path `"%s/%s"`), **not** `system`; nothing on
  the device auto-executes from `/mnt/UDISK`; and it is **not phone-reachable** (the CRP protobuf set has no
  file-write message).
- **No command injection & no shell/adb spawn anywhere**: every `system`/`popen`/`sh -c` across `libaglink` + all
  `ai_glass_*` binaries runs a **hardcoded** string (the only `sh -c` is a fixed `rmem_max` buffer tweak).
  `AG_AD_TEST` is a link self-test; `FactoryCtrl` (the one phone-reachable factory command) drives **JieLi hardware
  self-tests**, not a Vision shell.
- Confirms the OTA server IP comes from `aglink_get_dhcp_leases_by_popen` (`cat /var/dnsmasq.leases`) — the device
  fetches from the **DHCP-leased station** ([14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)).

⇒ The remaining BLE/Core lever is a **modified-Core bridge** (patch the pi32v2 Core to tap the inter-chip UART / issue
non-phone-reachable aglink commands), gated on **E3** (does a re-encrypted modified Core boot? — safe-ish via Core
dual-bank A/B). Otherwise device access still needs the `ttyS3` teardown.

## 3. Variants & version scheme
Lines: `openwrt_v821_aiglass-ai` (ships a `user` partition w/ version), `-ab` (single rootfs, no
version file), `_imx681-ab` / `_imx681-ai` (Sony **IMX681** sensor variant). Internal version format
`A.B.0.NN.3.<buildYYMMDDHHMM>`. This unit is on the **`1.4.x -ai`** branch — its exact build is
`1.4.0.20.3.2603302218` (matched by the unique build-stamp `2603302218`; the app shows `2.4.0.22`, a relabel
the Core **relays but does not synthesise** — `2.4.0.22` is absent from the Core image; leaning Vision-side,
mechanism still an open UART-sniff TODO — see [10 §3](10_Cloud_and_Device_Auth.md), [15 §7](15_Core_App_Analysis.md)).

## 4. WebRTC push — present in SOME builds, NOT ours
The vendor's `setSTALiveMode` (iOS SDK) is a **WebRTC** push. Scanning all 69 archived Vision builds:
**4 builds carry a real WebRTC stack** — `/usr/lib/libpeer.so` + `libsrtp2` + `libmbedtls`, and inside
`ai_glass_livestream`: `peer_signaling_*`, `webrtc_start`, `stun:rtc-sz.moyoung.com:3478`, and
`webrtc_apply_ag_config: token=…, web_urls=…, turn_urls=…` (the exact `CRPStaLiveMode` fields). These
are on the **`1.0.0.x`** branch (e.g. `1.0.0.10.3.2604161510`). Our `1.4.x` build is **RTSP-only** (no
`libpeer`, `ai_glass_livestream` = 47 `rtsp` strings / 0 webrtc). ⇒ native push is achievable on this
hardware only by flashing a WebRTC build ([11 §fix](11_Streaming_Bitrate_Analysis.md)) or a custom mod.

## 5. OTA safety (READ before flashing) — brick risk is real
From `sw-description`: **9 partitions written in-place, including `boot0`**; `bootenv` `swu_next=reboot`
is **commented out** → **no A/B rollback**; only the weak `cpio_item_md5`; **no HW-compat gate** (it
writes whatever it's handed). Recovery from a bad flash is **only Allwinner FEL over USB** — which we
don't have without a teardown. Our app *does* wire the push path
(`MoyoungAdapter.startAllwinnerOta(File)` → `conn.startAllWinnerOta`, needs `enableWifi(OTA)`+
`connectWifi()`). **Result (corrected 2026-08-08):** the device reports OTA **`SUCCESS` without flashing** — a
**device-side masked no-op**, reproduced **12/12** with a genuine unmodified `-ab` image. The phone serves the whole
file (`sendBody 12514304/12514304 complete`) and the Wi-Fi holds (no `onLost`), so it is **not** the
transport/repack (this corrects the earlier "device won't apply our repack" and "phone-side transport truncation"
readings). The `swupdate` accept/reject reason goes to **`ttyS3`** (not the BLE-pullable dmesg/aglink logs) — see
[14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md) / [log §24](moyoung_reverse_engineering.md). ⇒ **Don't rely on the
Vision flash until a teardown (UART/USB) gives both a recovery net *and* the `ttyS3` visibility.** (The Core's
dual-bank DFU is the safe OTA path — now **verified** — [08](08_Core_Firmware_Jieli.md) / [14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md).)

> **✅ 2026-07-30 — refined (see [12](12_Firmware_Patching_and_Flashing.md)).** Two corrections after
> reading the real manifests + `ai_glass_ota`:
> 1. **A/B rollback DOES exist in the firmware family** — the **`-ab` line** is true dual-bank:
>    `ai_glass_ota` runs `swupdate … -e stable,now_A_next_B` / `now_B_next_A`, and the `-ab`
>    `sw-description` writes the *inactive* slot (`bootB`/`rootfsB`/`riscv0-r`) then sets
>    `systemAB_next=B`. ⚠ **Superseded by §1 for THIS unit:** later evidence (§1 banner + [log §24](moyoung_reverse_engineering.md))
>    shows our live unit is itself **dual-bank A/B** (`-e stable,now_A_next_B`), not the `-ai`/`sdnand`
>    single-system profile the 2026-07-30 reading assumed here. Also: the device has a **NOR minimal-recovery rootfs**
>    (no `ai_glass_*`, no swupdate) + the SD-NAND full system; `preinstall_*.sh` shows a
>    `boot_type`/gprcm NOR↔SD-NAND selection.
> 2. **The `.swu` *we build* decides what's written** — swupdate only touches images listed in our
>    manifest. So a **rootfs-only** `.swu` writes `mmcblk0p9` and **never `boot0`/`uboot`**, removing
>    the hard-brick vector even on the in-place `-ai` path. That is exactly how
>    `vision_6mbps_adb.swu` is built ([12](12_Firmware_Patching_and_Flashing.md)).

## 6. Tools / repro
WSL Ubuntu (`cpio`, `unsquashfs` (lzo), `binwalk`, `file`); Ghidra headless for the RISC-V binaries.
Extraction commands and the per-build WebRTC scan are in the scratchpad scripts; results in the log
§6, §9-doc references, and [11](11_Streaming_Bitrate_Analysis.md).

## 7. "Will flashing the newest build ruin our RE?" — diff of `2.4.2.22` vs prior `-ab` (2026-08-11)
Da Echo offered Vision upgrade **`2.4.2.22.3.2608071744`** (Aug-7 build). Pulled from the open static
server = **`20260810110212_openwrt_v821_aiglass-ab.swu`** (md5 `d2a53de4…`, 12,631,552 B, internal
`tina.justin.20260807.112705`) — **not previously in our archive**; added to
`firmware/vision-v821/aiglass-ab/` + MANIFEST. Unpacked and diffed vs the newest prior `-ab`
(`20260729144724`). **Verdict: flashing does NOT ruin our RE/ownership — but gives us no benefit either.**

| Lever | Result (CONFIRMED from bytes) |
|---|---|
| **Signing / integrity** | `sw-description` **byte-identical** → **still UNSIGNED** (only `cpio_item_md5`; no sig/sha256/`hardware-compatibility`/anti-rollback). Modified-Vision flashing stays possible. |
| **adb-over-Wi-Fi lever** | `etc/init.d/adbd` + `rc.d/S80adbd` **byte-identical** — but this is a **DORMANT** capability: `#ADB_TRANSPORT_PORT=5555` is **commented**. We have **never** had adb to the glasses (it needs a *patched* rootfs that reflashes it, which never applied — [§5 banner](#5-ab-vs-single-system) / [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)). The diff only confirms the enable-path survives. |
| **Secure-boot / verity** | None — 0 dm-verity/IMA/EVM string hits (kernel + rootfs); `boot0`/`uboot` **md5-identical** (bootloaders untouched). |
| **OTA logic** | `bin/ai_glass_ota` **byte-identical** — same masked no-op, same A/B recipe. |
| **Streaming** | `bin/ai_glass_livestream` **byte-identical** → the 1.5 Mbps cap (`lui a4,0x16E` @ file `0xBA64`) is **unchanged**. No quality upside. |
| **Delta** | **0 files added/removed.** Only WiFi stack (`v821_smac.ko`, `libwifimg-v2.0.so`, `bin/wifi`) + `libaglink.so` + E907 riscv co-proc refresh + one `openwrt_release` date line. A WiFi/link maintenance release. |

**Caveat (Vision-only diff):** upgrading *via Da Echo* could **separately** push a new **Core** (JieLi)
`.ufw`, which the button/wake-word RE offsets (`FUN_0601ee90`, etc.) are version-specific to. Keep the
current Core if you want those exact offsets to stay valid. Confidence RE-safe: **high** (kernel/E907 blobs
not fully disassembled, but no verity plumbing exists and bootloaders are unchanged). Evidence:
scratchpad `DIFF_EVIDENCE.txt`, `{old,new}/rootfs_out`.
