# Bundled tinker firmware (git-ignored)

The `.swu` / `.ufw` files here are **copied into the APK** and surfaced in the app's OTA screen
via the native `resolveBundledFirmware(name)` (see `GlassModule.kt`). They are **git-ignored**
(large binaries, fully reproducible), so a fresh clone must repopulate them before building:

| File | What it is | Source / recipe |
|------|------------|-----------------|
| `core_0.0.9_dryrun.ufw` | JieLi Core 0.0.9 (dual-bank A/B DFU). Safe pipeline test. | copy from `firmware/core-jieli-a073/20260529161018_MOY-A073-0.0.9-BIN-58233E57-ENCRYPTED.ufw` |
| `vision_noop.swu` | Vendor Vision rootfs, repacked **rootfs-only**. Zero functional change. | `firmware/vision-v821/_patched/build_swu.sh` |
| `vision_6mbps_adb.swu` | Vision rootfs, bitrate 1.5→6 Mbps + adb-over-TCP. **rootfs-only**. | `firmware/vision-v821/_patched/build_{rootfs,swu}.sh` |

Repopulate (from the repo root, artifacts already built into `firmware/vision-v821/_patched/`):

```sh
A=modules/glass-sdk/android/src/main/assets/firmware
cp firmware/vision-v821/_patched/vision_noop.swu       $A/
cp firmware/vision-v821/_patched/vision_6mbps_adb.swu  $A/
cp firmware/core-jieli-a073/20260529161018_MOY-A073-0.0.9-BIN-58233E57-ENCRYPTED.ufw $A/core_0.0.9_dryrun.ufw
```

Full build recipe + the risk model: `docs/12_Firmware_Patching_and_Flashing.md`.
The Vision `.swu` writes **only** `/dev/mmcblk0p9` (rootfs) — never `boot0`/`uboot`.
