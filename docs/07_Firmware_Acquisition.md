# 07 — Firmware Acquisition & Local Archive

How we obtained the glasses' firmware, what the vendor's distribution looks like, and the curated
local mirror we keep. Established 2026-07-30. Companion to [10 — Cloud & Device Auth](10_Cloud_and_Device_Auth.md)
(the API that leaks the URLs) and [08](08_Core_Firmware_Jieli.md)/[09](09_Vision_Firmware_V821.md)
(what's inside the images).

## 1. The lead: `check-upgrade` returns a plaintext URL, no auth

The companion app (**Da Echo**, `com.moyoung.glasses`) checks for updates with an **unauthenticated**
POST:

```
POST https://altair.moyoung.com/api/v1/firmware/check-upgrade
{"fw1_ver":"MOY-A073-0.0.8","fw2_ver":"2.4.0.22.3.2603302218","mac":"F5:13:72:15:2C:31"}
→ {"data":{"has_upgrade":true,"firmware_ver":"MOY-A073-0.1.0",
    "firmware_file":"https://altair.moyoung.com/static/firmware/20260714183832_MOY-A073-0.1.0-BIN-DAD63A87-ENCRYPTED.ufw",
    "firmware_md5":"17b83173c38e0920e0a50cff3fb09017","firmware_size":1703232,"type":1,...},"status":"ok"}
```

`type:1` = the JieLi **Core**; the endpoint never returns the Vision image (that's V821/Allwinner-cloud
managed). No `Authorization` header is required — the `firmware_file` is a direct download link.

## 2. The jackpot: the static firmware dir has directory-listing ON

`GET https://altair.moyoung.com/static/firmware/` returns a full Apache-style index — **4077 files,
~46.5 GB**: every model, every version, both `.ufw` (JieLi Core) and `.swu` (Allwinner Vision), for
MoYoung *and* other ODM customers (`YX-`, `ZL-`, `MLB-`, `JDF-`, `SS-`, `ZKHS-` …). This is the entire
firmware factory, world-readable.

Tooling notes: on Windows `curl` needs `--ssl-no-revoke` (schannel throws `CRYPT_E_REVOCATION_OFFLINE`
otherwise). Files were sized without downloading via a `Range: bytes=0-0` request (reads
`Content-Range: …/<total>`).

## 3. De-duplication — 46.5 GB is mostly the same builds re-uploaded

There are only **437 distinct file sizes** among the 4077 files; the server re-uploads byte-identical
rebuilds under fresh timestamps. Deduped to distinct builds ≈ **15 GB**. Per MoYoung V821 Vision line:

| Line | copies | distinct builds |
|---|---|---|
| `openwrt_v821_aiglass-ab.swu` | 878 | 34 |
| `openwrt_v821_aiglass-ai.swu` | 444 | 26 |
| `openwrt_v821_aiglass_imx681-ab.swu` | 227 | 6 |

## 4. What we downloaded — the local archive (`../firmware/`)

A curated **~1.25 GB / 108-file** set = *every A073 Core build* + *one copy of every distinct MoYoung
V821 Vision build*. Layout + provenance are documented in [`../firmware/README.md`](../firmware/README.md):

```
firmware/
├── MANIFEST.csv          # filename, size, md5, sha256, + internal ag_user_version.conf per .swu
├── index/                # fwindex.html (raw server listing), fwsizes.txt, sel.tsv (the selection)
├── core-jieli-a073/      # 39 Core build files (15 distinct versions) — MOY-A073-0.0.1 … 0.1.2 (.ufw/.fw), ENCRYPTED
└── vision-v821/
    ├── aiglass-ai/  aiglass-ab/  aiglass-imx681-ab/  aiglass-imx681-ai/
```

The Core `.ufw`s are all downloaded (small, 1.5–1.8 MB each) — the multi-version set is what made the
Core encryption analysis possible ([08](08_Core_Firmware_Jieli.md)). Verified: the Core `0.1.0`
download's md5 matched the server's `17b83173…`.

## 5. Preservation

- The bulky binaries in `firmware/` are **git-ignored** (kept local; the server could lock down), but
  `MANIFEST.csv`/`README.md`/`index/` are tracked so the archive stays documented. See the workspace
  `.gitignore`.
- **Keep every image** — they are the only recovery baseline and cannot be re-derived if the server
  changes. This mirrors the Cyan lesson (an ignored firmware file is one `git clean` from gone).

## 6. Forging / enumeration notes (for later)
- The `check-upgrade` body is `{fw1_ver, fw2_ver, mac}` with no auth → you can query for **any**
  version/MAC (tested: forging old `fw2_ver` still only yields the Core; the server has no Vision entry,
  confirming Vision is not served here).
- Filenames encode the plaintext **CRC** (e.g. `-BIN-C363B807-`) — a free integrity oracle
  ([08 §validation](08_Core_Firmware_Jieli.md)).
