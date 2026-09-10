# tools/ — reverse-engineering scripts

The scripts behind the findings, preserved from the working scratchpad so the analysis is
reproducible and the methodology is part of the portfolio. **Archival:** several embed absolute
paths from the working session (WSL `~/…`, the scratchpad, `d:/…`) — adjust paths before re-running.
Each links to the doc it supports.

## vision-firmware/ — the streaming bitrate mod ([11](../docs/11_Streaming_Bitrate_Analysis.md), [12](../docs/12_Firmware_Patching_and_Flashing.md))
| File | What it does |
|------|--------------|
| `scan_bitrate.py` | Pure-Python RISC-V scan: finds the `lui`+`addi` pair that materialises 1,500,000 in `ai_glass_livestream` and prints the exact file offset + the 6/8 Mbps patch bytes. **This is what pinned offset `0xBA66`.** |
| `find_bitrate.py` / `find_bitrate.java` | Ghidra headless post-scripts (Jython / Java) that decompiled `take_video_livestream_start` and showed the hardcoded `1500000` + the H.265 `0xC00000` branch. |
| `ghidra_out*.txt` | Captured decompiler output (the evidence). |
| `build_rootfs.sh` | Extract the vendor `rootfs_sdnand`, apply the 2-byte bitrate patch + uncomment adbd-over-TCP, `mksquashfs` (lzo/32K, pseudo-file for `/dev/console`), self-validate. |
| `build_swu.sh` | Wrap the patched (and a no-op) rootfs in a trimmed **rootfs-only** newc-CRC `.swu`. |
| `swu_head.bin` | Sample `.swu` header (CPIO `070702`) kept for format reference. |

(The originals also live in [`../firmware/vision-v821/_patched/`](../firmware/vision-v821/_patched/) next to the built images; `ai_glass_livestream.orig` there is the unpatched binary.)

## core-firmware/ — JieLi Core cipher analysis ([08](../docs/08_Core_Firmware_Jieli.md))
`jl_crypto.py` / `jl_crypto2.py` / `jl_crypto3.py` — entropy + cross-build XOR analysis of the A073
Core `.ufw` set that showed the cipher is a reused-keystream LFSR (not AES), leading to the
`kagaimiq/jl-misctools` decrypt with chipkey `0x1607`.

## crypto/ — ciphertext-only attempt ([08](../docs/08_Core_Firmware_Jieli.md))
`cribdrag.py` (ciphertext-only crib-drag on the global reused keystream) and `extend.py` (grow the
keystream from the version-string anchor). Documents the honest **negative** result — crib-drag alone
couldn't recover the code region — before the chipkey route succeeded.

## firmware-server/ — the open firmware factory ([07](../docs/07_Firmware_Acquisition.md), [10](../docs/10_Cloud_and_Device_Auth.md))
`sizeall.py` (size every file in the open directory listing), `select.py` (curate the download set →
`sel.tsv`), `getfw.sh` (resumable download + MANIFEST), `an.sh` (auto-pull newest `-ab` build + report
its encoder config), `hunt.sh` (locate where `ai_glass_*` are launched/configured in a rootfs),
`fw_analyze.py` / `fw_analyze2.py` (the early two-time-pad / container feasibility study on the
bundled MOY-TTT3 images — the "red-herring band" investigation).

## streaming/ ([06](../docs/06_Live_Video_Streaming.md), [11](../docs/11_Streaming_Bitrate_Analysis.md))
`rtsp_probe.py` (find the glasses' RTSP server on the LAN, DESCRIBE + ffprobe the real
resolution/bitrate — the 1.5 Mbps measurement) and `vision_webrtc_scan.sh` (scan every archived
Vision `.swu` for a real WebRTC/TURN stack — found only the `1.0.0.x` builds carry it).

## app-decompile/
`jadx_out.txt` — the excerpt of the official **Da Echo** app decompile used to confirm the SDK stores
the version string verbatim (the `2.4.0.22` relabel is device-side).

## Not copied here (too large / reproducible / already in-repo)
Kept only in the working scratchpad or regenerable on demand:
- `da-echo-base.apk` (98 MB), `da-echo-src/` (jadx, 59 MB), `da-echo-dex/` (40 MB) — the official app; re-decompile the APK with jadx.
- `logcat.txt` / `logcat2.txt` (125 MB) — device logs from the app-bring-up/crash-fix sessions (the crashes are written up in [03](../docs/03_Mobile_App_and_SDK.md)).
- `vision.swu` (24 MB), `core-0.1.0.ufw` — duplicates of images already under [`../firmware/`](../firmware/).
- `aar/` — the vendor AAR, already in ``../vendor-sdk/``; `jlmt/` — a clone of `kagaimiq/jl-misctools`.
- Decrypted Core artifacts → preserved separately in ``../firmware/core-jieli-a073/_decrypted/``; stream-quality evidence (`frame.png`, `cap.mp4`) → [`../docs/reference/evidence/`](../docs/reference/evidence/).
