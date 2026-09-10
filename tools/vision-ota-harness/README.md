# Vision OTA laptop harness

> **⚠ OBVIATED (2026-08-07) — read before using.** This harness was built to test the hypothesis that
> *Android reaps the Wi-Fi AP and truncates the OTA transfer.* That hypothesis was **disproven** before the
> harness was needed: a wired `NetworkCallback` watch showed the phone's Wi-Fi does **not** drop during the
> transfer, the phone serves the whole file (`sendBody sent=12514304/12514304 complete`), and the **device
> reports OTA `SUCCESS` without flashing** (12/12 in one day, genuine `-ab` image). So the block is **device-side**,
> not the phone/transport — moving the server to a laptop **cannot** change the device's decision. This tool is
> kept for reference (and its firmware recon is accurate), but it is **not a path to a working flash.** The real
> unknown (why `swupdate` no-ops) lives on the device's `ttyS3` console — a teardown, which the user has declined
> for now. See `docs/14` §9 and `docs/moyoung_reverse_engineering.md` §24.

A test rig for the Vision (Allwinner V821) Wi-Fi OTA: a laptop stands in for the phone's HTTP server so the
transfer can be captured from a real endpoint. (Originally framed as "who truncates the transfer — phone, network,
or device"; the answer turned out to be **the device no-ops the apply**, see the banner above.)

## Background
The Vision firmware flashes over Wi-Fi: the glasses come up as an **OPEN** AP (`192.168.31.1`) +
DHCP server and HTTP-GET the `.swu` from whatever station holds the expected IP — normally the
**phone** at `192.168.31.2:8182` (the phone's in-app NanoHTTPD). That transfer **truncates at ~40%
every run**; a genuine unmodified image truncates identically, so the file is not the cause. The phone
serves the whole file (instrumented) yet the glasses receive only ~40% then a clean EOF, and the
firmware has **no failure opcode** so it reports the truncated download as *success* — only a **reboot +
version change** actually means it flashed.

Three suspects remain: (1) Android reaping the no-internet AP, (2) the RF path dropping, (3) the
device's OTA process under-reading / aborting. The phone-as-server can't be sniffed cleanly (PCAPdroid
sees ~nothing — the phone is the *server*, and Android has no monitor mode). This harness resolves it.

## What it does
Moves the **HTTP server onto the laptop**, keeping the **BLE trigger on the phone**:

- **Laptop** joins the open glasses AP, takes `192.168.31.2`, serves the exact genuine `.swu`, and runs
  **Wireshark** — a real endpoint, so it captures **both directions** (no monitor mode needed).
- **Phone** (app → *"Flash via LAPTOP server (diagnostic)"*) only brings up the AP and sends the BLE
  "start OTA". It does **not** join the AP, so it's out of the data path.

If the transfer now **completes + the device reboots + the version changes** → **Android was the
problem** (and this harness is also the fix). If it still stalls ~40% with a clean server + full route
control → **device-side / by design**, and Wireshark shows exactly who sends the FIN/RST.

Facts confirmed from the firmware: the OTA AP has **no `wpa`/passphrase** in `hostapd.conf` (open);
`192.168.31.x` / `8182` / any URL appear **nowhere** in the rootfs (the device learns the server from
the network via `getifaddrs` + DHCP) — which is exactly why swapping the phone for the laptop works.

## Run
On the app (phone), select build **"0 · Vision GENUINE -ab (control test)"**, then
*Flash — … → "Flash via LAPTOP server (diagnostic)"*. Follow its dialog. On the laptop:

1. **Test the server locally first:**
   ```
   python vision_ota_server.py
   # in another shell:  curl -o NUL http://127.0.0.1:8182/x   -> expect "DONE ... (FULL)"
   ```
2. When the app's OTA state shows **`awaitingExternalServer`**:
   - **Join** the open glasses Wi-Fi (it appears once the app enables it).
   - **Static IP** the Wi-Fi adapter to `192.168.31.2 / 255.255.255.0`, gateway `192.168.31.1`:
     ```
     netsh interface ip set address name="Wi-Fi" static 192.168.31.2 255.255.255.0 192.168.31.1
     ```
     (revert afterwards: `netsh interface ip set address name="Wi-Fi" dhcp`)
   - **Start the server:** `python vision_ota_server.py`
   - **Start Wireshark** on that Wi-Fi interface, display filter `tcp.port == 8182`.
3. Back in the app, tap **"Continue — laptop ready"**. Watch the server log + Wireshark.

## Reading the result
| Wireshark on `tcp.port == 8182` | Verdict |
|---|---|
| server log `DONE … (FULL)`, device reboots, **version changes** | transport works → **Android was the cause** |
| glasses(`.1`)→laptop `FIN` first at the stall | **device closed its read** (device-side / by design) |
| glasses → `RST` | **device reset** (device-side) |
| silence, laptop retransmits, no ACK, timeout | **network path dropped** |

## Files
- `vision_ota_server.py` — the stand-in HTTP server (defaults to the genuine `-ab` image; logs exact
  bytes sent + why it stopped). Pure stdlib, no deps.

## Notes / fallbacks
- The laptop must serve **the same genuine file the app declares over BLE** (size must match) — the
  script defaults to `firmware/vision-v821/aiglass-ab/20260729144724_openwrt_v821_aiglass-ab.swu`
  (12,514,304 bytes), which is the build "0" the app ships. Keep them in sync.
- If the glasses never GET from the laptop, the server IP may be phone-reported rather than
  DHCP-derived — pivot to serving from `.2` with the phone also reporting it, or the pure-laptop BLE
  path (needs the GATT UUIDs). Watch Wireshark for a SYN to a *different* IP/port to tell.
- If Windows won't sniff its own Wi-Fi cleanly, note the OTA AP is **open**, so a Linux box with a
  monitor-mode adapter can passively decode the phone↔glasses link with no key.
