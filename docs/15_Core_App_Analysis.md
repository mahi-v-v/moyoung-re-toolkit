# 15 — Core `app.bin` Analysis (JieLi AC701N, pi32v2)

Deep analysis of the **decrypted Core firmware** `firmware/core-jieli-a073/_decrypted/app.bin`
(1,065,100 bytes, from `MOY-A073-0.0.8`, git `C363B807`). This is the home for the app.bin findings:
the memory map, the architecture verdict, the string/table taxonomy, the on-device RCSP/voice command
surface, the Core↔Vision UART link, and the **resolution of the version-relabel question**. Companion to
[08 — Core Firmware Decryption](08_Core_Firmware_Jieli.md) (how we got the image) and the append-only log
[§18](moyoung_reverse_engineering.md).

> Tags: **CONFIRMED** (seen in the binary / in a shipped artifact), **INFERRED** (deduction),
> **UNVERIFIED/TODO** (needs a device, a BLE/UART sniff, or deeper decompilation). Every offset below is a
> **virtual address** (VA); the file maps 1:1 to VA `0x6000000` (file offset `X` → VA `0x6000000+X`).

---

## 1. Architecture — **CONFIRMED: JieLi pi32v2**, real disassembly achieved

The Core is **not** RISC-V/MIPS/ARM. It is JieLi's custom **pi32v2** ISA (the BR-series core, used on
AC701N / BR28). **CONFIRMED** two ways:

1. **Metadata** — `jlfw.yaml`: `entry-point 0x6000100`, `chip-key 0x1607` (5639); `cfg_tool.bin` names the
   chip **`AC701N`**, project **`AC701N-demo`**, base SDK **`earphone`** (JieLi's TWS-headset SDK).
2. **Coherent disassembly** — the community Ghidra processor module **[quarkslab/ghidra-jieli]** (an improved
   fork of kagaimiq's) disassembles the image cleanly. Stock Ghidra 12.1.2 ships **no** pi32 module; this
   one is a pure-Sleigh module (no Java), language id **`pi32v2:LE:32:default`**, compiled with Ghidra's
   own `support/sleigh.bat`. Sample (the reset stub at the image base):

   ```
   06000000: mov   sp,#0x103840          ; init stack pointer
   06000006: mov   ssp,#0x103840         ; init system stack
   0600000c: push  {r2,r1,r0}
   06000010: call  0x0605ed0c            ; early init
   0600001a: call  0x0605c412            ; -> main init chain
   ```
   Instruction forms (`sdw r4_r5,[r2+0x4]`, `add r2,#0x10`, `jb r2,r3,<addr>`, `pop {pc,r6,r5,r4}`,
   register pairs `rN_rN+1`, `if/then/else` + `rep` blocks) are exactly pi32v2. **This is the enabler for all
   future Core RE** — the previous blocker ("no pi32 module in stock Ghidra") is now solved and reproducible.

**Reproduce:** install the module into `<ghidra>/Ghidra/Processors/JieLi/` (copy its `data/` +
`Module.manifest`), compile `pi32v2.slaspec` with `support/sleigh.bat`, then headless-import raw with
`-processor pi32v2:LE:32:default -loader BinaryLoader -loader-baseAddr 0x6000000`. Full recipe + the driver
scripts (`PreSeed`, `PostDump`, `FindRefsDecompile`, `ExportListing`, `DecompileByEntry`) are in the log
[§18](moyoung_reverse_engineering.md).

### 1.1 Analysis outcome (this pass)
- Auto-analysis (with the Aggressive Instruction Finder + call-graph following, seeded at the reset stub):
  **2,605 functions**, **124,650 instructions**, ~1,940 printable strings (min-len 6). Raw dumps saved:
  [`reference/core-jieli/app_bin_functions_ghidra.txt`](reference/core-jieli/app_bin_functions_ghidra.txt),
  [`…_strings_min6.txt`](reference/core-jieli/app_bin_strings_min6.txt),
  [`…_refstrings_ghidra.txt`](reference/core-jieli/app_bin_refstrings_ghidra.txt).
- The build is **fully stripped** — every function is `FUN_*` (no symbol names leak in the `0.0.8` build).
  Debug/`__func__`-style names exist as **data** (task names, config keys) but are not attached to code.
- **Decompiler works** and resolves string arguments, e.g. the flash resource/config reader
  `FUN_0603e19a(len, name, …)` decompiles as `FUN_0603e19a(0xc, "res.bin", …)` (21 call sites). **CONFIRMED.**
- **Addressing limits auto-xrefs (honest caveat):** pi32v2 loads pointers either as full `mov rX,#imm32` or,
  very commonly, as **`add rX, base, #offset`** off a per-function section-base register. Ghidra resolved only
  ~63 string refs automatically; there are **no absolute pointer tables** for the phrase/tone strings (a raw
  4-byte-VA scan of the image finds them 0 times — only `MGlasses-living` has one absolute pointer). So
  string→handler mapping is per-function work in the decompiler, not a global table scan. This is why the
  taxonomy below is grounded in **string/table evidence** (rock-solid) with decompilation used surgically.

## 2. Memory map & board config — CONFIRMED

| Item | Value | Source |
|------|-------|--------|
| Code / image base (VA) | `0x6000000` (file 1:1) | `jlfw.yaml` `base-offset 0`, coherent disasm |
| `entry-point` (yaml) | `0x6000100` | `jlfw.yaml` (a mid-routine address; the true reset stub is at `0x6000000`) |
| Reset stub | `0x6000000` → `sp=ssp=0x103840`, calls `0x605ed0c`, `0x605c412` | disasm |
| Data / SRAM | stack top `0x103840`; globals seen up to `~0x1077d5+` | reset stub + `FUN_060546ea` (`_DAT_001077d5`) |
| Chip key | `0x1607` | `jlfw.yaml`, `config.dat`/`isd_config` |
| Debug UART | TX **PB02**, RX **PP00** | `isd_config.ini` (`UTTX PB02 / UTRX PP00`) |
| LED ("pilot lamp") | **PB03** | `config.dat` key `pilot_lamp_io` = `"PB03"` |
| Power button | **PC03** | `config.dat` key `power_io` = `"PC03_1"` |
| BT link key (16 B) | `06 77 5f 87 91 8d d4 23 00 5d f1 d8 cf 0c 14 2b` | `config.dat` key `link_key` |
| SPI flash cfg | `SPI 2_3_0_0` | `isd_config.ini` |

`config.dat` is a JieLi config table (magic **`JCRT`**) of `{key, offset, size}` entries:
`ver_info` (6 B = `00 00 00 00 00 01`), `ver_info_ext` (37 B = `"hE9yfseX6UdK7rFh,jl_sdk_ac697_publish"`),
`reset_io`, `pilot_lamp_io`, `link_key`, `power_io`. `cfg_tool.bin` adds `AC701N`, `AC701N-demo`, `3.0.0`,
`patch_02.03`, `earphone`. **Heritage note:** the Core is JieLi's **TWS-earphone SDK** re-skinned as glasses
(hence A2DP/HFP/music/call features below); `ver_info_ext` even carries the SDK tag **`jl_sdk_ac697_publish`**
(built from the ac697 SDK tree, though the chip is AC701N).

## 3. RTOS tasks & subsystem map — CONFIRMED (task-name strings)

The Core is JieLi's RTOS. Task/thread names (VA cluster `~0x60cd800`): **`jl_kws`** (wake-word),
**`ai_voice`**, **`moy_recorder`**, `dev_flow`, `app_core`, `btstack`, `I2cTask`, `xCommon`, `a2dp_dec`,
`file_dec`, `esco_adc`, `audio_vad`, `adda_loop`, `MIN_TASK`/`HOUR_TASK`/`DAY_TASK` (timers), and the two
inter-chip UART tasks **`UseUartRecvTask`** / **`UseUartSendTask`**. This is the firmware's live subsystem
decomposition, and it lines up with the SDK `RunningStatus` map ([01 §3](01_Hardware_Architecture.md)).

## 4. On-device voice / wake-word / AI stack — CONFIRMED

The always-on **Core** owns the wake word and the offline command grammar (not the Vision SoC):
- **Wake-word engine:** **`jl_kws`** (JieLi Keyword Spotting) task + **`batasr`** offline ASR
  (`batasr init succ/error`, `batasr_lib_uninit`, `batasr_start_proccess`, `batasr_stop_proccess`,
  `audio_moy_asr`, `audio_vad`).
- **Two wake phrases:** **`hello_echo`** (EN) and **`Nihao_Xiaoke`** (你好小可, ZH).
- **Offline command grammar** (contiguous string table `0x60d611c–0x60d647c`, delimited by `<eps>…<eps6>`):
  `Take_a_photo` / `Take_photo`, `Take_a_video`, `Start_recording`, `Answer_call`, `Reject_call`,
  `Start_audio_recording`, `Stop_audio_recording`, `Stop_recording`, `Play_music`, `Pause_music`,
  `Previous_track`, `Next_track`, `Increase_volume`, `Decrease_volume`, `Find_my_phone`, `Check_battery`,
  `Power_off`, `Photo_Recognition`, `Echo_power_off`. ⇒ the glasses run a **local voice-command vocabulary**;
  `Photo_Recognition` is the `takePhoto(ModeAIRecognition)` trigger, the rest map to media/BT/volume actions.
- **AI dialogue / voice** uses `ai_voice` + Opus (see §8) and drives tones `tone_en/wait_ai`, `end_ai`.
- **Photo/video handlers** validate with `TakePhoto: Invalid mode` (mirrors the SDK `TakePhoto.PhotoMode`),
  and cue `tone_en/take_pic`, `tone_en/{p4,t4}_living` (streaming), `capture_sync`.

## 5. RCSP / BLE control surface (device side) — CONFIRMED strings, opcodes UNVERIFIED

The phone↔glasses control protocol is JieLi **RCSP** over BLE + SPP. Device-side evidence:
- **Framework:** `rcsp`, `jl_rcsp_ble_test`; BT profiles **`JL_A2DP`**, **`JL_HFP`**, **`JL_HID`**,
  **`JL_SPP`**; classic-BT HFP AT set (`AT+BRSF/BIND/VGS/VGM/CNUM/CIND/CHLD/CGMI/BVRA/BIA/BCS/VTS`, `+CME
  ERROR`, and the concatenated result-code table `CIEV VGS VGM BRSF CIND CLCC CLIP CNUM …`).
- **Payload encoding = nanopb (protobuf):** the library's error strings are present — `array/bytes/varint/
  string overflow`, `wrong wire type`, `callback error/failed`, `bytes size exceeded`. This confirms the
  "large-data" BLE payloads the SDK calls `conn.protos.*` are **nanopb** on the device
  ([04](04_BLE_Protocol_Reference.md)).
- **Version handler:** `VersionInfo: Invalid type` is the RCSP handler that rejects an unknown
  `VersionInfo.VersionType` — the device-side of `queryDeviceVersion(...)` (see §7).
- **Limit (honest):** the numeric **RCSP opcode ↔ handler table** was **not** extracted — the build is
  stripped and dispatch strings are base-register-addressed, so opcodes need a **BLE sniff** or a focused
  decompile of the dispatch (TODO; feeds [14](14_InApp_OTA_and_the_JieLi_lib_gap.md) / a fuzz surface).

## 6. Core↔Vision inter-chip link = **UART** — INFERRED→strong (was "UART/SPI?")

No `aglink`/`ag_` string exists on the **Core** side (that library lives on the Vision, `libaglink.so`).
The Core names the link plainly as **UART to a "slave"**:
- Tasks `UseUartRecvTask` / `UseUartSendTask`; helpers `checkSlave`, `CheckUart`, `ble_slave_mult`.
- **8 stream channels** `jlstream_0 … jlstream_7` (`0x60da1be…`) — the Core-side handles for the Vision's
  **mode dispatch 0..7/8** ([09](09_Vision_Firmware_V821.md)); `jlstream_*` / `jlstream_0` are the format
  templates. **INFERRED:** the Core selects the Vision capture/stream mode over UART by channel index.
- **UART firmware push:** OTA image names include **`Zuart_ota2.bin`** and **`Zuart_user.bin`** plus
  `UART_UPDATE_CUSTOM` — the Core can update over/through UART (i.e. relay images to the slave).
- The Core knows the **Vision's** license path **`/mnt/UDISK/aw_LICENSE/aw_licecse.license`** and has
  `serial_num`, so the UART protocol carries **auth/serial** as well as media/status/version.

⇒ This upgrades [01 §5](01_Hardware_Architecture.md) item 2 from "UART or SPI (unverified)" to **UART
(strongly inferred from the Core firmware)**; the physical pins still want a teardown to confirm.

## 7. The version-relabel question — **REVISED with decisive evidence**

**Prior hypothesis** ([10 §3](10_Cloud_and_Device_Auth.md)): the app shows Vision `2.4.0.22.3.2603302218`
while the Vision image internally reports `1.4.0.20.3.2603302218`, and "by elimination" the `1.4.0.20→2.4.0.22`
relabel is applied **on the Core**, findable as a rule in `app.bin`.

**What the Core image actually shows (CONFIRMED by exhaustive search of every decrypted Core artifact —
`app.bin`, `config.dat`, `stream.bin`, `cfg_tool.bin`, `p11_code.bin`, tones):**
- **`2.4.0.22`, `1.4.0.20`, and the build-stamp `2603302218` appear NOWHERE in the Core.** The only dotted
  versions in `app.bin` are `0.0.8` (inside `MOY-A073-0.0.8`, the Core's own `VerFirmware`), a stray
  component `0.1.0`, and the libav tags `59.17.102` / `59.21.100`.
- The Core has **no version format string** — its only `printf` templates with dotted numbers are the two
  **IP-URL** formatters `rtsp://%u.%u.%u.%u:%u/%s` and `http://%u.%u.%u.%d%s`. There is **no** `%d.%d.%d.%d`.
- The Core's own version data (`config.dat`: `ver_info` = `00 00 00 00 00 01`, `ver_info_ext` = an SDK tag)
  contains no `2.4.x` either.

**Conclusion (corrected):** the Core does **not** hold or synthesize `2.4.0.22`. It therefore obtains the
Vision version **at runtime over UART** and relays it as `VerFirmware1`. The strong "by elimination → static
Core relabel" claim in [10 §3] is **refuted in that form** — the string is absent from the Core too. Two
possibilities remain, and Core-static analysis **cannot** decide between them:
- **(a) Relabel is on the Vision side.** The Vision reports a *product* version (`2.4.0.22.3.2603302218`) to
  the Core over aglink/UART that differs from its *internal* build version (`1.4.0.20…` in
  `ag_user_version.conf`); the Core relays it verbatim. The preserved, unique build-stamp `2603302218`
  strongly favours "same version data flows through, only the leading tuple differs".
- **(b) Runtime numeric transform on the Core.** Possible in principle, but there is **no printf/format
  evidence** for it in the Core — which argues *against* (b).

**INFERRED (leaning (a)):** the relabel is applied **before** the Core, i.e. on/for the Vision's
aglink-reported version — not baked into the JieLi firmware. **To close it (TODO):** sniff the Core↔Vision
**UART** during `queryDeviceVersion(VerFirmware1)`, or re-inspect the Vision image for a *second*
(product-facing) version field exposed via the aglink IPC (distinct from `ag_user_version.conf`'s
`1.4.0.20`). See [10 §3](10_Cloud_and_Device_Auth.md) for the corrected write-up.

## 8. Audio, media & OTA details — CONFIRMED

- **Codecs/audio:** `msbc` (HFP wideband), Opus recording — files named `%d.opus`; recorder writes libav
  container tags **`Lavf59.17.102`** / **`encoder=Lavc59.21.100 libopus`** (an FFmpeg/libav-derived muxer on
  the Core). Volume domains: `Vol_Sys/Btc/Btm/Btd`, `Vol_SysRing`, `Vol_BtcCall`, `Vol_BtmMusic`,
  `Vol_SysTone`, DSP blocks `MusicEq/Drc`, `EscoDlEq/UlEq`, `stereo_widener`.
- **Streaming role:** the Core **formats the RTSP live URL** `rtsp://<ip>:<port>/<path>` and the media
  `http://<ip>...` URL from the Vision's IP and relays them over BLE (`onLiveUrlChanged`), and advertises
  **`MGlasses-living`** for the live session. Consistent with the RTSP-on-this-unit finding
  ([06](06_Live_Video_Streaming.md), [11](11_Streaming_Bitrate_Analysis.md)). `wifi mode not support:
  p2p-gc`, `wifi start failed` are Core-side status for the Vision Wi-Fi it coordinates.
- **OTA transports (dual-bank JieLi DFU):** `Zble_ota.bin`, `Zble_app_ota.bin`, `Zspp_app_ota.bin`,
  `Zedr_ota2.bin`, `Znor_ota.bin`, `Zsd_update2.bin`, `Zuart_ota2.bin`, `Zuart_user.bin`, `ota.bin` —
  i.e. BLE / SPP / EDR / NOR / SD / **UART** update paths (the last two relevant to reaching the Vision).
- **Tones** (`tone_en/*.wts`, EN+ZH pairs `*_ch`/`*_en`): `awake`, `take_pic`, `call`, `low_battery`,
  `{p4,t4}_living`, `wait_ai`/`end_ai`, `no_wear`, `factory`, `reset`, `pow_on/off`, `e_re_a`/`e_re_v`
  (enter record audio/video), `s_re_a`/`s_re_v` (stop record), `charging`, `message`, `game_out`, `touch`,
  `key_tone`, `tws_dconn`, `{p4,t4}_{hello,timeout,conflict,memory,lowbat}`.
- **Auth storage:** Core keeps `flash/app/MAUTH` and `link_key`; references the Vision's
  `aw_LICENSE/aw_licecse.license` + `serial_num` (the device-auth in [10](10_Cloud_and_Device_Auth.md)).

## 9. Honest limits & recommended next steps

- **Achieved:** architecture proven **pi32v2**; a working, reproducible Ghidra disassembler/decompiler for
  the Core (2,605 functions); a grounded string/table map of the whole stack; the version-relabel question
  moved from hypothesis to an **evidence-backed correction**.
- **Not achieved (stripped build + base-relative addressing):** numeric **RCSP opcode↔handler** table; the
  exact **version-relay** code path; per-command decompilation of the ASR/photo/video handlers. All are now
  *tractable* with the module in hand — they just need focused per-function decompiler tracing (start from
  the reset chain `0x605ed0c`/`0x605c412`, the config reader `FUN_0603e19a`, and the `VersionInfo` handler).
- **Next (E3 / follow-ups):** (1) **UART sniff** to settle §7 and map the aglink/UART frame format + the
  `jlstream_0..7` mode commands; (2) decompile the RCSP dispatch for the opcode map (helps the stalled
  Core OTA, [14](14_InApp_OTA_and_the_JieLi_lib_gap.md)); (3) decrypt all 15 builds and diff `0.0.8↔0.1.x`
  (same chipkey) to isolate the STA↔AP Wi-Fi change ([10 §4](10_Cloud_and_Device_Auth.md)); (4) Core
  patch → `recrypt.py` → dual-bank flash.

## 10. Tooling / repro
- Module: **[quarkslab/ghidra-jieli](https://github.com/quarkslab/ghidra-jieli)** (improved fork of
  **[kagaimiq/ghidra-jieli](https://github.com/kagaimiq/ghidra-jieli)**; format notes at
  `kagaimiq.github.io/jielie/cpu/pi32v2.html`). Language id `pi32v2:LE:32:default`.
- Ghidra 12.1.2 (JDK 21); `support/sleigh.bat` to compile the spec; headless import at base `0x6000000`.
- Driver scripts + raw dumps are in the session scratchpad; curated evidence is committed under
  [`reference/core-jieli/`](reference/core-jieli/). Decryption + chipkey: [08](08_Core_Firmware_Jieli.md).
