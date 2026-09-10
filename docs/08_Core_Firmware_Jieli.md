# 08 — Core Firmware (JieLi AC701N) — Cryptanalysis & Decryption

The Core `.ufw` was **decrypted in software** on 2026-07-30 — no hardware, no brute-force. This doc is
the full account: how the encryption was classified from ciphertext, then broken with the known JieLi
scheme. It supersedes the earlier "AES-ish / needs a chip dump" guesses. The chronological version
(with the dead-ends) is the append-only log §11–§12.

## 1. The artifact
- `MOY-A073-*.ufw` — JieLi OTA container, tail magic `4a 4c 55 46 57` = **`JLUFW`**, ~1.7 MB,
  overall entropy 7.97 (encrypted). We hold all 15 A073 builds (0.0.1 → 0.1.2) in
  `../firmware/core-jieli-a073/`. Our device runs **`0.0.8`, git hash `C363B807`**.

## 2. Ciphertext-only classification (what we could tell before knowing the algorithm)
Using the multi-version set:
- **Deterministic** — same version+CRC builds are byte-identical (one md5). No per-build IV/nonce.
- **Reused GLOBAL positional keystream (two-time-pad reuse)** — same-size builds share 20–78 % of
  bytes with 400 KB+ contiguous identical runs; same-size *different-version* builds too
  (`0.0.8 ^ 0.0.9` = 76 % identical); 26 % of offsets identical across all 10 same-size builds. One
  ~1.7 MB **non-repeating** pad is XORed into every build at the same offsets.
- **Ciphertext-only crib-drag FAILED** (documented honestly in log §11.D): the "hits" were false
  positives from the 26 % identical region (`D_g = 0` → any ASCII crib "validates"); the 74 % that
  varies is recompiled code (entropy ~7.9, not guessable). Reused-keystream + non-cribbable plaintext
  ⇒ not breakable blind.

## 3. The break: the known JieLi cipher (`kagaimiq/jl-misctools`)
Online research → the JieLi firmware cipher is documented/implemented. It is a **16-bit LFSR**, not
AES:
```python
def jl_enc_cipher(buff, off, size, key=0xFFFF):      # keystream = low byte of a CRC16-CCITT LFSR
    for i in range(size):
        buff[off+i] ^= key & 0xFF
        key = ((key << 1) ^ (0x1021 if key & 0x8000 else 0)) & 0xFFFF
def jl_sfc_cipher(buff, off, size, base, key, blocksize=32):   # applied to the flash/app area
    for i in range(0, size, blocksize):
        jl_enc_cipher(buff, off+i, min(size-i, blocksize), key ^ ((off+i-base) >> 2))
```
So each **32-byte block** is XORed with an LFSR keystream seeded by `chipkey ^ (blockaddr >> 2)`. This
*exactly* reproduces our measured global-positional-non-repeating pad. The keyspace is only 16 bits
(the chipkey) — brute-forceable — **but not even needed**: the chipkey is stored **inside the
firmware** (`isd_config.ini` JLFS entry, `chipkeybin_decode`).

## 4. Running the decryptor
```
git clone https://github.com/kagaimiq/jl-misctools ; pip install crcmod pyyaml
python jl-misctools/firmware/fwunpack_newfw.py  <our .ufw>
```
Result:
- chip name **`AC701N`**, **chipkey `0x1607`**, JLFS parsed cleanly (all CRCs valid).
- Extracted: `uboot.boot`, **`app.bin` (1,065,100 B; entropy 7.97 → 7.0)**, `cfg_tool.bin`,
  `config.dat`, `p11_code.bin`, `stream.bin`, `isd_config.ini`, and `tone_en/*.wts` voice prompts
  (`awake.wts`, `take_pic.wts`, `call.wts`, `low_battery.wts`, …).
- `decrypted.bin` (whole descrambled flash) + `jlfw.yaml` (metadata) are written too.

**Validation:** `app.bin` has **1,894 plaintext strings** — `MOY-A073-0.0.8`, `VersionInfo: Invalid
type`, **`jl_kws`** (JieLi Keyword Spotting = the wake word), `ai_voice`, `moy_recorder`,
`Take_a_photo`/`Take_a_video`/`Start_recording`/`Photo_Recognition`, RCSP BLE (`jl_rcsp_ble_test`,
`JL_SPP`, `Zble_ota.bin`), BT profiles `JL_A2DP/HFP/HID`, `DBG_CPU0..3`. Filename CRCs (e.g.
`C363B807`) match JieLi `crc32` (poly `0x104C11DB7`, init `0x26536734`) — a built-in correctness check.

## 5. What this unlocks
- **Full Core RE — now DONE for the first pass ([15](15_Core_App_Analysis.md), 2026-08-03).** The core is
  JieLi **pi32v2** (CONFIRMED, not rv-like); stock Ghidra has no pi32 module but **`quarkslab/ghidra-jieli`**
  (a pure-Sleigh module, id `pi32v2:LE:32:default`) disassembles + decompiles `app.bin` coherently at base
  `0x6000000` (**2,605 functions**). From that pass: the **`jl_kws` wake word** + **`batasr`** offline ASR +
  a full local **voice-command grammar** (two wake phrases `hello_echo`/`Nihao_Xiaoke`); the RCSP surface
  (`JL_A2DP/HFP/HID/SPP`, **nanopb** payloads); the Core↔Vision link is **UART** (`jlstream_0..7`,
  `checkSlave`); and the **version relabel is corrected** — `2.4.0.22`/`1.4.0.20` are **absent from the
  entire Core image**, so the Core does not synthesise it (see [15 §7](15_Core_App_Analysis.md) and the
  corrected [10 §3](10_Cloud_and_Device_Auth.md)). Remaining: numeric RCSP opcode map + the exact
  version-relay path (need a BLE/UART sniff or focused decompile).
- **Decrypt all 15 builds** (same tool; chipkey auto-read) → clean cross-version diffing.
- **Modify → re-encrypt → flash — the flash channel is now PROVEN (2026-08-03).** The tinker app drove a
  **verified Core flash** over BLE: genuine `0.0.9` `.ufw` → device rebooted → version confirmed
  `MOY-A073-0.0.9` (dual-bank DFU, `startOta(File)`; needs `jl_bt_ota` **v1.10.0**, see
  [14 §3](14_InApp_OTA_and_the_JieLi_lib_gap.md) / [log §20](moyoung_reverse_engineering.md)) — **lower brick
  risk than the in-place Allwinner path** ([09](09_Vision_Firmware_V821.md)) and auto-rollback on a bad bank.
  So a modified image flashes the same way: `jl-misctools/firmware/recrypt.py` re-applies the cipher (chipkey
  `0x1607`) → repack `.ufw` → `startOta`. **Remaining unknown:** whether the device accepts a *modified*
  (re-encrypted) image vs a genuine factory one.
- Hardware fallback (not needed for decrypt): `jl-uboot-tool` dumps AC701N flash over JieLi UBOOT
  (USB/UART).

## 6. Tools / repro
`kagaimiq/jl-misctools` (cipher in `firmware/jltech/cipher.py`, unpacker `fwunpack_newfw.py`, brute
`bruteforce.py`, re-encrypt `recrypt.py`), `kagaimiq/jl-uboot-tool`, format notes at
`kagaimiq.github.io/jielie/`. Local clone: `scratchpad/jlmt`; decrypt output: `scratchpad/coredec`.
