# 04 — BLE Protocol Reference

The complete, grounded catalog of the MoYoung CRP SDK surface: every `CRPBleConnection` method,
every callback/listener, and the data shapes they carry. Derived from `javap` on the shipped AAR
(see [`reference/`](reference/)) and cross-checked against the vendor `dev-docs/`.

> **Wire vs API note:** these are the *SDK-level* commands. The actual GATT service/characteristic
> UUIDs are built in obfuscated code and are **not** in the public API (only the CCCD `0x2902`
> descriptor is hardcoded). To get the raw ATT opcodes/handles you need a **live BLE sniff**
> (nRF Connect, or nRF52840 + Wireshark) — that's an open worklist item ([05](05_Reverse_Engineering_Findings.md)).
> Payloads for "large data" are **protobuf** (`conn.protos.*`).

---

## CRPBleClient (entry)
| Method | Signature | Purpose |
|--------|-----------|---------|
| `create` | `static CRPBleClient create(Context)` | singleton init (Application.onCreate) |
| `isBluetoothEnable` | `boolean` | adapter on? |
| `getBondedDevices` | `Set<BluetoothDevice>` | paired devices |
| `scanDevice` | `boolean scanDevice(CRPScanCallback, long timeoutMillis)` | start scan |
| `cancelScan` | `void` | stop scan |
| `getBleDevice` | `CRPBleDevice getBleDevice(String mac)` | obtain a device handle |

## CRPBleDevice
`CRPBleConnection connect()` · `void disconnect()` · `boolean isConnected()` ·
`String getName()` · `String getAddress()` · `BluetoothDevice getBluetoothDevice()`

## CRPScanDevice (scan result)
`BluetoothDevice getDevice()` · `int getRssi()` · `byte[] getScanRecord()`
Parse the raw record with `CRPScanRecordParser.parseScanRecord(byte[])` →
`CRPScanRecordInfo { String firmwareType, int battery, boolean isCharging }`.
(So the advertisement carries firmware-type + battery + charging in the scan record — useful for
identifying/labeling devices before connecting.)

---

## CRPBleConnection — command surface

### Connection / time / language
| Method | Args | Returns / callback |
|--------|------|--------------------|
| `setConnectionStateListener` | `CRPBleConnectionStateListener` | — |
| `connect` | — | `boolean` (result via listener) |
| `syncTime` | — | — (usually auto after connect) |
| `sendLanguage` | `byte language` | — (codes are firmware-specific) |

`CRPBleConnectionStateListener`: `STATE_DISCONNECTED(?)`, `STATE_CONNECTING`, `STATE_CONNECTED`,
`STATE_DISCONNECTING` → `onConnectionStateChange(int)`.

### Device info
| Method | Args | Callback → data |
|--------|------|-----------------|
| `queryDeviceVersion` | `VersionInfo.VersionType, CRPDeviceVersionCallback` | `onDeviceVersion(VersionInfo)` |
| `queryDeviceId` | `CRPDeviceIdCallback` | `onDeviceId(String)` |
| `checkFirmwareVersion` | `CRPFirmwareRequestInfo, CRPNewFirmwareVersionCallback` | `onNewVersion(CRPNewFirmwareVersionInfo)` / `onLatestVersion()` |

`VersionInfo.VersionType`: `VerFirmware`, `VerFirmware1`, `VerProtocol`, `VerGithash`,
`VerTpVersion`, `VerTpCheckSum`. `VersionInfo { VersionType type, String ver, int tpVersion }`.
(Two firmware versions — `VerFirmware` + `VerFirmware1` — line up with the two chips; `VerGithash`
gives the exact build commit, handy for matching against downloaded images.)

`CRPFirmwareRequestInfo(mac, fw1_ver, fw2_ver)` → posts to `altair.moyoung.com` (see [02 §4](02_Firmware_and_OTA.md)).
`CRPNewFirmwareVersionInfo { newVersion, fileUrl, md5, otaType, firmwareType, has_upgrade }`,
consts `OTA_TYPE_{CLOSE_POPUP,FORCE,PROGRESS,RED_POINT}`, `FIRMWARE_TYPE_{JIELI=1,ALLWINNER=2}`.

### Battery
| Method | Args | Callback → data |
|--------|------|-----------------|
| `setBatteryListener` | `CRPBatteryListener` | `onBatteryChange(BatteryInfo)` |
| `queryBattery` | — | (via listener) |

`BatteryInfo { boolean charging, int lvl, int volt, int lowLvl, int powerOffLvl, BatteryVolt voltArray }`.

### Wi-Fi (bulk transport control)
| Method | Args |
|--------|------|
| `setWifiListener` | `CRPWifiChangeListener` |
| `enableWifi` | `CRPWifiType` (`FILE`/`OTA`/`LIVE`) |
| `disableWifi` | — |
| `connectWifi` | — |

`CRPWifiChangeListener`: consts `STATE_SUCCESS(0)`, `STATE_LOW_BATTERY(1)`, `STATE_TIMEOUT(2)`,
`STATE_BUSY(3)`; callbacks `onWifiStateChange(CRPWifiType, int)`,
`onWifiConnectionStateChanged(boolean)`, `onLiveUrlChanged(String)`.
`CRPWifiType`: `FILE(0x00)`, `OTA(0x01)`, `LIVE(0x02)`.

### Media files (over Wi-Fi)
| Method | Args | Callback |
|--------|------|----------|
| `setMediaFileChangeListener` | `CRPMediaFileChangeListener` | `onNewMediaFileChanged(CRPMediaFileInfo)` |
| `queryNewMediaFile` | — | (via listener) |
| `downloadMediaFile` | `CRPFileDownloadCallback` | see below |
| `downloadLogFile` | `CRPFileDownloadCallback` | device logs over Wi-Fi |

`CRPMediaFileInfo { int photoCount, videoCount, audioCount }` (wraps `FileCount`).
`CRPFileDownloadCallback`: `onStart()`, `onProgress(int)`, `onProgress(int total, int done)`,
`onDownloadFile(String dirPath, List<String> filePaths)`, `onSuccess()`, `onFail(int)`; error consts
`CODE_NOT_NETWORK(1)`, `CODE_HTTP_FAIL(2)`, `CODE_RESPONSE_FAIL(3)`, `CODE_RESPONSE_DATA_FAIL(4)`,
`CODE_URL_NULL(5)` — confirming an HTTP client pulling from a device-served URL.

### Audio recording & live
| Method | Args |
|--------|------|
| `startAudio` | `int time (sec, 0=∞), CRPCommandCallback` |
| `stopAudio` | — |
| `queryAudioState` | `CRPAudioStateCallback` → `onAudioState(CRPAudioStateInfo{recording, duration})` |
| `stopLive` | — |

### Photo / video
| Method | Args |
|--------|------|
| `takePhoto` | `TakePhoto.PhotoMode` (`ModeNormal`/`ModeAIRecognition`/`ModeContinuous`) |
| `queryVideoConfig` | `CRPVideoConfigCallback` → `onVideoConfig(VideoConfig{fps, maxDuration})` |
| `sendVideoConfig` | `VideoConfig, CRPCommandCallback` |

`TakePhoto { PhotoMode mode, ByteString param }` — the `param` bytes carry extra args (e.g. burst
count for `ModeContinuous`). `ModeAIRecognition` triggers the AI-vision path and delivers the image
via `CRPAiDialogueListener.onDialogueImageChange(File)` (see AI dialogue).

### OTA
| Method | Args | Path |
|--------|------|------|
| `startOta` | `CRPOtaListener` | Jieli / BLE, server-downloaded |
| `startOta` | `File, CRPOtaListener` | Jieli / BLE, local file |
| `abortOta` | — | |
| `startAllWinnerOta` | `File, CRPOtaListener` | Allwinner / Wi-Fi, local file |
| `resumeAllWinnerOta` | `CRPOtaListener` | Allwinner resume |
| `abortAllWinnerOta` | — | |

`CRPOtaListener`: `onDownloadStarting/onDownloadComplete/onProgressStarting/onProgressChanged(int)/
onCompleted/onAborted/onError(int, String)`; error consts `FIRMWARE_DOWNLOAD_FAILED(0x11)`,
`NO_FOUND_DEVICE(0x13)`, `FIRMWARE_VERSION_NULL(0x14)`, `NO_NEW_FIRMWARE_VERSION(0x15)`,
`DFU_PROCESS_FAILED(0x17)`. `CRPOtaState { NORMAL, OTA, NULL }`, `CRPOtaType { GR_A, GR_B }`.
Full flow in [02 — Firmware & OTA](02_Firmware_and_OTA.md).

### Translation (simultaneous interpretation)
`setTranslationListener(CRPTranslationListener)` → `onAudioChange(byte[])` (Opus/PCM audio) ·
`startTranslation(CRPCommandCallback)` · `pauseTranslation()` · `stopTranslation()`.

### AI dialogue
`setAiDialogueListener(CRPAiDialogueListener)`:
`onDialogueStart()`, `onDialogueAudioChange(byte[])` (PCM, ~16 kHz mono 16-bit),
`onDialogueImageChange(File)` (from `takePhoto(ModeAIRecognition)`), `onDialogueStop(boolean isTimeout)`.
`sendAIDialogueState(FlowStatus.FlowStatusType)` — `FlowStatusStart` / `FlowStatusComplete` /
`FlowStatusInterrupt`. `exitAIDialogue()`.

### Device control & settings
| Method | Args | Notes |
|--------|------|-------|
| `restart` | `CRPCommandCallback` | disconnects |
| `reset` | `CRPCommandCallback` | **factory reset — wipes data** |
| `shutdown` | `CRPCommandCallback` | power off |
| `removeBond` | `CRPCommandCallback` | unpair |
| `setFeatureActiveStateListener` | `CRPFeatureStateListener` | → `onFeatureStateChanged(RunningStatus)` |
| `queryFeatureActiveState` | — | live feature map ([01 §3](01_Hardware_Architecture.md)) |
| `sendVoiceWakeUpState` | `boolean enable` | wake word on/off (Cyan-parallel) |
| `queryVoiceWakeUpState` | `CRPVoiceWakeUpCallback` | → `onVoiceWakeUpState(boolean)` |
| `sendWearCheckState` | `boolean enable` | wear detection on/off |
| `queryWearCheckState` | `CRPWearCheckCallback` | → `onWearCheckState(boolean)` |

`CRPCommandCallback`: `onSuccess()` / `onFailure(int code)`.

---

## Beans, callbacks & enums (quick index)

**Beans** (`conn.bean`): `CRPAlarmInfo`, `CRPAudioStateInfo`, `CRPFirmwareRequestInfo`,
`CRPMediaFileInfo`, `CRPNewFirmwareVersionInfo`, `CRPSettingInfo`, `CRPSupportTestInfo`,
`CRPTestResultInfo`, `CRPUserInfo`, `CRPVoltageInfo`.

**Callbacks** (`conn.callback`): `CRPAlarmCallback`, `CRPAudioStateCallback`,
`CRPBootLoaderCallback`, `CRPCommandCallback`, `CRPDeviceIdCallback`, `CRPDeviceInfoCallback`,
`CRPDeviceTpVersionCallback`, `CRPDeviceVersionCallback`, `CRPFileDownloadCallback`,
`CRPHardwareCheckCallback`, `CRPMessageCallback`, `CRPMtuChangeCallback`,
`CRPNewFirmwareVersionCallback`, `CRPOtaStateCallback`, `CRPSettingCallback`, `CRPShutDownCallback`,
`CRPSosStateCallback`, `CRPVibrationLevelCallback`, `CRPVideoConfigCallback`,
`CRPVoiceWakeUpCallback`, `CRPWearCheckCallback`.

**Listeners** (`conn.listener`): `CRPAiDialogueListener`, `CRPBatteryListener`,
`CRPBleConnectionStateListener`, `CRPDeviceRssiListener`, `CRPFeatureStateListener`,
`CRPFileTransListener`, `CRPMediaFileChangeListener`, `CRPOtaListener`, `CRPShutterListener`,
`CRPTranslationListener`, `CRPWifiChangeListener`.

**Enums / types** (`conn.type`): `CRPHistoryDay` (TODAY…DAYS_AGO_14), `CRPOtaState`, `CRPOtaType`,
`CRPVibrationLevel` (OFF/WEAK/MEDIUM/STRONG), `CRPWifiType`.

**Not surfaced on `CRPBleConnection` but present in the AAR** (available if you go under the SDK):
alarms (`CRPAlarmCallback`), hardware self-test (`CRPHardwareCheckCallback` — ACC, HR_IC,
TEMPERATURE_IC, WEARING_CHECK, TOUCH_IC, COULOMB_METER, BATTERY_METER), SOS state
(`CRPSosStateCallback`), vibration level, MTU change, boot-loader state, RSSI, user info, shutter.
These hint at a shared CRP wearable codebase (the SDK is reused across MoYoung watches/bands), so
the glasses firmware may respond to more opcodes than the glasses-specific API exposes — a good
fuzzing surface.

---

## Data-shape appendix (protobuf messages)

```
BatteryInfo   { bool charging; int lvl; int volt; int lowLvl; int powerOffLvl; BatteryVolt voltArray }
VersionInfo   { VersionType type; string ver; int tpVersion }   // type ∈ {VerFirmware, VerFirmware1, VerProtocol, VerGithash, VerTpVersion, VerTpCheckSum}
VideoConfig   { int fps; int maxDuration }
FileCount     { int fileCount; int pictureCount; int videoCount; int audioCount }
TakePhoto     { PhotoMode mode; bytes param }                    // mode ∈ {ModeNormal, ModeAIRecognition, ModeContinuous}
FlowStatus    { FlowStatusType type }                           // ∈ {FlowStatusStart, FlowStatusComplete, FlowStatusInterrupt}
RunningStatus { bool takePicture, aiVisual, audioRecording, videoRecording, fileSync,
                livingMode, slaveActive, simuInterpretation, aiDialogue, slaveOta, jieliOta }
```

For the raw ATT-level protocol (handles, opcodes, notification framing), capture a live session —
the SDK API above tells you *what* each command is; a sniff tells you the *bytes*.

---

## Device-side (Core firmware) confirmation — from `app.bin` ([15](15_Core_App_Analysis.md))

The decrypted JieLi Core firmware was disassembled (pi32v2, Ghidra) and corroborates / extends this map:
- **Transport = JieLi RCSP** over BLE **and** SPP (`rcsp`, `jl_rcsp_ble_test`, profiles
  `JL_A2DP/HFP/HID/SPP`). The "large-data" protobuf payloads (`conn.protos.*`) are **nanopb** on the device
  (its `varint/bytes/string overflow`, `wrong wire type` error strings are present). **CONFIRMED.**
- **`queryDeviceVersion` handler** exists device-side (`VersionInfo: Invalid type` rejects unknown
  `VersionType`). Note the **version-relabel correction**: the Vision version the Core relays as
  `VerFirmware1` is **not** stored/synthesised in the Core (see [10 §3](10_Cloud_and_Device_Auth.md) /
  [15 §7](15_Core_App_Analysis.md)).
- **Beyond the glasses API — a local voice-command grammar** runs on the Core (`jl_kws` + `batasr`): wake
  phrases `hello_echo`/`Nihao_Xiaoke` plus `Take_a_photo`/`Take_a_video`/`Start_recording`/
  `Photo_Recognition`/`Answer_call`/`Play_music`/… — i.e. the device acts on spoken commands independently
  of BLE. This is a larger command surface than `sendVoiceWakeUpState` (on/off) exposes.
- **Not yet recovered:** the numeric **RCSP opcode↔handler** table (stripped build + base-relative
  addressing) — still the job of a **live BLE sniff** (or a focused decompile of the dispatch).
