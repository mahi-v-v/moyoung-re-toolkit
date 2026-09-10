# MoYoung Glasses Firmware Archive

A curated, de-duplicated mirror of firmware pulled from MoYoung's public firmware
server, focused on the **MOY-A073 "V06"** glasses (this project's device) and its
Allwinner-V821 firmware family.

- **Source:** `https://altair.moyoung.com/static/firmware/` (directory listing was open,
  no auth). Full raw catalog snapshot: [`index/fwindex.html`](index/fwindex.html)
  (4077 files, ~46.5 GB on the server — mostly identical rebuilds re-uploaded under new
  timestamps).
- **Captured:** 2026-07-29.
- **What's here:** every distinct **A073 Core** build (Jieli `.ufw`/`.fw`) + one copy of
  every distinct **MoYoung V821 Vision** build (`.swu`), ~1.25 GB total.

## Layout

```
firmware/
├── README.md                     ← you are here
├── MANIFEST.csv                  ← filename, size, md5, sha256, internal version (per .swu)
├── index/
│   ├── fwindex.html              ← raw server directory listing (provenance)
│   ├── fwsizes.txt               ← size <TAB> filename, for every server file
│   └── sel.tsv                   ← the curated selection that was downloaded
├── core-jieli-a073/              ← Core MCU firmware — Jieli AC701N, ENCRYPTED
│   └── <ts>_MOY-A073-<ver>-BIN-<crc>-ENCRYPTED.ufw   (+ .fw UI/DSP packages)
└── vision-v821/                  ← Vision SoC firmware — Allwinner V821, OpenWRT/Tina, swupdate
    ├── aiglass-ai/               ← generic AI-glass build (ships a `user` partition w/ version)
    ├── aiglass-ab/               ← A/B build (single rootfs, no user partition)
    ├── aiglass-imx681-ab/        ← Sony IMX681 sensor variant, A/B
    └── aiglass-imx681-ai/        ← Sony IMX681 sensor variant, AI
```

## Notes / caveats

- **De-dup method:** Core builds de-duped by logical name (version+CRC in the filename).
  Vision `.swu` de-duped by **file size** (a reliable proxy for distinct content here — the
  server's dupes are byte-identical). Two genuinely different builds that happen to share a
  size would collapse to one; `MANIFEST.csv`'s per-`.swu` internal version lets us confirm
  coverage after the fact.
- **Device version to match:** this unit reports Vision `2.4.0.22.3.2603302218`
  (build 2026-03-30). The `-ai` line seen so far is a `1.2.0.x` branch; the exact `2.4`
  build is expected in one of these lines — check `MANIFEST.csv`'s `internal_version`.
- **Formats:** `.ufw` = Jieli OTA container (tail magic `JLUFW`, encrypted). `.swu` =
  Allwinner swupdate = newc-CRC CPIO (magic `070702`), unsigned, integrity via a plaintext
  `cpio_item_md5` member; rootfs is squashfs 4.0 / lzo / 32K block. Unpack with
  `cpio -idm` then `unsquashfs` (WSL).
- **Local filenames** have URL-encoding decoded and spaces → `_`.
