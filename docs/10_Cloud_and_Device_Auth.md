# 10 — Cloud, OTA API & Device Authentication

What the **Da Echo** app (`com.moyoung.glasses`, v `2.4.31_20260712`) talks to, captured via mitmproxy
(TLS-decrypted) on 2026-07-30, plus the Da-Echo APK decompile that resolved the version-display
question. Companion to [07 — Firmware Acquisition](07_Firmware_Acquisition.md).

## 1. `altair.moyoung.com` — the "Altair AI Hub"
| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/v1/firmware/check-upgrade` | **none** | firmware update check → `firmware_file` URL + md5 (Core only; see [07](07_Firmware_Acquisition.md)) |
| `GET /static/firmware/` | **none** | **directory listing ON** — the whole firmware catalog |
| `POST /api/v2/oauth/auth` | none (device-keyed) | issues an **`altair-ai-hub` JWT** (`device_id`, scope `device_access`, `aud:["altair-devices"]`) — full decode in **§1.1** |
| `POST /api/v3/activation-code/api/tool-other/{get,bind}-activation-code` | Bearer JWT | issues/binds a per-device **`device_license`** blob |

### 1.1 The device-auth flow (JWT) — full capture (Reqable, 2026-08-03)
`POST /api/v2/oauth/auth` (HTTP/2, `okhttp/4.12.0`, **no signature/`Authorization` header**) — the app
authenticates the **device** (there is no user account) and gets back a JWT pair. Request body:
```json
{"app_version":"2.4.31_20260712","device_id":"4150345a39393316002b694c4c019b78",
 "device_name":"V06","mac":"F5:13:72:15:2C:31","soft_version":"MOY-A073-0.0.8","timestamp":"1785753483"}
```
`app_version` = the installed **Da Echo build** (on-wire confirmation, matters for the pinning regression
below); `soft_version` = the device's live **Core** version (`MOY-A073-0.0.8`); identity = the connected
glasses' uuid+mac. Response `200` → `{bearer_token:"at_…", refresh_token:"rt_…", token_type:"Bearer",
expires_in:432000}`; both are **HS256** JWTs. Bearer claims decoded:
```json
{"device_id":"230673","device_uuid":"4150345a39393316002b694c4c019b78","mac_address":"F5:13:72:15:2C:31",
 "scope":"device_access","iss":"altair-ai-hub","sub":"230673","aud":["altair-devices"],
 "exp":1786185485,"nbf":1785753485,"iat":1785753485,"jti":"71166c57f4749495d954c1c8231e2fd6"}
```
- The server maps our device uuid → **internal `device_id 230673`** (new; we previously had only the
  uuid/mac). Refresh token adds `token_type:"refresh"`.
- **Lifetimes:** bearer **5 days** (`expires_in 432000`), refresh **30 days** (`exp` +2 592 000).
  Subsequent API calls carry `Authorization: Bearer at_…`.
- **HS256 = symmetric** → the signing secret lives on `altair-ai-hub`; tokens are **not forgeable
  client-side**. This does **not** obstruct our MITM route ([13](13_OTA_via_Official_App_MITM.md)):
  we rewrite the check-upgrade *response*, the real device already holds a valid bearer token, and
  firmware is served in the clear — token forgery never enters into it.
- Response CORS is wide-open (`access-control-allow-credentials: true`, all methods) + `x-robots-tag`
  noindex; cosmetic, but underscores the lax posture.

### 1.2 API-client TLS handshake aborts under Reqable — MITM route blocked (2026-08-03)
The app runs **two `okhttp/4.12.0` clients** to the *same* `altair.moyoung.com:443`: the **auth client
above decrypts cleanly** under Reqable, but a **second client aborts every TLS handshake** ("Unable to
perform SSL handshake with client", `Status: Aborted`) in a tight retry storm — the hallmark of
**certificate pinning on that client.** Because both share the identical host:port, Reqable's per-host
"do-not-decrypt" can't isolate them, and Reqable's only pin bypass needs the server's private key.
**Caveat — do not blame an app update:** the *same* build `2.4.31_20260712` was pulled off the phone on
2026-07-30 **when the MITM worked** (§E), and today's on-wire `app_version` is identical — so this is
**not** pinning newly added by an update, and there is no older build to downgrade to. Two live
possibilities: (a) the aborting client is a **background/realtime channel that pinned all along**, and
the real regression is `check-upgrade` **not firing / not being caught** (a stateful okhttp pool/session
condition); or (b) the server toggles behaviour by config. **Re-verify whether `check-upgrade` itself
decrypts** (as on 2026-07-30) after a state reset before concluding the check is pinned. Full analysis in
[13](13_OTA_via_Official_App_MITM.md).

## 2. `deviceauth.allwinnertech.com` — Allwinner device authorization
`POST /algorithm/api/authDeviceCode` with `{activationCode, customerIndex:"4", deviceSerial, timestamp}`
and an RSA-ish `signature` header; our device: `deviceSerial 22806c006c00482000c30388549b2113`,
`customerIndex 4` (MoYoung's OEM id). This is Allwinner's cloud gating the AI/vision features; the
**`device_license`** (335-byte blob, embeds the serial) is the per-device certificate. The video/OTA
path does **not** depend on it — firmware is served in the clear ([07](07_Firmware_Acquisition.md)).

## 3. The version-display question — static-Core-relabel hypothesis REFUTED; mechanism still INFERRED (leaning Vision-side, UART-sniff TODO)
The app shows Vision `2.4.0.22.3.2603302218`, but **no firmware anywhere reports `2.4.x`**. Chased to
ground:
- **The app does NOT transform it** (Da-Echo APK decompiled with jadx): `DeviceVersionCallback`
  stores `versionInfo.getVer()` **verbatim** (`VerFirmware`→Core, `VerFirmware1`→Vision); only the TP
  version is reformatted (`%08X`). `VersionInfo` is a plain protobuf — `ver` is one UTF-8 string off
  the wire, no client assembly.
- **The Vision firmware reports `1.4.0.20.3.2603302218`** (baked into `libaglink.so` + its
  `ag_user_version.conf`); `2.4.0.22` appears **nowhere** in the Vision image.
- ⇒ (original inference) *"by elimination the relabel is applied on the Core (JieLi) MCU."*
  **⚠ CORRECTED 2026-08-03 — the strong form is REFUTED** ([15 §7](15_Core_App_Analysis.md)). The Core
  `app.bin` was decompiled (pi32v2, Ghidra) and its data exhaustively searched: **`2.4.0.22`, `1.4.0.20`, and
  the build-stamp `2603302218` appear NOWHERE in the Core** (not in `app.bin`, `config.dat`, `stream.bin`,
  `cfg_tool.bin`, `p11_code.bin`, or tones), and the Core has **no `%d.%d.%d.%d` version format** (its only
  dotted formatters are the IP-URL templates `rtsp://…`/`http://…`). So the Core does **not** hold or
  synthesise `2.4.0.22`; it relays the Vision version obtained **at runtime over UART**. The most likely
  mechanism (INFERRED, from the preserved unique build-stamp): the `1.4.0.20→2.4.0.22` relabel is applied
  **on/for the Vision's aglink-reported version before the Core** — i.e. the Vision reports a *product*
  version distinct from its internal `1.4.0.20`. **To close it (TODO):** a Core↔Vision **UART sniff** during
  `queryDeviceVersion(VerFirmware1)`, or a second (product-facing) version field in the Vision image. The
  build-stamp `2603302218` is still preserved and unique, so the running Vision build remains
  `…3.2603302218`.

## 4. Behavioural finding: Core version drives Wi-Fi mode
The Da-Echo APK's `DeviceVersionCallback.FORCED_AP_VERSIONS` =
`{MOY-A073-0.1.0, -0.0.7, -0.0.6, -0.0.3, MOY-A253-…}`. For those Core versions the app forces Wi-Fi
**AP** mode; our Core `0.0.8` is **absent** ⇒ **STA** mode (consistent with the observed
RTSP-on-shared-LAN, [06](06_Live_Video_Streaming.md)). Taking the offered `0.1.0` Core update would
flip streaming to **AP** mode.

## 5. Security posture (portfolio note)
- **Open directory listing** of the entire firmware factory (~46 GB, *all* OEM customers' images).
- **No-auth `check-upgrade`** leaking firmware URLs + md5.
- **Unsigned Vision firmware** — integrity is a plaintext `cpio_item_md5`, no signature, no HW-compat
  gate ([09 §5](09_Vision_Firmware_V821.md)) → a crafted `.swu` would flash if the OTA path is reached.
- **Weak Core encryption** — a 16-bit-keyed LFSR with the key stored in the firmware ([08](08_Core_Firmware_Jieli.md)).
- Per-device `device_license`/activation exposed in cleartext-decrypted API traffic.
- **Device-only auth with no secret** (§1.1): `oauth/auth` mints a 5-day `device_access` JWT from just
  the glasses' `device_id`+`mac` (both are BLE-advertised) — no user account, no per-device secret in
  the request. Anyone who can observe a unit's uuid/mac can request a token for it.
- **Apparent (inferred) TLS pinning on an API/realtime client** (§1.2) — on the *same* build that MITM'd
  successfully earlier (2026-07-30), so it is **not** a newly-added defence; notable only because
  everything else (firmware, check-upgrade) remains unsigned/no-auth, so the pin guards the channel
  that leaks the least.

These are candidates for a responsible-disclosure writeup (see [paths_forward.md](paths_forward.md) §G).
