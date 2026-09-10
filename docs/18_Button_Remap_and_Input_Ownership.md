# 18 — Button Remap & Physical-Input Ownership (power / AI / touch)

Can the glasses' **physical inputs be remapped** to custom actions — and could a **single "dynamic"
firmware** let the phone app reconfigure them at runtime instead of reflashing each time? This doc is the
grounded answer, from a read-only RE of both chips (Core `firmware/core-jieli-a073/_decrypted/app.bin`,
MOY-A073-0.0.8, VA base `0x6000000`, file 1:1; and a Vision `-ai` `.swu`). Companion to
[15 — Core `app.bin` Analysis](15_Core_App_Analysis.md), [09 — Vision Firmware](09_Vision_Firmware_V821.md),
[01 — Hardware](01_Hardware_Architecture.md), and the append-only log [§27](moyoung_reverse_engineering.md).
Investigation date **2026-08-10**.

> Tags: **CONFIRMED** (seen in the binary / a shipped artifact), **INFERRED** (deduction), **OPEN** (needs a
> runtime test or an artifact we don't hold). Confidence is stated explicitly and deliberately **not**
> rounded up — several claims here are inference-by-elimination, not end-to-end traces.

---

## 0. Bottom line (with honest confidence)
The device inputs the user confirmed physically: a **power button** (single/double/long → photo/video/audio),
a **second "AI" button**, and a **right-temple capacitive touch strip** (volume + media).

- **The Allwinner V821 (Vision) does NOT read any of these inputs — HIGH confidence (~85–90%).** Strong
  *negative* evidence from one unpacked `-ai` build. **CONFIRMED-in-that-image / INFERRED for the exact
  on-device build.**
- **Power button is on the JieLi Core — ~95%, CONFIRMED (directly observed):** `power_io=PC03_1` in the Core
  config + a decompiled handler.
- **AI button is on the Core — CONFIRMED at runtime (~95%), 2026-08-11:** pressing *only* the AI button drove
  the Core→Vision link + a BLE media-count change, with the Vision owning no input HW ([§7](#7-runtime-confirmation-2026-08-11-e5)).
  *(Static-inference caveat retired.)* **But the observed action was AUDIO, not the mode-4 vision I had
  inferred — see §7.**
- **Touch strip is on the Core — ~60–70%, INFERRED-by-elimination:** Vision has no touch HW and volume/AVRCP
  are BT-audio (JieLi) functions, but **no touch handler was located in the Core `app.bin`** (likely
  ROM/framework). This is the weakest claim here.
- **Remapping the *action* is code-editable** for the power + AI paths (patch points identified). A
  **flash-once "dynamic" firmware** that lets the app reconfigure mappings at runtime is **architecturally
  plausible** but is real firmware-dev (code injection + NVM + a BLE command), not a byte swap — and it still
  rides the **unproven re-encrypt/flash (E3) gate**.
- **Everything here is static RE with no runtime confirmation** (a teardown was declined). One teardown-free
  runtime check would upgrade AI/touch from INFERRED to CONFIRMED — see [§6](#6-what-would-close-the-gaps).

---

## 1. The Allwinner side owns no input hardware — CONFIRMED (in the build examined)
Unpacked `firmware/vision-v821/aiglass-ai/20260721182507_openwrt_v821_aiglass-ai.swu` (rootfs + `user` +
carved `board.dtb`→`board.dts`; extract in scratchpad `vision-extract/`):
- **Device tree:** no `gpio-keys`/`gpio-keys-polled`, no `linux,code`, **no I2C/SPI touch-controller node**
  (no goodix/ft5x/cst/focaltech/sitronix/hynitron/atmel_mxt). `gpadc0` (the resistor-ladder button ADC) is
  `status="disabled"`; `wakeup_io` disabled; all three TWI/I2C controllers have **no child devices**.
- **Kernel:** the only module in `/lib/modules/5.4.220/` is `v821_smac.ko` (Wi-Fi). No touch/input/key driver.
- **Userspace:** across all `ai_glass_*` binaries + `libaglink.so`, **zero** references to `/dev/input`,
  `input_event`, `EVIOCG*`, `/sys/class/gpio`, or any I2C touch read. "touch"/"media" string hits are the
  busybox `touch` command and media-*file* sync — **no volume/AVRCP/play/pause code anywhere on the Vision**.
- **RISC-V e907 co-proc** (`riscv` amp ELF): camera/ISP + rpmsg + Wi-Fi-PHY only; `main_key`/`sub_key` are
  Allwinner **sysconfig INI** parser terms, not physical keys.
- **aglink:** no KEY/BUTTON opcode and no key-event producer. The Vision is a pure **mode-driven slave** — it
  reads `/sys/kernel/aglink_mode` (Core-set) and launches one arg-less app per mode; "AI" is **mode 4**.

**Caveat (why not 100%):** one firmware build was examined, not verified byte-identical to the exact version
on the unit; and the DT/driver absence is decisive for *hardware presence* but was not runtime-confirmed.

## 2. The physical-key handler on the Core — `FUN_0601ee90` — CONFIRMED
`FUN_0601ee90` @ `0x0601ee90` is the app's master key-event callback. It reads a key message (u32):
**low byte = `key_value`, high byte = key group** (0 = short/primary, 1 = long/alt), then dispatches straight
to the action funnels. (**Correction to earlier passes:** `FUN_06018130` and the `0x0602a560`/`0x0602axxx`
cluster are the **RCSP/BLE nanopb** message path — `_DAT_0041ccfc` is written only by protobuf decode
`FUN_0602027e` — **not** physical keys. Evidence: scratchpad `dec_keyhandler.txt`/`dec_phase0.txt`/
`dec_phase1.txt`.)

**Input map (CONFIRMED-from-bytes), patch points all in `FUN_0601ee90` (absolute `call`/`je` — addressing-safe):**

| Input / gesture | Key code (group, value) | Current action → sink | Patch point |
|---|---|---|---|
| Power — single | (0, 0) | photo `FUN_06018076` | call @ `0x0601ef32` |
| Power — double | (0, 4) | video `FUN_06017fee` | `je #4` @ `0x0601ef28` |
| Power — long | (1, 4) | audio `FUN_06017cca` | tbb → `0x0601f096` |
| **AI button** | (0, 0x10) | AI dialogue / photo-recognition `FUN_0601ed90`→`FUN_0601ed14`→`FUN_060183ca(5,9)` (mode-4/slot-9) | `je #0x10` @ `0x0601ef20`, or the `FUN_0601ed90` call @ `0x0601efc0` |
| **Touch strip** | *(unresolved — see §3)* | volume / media | empty group-1 slots `2,5,6,7,8,9,10` (currently no-op) |

**Action funnels available as remap targets:** photo `FUN_06018474`, video `FUN_06017fd6`, audio
`FUN_060184c4`, AI `FUN_0601ed14`, livestream-stop `FUN_0600cee8`, volume `FUN_0601862c`(+)/`FUN_06018638`(−),
AVRCP `FUN_060185f0`/`FUN_06018618`/`FUN_06018604`, raw Core→Vision UART verb
`FUN_0600ce60(payload,cmd,len)` (photo = cmd `0x0a`, header `'A''W'`), app-notify `FUN_0600a308(idx,val)`
(RunningStatus + dirty flag → BLE notify), event bus `FUN_0600435e(code,arg)`.

## 3. The two honest gaps
1. **The touch strip's volume/media action is NOT in the Core key table (this build).** The volume/AVRCP
   sinks are called **only** by the voice dispatcher `FUN_06018644` — re-verified with an indirect/pointer-table
   caller scan, and no other emitter of the `0x3c`/`0x3d` volume opcodes exists. So the temple strip is either
   handled in the **JieLi ROM/framework** (standard for earphone volume keys) or arrives as the currently-empty
   group-1 key slots. **We did not observe its handler.** INFERRED that it's Core-owned (Vision has no touch
   HW; volume is BT-audio); NOT proven.
2. **The raw pin/ADC-band/touch-channel → `key_value` SCAN driver is in the AC701N maskROM, not `app.bin`.**
   `config.dat` (JCRT) has only `power_io=PC03_1` (+ `reset_io`, `pilot_lamp_io`, `link_key`, `ver_info*`);
   there is no adkey/touch config, and `gpadc`/`DBG_IIC`/`DBG_SDTAP` are clock-domain names, `I2cTask` a
   pointer-math anchor. So we can't name the AI button's exact GPIO/ADC from firmware. **But the ACTION map for
   every input is in `app.bin` and every action patch point is identified** — remapping = repoint an action,
   fully in scope.

## 4. Static remap (flash per change) — feasible for power + AI
Patch a `FUN_0601ee90` dispatch (e.g. AI button `(0,0x10)` → a different funnel, or power single → video) →
re-encrypt (JieLi 16-bit LFSR, chipkey `0x1607`, symmetric; `kagaimiq/jl-misctools`) → reinsert into the
`MOY-A073-0.0.8` `.ufw` (fix len/CRC) → **round-trip verify** (decrypt == patched) → Core dual-bank BLE OTA
(`jl_bt_ota` v1.10.0, app `ota.tsx`). Dual-bank A/B auto-rolls-back a no-boot. **This first modified-Core flash
is the project's open "E3 / modified-Core bridge" proof** ([paths_forward.md](paths_forward.md) E3) — the
re-encrypt/repack toolchain is **not yet demonstrated**. OPEN.

## 5. "Dynamic" firmware — flash once, configure over BLE — PLAUSIBLE (design, not yet built)
Instead of reflashing per mapping, flash **once** a config-driven dispatcher and let the app set behavior at
runtime. Design, grounded in what we found:
- Replace `FUN_0601ee90`'s hardcoded branches with a **table lookup**: `action_id = keymap[group][key_value]`
  → `dispatch[action_id]()`, where `dispatch` is a jump table of the funnel addresses in §2.
- Store `keymap` in the **JieLi flash VM/syscfg** so it survives reboot; ship a **safe default + reset** so a
  bad config can't leave the buttons dead.
- Add a **BLE/RCSP command** to write `keymap`. Likely *extends* an existing mechanism rather than inventing
  one: the app already pushes settings to the Core over RCSP (nanopb), and there is a `buttonDetection`
  settings field — worth confirming as a hook.
- **Killer tier:** include an action "**notify the app on press**" (via `FUN_0600a308`/`FUN_0600435e`). Map a
  gesture to that and the **app** decides what happens — arbitrary, phone-side, changeable without touching
  firmware again (limited to when the phone is connected; on-device standalone actions must be done by the
  firmware directly).

**Effort/risk (honest):** this is code injection (a code cave + pi32v2 asm + NVM read/write + a BLE opcode),
not a one-byte swap; it still needs the E3 flash toolchain; and the app can only pick actions the firmware
knows (the funnel list) plus the "delegate to app" escape hatch. Still **flash-once**, then dynamic.

## 6. What would close the gaps
A **teardown-free runtime check** upgrades AI-button + touch from INFERRED to CONFIRMED: press each input while
pulling the Core logs over BLE (`downloadLogFile`, the OTA-log channel) and/or watching the app's
`CRPShutterListener.onShutter`/event callbacks. If the Core reacts and reports the keycode, ownership is
proven and the touch `key_value`(s) are revealed (which also unblocks a touch remap). No case-opening needed.

**Decision (2026-08-10): user chose to STOP AT ANALYSIS** — feasibility answered; no firmware modified or
flashed. Evidence: scratchpad `core-analysis/gproj2/jlfull`, `dec_keyhandler.txt`/`dec_phase0.txt`/
`dec_phase1.txt`, `vision-extract/board.dts`. Plan snapshot: `~/.claude/plans/floofy-marinating-badger.md`.

## 7. Runtime confirmation (2026-08-11, E5)
Ran the teardown-free check by pressing physical inputs and pulling the device log over BLE
(`downloadLogFile`). **Key method note:** that channel returns the **Vision** per-boot `aglink_*.log` files
(named `aglink_<ts>_<mode>.log`, suffix = boot mode: 0=photo, 1=video, 2=download, 3=ota, 4=AI, 6=audio) +
live `[CRP] media photo/video/audio` counts over BLE — i.e. it shows **downstream effects on the Vision, not
the Core keycode.** So it confirms *ownership and action*, but **cannot** resolve single-vs-double-vs-long
per gesture. Sensor also identified from these logs: **Sony `imx681a` (IMX681) MIPI**.

**Run A — power button** (single/double/long, mixed): fresh Vision boots in **PHOTO/VIDEO/AUDIO** modes at the
press timestamps, and BLE counts moved **video 4→5, audio 0→3**. ⇒ power path **Core-confirmed live** (Core
decoded the presses and commanded the Vision; Vision owns no input HW).

**Run B — AI button ONLY** (user pressed the AI button repeatedly — single/double/long — and NOT the power
button): exactly **one** new Vision boot, **mode `_6` = AUDIO** (`aglink_20260811094436422_6.log`), BLE count
**audio 3→4**; photo/video unchanged. ⇒ **two findings:**
1. **AI button is Core-owned — CONFIRMED.** A press with no power-button involvement produced a Core→Vision
   command + media-count change, and the Vision owns no input HW ⇒ the Core read it.
2. **Action correction:** the AI button fired an **AUDIO capture (mode 6)**, i.e. it behaves like a
   **press-to-talk / voice-AI trigger** — **not** the mode-4 "AI vision / photo-recognition" path I inferred
   statically in §2. The static *ownership* held; the static *action* did not. (Ground-truth per-button
   meanings to be taken from the **official app**, not inferred — pending.)

**Still open:** the **touch strip** (volume/media) will not appear in this Vision-log channel (Core-local
BT-audio) — confirm behaviorally (audible volume change while the phone streams audio to the glasses). And a
precise per-gesture map needs the official-app button spec (pending) rather than these effect-only logs.
