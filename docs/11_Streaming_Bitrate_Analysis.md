# 11 — Live-Stream Quality: Bitrate Diagnosis & Fix

Why the live video looks poor, proven by measurement + Ghidra, and the concrete fixes. This is the
"why is streaming so low quality" investigation. See also [06](06_Live_Video_Streaming.md) (transport)
and the hands-on restreaming how-to [streaming_glasses_to_a_url.md](streaming_glasses_to_a_url.md).

## 1. The measurement (ffprobe/ffmpeg, on-LAN)
On this unit (`MOY-A073`, Vision `1.4.x`, STA mode) the glasses run an **RTSP server**;
`onLiveUrlChanged` hands the phone `rtsp://<glasses-ip>:8554/ch0`. Measured directly:

| | Measured |
|---|---|
| Resolution | **1600×1200 (2 MP)** — fine, *not* the problem |
| Frame rate | ~30 fps |
| Codec | H.264 **Main**, `yuvj420p`; audio AAC-LC 8 kHz mono |
| **Video bitrate** | **≈ 1.5 Mbps** (1,524,012 bps over 367 frames / 12.0 s) |

1.5 Mbps for 1600×1200@30 ≈ **0.026 bits/pixel/frame** (good H.264 wants ~0.1–0.15). The encoder
throws away high-frequency detail → the soft, smeared frame we captured. **The ceiling is bitrate, not
resolution.** Only `/ch0` exists (no hidden high-quality channel); in STA mode only TCP 8554 is open.

## 2. The root cause (Ghidra of `ai_glass_livestream`)
The bitrate is a **hardcoded constant** in `take_video_livestream_start` (`FUN_ram_0001b99a`):
```c
iStack_154 = 1500000;                 // c0 (channel-0) bitrate — HARDCODED = our measured 1.5 Mbps
iStack_558 = iStack_154;              // → AWVideoInput encoder target
if (encode_format == 1) {             // H.265/HEVC branch (NOT taken — our stream is H.264/format 0)
    iStack_558 = 0xC00000;            //   = 12,582,912 → 12 Mbps target (VBR 10–14 Mbps)
}
```
- `--bitrate`/`c0_bitrate` strings exist in the binary but have **no code xref** into this path, and
  `rtc_init.sh` launches the daemon **arg-less** → editing the launch line does **not** help.
- Width/height come from a "PresetConfig"; the **bitrate is overwritten in code** regardless.

## 3. Fixes (ranked)
1. **Relay/restream as-is (works today, no device change)** — an `ffmpeg`/MediaMTX box pulls
   `rtsp://…:8554/ch0` and republishes to any RTMP/RTSP/SRT URL. Won't raise quality (can't add detail
   the 1.5 Mbps encoder discarded) but makes the feed globally viewable. Full how-to:
   [streaming_glasses_to_a_url.md](streaming_glasses_to_a_url.md).
2. **Binary-patch the constant** — ✅ **BUILT (2026-07-30).** The `lui x14,0x16E` immediate at file
   offset **`0xBA66`** in `ai_glass_livestream` → `lui x14,0x5B9` (**6.0 Mbps**); repacked as a
   **rootfs-only** `.swu` (`vision_6mbps_adb.swu`) and wired into the app's OTA screen. Keeps H.264,
   never touches `boot0`. Full recipe + risk model: [12](12_Firmware_Patching_and_Flashing.md).
3. **Force H.265** (`encode_format=1`) — gets the built-in **12 Mbps** path for free + better
   compression, *iff* the phone app decodes HEVC (unverified).
4. **Flash a WebRTC `1.0.0.x` Vision build** — different capability (native push, [09 §4](09_Vision_Firmware_V821.md)),
   cross-branch, higher risk.

**All of 2–4 require a Vision reflash**, which is **in-place with no rollback and FEL-only recovery**
([09 §5](09_Vision_Firmware_V821.md)) → needs the UART/USB safety net first. Until then, **#1 is the
grounded working answer**.

## 4. Tools / repro
`ffprobe`/`ffmpeg` (present at `C:\ffmpeg\...`); the RTSP scanner + 12 s capture are in the scratchpad
scripts. Ghidra 12.1.2 headless decompiled the RISC-V `ai_glass_livestream` (Java post-script; PyGhidra
not required). Chronology in the append-only log §8.
