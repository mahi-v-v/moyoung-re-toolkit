# 17 — What We Can Do For The Company Now (business value, ranked)

**Audience:** product / leadership. **Purpose:** an honest decision tool — what we can ship **right now**, given
that **every device-modification path is blocked or gated**. So this doc is deliberately *not* about changing the
glasses' firmware. It's about the **software + cloud + app layer we fully control**, built on the glasses'
**existing, as-shipped features**. Every claim links to its grounded technical doc and is tagged **NOW**
(deployable today, no device changes) or, in the last section, **BLOCKED** (tried, not currently possible).

> **Reality check first (why the earlier "modify the device" framing was wrong):**
> - The **RTSP video output is the vendor's own feature**, not something we built — the glasses already stream it.
> - We **cannot flash the Vision (Wi-Fi/camera) chip** — its OTA reports success but silently doesn't apply
>   ([14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)). ⇒ the **6 Mbps quality bump is a no-go**; video stays ~1.5 Mbps.
> - We **cannot change the wake word** — it needs JieLi's server-side trained model ([16](16_WakeWord_and_Offline_Voice.md)).
> - Modifying the Core (BLE) chip's behavior is unproven (E3 gate) **and** low-value.
>
> None of that blocks building a real product — it just means the product lives in **our** code, not in their firmware.

> Device recap: **Core** = JieLi AC701N (BLE + audio, always-on); **Vision** = Allwinner V821 (camera + Wi-Fi + AI).
> See [01](01_Hardware_Architecture.md). We drive the glasses over BLE via the CRP SDK and pull media/video over Wi-Fi.

---

## The one-screen answer (ranked by business value)

| # | What we can do NOW (glasses unmodified) | Business value | Why it's real today |
|---|---|---|---|
| 1 | **"Capture & ask" AI on full-res photos** — trigger a shot, run our vision AI on it | ★★★★★ — *product*, and **sidesteps the video quality cap** | SDK triggers photo + AI-recognition capture; stills are full-res, not bitrate-limited — [03](03_Mobile_App_and_SDK.md), [15 §4](15_Core_App_Analysis.md) |
| 2 | **Branded companion app orchestrating the glasses as-is** — capture, live view, media sync, status | ★★★★★ — the product's delivery surface, no firmware needed | Our app already connects + drives the SDK on real hardware — [03](03_Mobile_App_and_SDK.md), [04](04_BLE_Protocol_Reference.md) |
| 3 | **Live AI on the POV video stream** (our cloud) — describe / Q&A / OCR / translate the wearer's view | ★★★★☆ — the "always-on" version of #1 (capped at ~1.5 Mbps) | Relay of the vendor RTSP into our pipeline works today — [streaming](streaming_glasses_to_a_url.md), [06](06_Live_Video_Streaming.md) |
| 4 | **Multi-destination live distribution** — RTSP → **RTMP** (YouTube/Twitch/social), HLS/WebRTC (web viewer), SRT, + record/clip | ★★★★☆ — *your RTMP idea*; broadcast the POV anywhere | Standard ffmpeg/MediaMTX transcode of the existing RTSP — [streaming](streaming_glasses_to_a_url.md) |
| 5 | **Voice & audio AI flows** — transcription / translation / voice commands on the glasses' mic + existing voice stack | ★★★☆☆ — hands-free UX layer | Glasses do AI-voice + offline commands + Opus audio; we build flows around them — [15 §4,§8](15_Core_App_Analysis.md) |
| 6 | **Vendor independence, recovery & security diligence** | ★★☆☆☆ — de-risks the business | Full firmware archive + both firmwares understood — [07](07_Firmware_Acquisition.md), [08](08_Core_Firmware_Jieli.md), [09](09_Vision_Firmware_V821.md) |

The through-line: **#1 + #2 are a shippable product on their own, at good quality, with zero device changes.**
#3–#4 add the live/broadcast dimension (at the vendor's video quality). Details and honest constraints below.

---

## 1. "Capture & ask" AI on full-res photos — ★★★★★ — **NOW** *(the quality-cap sidestep)*
The single most under-rated option, because it **dodges the blocker that killed everything else.** The ~1.5 Mbps
ceiling is on the **video encoder** — it does **not** apply to **still photos**, which the glasses capture at full
sensor resolution. The SDK can **trigger a photo** (including the on-device "AI recognition" capture mode) and we
retrieve the image ([03](03_Mobile_App_and_SDK.md), [15 §4](15_Core_App_Analysis.md)).
- **Product:** wearer says/taps → glasses snap a **high-res** frame → our cloud VLM describes it, answers a
  question about it, reads text (OCR), identifies objects/products/landmarks, translates a sign. This is the core
  "the app" promise at a quality the live stream can't match.
- **Why now:** no firmware change — it uses shipped capture + our cloud. **Honest constraint:** it's snapshot-based
  (not continuous), and capture→retrieve latency depends on the BLE/Wi-Fi transfer.

## 2. Branded companion app orchestrating the glasses as-is — ★★★★★ — **NOW**
We don't need the vendor's "Da Echo" app. **Our** app already runs on hardware and drives the glasses over the CRP
SDK ([03](03_Mobile_App_and_SDK.md), [04](04_BLE_Protocol_Reference.md)): connect/pair, **remote-trigger photo &
video**, start/stop **live view**, **sync/download** the on-device media library, read **status/battery**, manage
Wi-Fi. That's a complete, ownable product surface — the delivery vehicle for #1, #3, #5 — with **no firmware work**.
- **Value:** our brand, our UX, our feature set, our cloud — the glasses become "our" device to the end user.

## 3. Live AI on the POV video stream — ★★★★☆ — **NOW** *(at vendor quality)*
For continuous (not snapshot) AI, we relay the glasses' **existing RTSP** into our pipeline and run vision AI on
the frames — live description, visual Q&A, scene/object detection, live captioning/translation of what's seen.
Relay-based glasses→server streaming **works today** ([streaming](streaming_glasses_to_a_url.md), [06](06_Live_Video_Streaming.md)).
- **Honest constraints:** (a) video is **~1.5 Mbps, 1600×1200@30** — usable for most CV/VLM tasks but not crisp;
  the 4× bump is blocked (see BLOCKED). (b) The glasses must be on **Wi-Fi (STA mode)** and reachable by our
  server/relay. (c) Continuous cloud inference has a **compute-cost** dimension to design for.

## 4. Multi-destination live distribution (your RTMP idea) — ★★★★☆ — **NOW**
The RTSP the glasses emit is a standard source, so we can **transcode and fan it out** with ffmpeg/MediaMTX on our
server: **RTSP → RTMP** to YouTube/Twitch/Facebook/any social endpoint, **HLS/WebRTC** to a low-latency web
viewer, **SRT** for contribution, plus **recording and clipping**. This is exactly the "export it to RTMP or
something else" idea — and it needs **nothing on the device** ([streaming](streaming_glasses_to_a_url.md)).
- **Value:** turn the wearer's POV into a broadcastable/recordable stream — live events, remote assistance,
  creator/streamer use. **Same quality ceiling** (~1.5 Mbps) and **STA-Wi-Fi** constraint as #3.

## 5. Voice & audio AI flows — ★★★☆☆ — **NOW-ish**
The glasses already run offline command recognition + an online "AI voice" path and record Opus audio
([15 §4,§8](15_Core_App_Analysis.md)). We can build **hands-free flows** around that: pull/stream the mic audio for
**transcription, translation, meeting notes, voice-triggered capture** (tie a spoken command to #1's snapshot).
- **Honest note:** we cannot add a *new custom wake word* (BLOCKED), but we **can** build voice features on top of
  the existing recognized triggers and the audio stream. Integration depth needs a short scoping pass.

## 6. Vendor independence, recovery & security diligence — ★★☆☆☆ — **NOW**
Insurance the RE already bought: a **full firmware archive** (both chips, every version) for recovery/version-
pinning/diffing ([07](07_Firmware_Acquisition.md)); **both firmwares understood** ([08](08_Core_Firmware_Jieli.md),
[15](15_Core_App_Analysis.md), [09](09_Vision_Firmware_V821.md)); and a clear **security exposure map**
(unauthenticated firmware server, unsigned Vision images, mapped device-auth/JWT — [10](10_Cloud_and_Device_Auth.md))
useful for our own hardening and partner/security conversations.

---

## BLOCKED / not possible now (the honest record of what was tried)
| Item | Status | Why | Would need |
|---|---|---|---|
| **Raise video quality 1.5 → 6 Mbps** (image is built) | ❌ blocked | Vision Wi-Fi OTA reports success but **doesn't apply** the image ([14 §9](14_InApp_OTA_and_the_JieLi_lib_gap.md)) | A/B-correct `.swu` retarget **or** a UART teardown |
| **Custom / changed wake word** | ❌ no-go | Word set is compiled by **JieLi's server-side pipeline**; no self-serve tool, no unofficial method ([16](16_WakeWord_and_Offline_Voice.md)) | OEM (CRREPA) → JieLi trained-model request |
| **Modify Core (BLE) firmware behavior** | ⏳ gated + low value | Whether a modified re-encrypted Core boots is the unproven **E3** gate — and the payoff is small | Prove E3 (low-risk via A/B), for little benefit |
| **Run our AI models ON the glasses (edge)** | ❌ hard | Needs a root shell on Vision; no USB data line, OTA route dead | Hardware teardown / UART |

**Net:** the device's *behavior is fixed* for us — but that doesn't limit the product, because items 1–6 live in
our app and cloud, not in the firmware.

---

## Recommended sequencing (value ÷ effort, all unblocked)
1. **Ship the "capture & ask" loop (#1 + #2)** — highest value, best quality (full-res stills), zero device work.
   This is a real product demo on its own.
2. **Add live AI + distribution (#3 + #4)** on the relayed RTSP for the always-on / broadcast use cases — accept
   the ~1.5 Mbps ceiling and the Wi-Fi-STA requirement.
3. **Layer voice flows (#5)** for hands-free triggering once #1 is in place.
4. **Keep #6 as posture** — recovery images and security notes on hand.
5. **Only revisit the BLOCKED list** if a specific need justifies the cost — quality bump (teardown) or branded
   wake word (OEM relationship). Don't build the roadmap on them.

*Provenance: synthesis of docs 01–16 + the append-only log; claims tagged NOW / BLOCKED to avoid over-claiming.
The video-quality ceiling (~1.5 Mbps) and Wi-Fi-STA requirement apply to all live-video items (#3, #4).*
