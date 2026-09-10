# MoYoung Glasses — Paths Forward (living roadmap)

> **Purpose:** a menu of every avenue we've identified but not yet finished, so this thread can be the
> "main" and we can pick up any thread later without re-deriving it. Companion to the append-only journey
> log [`moyoung_reverse_engineering.md`](moyoung_reverse_engineering.md). **Update this file freely** (unlike
> the RE log) — tick items off, add new ones, re-prioritise.
>
> Legend — **Risk:** 🟢 none/offline · 🟡 reversible/soft · 🔴 can brick. **Needs HW** = requires the physical
> device (or a teardown).
>
> **📋 Table maintenance — KEEP THIS CONVENTION (persisted instruction):** the **Quick-reference priority
> table** at the top holds **ONLY current/open paths** — anything not finished (investigate / ready / blocked
> / parked / declined). The moment a path is **finished**, **move its whole row out** of the top table and
> into the **[✅ Done table](#-done--completed-paths)** at the bottom — do **not** just strike it through in
> place. This keeps the top table a clean menu of what's actually left to do, and the bottom table the record
> of what's been accomplished. Last updated 2026-08-10.

## Quick-reference priority table

| ID | Path | Risk | Needs HW | State | Value |
|----|------|------|----------|-------|-------|
| **A2** | Force H.265 (`encode_format=1`) → built-in 12 Mbps path | 🟢 | no | **HEVC branch found** ([11](11_Streaming_Bitrate_Analysis.md)); OPEN = can `encode_format` be forced to 1, **and** does the phone app decode HEVC (both unverified) | high |
| **B2** | **Native push FROM the glasses** (RISC-V RTMP/SRT client → server, no phone in the loop) — the only path that removes the phone-relay | 🟡 | yes | **gated on D2** (needs a Vision flash, currently blocked) **+** only works where the glasses have their own internet egress; does **not** rescue the "glasses can't join a network" case (only the phone-AP relay does) | med |
| **C1** | Teardown → UART (`ttyS3`) console → root shell (the enabler for everything) | 🟡 | yes | **declined by user (2026-08-07)** — the only way to read the `swupdate` no-op reason (D2) | ⭐ unlocks A/D |
| **D2** | Flash the patched Vision `.swu` via app `startAllwinnerOta` | 🟠 | yes | **BLOCKED at device (2026-08-07):** phone serves full file + Wi-Fi holds (no `onLost`), device reports `SUCCESS` **without flashing** (12/12, genuine `-ab`) → **not phone/transport**; `swupdate` reason needs `ttyS3` teardown (declined) ([14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md), [log §24](moyoung_reverse_engineering.md)) | high |
| **D4** | Flash via the OFFICIAL app + MITM (forge `check-upgrade`) | 🟠 | yes | **PARKED** — worked 2026-07-30, but `check-upgrade` can no longer be intercepted (inferred pinning); superseded by in-app Core OTA (D1) ([13](13_OTA_via_Official_App_MITM.md)) | low |
| **D5** | In-app OTA — Core DONE; **Vision blocked at device (masked no-op)** | 🟠 | yes | Vision reports `SUCCESS` without flashing (12/12) → reason needs `ttyS3` teardown, declined ([14 §3,§9](14_InApp_OTA_and_the_JieLi_lib_gap.md)) | ⭐ Core proven |
| **E3** | Core mods: patch `app.bin` → re-encrypt (`0x1607`) → repack `.ufw` → flash in-app | 🟡 | yes | **UNBLOCKED** — flash channel proven (D1); open Q = does the device accept a *modified/re-encrypted* image? | high |
| **E4** | Core↔Vision **UART sniff** + RCSP opcode map (settle the version relabel; map `jlstream_0..7`) | 🟡 | yes | ready (needs UART pads / BLE sniff) | high |
| **E5** | Confirm AI-button + touch-strip ownership at runtime (teardown-free, `downloadLogFile` → Vision mode logs + BLE media counts) | 🟢 | device on BLE | **AI button CONFIRMED Core-owned (2026-08-11)** — AI press→Vision AUDIO mode (not the inferred vision/mode-4); power path confirmed; **touch still pending** (behavioral) ([18 §7](18_Button_Remap_and_Input_Ownership.md)) | med |
| **E6** | **Dynamic app-configurable button firmware** — flash-once `keymap`-in-flash-VM dispatcher in `FUN_0601ee90` + a BLE/RCSP write command + "notify-the-app" action; rides E3 ([18 §5](18_Button_Remap_and_Input_Ownership.md)) | 🟡 | yes | design only | med |
| **G1** | Responsible-disclosure writeup (open fw server, no-auth OTA API) | 🟢 | no | not started | portfolio |

---

## A. Streaming quality — raise the encoder bitrate (primary goal)

Root cause is **confirmed** (§8 of the RE log): `ai_glass_livestream` hardcodes `c0` bitrate = `1500000`
(0x16E360); `--bitrate` is not wired into the live path.

- **A1 — Patch the constant.** Find the `lui`/`addi` immediate pair loading `0x16E360` in
  `ai_glass_livestream`, rewrite to 6 Mbps (`0x5B8D80`) or 8 Mbps (`0x7A1200`), drop the binary back into the
  rootfs, `mksquashfs` (lzo, 32 KB block), refresh `cpio_item_md5`, repack the newc-CRC CPIO. **Offline; produces
  a concrete artifact** that just waits for a flash path. Template: Cyan's `repackage_swu.py` (§7 there).
- **A2 — Force H.265.** The decompile shows `if (encode_format==1) target = 0xC00000` (12 Mbps, VBR 10–14).
  Trace where `encode_format` is set from the "PresetConfig" (`FUN_ram_0001eedc`), see if it can be forced to 1,
  and **verify the phone app decodes HEVC** before committing. Bigger jump + better compression than A1.
- **A3 — Keep resolution, only lift bitrate** (safest visual win) vs. also try 1920×1080. 1600×1200 is already
  2 MP; bitrate is the lever, so A1 alone should transform quality.

## B. Make the glasses stream *to* a server (not host)

> **Framing (2026-08-10, per user):** "relay" is effectively **already solved by the phone** and moved to the
> [✅ Done table](#-done--completed-paths). The phone connects **directly** to the glasses (AP mode) and
> egresses over **cellular**, so it bridges glasses↔internet even under strict NAT — it does **not** depend on
> the glasses joining anyone's LAN. A *separate LAN relay box* is strictly worse (needs the glasses reachable
> on the LAN — exactly what strict NAT / Wi-Fi client-isolation breaks) and adds nothing over the phone. So
> the only forward path worth building is **B2 — removing the phone entirely.**

- **B2 — Native push FROM the glasses (firmware mod).** Cross-compile a small RISC-V RTMP/SRT client
  (ffmpeg/librtmp) into the Vision rootfs and change `rtc_init.sh` mode 8 to launch it instead of the live555
  RTSP server, so the glasses push straight to a public server with no phone in the loop. **Two honest
  gates:** (1) it needs a **Vision firmware flash → rides on D2, which is currently a device-side no-op
  (blocked)**; (2) it only works where the **glasses themselves have internet egress** (STA on an internet
  Wi-Fi) — in the "glasses can't join any network" case native push *also* can't reach the internet, and the
  **phone-AP + cellular relay remains the only option.** So B2 removes the phone dependency but is **not** a
  cure for the no-network case.

## C. Device access & recovery — THE enabler (unblocks A-flash, D, live edits)

> **2026-07-30:** the charging port has **no USB data line** (user-confirmed) → C2 (USB ADB) and C3
> (FEL) both require a **teardown**. Software alternative *hoped for*: the patched `.swu` enables
> **adb-over-Wi-Fi** (`ADB_TRANSPORT_PORT=5555`), so if it booted we'd get a root shell over Wi-Fi with
> no teardown — then live, reversible edits via the `/overlay` upperdir ([12 §7](12_Firmware_Patching_and_Flashing.md)).
>
> **⚠ 2026-08-04 — that software alternative is BLOCKED (chicken-and-egg).** The in-app Vision OTA runs to
> "completed" but the **device reports `SUCCESS` without flashing** (device-side masked no-op, 12/12 — [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)),
> so the `5555` adbd never starts. ⇒ **there is currently NO ADB access — USB *or* Wi-Fi — without a teardown.**
> Teardown-free device-side visibility now rests on the BLE **`downloadLogFile`** channel and/or a **modified
> Core image relaying the Vision's `aglink` UART** (both unproven — see E3 and the D2 note).

- **C1 — UART console.** Teardown, find the V821 serial pads, get a root shell → change the bitrate *live*
  (no flashing), pull the exact running firmware as our reflash baseline, and read the real mtd/partition map.
- **C2 — USB ADB.** `adbd` is an init.d service (USB-gadget FFS). If a USB data line is exposed/added, `adb`
  gives the same root shell over USB.
- **C3 — Allwinner FEL/FES (unbrick).** Over USB, `sunxi-fel`/`xfel` is the recovery net that makes a bad
  Allwinner flash survivable. Without C1/C2/C3 there is **no recovery** — which is why D2 is on hold.

## D. Firmware modification & flashing pipeline

- **D1 — Safe pipeline test (JieLi Core).** Flash the vendor-offered Core `0.1.0` via `startOta(File)` — it's
  **dual-bank A/B** (bad image rolls back), so it validates our app's OTA path end-to-end without risking the
  camera SoC.
- **D2 — Flash the Vision `.swu` — BLOCKED at the DEVICE (2026-08-07, corrected).** The in-app path runs
  end-to-end; the phone serves the whole file (`sendBody sent=12514304/12514304 complete`) and Android does **not**
  drop the Wi-Fi (a `NetworkCallback` watch logs **no** `onLost` across the transfer) — yet the device caps its
  reported progress (~45%), fires `completed`, and does **not** reboot or change version. BLE-pulled device logs
  show this **12/12 today** (`AG_AD_OTA_START ×12 → AG_VD_OTA_SUCCESS ×12 → AG_VD_OTA_FAIL ×0`) with the **genuine
  `-ab` image**. ⇒ **not the file, not the phone/transport** (the earlier "Wi-Fi transport truncation / AP-reaping"
  reading is **refuted**). The `swupdate` accept/reject reason goes to `ttyS3` (not the BLE-pullable logs), and
  whether the device received ~100% or stopped reading at ~45% is **undecidable teardown-free**. This unit is
  **dual-bank A/B** (inactive-slot write, rollback-safe). Full write-up
  [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md), [log §24](moyoung_reverse_engineering.md).
- **D2a — Laptop Node harness — OBVIATED (2026-08-07).** Its whole premise was "Android reaps the AP, so move the
  server off the phone." The `[NET]` watch **disproved** that (Wi-Fi held; phone served fully), so relocating the
  server cannot change the device's decision. It was **built anyway** as an in-app **external-server mode**
  (`startVisionOtaExternal`/`continueVisionOtaExternal`, pause at `awaitingExternalServer`; server at
  `tools/vision-ota-harness/`) and confirmed the device is a phone-driven **SoftAP** (per-session SSID
  `glasses_<rand>` + random WPA2 password the *phone* generates and sends in `WifiCtrl{ssid,password,channel,mode}`;
  `OTAPackageInfo` has **no URL/IP** → device fetches from the DHCP-leased station; subnets `192.168.5.1`/`.6.1`).
  Kept for reference; **not a path to a flash.**
- **D2b — Build a proper A/B `.swu` for this unit** (needed once transport works). Rebuild from the `-ab` base
  with **both** `stable.now_A_next_B` and `now_B_next_A` selections (`/dev/by-name/{bootB|A,rootfsB|A,riscv0-r}`
  + `systemAB_next` bootenv) — **not** the single `sdnand → mmcblk0p9` group our current `vision_noop`/`6mbps`
  builds use. **No signature/hash/hwcompat needed** (confirmed on-device). See [09 §1](09_Vision_Firmware_V821.md).
- **D3 — Repack tooling.** Build a reusable `mksquashfs`+`cpio_item_md5`+CPIO(070702) repacker (adapt Cyan's).

## E. JieLi Core (`.ufw`) — the second chip

- ✅ **E1 — Decrypt — DONE (2026-07-30).** The cipher is a 16-bit JieLi LFSR (not AES); chipkey **`0x1607`**
  is stored in the firmware's `isd_config.ini`. Decrypted with `kagaimiq/jl-misctools`
  (`fwunpack_newfw.py`) → `app.bin` + `uboot.boot` + voice prompts, all readable. Full writeup:
  [`08_Core_Firmware_Jieli.md`](08_Core_Firmware_Jieli.md).
- ✅ **E2 — Ghidra the decrypted `app.bin` — DONE (2026-08-03).** Core is **JieLi pi32v2**; stock Ghidra has
  no module but **`quarkslab/ghidra-jieli`** (pure Sleigh, `pi32v2:LE:32:default`) disassembles + decompiles
  it at base `0x6000000` (2,605 funcs). Recovered the RCSP surface (`JL_A2DP/HFP/HID/SPP`, **nanopb**
  payloads), the **`jl_kws`+`batasr` voice stack** with a full local command grammar (wake `hello_echo` /
  `Nihao_Xiaoke`), the **UART** Core↔Vision link (`jlstream_0..7`, `checkSlave`), and **corrected the version
  relabel** — `2.4.0.22`/`1.4.0.20` are **absent from the whole Core image**, so the Core relays (not
  synthesises) the Vision version. Full writeup [15](15_Core_App_Analysis.md). Left open → **E4** (opcode map
  + version-relay path need a BLE/UART sniff).
- **E3 — Core mods + re-encrypt**: patch `app.bin`, re-encrypt with `recrypt.py` (chipkey `0x1607`),
  flash via `startOta(File)` — dual-bank, safer than the Vision path. Decrypt all 15 builds to diff (same
  chipkey) — e.g. isolate the `0.0.8↔0.1.0` STA→AP Wi-Fi flip ([10 §4](10_Cloud_and_Device_Auth.md)).
- **E4 — Core↔Vision UART sniff + RCSP opcode map**: settle the version relabel (§E is corrected but not
  fully pinned), map the aglink/UART frame format and the `jlstream_0..7` mode commands, and recover the
  numeric RCSP opcode↔handler table (helps the stalled Core OTA, [14](14_InApp_OTA_and_the_JieLi_lib_gap.md)).

## F. Cloud / API surface

- **F1 — `altair-ai-hub`** — map `/api/v2/oauth/auth`, `/api/v3/activation-code/*`; understand the JWT scope
  and whether device endpoints are enumerable.
- **F2 — `deviceauth.allwinnertech.com`** — the Allwinner device-auth (`activationCode`, `customerIndex:4`,
  `device_license`); assess whether licensing is forgeable / what it gates.
- **F3 — Firmware server** — mirror more (other models for cross-reference), watch for new Vision builds, and
  use the many A073 Core builds for E1.

## G. Security findings → responsible disclosure & portfolio

- **G1** — Writeups (portfolio + optional disclosure to MoYoung/Allwinner): (a) **open directory listing** of
  the entire firmware factory (~46 GB, *every customer's* images) at `altair.moyoung.com/static/firmware/`;
  (b) **no-auth `check-upgrade`** API leaking firmware URLs; (c) **unsigned Vision firmware** — integrity is a
  plaintext `cpio_item_md5`, no signature, no hardware-compat gate (trivial malicious-image flash if you reach
  the OTA path); (d) per-device `device_license` + activation flow exposure.

## H. Companion app / tooling

- **H1** — Verify the tinker app's OTA screen end-to-end (`startAllwinnerOta` never run on hardware).
- **H2** — Wire the RTSP live view into the app (we already have the `onLiveUrlChanged` → URL flow).
- **H3** — BLE sniff (nRF Connect / nRF52840) to map the CRP wire format (UUIDs are built dynamically).

## I. Camera / sensor questions

- **I1** — Identify the sensor; compare **live (sub)stream vs recorded (main)** resolution — a captured
  photo/video may be higher-res than the 1600×1200 live view, telling us if the sensor/ISP can do more.

## J. Housekeeping / provenance

- **J1** — Keep appending to `moyoung_reverse_engineering.md` (append-only).
- **J2** — Preserve `firmware/` (server could lock down); it's outside the git repo so it won't push.
- **J3** — Decide the workspace git strategy (root not yet a repo — see the workspace `.gitignore` header for
  the submodule vs monorepo options).

---

## ✅ Done — completed paths

> The record of finished avenues, moved out of the priority table above (per the **📋 Table maintenance** rule
> at the top). Full detail lives in the linked docs / RE-log sections. When a top-table path finishes, add its
> row here.

| ID | Path | Finished | Result / where |
|----|------|----------|----------------|
| **A1** | Build the modified Vision `.swu` (bitrate 1.5→6–8 Mbps) | 2026-07-30 | `vision_6mbps_adb.swu` (rootfs-only) built + app-wired ([12](12_Firmware_Patching_and_Flashing.md)) |
| **B1** | Relay glasses→server (works today — the **phone** is the relay) | 2026-07 | glasses RTSP `:8554/ch0` pulled + re-published (MediaMTX/ffmpeg); the phone bridges glasses-AP↔cellular so it survives strict NAT — a separate LAN box adds nothing ([streaming_glasses_to_a_url.md](streaming_glasses_to_a_url.md)) |
| **D1** | Validate OTA pipeline via JieLi Core OTA (dual-bank A/B) | 2026-08-03 | in-app flash of genuine `0.0.9`, device rebooted, version → `0.0.9` (needs `jl_bt_ota` **v1.10.0**) ([14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md), [log §20](moyoung_reverse_engineering.md)) |
| **H1** | Guided in-app OTA screen (bundled builds + progress) | 2026-08 | ([12](12_Firmware_Patching_and_Flashing.md)) |
| **E1** | Decrypt the JieLi Core `.ufw` | 2026-07-30 | 16-bit LFSR, chipkey `0x1607`, `jl-misctools` ([08](08_Core_Firmware_Jieli.md)) |
| **E2** | Ghidra the decrypted Core `app.bin` | 2026-08-03 | pi32v2, `quarkslab/ghidra-jieli` (2,605 funcs); taxonomy + UART link + **version relabel corrected** ([15](15_Core_App_Analysis.md)) |
| **R1** | Research: same OTA behaviour on sibling/parent HW (HeyCyan/Cyan, V821) | 2026-08-07 | no public V821-glasses flash exists; siblings stuck at same wall; OEM=CRREPA/Kunpeng ([log §24](moyoung_reverse_engineering.md)) |
| **R2** | BLE/`aglink` attack-surface RE — teardown-free exec vector? | 2026-08-07 | full opcode set mapped; **no phone-reachable exec vector** (cmd-injection ruled out; no arbitrary file-write; factory=HW self-test) ([log §25](moyoung_reverse_engineering.md)) |
| **W1** | Custom wake word feasibility (JieLi `JL_KWS`/`batasr` RE) | 2026-08-08 | a new spoken word needs JieLi's trained-model pipeline (no self-serve compiler); grammar-only edits (disable/relabel) doable ([16](16_WakeWord_and_Offline_Voice.md), [log §26](moyoung_reverse_engineering.md)) |
| **K1** | Button/input remap — ownership RE across both chips | 2026-08-10 | all inputs are JieLi-Core, Allwinner owns none (calibrated: power CONFIRMED ~95%, AI INFERRED ~75%, touch ~60–70%); handler `FUN_0601ee90` + keycode→action map + patch points ([18](18_Button_Remap_and_Input_Ownership.md), [log §27](moyoung_reverse_engineering.md)) |
