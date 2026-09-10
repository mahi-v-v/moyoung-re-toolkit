# Vision `.swu` patch + build outputs

Ready-to-flash, **rootfs-only** Vision (Allwinner V821) images and the scripts that build them.
Full writeup + risk model: [`../../../docs/12_Firmware_Patching_and_Flashing.md`](../../../docs/12_Firmware_Patching_and_Flashing.md).

## Outputs
| File | Change | Size |
|------|--------|------|
| `vision_noop.swu` | none (vendor rootfs, repacked rootfs-only) — OTA pipeline test | 7.21 MB |
| `vision_6mbps_adb.swu` | live bitrate 1.5→6.0 Mbps + adb-over-TCP:5555 | 7.08 MB |
| `rootfs_sdnand.patched` | the patched squashfs alone (for inspection) | 7.08 MB |

Base build: `../aiglass-ai/20260402205157_openwrt_v821_aiglass-ai.swu` (closest archived `-ai` build
to the device's running `2603302218`; we don't have the exact running image without a root shell).

## What the patch is
- **Bitrate:** one RISC-V `lui` immediate in `/bin/ai_glass_livestream` at **file offset `0xBA66`**:
  `37 E7 16 00` (`lui x14,0x16E`, →1,500,000 with the following `addi +864`) →
  `37 97 5B 00` (`lui x14,0x5B9`, →**6,001,504**). 2 bytes. See `scan_bitrate.py`.
- **adb over Wi-Fi:** uncomment `#ADB_TRANSPORT_PORT=5555` in `/etc/init.d/adbd` (vendor left the
  `procd_set_param env ADB_TRANSPORT_PORT` plumbing in place). `adb connect <glasses-ip>:5555`.

## What it writes (safety)
`sw-description` declares **only** `rootfs_sdnand → /dev/mmcblk0p9` (+ vendor `preinstall_sdnand.sh`).
It **never** writes `boot0`/`uboot`/`kernel`/`riscv`/`user` — the bootloader is untouched, removing
the worst brick vector. newc-**CRC** CPIO (`070702`) so swupdate rejects a corrupt transfer.

## Rebuild
Requires WSL with `squashfs-tools` (lzo) + `cpio`. No root needed (a pseudo-file recreates the lone
`/dev/console` node). The scripts assume the base `.swu` is extracted under `~/otawork/a2` — see the
top of each script.

```sh
bash build_rootfs.sh   # extract, 2-byte patch, adbd uncomment, mksquashfs (lzo/32K), self-validate
bash build_swu.sh      # trim sw-description to rootfs-only, md5, repack noop + patched .swu
```

Tune the target bitrate in `build_rootfs.sh` (the `new` bytes): `37 97 5B 00`=6 Mbps,
`37 37 A1 00`=8 Mbps (`lui x14,0x7A1`).
