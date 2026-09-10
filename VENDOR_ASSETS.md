# Vendor assets (not included in this repository)

This is a reverse-engineering and portfolio repository. It contains **only the author's own
work** — analysis, documentation, tooling scripts, and companion-app source. It intentionally
**does not redistribute** any third-party proprietary material, namely:

| Asset | What it is | Where the code/docs reference it |
|-------|------------|----------------------------------|
| MoYoung BLE SDK (`my_glasses_sdk.aar`, `jl_bt_ota_*.aar`) | Vendor Android SDK | `moyoung-app/modules/glass-sdk/android/libs/` |
| Vendor iOS SDK (`CRPSmartGlasses.xcframework`, JieLi `JL_*` frameworks) + dev-doc PDFs | Vendor iOS SDK | referenced in `docs/` |
| Root SDK archives (`IOS-SDK-Glasses-1.2.0.zip`, `MoYoung Glasses BLE SDK-*.zip`) | The archives that seeded the project | referenced in `docs/` |
| Vendor source packages (`com.jieli.jl_audio_decode` Opus decoder, `com.android.mltcode` pay-certification) | Vendor-supplied Java sources the native module compiles against | `moyoung-app/modules/glass-sdk/android/src/main/java/` (referenced by `VendorCrashGuard.kt`, `…/vendors/moyoung/MoyoungAdapter.kt`) |
| Firmware images (`.swu`, `.ufw`, MOY-TTT3 `.bin`) and decrypted payloads | Device firmware | `firmware/`, `tools/`, `docs/` |

## Why they are excluded

These are copyrighted works owned by MoYoung and its chip suppliers (Jieli, Allwinner), and their
SDK licenses generally prohibit redistribution. Publishing verbatim copies would be a
redistribution / licensing issue independent of this project. The **analysis** of how they work is
original work and is included; the **binaries themselves** are not.

## How to obtain them

- **Vendor SDKs** ship to registered developers from MoYoung; request them through the vendor's
  developer channel.
- **Firmware images** can be captured from the device's own OTA flow (documented in
  [`docs/02_Firmware_and_OTA.md`](docs/02_Firmware_and_OTA.md)) or the firmware server described in
  [`firmware/README.md`](firmware/README.md).
- To **build the companion app**, place `my_glasses_sdk.aar` (and `jl_bt_ota_*.aar`) in
  `moyoung-app/modules/glass-sdk/android/libs/`, restore the vendor source packages referenced
  above, then run the build steps in the root README.

Nothing here needs those binaries in order to *read* the investigation — only to compile the app
or re-run the firmware tooling against real inputs.
