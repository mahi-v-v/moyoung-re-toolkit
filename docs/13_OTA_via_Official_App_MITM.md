# 13 — Flashing via the OFFICIAL app + a MITM proxy (PARKED 2026-08-03 — see the decision at the end)

Instead of driving the OTA from our own (BLE-flaky) tinker app, let the **official Da Echo app** do the
flash and just **intercept its firmware check** to point it at our image. The vendor's BLE + Wi-Fi OTA
stack is battle-tested; we only swap the metadata. **Our safety is unchanged** — the device runs *our*
rootfs-only `sw-description` regardless of which app delivered the file ([12](12_Firmware_Patching_and_Flashing.md)).

## Why it works (grounded in the decompiled app)
`FirmwarePresenter` + `FirmwareUpdateFragment` (decompiled):
```
POST {baseUrl}/api/v1/firmware/check-upgrade  ->  { "status":"ok", "data": { … } }
data = { firmware_ver, firmware_file (URL), firmware_md5,
         firmware_num (1=Jieli/Core, 2=Allwinner/Vision), type (otaType), has_upgrade }

onNewVersion(): download(firmware_file) -> checkFileOfMd5(firmware_md5, file) ->
    firmware_num==1 -> startOta(file)           // Core, BLE DFU
    firmware_num==2 -> startAllWinnerOta(file)   // Vision, Wi-Fi   ← our target
```
Three facts made the swap look trivial (as of the 2026-07-30 captures; see the superseding note on item 3):
1. **We forge the whole `data`**, so `firmware_md5` = md5 of *our* file → the integrity check passes.
   (There's no signature on the `.swu`; the md5 in the response is the only gate.)
2. `has_upgrade:true` alone makes the app offer it — the client does **no** version comparison.
3. **No cert pinning observed** on the intercepted client in the 2026-07-30 captures (only okhttp's unused
   `CertificatePinner` class was present), and the check host was MITM-able then. **⚠ Superseded:** the
   2026-08-03 field results infer pinning on a *second* okhttp client that carries `check-upgrade` (see
   the field-results + decision sections below).

## The forged response
```json
{ "status": "ok",
  "data": {
    "firmware_ver":  "9.9.0.99.3.2699999999",
    "firmware_file": "https://altair.moyoung.com/static/firmware/_tinker/custom_image",
    "firmware_md5":  "<md5 of the .swu you serve>",
    "firmware_num":  2,
    "type":          3,
    "has_upgrade":   true } }
```
Our built images + md5s (the value for `firmware_md5`):

| Image | md5 | use |
|-------|-----|-----|
| `vision_noop.swu` | `7934a6b93efd97ebbd7d411e7c256dd8` | **1st** — proves the intercept+flash pipeline, zero change |
| `vision_6mbps_adb.swu` | `7f63ffd08b481db1d0a73a77bfbcc7f9` | **2nd** — the real bitrate mod |

## How to run it
**Turnkey (mitmproxy, no separate backend):** [`../tools/proxy-ota/moy_ota_mitm.py`](../tools/proxy-ota/moy_ota_mitm.py)
rewrites the check response **and** serves the image (short-circuits the advertised URL), so you don't
need a file server.
```sh
MOY_SWU=".../firmware/vision-v821/_patched/vision_noop.swu" mitmdump -s tools/proxy-ota/moy_ota_mitm.py
# phone Wi-Fi proxy -> this machine:8080 ; install the mitmproxy CA on the phone
# open Da Echo's firmware-update screen -> it offers the update -> confirm -> it flashes
```
Then re-run with `MOY_SWU=…/vision_6mbps_adb.swu`.

**Reqable (your stated setup):** phone → Reqable on the laptop. Add two rules: (a) a **response
rewrite** on `…/api/v1/firmware/check-upgrade` replacing the body with the JSON above; (b) a
**Map Local** on the `firmware_file` URL → your local `.swu`. A separate `python -m http.server` also
works, but prefer an **`https://` URL on a host the proxy already intercepts** (avoids Android's
cleartext-HTTP block); the proxy's trusted CA covers the TLS.

## Gotchas
- **Trigger the check:** the request only fires when the app looks for an update (open the firmware
  screen / reconnect). Nothing to intercept until then.
- **Serve URL scheme:** use HTTPS on an intercepted host (as above), or ensure the app permits
  cleartext if you use `http://<laptop>:port/…`.
- **Same brick model as [12](12_Firmware_Patching_and_Flashing.md):** this changes *reliability*, not the
  write. The image is still rootfs-only (no `boot0`). Do the **no-op first**, keep the unit charged.
- Install the proxy CA on the phone; leave the glasses connected to Da Echo as usual.

## Verify after
`adb connect <glasses-ip>:5555` (the 6 Mbps build enables it), and re-measure the RTSP bitrate
([11 §1](11_Streaming_Bitrate_Analysis.md)). Roll back by serving `vision_noop.swu` (or any stock `-ai`
rootfs) the same way.

## Field results (2026-07-30) — pipeline CONFIRMED, device-write UNVERIFIED
- **Tool:** must be **Reqable / PCAPdroid** (whose CA is system-trusted on the user's non-rooted
  phone), **not mitmproxy** — Da Echo (Android 7+, no pinning) simply ignores *user*-installed CAs, so
  the mitmproxy CA was rejected on every `altair.moyoung.com` handshake.
- **It works:** a Reqable **breakpoint** rewriting the `check-upgrade` response made Da Echo download
  our `.swu` (UA `okhttp/4.12.0`) from `python serve_firmware.py`, pass the md5 gate (we set it), and
  reach **"Upgrade successful"** — caching our forged `firmware_ver`.
- **Cleartext HTTP is fine** — `firmware_file: http://<laptop-ip>:8000/vision_noop.swu` downloaded
  without a `network_security_config` block.
- **Not yet proven the device wrote it.** The no-op is unobservable; the app's "success" only proves
  its own flow. Verify with the **6 Mbps** build → `adb connect <ip>:5555` + ffprobe.
- **Operational gotchas:** the app caches "up to date" (re-checks only on the firmware screen / after
  clearing data); a **broad breakpoint stalls login/bind** — keep it off during connect, on only for
  the firmware screen; **force-stop the tinker app** so it doesn't hold the BLE link.
- Latest working forged body (no-op, plain-digits version):
  `{"data":{"has_upgrade":true,"firmware_ver":"2.4.0.99","firmware_file":"http://<ip>:8000/vision_noop.swu","firmware_md5":"7934a6b93efd97ebbd7d411e7c256dd8","firmware_size":7211008,"type":1,"firmware_num":2},"status":"ok"}`
  (6 Mbps: file `vision_6mbps_adb.swu`, md5 `7f63ffd08b481db1d0a73a77bfbcc7f9`, size `7084032`.)

## Field results (2026-08-03) — the intercept regressed: (inferred) TLS pinning on the API client
The MITM stopped working mid-session (it had worked *earlier the same day*). Best diagnosis —
**certificate pinning (INFERRED, not confirmed)** on one client, not a proxy/CA problem:
- **Symptom:** `POST /api/v2/oauth/auth` (the device-auth call) **decrypts fine**, but the connections
  carrying the API/firmware traffic **abort every TLS handshake** — Reqable shows *"Unable to perform SSL
  handshake with client"*, `Status: Aborted`, in a 20+ retry storm.
- **Both are the same** host:port (`altair.moyoung.com:443`) and the **same library** (`okhttp/4.12.0`)
  → the app runs **two okhttp clients**: the **auth client decrypts**, the **API/device client aborts**
  (consistent with pinning on it). `check-upgrade` rides the aborting one, so it can no longer be
  intercepted. (Detail + JWT capture in [10 §1.1–§1.2](10_Cloud_and_Device_Auth.md).)
- **Why Reqable can't win it:** the pinned + unpinned clients share the identical host:port, so a
  per-host "do-not-decrypt" can't isolate the pin; and Reqable's *only* pin bypass needs the server's
  **private key** (per Reqable's own [SSL FAQ](https://reqable.com/en-US/docs/faq/ssl)). Not obtainable.
- **NOT server-side flagging / rate-limiting.** The abort is client→proxy, *before* the server is
  reached, so altair never sees the connection. A healthy 5-day JWT was issued the same minute
  ([10 §1.1](10_Cloud_and_Device_Auth.md)) — the account/device is fine. "It'll work in a few days" is a
  red herring: what actually resets is the **local okhttp connection-pool / TLS-session state** (force-stop
  Da Echo or reboot the phone to force fresh handshakes — no need to wait).
- **CORRECTION — it is NOT an app update.** Our own §E capture (2026-07-30, when the MITM *worked*)
  recorded the **same build `2.4.31_20260712`** pulled off the phone via adb; today's on-wire
  `app_version` is identical. A single unchanged binary can't pin one day and not the next, so
  **hard pinning added by an update is ruled out.** More likely: (a) the aborting connections are a
  **background/realtime channel that was pinned all along** (noise — probably aborted on 2026-07-30 too),
  and the real regression is that **`check-upgrade` isn't firing / isn't being caught right now** — a
  stateful okhttp connection-pool / TLS-session condition (exactly why it "worked earlier today"); or
  (b) the server toggles behaviour by config. **Do NOT downgrade** — there's nothing older to go to.
- **Fix:** reset local state (force-stop Da Echo, reboot the phone, clear the Reqable flow), reconnect,
  and confirm whether a **decrypted `check-upgrade` POST** reappears as it did on 2026-07-30 — and whether
  *it* is what aborts, or whether it simply isn't sent. Only if `check-upgrade` **itself** aborts is it
  genuinely pinned → then repackage with `apk-mitm`/objection, or use the in-app Vision OTA
  ([14](14_InApp_OTA_and_the_JieLi_lib_gap.md)).

## DECISION — this route is PARKED (2026-08-03)
After the diagnosis above we're **parking the official-app + MITM route** and pivoting to our **own
tinker app's in-app Vision OTA** ([14](14_InApp_OTA_and_the_JieLi_lib_gap.md)), which doesn't touch Da
Echo or its TLS pinning at all.

**Reasons / observations recorded before parking:**
- In the current session **`check-upgrade` could not be intercepted** — it rides an okhttp client
  Reqable can't decrypt (aborts every TLS handshake; **inferred certificate pinning**, not confirmed).
  Without seeing/rewriting that response we cannot inject `has_upgrade`/our firmware URL, so the route is
  dead-ended here.
- **User observation:** *oauth* (`/api/v2/oauth/auth`) **and** the firmware *download* WERE interceptable
  this session, but **the check itself was not** — i.e. the un-interceptable requests are the ones on
  the un-decryptable client, and `check-upgrade` is among them. Practical evidence the check now rides
  that client (inferred-pinned), not merely a caching hiccup.
- It **worked on 2026-07-30** on the *same* build `2.4.31_20260712` (§1.2 of
  [10](10_Cloud_and_Device_Auth.md)) → a stateful/pinning condition, **not** an app update — but chasing
  it (repackage to strip the pin, or reproduce the state that let it through) isn't worth it when the
  in-app path bypasses the whole problem.
- Not abandoned — **revivable** later via an `apk-mitm`/objection pin-strip if we ever want the vendor's
  proven OTA stack.

**⇒ We pivoted to the in-app OTA** ([14](14_InApp_OTA_and_the_JieLi_lib_gap.md)). **Outcome (later the
same day):** the in-app **Core BLE OTA is verified working** (`jl_bt_ota` v1.10.0 → 0.0.8→0.0.9), while
the in-app **Vision OTA is blocked device-side — the glasses report `SUCCESS` without flashing** (a masked no-op,
12/12; NOT the transport — [14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)). So the reliable flash channel is now the
in-app **Core** path — not this MITM route.
