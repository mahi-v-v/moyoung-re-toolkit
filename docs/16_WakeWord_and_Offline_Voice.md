# 16 — Wake Word & Offline Voice (JieLi `JL_KWS` / `batasr`)

Can we **add a custom wake word** (e.g. "hey glass" / "hello glass") to the glasses? This doc is the
grounded answer, built from a full reverse-engineering of the Core's offline keyword-spotting engine in
`firmware/core-jieli-a073/_decrypted/app.bin` (MOY-A073-0.0.8, git `C363B807`, VA base `0x6000000`,
file 1:1). Companion to [15 — Core `app.bin` Analysis](15_Core_App_Analysis.md) and the append-only log
[§26](moyoung_reverse_engineering.md). Investigation date **2026-08-08**.

> Tags: **CONFIRMED** (seen in the binary / a shipped artifact), **INFERRED** (deduction), **OPEN** (needs
> more work / an external artifact).

---

## 0. Bottom line
- **It was never "just a string edit."** Renaming a word symbol only changes the *label the device reports*,
  not the *sound it wakes on*. **CONFIRMED** (the acoustic trigger is not the ASCII label).
- **Adding a genuinely NEW spoken word cannot be reliably hand-authored** — not "glass" and not any other new
  word. The device's wake/command recognition is a **compact trained model** whose new-word expansion is done
  by **JieLi's server-side pipeline** (a licensed grammar/lexicon compiler + generic acoustic model). This is
  a *coupling/tooling* wall, not a flashing wall. **INFERRED-strong**, from the located model + the public-SDK
  research ([§26](moyoung_reverse_engineering.md)).
- **The flash path is NOT the blocker.** The Core BLE OTA is a proven, dual-bank A/B verified write of a
  *genuine* image ([14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md)); a bad grammar just fails to wake and rolls
  back — low brick risk. **Caveat:** whether the Core boots a *modified, re-encrypted* image is the still-open
  **E3 gate** ([paths_forward.md](paths_forward.md) E3) — not yet demonstrated. So a valid model would be
  flashable *pending E3*, not proven-flashable today.
- **Two real ways to actually get a new wake word:** (a) the **OEM (CRREPA) asks JieLi to train** a model
  containing the phrase; (b) **repurpose an existing already-trained phrase** as the wake trigger (grammar
  rewire — reliable, but limited to words the device already knows). See [§6](#6-routes-to-actually-add-a-wake-word).

## 1. The engine — `JL_KWS` (== `batasr`) — CONFIRMED
The always-on **Core (JieLi AC701N)** owns the wake word and offline command grammar, not the Vision SoC
([15 §4](15_Core_App_Analysis.md)). Evidence in `app.bin`:
- RTOS task **`jl_kws`** (keyword spotting) + engine **`batasr`** (`batasr init succ/error`,
  `batasr_start_proccess/stop_proccess/lib_uninit`, `audio_moy_asr`, `audio_vad`, `res.bin`).
- Public-SDK research ([§26](moyoung_reverse_engineering.md)) ties `batasr` to JieLi's **`JL_KWS`**
  (`jl_far_kws_model_process(kws, model, …)`), documented on the AC792 wifi-video SDK — the **same
  glasses/wifi-video chip class** as MOY-A073. Our on-device grammar = the `model` resource that call consumes.
- It is a **Kaldi/OpenFST-style WFST decoder** (epsilon markers `<eps>`…`<eps6>` in the tables).

**Two wake phrases + two command grammars, one per language** (CONFIRMED):
- **English FST** — wake `hello_echo` + the English command set.
- **Chinese FST** — wake **你好小可** ("Nihao Xiaoke", GBK) + the Chinese command set (开始拍照, 播放音乐, …).

## 2. The grammar FST format — fully reverse-engineered + VALIDATED
Each language grammar is a self-contained container: `[header][state table][arc array][output array][symbol
table]`. The two live back-to-back in `app.bin`, followed by a math LUT and the acoustic model (§4).

| Grammar | Container magic @ | Symbol table @ | States / Arcs / Z / nSym |
|---|---|---|---|
| Chinese | `0x0d4288` | `0x0d4fcc` | 223 / 360 / 77 / 28 |
| English | `0x0d5350` | `0x0d611c` | 232 / 374 / 81 / 28 |

**Header** (little-endian, at the 8-byte magic `f5 1a 2c 1b f7 6a 3c 2b`):
```
+0x00  f5 1a 2c 1b f7 6a 3c 2b   magic (identical for both FSTs)
+0x08  u32 = 0                    (flags/pad)
+0x0c  u32 = 1                    version
+0x10  u32 nStates
+0x14  u32 nArcs
+0x18  u32 Z         (= number of output-emission entries)
+0x1c  u32 nSymbols  (= 28; MATCHES the symbol-table record count exactly)
+0x20  body begins
```
**Body = CSR-encoded WFST.** For English (nStates=232, nArcs=374, Z=81) the 3500-byte body (1750 u16)
partitions EXACTLY as (this exact fit is the proof the layout is right):
```
[0    .. 465 ]  state table  : (nStates+1)=233 pairs (arcPtr, outPtr) — CSR row pointers
                               arcPtr cumulative 0..374(=nArcs); outPtr cumulative 0..81(=Z)
[466  .. 1587]  arc array    : nArcs=374 triples (ilabel, weight, nextstate)
                               ilabel = acoustic-unit id (0..1662); nextstate < nStates
[1588 .. 1749]  output array : Z=81 pairs (marker≈0xCD00.., value)
                               where value<28 → a word id (emission); other values reference states
```
**Symbol table** = array of **32-byte records**, name zero-padded (GBK Chinese / ASCII English),
**symbol ID = array index**. English table `0x0d611c`–`0x0d649c`.

**VALIDATION (CONFIRMED):** decoding the output array cold reproduces the word list — `word 1 → hello_echo`,
`word 2 → Take_a_photo`, `word 4 → Take_a_video`, `word 7 → Reject_call`, `word 16 → Next_track`,
`word 24 → Power_off`, `word 27 → Echo_power_off`, … Start hub = **state 8** (13 out-arcs). This confirms the
container format end-to-end; adding a *symbol* + wiring the grammar is mechanically understood.

## 3. Why the units aren't reusable phones — CONFIRMED measurement
The arc `ilabel`s are the acoustic units the grammar strings together. If they were simple monophones we'd see
~40–60 distinct values reused heavily. Instead:
- **English FST:** 374 arcs, **284 distinct** ilabels (76% unique), range 3..1662.
- **Chinese FST:** 360 arcs, **249 distinct** ilabels, range 12..1673.
- **Overlap: only 37 (13%)** — same ~1663-unit pool, but each language activates a mostly-distinct subset.

⇒ The units are **context-dependent / word-tied** (the final grammar is a compiled expansion), **not** a
reusable generic phone set. So there is no clean "/s/ + /iː/ + /ɪ/ + /t/" to splice out of existing words.
(Note: the *underlying* acoustic model is generic enough to represent any word — see §5 — but the *compiled
grammar* the device runs is word-specific.)

## 4. The acoustic model — LOCATED + characterized — CONFIRMED
An entropy scan of `app.bin` finds exactly one near-random blob in the voice region:
- **Acoustic model:** `0x0d0800`–`0x0d2800` (**~8 KB**, entropy 7.6–8.0), int16-structured (strongest
  self-correlation at stride 2) → ~4096 int16 params = a **compact, quantized KWS model** (not a full LVCSR
  DNN). No absolute pointer to it (pi32v2 base-relative addressing, per [15 §1.1](15_Core_App_Analysis.md)).
- **Math LUT** right before it: `0x0d0000`–`0x0d0800` (regular ramp of u32 triples
  `0x00baaaab, 0x00c55555, 0x00d00000, …` +`0x200000`/step — a companding/log table).

Its small size + the word-tied grammar (§3) = a **lightweight discriminative KWS tuned for this specific word
set**, exactly the kind of artifact JieLi generates server-side.

## 5. Verdict on hand-authoring a new spoken word — INFERRED-strong
To make the glasses **wake on a new sound**, that sound must be scored by the §4 model against a *correct
unit sequence*. That sequence is produced by JieLi's **G2P + acoustic-training/compile pipeline** (the licensed
piece — public research found **no self-serve compiler anywhere**; JieLi returns a generated `model` bin from
an emailed request, [§26](moyoung_reverse_engineering.md)). Therefore:
- Hand-splicing existing arcs **won't work** (units are word-specific; splice boundaries mismatch; and there is
  no tool to tune the per-word confidence threshold).
- This is true for **any** new word — "glass" is not special. The wall is the **compiler/coupling**, not the
  acoustic model (which is generic) and **not** the flash path.
- Honest bound: **very likely intractable by hand**, not *provably impossible*. A full decode of the 8 KB model
  + the compile semantics is the only thing that could overturn it, and it would still likely need JieLi's
  training data to make a robust trigger. **OPEN**, but low expected value.

## 6. Routes to actually add a wake word
1. **Sanctioned (reliable): OEM → JieLi model generation.** JieLi trains/compiles a `JL_KWS` model containing
   the new phrase (any language; English at their discretion), returning an `auth_key` + `proj_code` + model.
   Requires the **OEM (CRREPA / Shenzhen Kunpeng)** customer relationship — not doable by a third party
   directly. Contact surfaced in research: `weiyushu@zh-jieli.com` (type=KWS). Once the model exists, flashing
   it uses the **Core BLE OTA** ([14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md)) — proven for genuine images;
   a modified/re-packed Core still needs the **E3** boot check ([paths_forward.md](paths_forward.md)).
2. **Repurpose an existing trained phrase (grammar rewire, no new acoustics).** The device already recognizes
   ~14 English commands + 2 wake phrases with real trained acoustics. We *can* rewire the grammar so an
   existing phrase acts as the wake trigger. **Reliable acoustically**, but the spoken trigger is limited to
   words the model already knows (e.g. an existing command phrase) — **not** a brand-new custom word.
3. **Hand-RE the model (research):** decode the 8 KB model + compile semantics to synthesize a new unit
   sequence. **OPEN, low expected value** (§5).

## 7. What grammar-only editing CAN do today (no new acoustics) — INFERRED
Using the fully-mapped §2 format, all reliable *without* touching the acoustic model:
- **Disable** an existing wake/command word (remove its arcs + output entry, fix header/CSR counts).
- **Relabel** what an existing trigger reports (change its word symbol) — **the spoken sound is unchanged**
  (renaming `hello_echo` → `hello_glass` still wakes on "hello echo").
- **Retarget** which recognized phrase counts as the "wake" emission (route 2 above).
- **Tune** a word's trigger sensitivity via the in-arc weight (plausible; UNVERIFIED on device).

Any of these → splice into `app.bin` → re-encrypt (chipkey `0x1607`, [08](08_Core_Firmware_Jieli.md)) → repack
`.ufw` → flash via Core BLE OTA (dual-bank A/B rollback). **All of these edit a *modified* Core image, so they
inherit the open E3 gate** — whether the Core boots a modified/re-encrypted image is not yet demonstrated
([paths_forward.md](paths_forward.md) E3). Judge success only by observed on-device behaviour.

## 8. Is there an UNOFFICIAL way? — exhaustive web sweep, none found — CONFIRMED
A multi-site sweep (EN + ZH: GitHub, CSDN, Zhihu, the RE/crack forums 52pojie & amobbs, JieLi's own docs, the
yunthinker distributor, and every third-party voice vendor) found **no published unofficial method** to add a
word to JieLi's `batasr`/`JL_KWS` engine. Specific results:
- **Cyberon DSpotter DSMT** (`tool.cyberon.com.tw/DSMT_V2`) *is* a self-serve, no-ML tool that authors custom
  Chinese+English wake/command words — but it is **ruled out for this device**: a firmware marker grep of
  `app.bin` finds **no** `cyberon`/`dspotter`/`aispeech`/`sensory` strings (only `jl_kws`/`batasr`). Our chip
  runs JieLi's **own** engine, so a Cyberon model won't load and the format is JieLi-proprietary.
- **Generic trainers** (Picovoice Porcupine, OpenWakeWord, WeKWS) can train custom words but only for
  ESP32/RPi/PC runtimes — they cannot emit a model this JieLi firmware would run.
- A ZH forum data point corroborated our RE: "一个离线词条占用大概 3–4KB" (each offline entry ≈3–4 KB) — matches
  the compact ~8 KB model (§4) holding a small word set.
⇒ Confirms §5: a custom new word has **no unofficial route** (public or hand-edit); the word set is compiled by
JieLi's backend only. One unread thread remains (52pojie "智能语音项目开发", login-walled) — low expected value.

## 9. Tooling / repro / artifacts
- Working RE notes (byte-level offsets, parser snippets, all measurements): session scratchpad
  `batasr_fst_re_notes.md`.
- Engine identity + authoring research: three public-source sweeps ([§26](moyoung_reverse_engineering.md)) —
  `batasr`=`JL_KWS`; no public/self-serve command-word compiler; public AD-series voice SDKs are stripped; the
  BR28 `ac701n_soundbox_sdk` is reachable via JieLi GitLab's unauthenticated REST/raw API but retrieving it was
  **blocked by the environment's permission classifier** (proprietary-SDK gray area) and not pursued; and no
  unofficial method exists anywhere (§8).
- pi32v2 disassembly for any deeper decode: the [quarkslab/ghidra-jieli] recipe in [15 §1](15_Core_App_Analysis.md).

## 10. Primary-source confirmation — JieLi's own `smart_voice` / `jl_kws` source (2026-08-10) — CONFIRMED
A JieLi engineer's personal GitLab repo exposes the **integration source** for both KWS engines, fetched via the
unauthenticated API (repo `chenhuanhui/common_function`, ref `2c2ce9e8dc8f9f8f6037706c935de1c75a933963`, paths
`SDK/audio/jl_kws/` and `SDK/audio/smart_voice/`). It is the glue/framework layer — **the model itself is not in it**
— and it independently confirms every RE conclusion above:

- **`jl_kws/`** — a minimal **"yes/no" wake demo**. The acoustic model is a **statically-linked named library**
  (`jlsp_wake_word_yesno`): `jlsp_wake_word_yesno_heap_size()` → `JL_kws_init()` → `jl_detect_kws()`, with per-word
  thresholds in code (`KWS_YES_THR/KWS_NO_THR = 0.6f`) and events mapped in a switch (`YES→answer, NO→hangup`). ⇒ a
  vocabulary = a **pre-built library**; you don't compile words, you link the matching model lib.
- **`smart_voice/`** — the fuller engine (our glasses' family). `smart_voice_config.c` compile-switches between
  **AiSpeech (思必驰) / a user-custom engine / JieLi-KWS** (`config_aispeech_asr_enable`, `config_jl_audio_kws_enable`)
  and calls an external `kws_model_api` → **`audio_kws_model_init` / `audio_kws_model_process`**. The words, phonemes,
  thresholds, and model data are **all external** (the licensed blob) — none appear in the source. `user_asr.c` is a
  **skeleton with empty stubs** (`user_asr_core_open` returns `NULL`).

**What it confirms:** the recognized vocabulary + acoustics live in an **external, pre-built, licensed model/library**;
there is **no editable word list, no lexicon, and no local compiler** in the open source; per-word thresholds are
settable in code but the *words* are not. ⇒ same wall as §5, now verified from JieLi's **own code**, not just RE.

**One new angle (does not change the verdict):** the `user_asr.c` **custom-engine hook** (`user_asr_core_open /
_data_handler / _close`, exposed via `user_platform_asr_open`) is a documented plug-in point. In principle a
**bring-your-own trained KWS** (e.g. an open-source keyword spotter) could be wired in there instead of JieLi's
licensed engine — but it means training a model AND running it on the always-on Core MCU's limited compute. It is the
**only DIY-beyond-JieLi path**, and this is where it would attach. Otherwise, a custom "glass" still needs JieLi to
build the model (§6).
