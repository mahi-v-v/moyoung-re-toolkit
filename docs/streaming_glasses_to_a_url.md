# Streaming the MoYoung Glasses to a URL (push / restream)

**Goal:** get the glasses' live video onto a destination **URL** you choose (an RTMP/RTSP/SRT ingest,
a media server, YouTube/Twitch, your own box) — instead of the stock model where the *glasses host a
server and the phone pulls from them*.

> **Grounding policy.** Everything in §1 is **measured on this device this session** (marked
> *[verified]*). Commands in §§3–5 are standard `ffmpeg`/media-server usage; the parts specific to this
> device (source URL, codec, transport) are verified, and everything you must supply is a clearly-marked
> `<PLACEHOLDER>`. Where something is *not* yet proven on this hardware it says so explicitly (§6).

---

## 1. What the glasses actually do (verified facts)

| Fact | Value | How we know |
|------|-------|-------------|
| Stream is **RTSP** (pull model) | glasses = RTSP **server**, client pulls | SDK guide `audio.md` (*"video stream format is RTSP"*) + we connected as a client *[verified]* |
| Source URL | `rtsp://<GLASSES_IP>:8554/ch0` | our LAN scan + `DESCRIBE` returned `200 OK`; SDP `s=… "testH264VideoStreamer"`, `a=tool:AW RTSP Streaming` *[verified]* |
| Video codec | **H.264, 1600×1200, ~30 fps, Main profile, `yuvj420p`** | `ffprobe` on the live stream *[verified]* |
| Audio codec | **AAC-LC, 8000 Hz, mono** | SDP `rtpmap:97 MPEG4-GENERIC/8000` + `ffprobe` *[verified]* |
| Measured bitrate | **≈1.5 Mbps** video | 12 s `ffmpeg -c copy` capture = 1,524,012 bps *[verified]* |
| Ports exposed (STA mode) | **only TCP 8554** | port scan of the glasses IP *[verified]* |
| RTSP server only runs **in live mode** | app must trigger `enableWifi(LIVE)`+`connectWifi()` first | SDK guide + the server only appeared once the app started live view *[verified]* |
| No push client in firmware | `ai_glass_livestream` is a **live555 RTSP *server*** (no RTMP/SRT client) | Ghidra: `RTSPServer`, `OnDemandServerMediaSubsession`, `testH264VideoStreamer` symbols; no `rtmp`/`librtmp` strings *[verified]* |

**Consequence (the core logic):** RTSP is a *pull* protocol — the client initiates `DESCRIBE/SETUP/PLAY`.
The glasses implement only the **server** side, and the firmware contains **no** RTMP/SRT/RTSP-push client.
So to get the feed onto a destination URL you either (A/B) **interpose a relay** that pulls RTSP and
pushes to your URL, or (C) **modify the firmware** to push natively. A relay is the only route that works
**today with zero device changes** — this doc focuses on it.

Bandwidth note: because the source is only ~1.5 Mbps *[verified]*, a `-c copy` relay needs only
~1.5–1.6 Mbps of upload — trivial for any connection.

---

## 2. Prerequisites (all required)

1. **The glasses must be in live mode**, so `rtsp://…:8554/ch0` is actually being served. Start live view
   in the official *Da Echo* app (or our tinker app: Capture tab → *Enable Wi-Fi (LIVE)*). If the stream
   isn't live, the relay has nothing to pull. *[verified: server is absent until live view is started]*
2. **The relay host must reach the glasses on the LAN** (same Wi-Fi/router; TCP 8554 reachable). In STA
   mode the glasses join the router the phone is on — put the relay box on that same network. *[verified:
   this PC on `10.136.210.x` reached the glasses at `10.136.210.76:8554`]*
3. **The relay host must reach your destination URL** (internet, for RTMP/SRT/etc.).
4. **`ffmpeg` installed** on the relay host. *[verified present on this PC at
   `C:\ffmpeg\ffmpeg-master-latest-win64-gpl-shared\bin`]*

### Find the glasses IP / confirm the stream
- **From our tinker app:** it logs the exact URL — `adb logcat | grep "live url"` shows
  `[CRP] live url = rtsp://<ip>:8554/ch0`. *[verified: wired in `MoyoungAdapter.onLiveUrlChanged`]*
- **Or confirm/scan from the relay host:**
  ```bash
  ffprobe -rtsp_transport tcp "rtsp://<GLASSES_IP>:8554/ch0"     # prints the H.264 1600x1200 stream
  ```
  (The glasses IP is DHCP-assigned; the **path `/ch0` and port `8554` are fixed** *[verified]*. Get the IP
  from the app log above or your router's DHCP table.)

---

## 3. Option A — `ffmpeg` relay (recommended; works today)

Run this on the relay host (this PC, a laptop, or a Pi on the same LAN). It pulls the glasses' RTSP and
pushes to your URL. Replace every `<PLACEHOLDER>`.

### A1 — Push to an RTMP ingest (YouTube / Twitch / nginx-rtmp / SRS) — copy, lowest CPU
```bash
ffmpeg -rtsp_transport tcp -i "rtsp://<GLASSES_IP>:8554/ch0" \
       -c copy -f flv "rtmp://<HOST>/<app>/<STREAM_KEY>"
```
- `-rtsp_transport tcp` — we used TCP successfully; avoids UDP packet loss on Wi-Fi. *[verified working]*
- `-c copy` — no re-encode: keeps CPU near-zero and preserves the stream exactly. Valid because FLV/RTMP
  carries H.264 + AAC, both of which the glasses already produce *[verified codecs]*. `ffmpeg` auto-applies
  the H.264 bitstream conversion for FLV.
- Benign startup warnings `non-existing PPS 0 referenced` / `no frame!` are expected — the server sends
  SPS/PPS in-band and the first packets can precede them; the stream still relays fine. *[verified: we saw
  these during our capture and still got a valid 367-frame file]*

### A2 — When the ingest is strict (re-encode for compatibility)
Some ingests (notably **YouTube Live**) reject 8 kHz audio and want standard settings. Re-encode:
```bash
ffmpeg -rtsp_transport tcp -i "rtsp://<GLASSES_IP>:8554/ch0" \
  -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p -g 60 \
  -b:v 2M -maxrate 2M -bufsize 4M \
  -c:a aac -ar 44100 -b:a 128k -af aresample=async=1 \
  -f flv "rtmp://<HOST>/<app>/<STREAM_KEY>"
```
- `-c:a aac -ar 44100` fixes the unusual **8 kHz mono** audio *[verified codec]* that strict ingests refuse.
- `-pix_fmt yuv420p` normalizes the source's full-range `yuvj420p` *[verified]* to the widely-accepted
  limited-range 4:2:0.
- **Honest caveat:** re-encoding **cannot add quality** — the ~1.5 Mbps ceiling is baked in by the glasses'
  encoder *[verified: see the Ghidra bitrate finding in `moyoung_reverse_engineering.md` §8]*. Setting
  `-b:v 2M` here only avoids further loss; the real fix for quality is the firmware bitrate patch, not the
  relay.

### A3 — Push to another RTSP server (e.g. MediaMTX) via ANNOUNCE
```bash
ffmpeg -rtsp_transport tcp -i "rtsp://<GLASSES_IP>:8554/ch0" \
       -c copy -f rtsp -rtsp_transport tcp "rtsp://<SERVER_HOST>:8554/<PATH>"
```

### A4 — Push over SRT (low-latency, loss-tolerant links)
```bash
ffmpeg -rtsp_transport tcp -i "rtsp://<GLASSES_IP>:8554/ch0" \
       -c copy -f mpegts "srt://<HOST>:<PORT>?streamid=<ID>&pkt_size=1316"
```

### A5 — Auto-restart on drops (Wi-Fi flakiness)
`ffmpeg`'s `-reconnect` flags apply to HTTP inputs, **not** RTSP, so wrap the relay in a restart loop:
```bash
# bash
while true; do
  ffmpeg -rtsp_transport tcp -i "rtsp://<GLASSES_IP>:8554/ch0" -c copy -f flv "rtmp://<HOST>/<app>/<KEY>"
  echo "relay exited, restarting in 2s…"; sleep 2
done
```
```powershell
# PowerShell
while ($true) {
  & ffmpeg -rtsp_transport tcp -i "rtsp://<GLASSES_IP>:8554/ch0" -c copy -f flv "rtmp://<HOST>/<app>/<KEY>"
  Start-Sleep 2
}
```

---

## 4. Option B — MediaMTX (a media server that pulls once and fans out)

If you want **many viewers** and/or multiple output protocols (RTSP + RTMP + HLS + WebRTC) from one pull,
run **MediaMTX** (open source) on a box that can reach the glasses, and point a path at the glasses as its
source. MediaMTX then re-serves that feed and can also push it onward.

- Minimal idea: define a path whose **source is `rtsp://<GLASSES_IP>:8554/ch0`** with on-demand pulling, and
  read it back from MediaMTX's RTSP/RTMP/HLS/WebRTC endpoints, or use its "run on ready" hook to launch the
  §3 `ffmpeg` push to your final URL.
- **Grounding caveat:** MediaMTX's exact YAML keys (`source`, `sourceOnDemand`, transport option name) have
  changed across versions — follow the config reference for *your* installed version rather than trusting a
  pasted snippet. The device-specific part (the `rtsp://…:8554/ch0` source) is the only thing verified here;
  the server config is standard MediaMTX, not something we tested against this device.

Functionally MediaMTX just automates what §3 does by hand; if you only need one destination URL, the §3
`ffmpeg` one-liner is simpler and is the fully-verified path.

---

## 5. Where to run the relay

| Host | Works? | Notes |
|------|--------|-------|
| **This PC / a laptop on the LAN** | ✅ *[verified]* | We ran `ffmpeg` here against the glasses successfully. Simplest. |
| **Raspberry Pi / mini-PC on the LAN** | standard | Same commands; good for an always-on relay. Not tested in this project. |
| **The phone (Termux + ffmpeg)** | plausible, **untested here** | The phone is already on the glasses' network, so it could both host the app *and* relay. We have **not** verified Termux `ffmpeg` on this phone — treat as an experiment, not a verified path. |
| A cloud VPS | ❌ for the *pull* | A VPS can't reach `rtsp://<glasses>:8554` (LAN-only). A VPS is only useful as the **destination** (run MediaMTX/nginx-rtmp there and push to it from a LAN relay). |

---

## 6. The vendor's OWN push API — a real WebRTC path that some builds implement

There **is** a vendor API for "glasses push out to a server you provide" — it's a **WebRTC** design, exposed in
the **iOS SDK** and **implemented on-device in some `1.0.0.x` Vision builds** (via `libpeer`). The build this
unit runs (`1.4.x` branch) does **not** include it, but flashing a WebRTC build could — see §6.4.

### 6.1 The API (iOS SDK 1.2.0 — documented, `CRPSmartGlasses` framework) *[verified]*
Manual §4.6 "Live streaming and STA live mode" and the framework interface define:
```
setLiveStreamEnter(wifiCtrl: CRPWifiCtrl)   // enter live mode (Wi-Fi starts)
setSTALiveMode(staInfo: CRPStaLiveMode?)     // configure "push to your server" mode
setLiveStreamExit()                          // leave live mode
// progress via delegate:  receiveSTALiveState(_ state: CRPStaLiveState)
```
`CRPStaLiveMode` is the config you hand it *[verified from `.swiftinterface`]*:
```
wifi_ssid, wifi_pwd      // the Wi-Fi the glasses JOIN (STA) — your router/hotspot
web                      // your SIGNALING server URL
token, user_id, user_pwd // auth for that server
turn_urls, turn_id, turn_pwm   // your TURN server (WebRTC NAT traversal)
```
`CRPStaLiveState` transitions confirm it is **WebRTC**: `wifi_search/connect_*`, then
`webrtc_start_success`, `webrtc_new_connection`, `webrtc_connection_check`, `webrtc_connect_success`,
`webrtc_data_transfer`, `webrtc_disconnected`, plus `high_temperature_warning`. So the glasses join your
Wi-Fi, connect to **your** signaling + TURN infrastructure, and **push** a WebRTC video track — exactly the
"stream to a given server, viewer connects through the server" model.

### 6.2 It is NOT in the Android SDK *[verified]*
`setSTALiveMode`/`CRPStaLiveMode`/WebRTC/TURN appear **nowhere** in the Android BLE SDK 0.0.7 (the base of
our tinker app) — grep of the AAR + dev-docs is empty. So it's not callable from our current app without a
newer Android SDK or reversing the underlying BLE command.

> **⚠️ CORRECTION (2026-07-30).** An earlier version of this section claimed "the A073 isn't one of those
> models / no MoYoung V821 build has WebRTC." **That was wrong.** A scan of all 69 archived Vision builds found
> **4 builds with a real on-device WebRTC stack** (`/usr/lib/libpeer.so` + `libsrtp2` + `libmbedtls`;
> `peer_signaling_*`, `webrtc_start`, `stun:rtc-sz.moyoung.com:3478`, and `webrtc_apply_ag_config` taking the
> exact `CRPStaLiveMode` fields — `token/web_urls/user_id/user_pwd/turn_urls/turn_id/turn_pwd`). They are on the
> **`1.0.0.x` branch** (e.g. `1.0.0.10.3.2604161510`, `1.0.0.8.3.2601221555`). So the STA-WebRTC push **is
> implemented on-device**, just not in the build/branch this unit runs. §6.3 below is corrected accordingly;
> §6.4 gains the "flash a WebRTC build" option.

### 6.3 What's actually in the firmware — verified across all 69 archived Vision builds *[static analysis]*
A scan of every archived `.swu` (unpack rootfs, grep for a real WebRTC stack) shows two groups:
- **WebRTC-capable (4 builds):** e.g. `-ai` `1.0.0.10.3.2604161510` and `1.0.0.8.3.2601221555`, plus two `-ab`
  builds. Verified contents of `1.0.0.10…`: **`/usr/lib/libpeer.so`** (embedded WebRTC C lib) + **`libsrtp2.so`**
  + **`libmbedtls.so`**; and inside `ai_glass_livestream`: `peer_signaling_join_channel`, `peer_signaling_thread`,
  `peer_connection_oniceconnectionstatechange`, `webrtc_start`, `stun:rtc-sz.moyoung.com:3478`, and
  `webrtc_apply_ag_config: token=%s, web_urls=%s, user_id=%s, user_pwd=%s, turn_urls=%s, turn_id=%s, turn_pwd=%s`
  — i.e. the exact `CRPStaLiveMode` fields. This is the genuine on-device receiver for `setSTALiveMode`.
- **RTSP-only (everything else, incl. the whole `1.4.x` branch):** no `libpeer`, no `peer_signaling`, no `stun:`;
  `ai_glass_livestream` = 47 `rtsp` strings, 0 real WebRTC. (A lone `rtcp-mux` token appears — that's standard
  RTSP SDP, not WebRTC.)

⇒ **Grounded conclusion:** the STA-WebRTC push is **real and implemented on-device**, but only in specific
`1.0.0.x`-branch builds. This unit runs the **`1.4.x -ai` branch, which is RTSP-only** in every archived build,
so `setSTALiveMode` has nothing to honour *on the firmware it runs today*.

> **⚠️ Which exact build is on the device — UNRESOLVED (and the relabel is DEVICE-side, not app-side).**
> No archived build matches the app's reported `2.4.0.22.3.2603302218` on all fields: `2.4.x` exists in **no**
> firmware, the build-stamp `2603302218` maps to `1.4.0.20.3.2603302218`, but the 4th segment `22` maps to a
> *different-stamp* build (`1.4.0.22.3.2606031721`). **Decompiling the Da Echo APK (2026-07-30) proved the app
> does NOT transform the version** — `DeviceVersionCallback` stores `versionInfo.getVer()` verbatim — so the
> glasses *themselves* report `2.4.0.22…` over BLE, even though the running `.swu`'s `ag_user_version.conf`
> says `1.4.0.20…`. The `2.4`↔`1.4` relabel is therefore **in the device firmware, not decodable from the
> APK.** Only the unique build-stamp `2603302218` reliably links to `1.4.0.20.3.2603302218`; pinning the exact
> image definitively needs reading it off the **live device** (glasses adb/UART). Branch is certain: `1.4.x`,
> RTSP-only.

> **⚠️ Remaining limits (static analysis).** The scan covered **all 69 archived Vision builds' squashfs
> rootfs**, so the "which builds have WebRTC" split is well-grounded across what's on the server. It still does
> **not** cover: the `riscv` co-processor blob / kernel (not grepped — WebRTC there would be unusual), future
> builds that don't exist yet, or **runtime** behaviour (strings show present code, not how the device acts).
> **Definitive test = call `setSTALiveMode` and observe `receiveSTALiveState`.** Testing the API on the
> *current* firmware is low-risk (a BLE command + Wi-Fi/WebRTC attempt; worst case `webrtc_start_fail`; cannot
> brick). *Flashing a WebRTC build (§6.4) is a separate, higher-risk step.*

### 6.4 So, to push from *this* device, options ranked by effort:
1. **A relay (Option A) — works today.** Recommended; nothing on the device changes.
2. **Flash a WebRTC-capable stock build** (e.g. `1.0.0.10.3.2604161510`, which ships `libpeer`), then drive it
   with `setSTALiveMode` (via iOS SDK, or by replaying its BLE command). This uses the vendor's *own* push path
   — no custom code. Caveats: it's a **cross-branch** flash (`1.4.x`→`1.0.0.x`; likely different/older camera
   tuning, and unverified it boots cleanly on this exact hardware), and it carries the **brick risk** (in-place
   Allwinner flash, no recovery net without UART/USB — see `moyoung_reverse_engineering.md` §7,
   `paths_forward.md` C/D). Also needs MoYoung's (or your own) signaling+TURN, since the build points at
   `rtc-sz.moyoung.com`.
3. **A custom firmware mod** — port a pusher into *our* build and swap `/etc/media/rtc_init.sh` mode 8
   *[verified: mode 8 launches `ai_glass_livestream` arg-less]*, repack (`mksquashfs` lzo/32K + refresh
   `cpio_item_md5` + newc-CRC CPIO), flash. Most work; same flash prerequisite as #2.

Until a safe flash path exists, **Option A (relay) is the grounded, working answer** for this unit. #2 is the
most promising native route because the capability is already in shipped firmware — it just isn't on our branch.

---

## 7. Quick verification checklist

1. Start live view in the app → glasses serve `rtsp://<ip>:8554/ch0`.
2. `ffprobe -rtsp_transport tcp "rtsp://<ip>:8554/ch0"` → prints `Video: h264 … 1600x1200 … 30 fps` and
   `Audio: aac … 8000 Hz`. If this fails, the relay will too — fix connectivity/live-mode first.
3. Start the §3 relay to your `<URL>`.
4. Confirm on the destination (player pointed at your RTMP/RTSP/SRT URL, or the platform's dashboard).

---

## 8. Provenance

Source URL, port, codecs, bitrate, transport, port-exposure, "live-mode-only", and the "no push client in
firmware" facts were all established **this session** on the user's MOY-A073 / V06 unit (RTSP scan + SDP +
`ffprobe`/`ffmpeg` measurement + Ghidra of `ai_glass_livestream`). See the append-only log
[`moyoung_reverse_engineering.md`](moyoung_reverse_engineering.md) §3 (RTSP diagnosis) and §8 (encoder
analysis). The `ffmpeg`/MediaMTX/SRT invocations are standard tool usage; only the device-specific inputs
are asserted as verified.
