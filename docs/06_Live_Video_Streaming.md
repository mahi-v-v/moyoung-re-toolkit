# 06 — Live Video Streaming (`setSTALiveMode` / STA Live Mode)

Every claim below is tagged. **CONFIRMED** = read directly out of a shipped artifact (quoted, with
the file it came from). **UNKNOWN** = not determinable from the artifacts in this repo; do not guess.

> ## ⚠️ 2026-07-30 — ON-DEVICE REALITY (read first; scopes this whole doc)
> This document describes the **iOS SDK 1.2.0 `setSTALiveMode` = WebRTC** design. On-device testing
> + firmware analysis this session refined it substantially:
> 1. **This unit (`MOY-A073`, Vision branch `1.4.x`) streams over plain RTSP**, *not* WebRTC:
>    `rtsp://<glasses-ip>:8554/ch0`, **H.264 1600×1200 @ ~30 fps, AAC 8 kHz** — measured with
>    `ffprobe`. The phone gets the URL over BLE via `onLiveUrlChanged`.
> 2. **The quality problem is a BITRATE cap, not resolution.** Measured ≈ **1.5 Mbps** for 2 MP/30 —
>    ~0.026 bits/px/frame. Ghidra found the constant hardcoded in `ai_glass_livestream`
>    (`iStack_154 = 1500000`); a hidden H.265 branch would use 12 Mbps.
> 3. **WebRTC push IS real but only in specific Vision builds** (the `1.0.0.x` branch ships
>    `libpeer`/`libsrtp`/`peer_signaling` + `stun:rtc-sz.moyoung.com`). Our `1.4.x` build has none.
>    So `setSTALiveMode` (below) targets firmware this unit isn't running.
>
> Full analysis + fix options: **[11 — Streaming Bitrate Analysis](11_Streaming_Bitrate_Analysis.md)**;
> practical restreaming how-to: **[streaming_glasses_to_a_url.md](streaming_glasses_to_a_url.md)**.
> The WebRTC/`setSTALiveMode` reference below remains accurate *as an iOS-SDK/other-branch capability*.

> **Correction (this doc supersedes an earlier draft).** An earlier version of this document stated
> that signaling runs over WebSocket, based on a `CRPWebSocketManager` class name found in the
> binary. **That was not supported by evidence and has been retracted** — see [§6](#6-what-was-wrong-before).

**Sources.** iOS SDK **1.2.0** only:
- `CRPSmartGlasses.xcframework/ios-arm64/.../Modules/CRPSmartGlasses.swiftmodule/arm64-apple-ios.private.swiftinterface`
  — the complete public Swift interface, in source form. Primary source.
- `.../Headers/CRPSmartGlasses-Swift.h` — the ObjC header (carries the vendor's Chinese comments).
- `.../CRPSmartGlasses` — the Mach-O binary (load commands + string extraction).
- `IOS-SDK Development Guide.pdf` / `IOS-SDK开发指南.pdf` — the official guides.

---

## 1. The one-line answer

**The glasses do WebRTC. The phone does not.**

The phone SDK's entire role is to hand the glasses nine configuration strings over BLE. It contains
no WebRTC code, no signaling code, and never touches the media. Everything after that runs on the
glasses (the Allwinner SoC — see [01](01_Hardware_Architecture.md)).

---

## 2. Evidence that the protocol is WebRTC — CONFIRMED

Two independent lines, both from the vendor's own shipped API.

**(a) The state enum is literally named after it.** From the `.swiftinterface`, verbatim:

```swift
@objc public enum CRPLiveState : Swift.Int {
  case start_success = 1
  case start_fail = 2
  case wifi_search_success = 3
  case wifi_search_fail = 4
  case wifi_connect_success = 5
  case wifi_connect_fail = 6
  case webrtc_start_success = 7
  case webrtc_start_fail = 8
  case webrtc_connection_closed = 9
  case webrtc_new_connection = 10
  case webrtc_connection_check = 11
  case webrtc_connect_success = 12
  case webrtc_data_transfer = 13
  case webrtc_connect_fail = 14
  case webrtc_disconnected = 15
  case high_temperature_warning = 16
}
```
Nine of sixteen states are named `webrtc_*`. The ObjC header carries the vendor's own comments on
the same cases (`/// webrtc启动成功`, `/// webrtc连接成功`, …). This is the vendor naming its own
shipped public API — not an inference.

**(b) TURN credentials are a first-class part of the config.** `turn_urls`, `turn_id`, `turn_pwm`
(§3). TURN (RFC 5766/8656) is the standard ICE relay used by WebRTC.

The state ordering also reads as a coherent WebRTC lifecycle: bring up Wi-Fi (3–6), start the stack
(7–8), then per-peer `new_connection → connection_check → connect_success → data_transfer` (10–13)
with `connection_closed` / `connect_fail` / `disconnected` as the failure/teardown cases.

> **Note on scope:** this establishes WebRTC as what the **glasses** run. It says nothing about the
> signaling wire format — see [§5](#5-what-is-still-unknown).

---

## 3. `CRPStaLiveMode` — the payload — CONFIRMED

Verbatim from the `.swiftinterface`:

```swift
@objc @objcMembers public class CRPStaLiveMode : ObjectiveC.NSObject {
  @objc public var wifi_ssid: Swift.String
  @objc public var wifi_pwd: Swift.String
  @objc public var token: Swift.String
  @objc public var web: Swift.String
  @objc public var user_id: Swift.String
  @objc public var user_pwd: Swift.String
  @objc public var turn_urls: Swift.String
  @objc public var turn_id: Swift.String
  @objc public var turn_pwm: Swift.String
  @objc public init(wifi_ssid: ..., wifi_pwd: ..., token: ..., web: ..., user_id: ...,
                    user_pwd: ..., turn_urls: ..., turn_id: ..., turn_pwm: ...)
}
```

Nine fields, all `String`. Grouping by evident role:

| Field | Role | Confidence |
|-------|------|-----------|
| `wifi_ssid`, `wifi_pwd` | the Wi-Fi network the glasses join | **Confirmed** (names + `wifi_search`/`wifi_connect` states) |
| `web` | an endpoint URL the glasses contact | **Confirmed** it's a URL string; its protocol is **UNKNOWN** |
| `token`, `user_id`, `user_pwd` | credentials presented to that endpoint | **Inferred** from names — no code path in the phone SDK to verify |
| `turn_urls`, `turn_id`, `turn_pwm` | TURN server + long-term credentials | **Confirmed** as TURN by name; `pwm` is presumably a typo for `pwd` (**inferred**) |

The API:
```swift
@objc open func setSTALiveMode(staInfo: CRPSmartGlasses.CRPStaLiveMode?)   // note: Optional
@objc optional func receiveSTALiveState(_ state: CRPSmartGlasses.CRPStaLiveState)
```
The parameter is Optional. Passing `nil` is legal; **what nil does is UNKNOWN** (plausibly clears or
exits the mode — untested).

Guide §4.6, verbatim: *"Set STA live mode (state changes via receiveSTALiveState)"*.

---

## 4. The Wi-Fi modes — CONFIRMED, and not what the earlier draft implied

`CRPWifiCtrl` is a **separate, more general** mechanism. Verbatim:

```swift
@objc @objcMembers public class CRPWifiCtrl : ObjectiveC.NSObject {
  @objc public var mode: CRPSmartGlasses.CRPWifiMode
  @objc public var ssid: Swift.String
  @objc public var password: Swift.String
  @objc public var channel: Swift.UInt32
}
@objc public enum CRPWifiMode : Swift.Int {
  case startAp    = 0     // glasses become an access point
  case startSta   = 1     // glasses join a network (station)
  case startP2Pgo = 2     // Wi-Fi Direct group owner
  case startP2Pgc = 3     // Wi-Fi Direct group client
}
```

**Four** Wi-Fi modes, not two. And the same `CRPWifiCtrl` is used by three different subsystems:

```swift
@objc open func setLiveStreamEnter(wifiCtrl: CRPWifiCtrl)     // guide: "Enter live streaming mode (Wi-Fi will be started)"
@objc open func setLiveStreamExit()
@objc open func setFileSyncModeEnter(wifiCtrl: CRPWifiCtrl)
@objc open func setOTAModeEnter(wifiCtrl: CRPWifiCtrl)
```

So "AP mode vs STA mode" is a property of `CRPWifiCtrl.mode`, orthogonal to which subsystem is being
started. `setLiveStreamEnter` can itself be given `startSta`.

**UNKNOWN: how `setLiveStreamEnter` and `setSTALiveMode` relate.** Both concern live streaming, and
`CRPStaLiveMode` carries its own `wifi_ssid`/`wifi_pwd`, so they may be alternatives *or* a
required sequence (`setLiveStreamEnter` first to bring up Wi-Fi, then `setSTALiveMode` to configure
WebRTC). Neither guide states the ordering. **Do not assume one; test it.**

---

## 5. What is still unknown

Each of these was actively checked against the artifacts and could not be resolved:

| Question | Why it can't be answered here |
|----------|-------------------------------|
| Signaling protocol/scheme of `web` | The phone never speaks it. No `ws://`/`wss://`/`http://` scheme strings related to live exist in the binary; no SDP/ICE keys (`offer`, `answer`, `candidate`, `sdp`, `sdpMid`, `iceServers`) appear at all — searched, **0 hits**. Neither PDF mentions signaling (Chinese guide: 信令 = 0 occurrences, webrtc = 0). |
| Whether media is peer-to-peer or via a media server | `webrtc_new_connection` firing per connection is suggestive of the glasses accepting peers directly, but a server-side SFU would produce the same states. Not decidable from the phone SDK. |
| Codec, resolution, bitrate, audio presence | Not exposed anywhere in the API. Read off the SDP at runtime. |
| Protobuf field numbers for `StaLiveMode` | The type conforms to `SwiftProtobuf.Message` (binary symbols confirm), but the field-number map is not recoverable from strings. Declaration order 1–9 is the protoc convention — **unverified**. |
| TLS validation on `web` / `turn_urls` | Device-side behaviour; not in the phone SDK. |
| What `setSTALiveMode(nil)` does | Undocumented. |

---

## 6. What was wrong before

The earlier draft claimed *"WebSocket signaling (`CRPWebSocketManager` over
`NSURLSessionWebSocketTask`)"*. That was a leap from two co-occurring strings to a causal claim, and
the follow-up checks refute it as a basis:

- `CRPWebSocketManager` is **not in the public Swift interface** — it's an internal class. Nothing
  ties it to live streaming.
- The framework's recovered source paths contain **no live/WebRTC file**: `CRPBle.swift`,
  `CRPManager.swift`, `CRPDiscovery.swift`, `CRPFileTransfer.swift`, `CRPUpgrade.swift`,
  `CRPSpeechToTextManage.swift`, `CRPVoiceAuth.swift`, `CRPAllwinnerFileProcessor.swift`,
  `JLUpgradeManager.swift`, `CRPSmartBand.swift`.
- **No WebRTC library is linked.** The Mach-O's 22 load commands are: AFNetworking, SwiftProtobuf,
  JL_OTALib, JLAudioUnitKit, JLLogHelper, JL_AdvParse, JL_HashPair, CoreBluetooth, Foundation,
  CoreFoundation, CoreServices, Security, SystemConfiguration, UIKit, libz, libobjc, libSystem,
  libc++, and four libswift dylibs. No libwebrtc/GoogleWebRTC.

`CRPWebSocketManager` most likely belongs to some other feature; **its purpose is UNKNOWN** and it
should not be cited for streaming.

The WebRTC conclusion itself stands — it rests on the `webrtc_*` enum names and the TURN fields, not
on the WebSocket class.

---

## 7. Android availability — CONFIRMED absent

The Android AAR `my_galsses_sdk_0.0.7` contains no `StaLiveMode`/`CRPStaLiveMode` class, no
`turn_urls`/`wifi_ssid`/`webrtc` strings in any class file, and no equivalent protobuf message
(searched; 0 hits). Its live support is limited to `CRPWifiType.LIVE` plus
`CRPWifiChangeListener.onLiveUrlChanged(String)`.

Per the iOS changelog, **live-streaming interfaces landed in iOS SDK 1.1.9** — Android 0.0.7
predates the feature. Options: request a current Android SDK from MoYoung, or reconstruct the
message and opcode (needs a BLE capture of an iOS session first).

The tinker app in this repo therefore does **not** expose STA live.

---

## 8. How to establish the unknowns (no teardown needed)

1. Stand up coturn + a throwaway HTTP/WS endpoint on a LAN box; put its URL in `web`.
2. Drive `setSTALiveMode` from the bundled demo (`vendor-sdk/ios/.../swift-SdkDemo`).
3. Capture simultaneously:
   - **BLE** → recovers the protobuf field numbers and the command opcode (enables an Android port).
   - **Traffic to your endpoint** → reveals the signaling scheme and message schema. Start by just
     logging *any* inbound connection: that alone settles whether it's HTTP, WS, or something else.
   - **RTP/SDP** → codec, resolution, bitrate, audio.
4. Watch `receiveSTALiveState` throughout; the enum tells you exactly how far it got.

Test `setLiveStreamEnter` vs `setSTALiveMode` ordering explicitly (§4) — try `setSTALiveMode` alone
first, and see whether `wifi_search_success` (state 3) ever arrives.

---

## 9. Incidental findings from the same sources

Not about streaming, but confirmed while verifying it, and directly relevant to the firmware work
(these **upgrade** claims in [02](02_Firmware_and_OTA.md) from inferred to confirmed):

| Finding | Source |
|---------|--------|
| `fw1_ver` is the **Jieli** ("Jerry") version, `fw2_ver` is the **Allwinner** version | Guide §4.10, verbatim |
| Jieli OTA takes a local **`.ufw`** file | Guide §4.10: *"Start Jerry OTA (path is local file path, .ufw format)"* |
| **Allwinner OTA is `.swu`** — the same swupdate format as the Cyan V821 | Guide §4.10: *"Start Allwinner OTA (.swu format, Wi-Fi will be started)"* |
| Allwinner OTA can be driven by a **package URL** after joining the glasses' Wi-Fi | Guide §4.10: *"Send Allwinner OTA package URL"* |
| The cloud API base URL is **settable at runtime** — `setApiBaseUrl(baseUrl: String)` | Guide §4.9 / swiftinterface |
| SDK 1.2.0 added `analysisAllwinnerFile(url:outputDir:completion:)` — a built-in **Allwinner firmware parser** | swiftinterface; changelog *"Added Allwinner video parsing method"* |
| The iOS SDK is built from a shared `MOYSmartBand` codebase | recovered source paths |

`setApiBaseUrl` and `analysisAllwinnerFile` are both worth following up: the first lets you redirect
firmware checks to your own server without patching anything, the second is vendor code that already
knows how to decompose Allwinner images.
