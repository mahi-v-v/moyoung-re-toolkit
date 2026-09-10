package expo.modules.moyoung.glass

import android.content.Context

/** Generic result for a request/response command. */
data class CommandResult(
  val success: Boolean,
  val errorCode: Int = 0,
  val message: String? = null
)

/**
 * The vendor-agnostic contract. Everything above this line (GlassModule, GlassManager,
 * BaseGlassAdapter, GlassScanner) is reusable framework; everything below it is vendor-specific.
 *
 * For MoYoung the implementation is [expo.modules.moyoung.glass.vendors.moyoung.MoyoungAdapter],
 * which maps these calls onto the CRP SDK (`com.moyoung.glasses.*`).
 *
 * Note the long tail of device pokes is intentionally NOT modelled here — it goes through
 * [sendCommand], the extension seam, so new experiments don't require a native rebuild.
 */
interface GlassAdapter {
  /** Static feature map surfaced to JS, e.g. {"camera": true, "wakeword": true}. */
  val capabilities: Map<String, Boolean>

  suspend fun init(context: Context)
  fun destroy()

  // ── discovery / connection ────────────────────────────────────────────────
  suspend fun startScan()
  suspend fun stopScan()
  suspend fun connect(deviceId: String)
  suspend fun disconnect()
  fun isConnected(): Boolean

  // ── device info ───────────────────────────────────────────────────────────
  suspend fun syncTime()
  suspend fun queryBattery()
  suspend fun queryDeviceVersion(type: String)
  suspend fun queryDeviceId()
  suspend fun queryFeatureState()

  // ── settings / toggles ────────────────────────────────────────────────────
  suspend fun setWakeWord(enabled: Boolean)
  suspend fun queryWakeWord()
  suspend fun setWearCheck(enabled: Boolean)
  suspend fun queryWearCheck()
  suspend fun sendLanguage(language: Int)

  // ── capture ───────────────────────────────────────────────────────────────
  suspend fun takePhoto(mode: String): CommandResult
  suspend fun startAudio(seconds: Int): CommandResult
  suspend fun stopAudio()
  suspend fun queryAudioState()
  suspend fun queryVideoConfig()
  suspend fun sendVideoConfig(fps: Int, maxDuration: Int): CommandResult

  // ── Wi-Fi bulk transport + media ──────────────────────────────────────────
  suspend fun enableWifi(type: String)
  suspend fun disableWifi()
  suspend fun connectWifi()
  suspend fun queryNewMediaFile()
  suspend fun downloadMediaFile()
  suspend fun downloadLogFile()

  // ── OTA (two independent paths — see docs/02_Firmware_and_OTA.md) ─────────
  suspend fun checkFirmware(mac: String, fw1Version: String, fw2Version: String)
  /** Jieli / BLE DFU. [filePath] null = let the SDK download from the server. */
  suspend fun startJieliOta(filePath: String?)
  suspend fun abortJieliOta()
  /** Allwinner / Wi-Fi. Requires enableWifi(OTA) + connectWifi() first. */
  suspend fun startAllwinnerOta(filePath: String)
  suspend fun resumeAllwinnerOta()
  suspend fun abortAllwinnerOta()

  // ── device management (destructive) ───────────────────────────────────────
  suspend fun restart()
  suspend fun factoryReset()
  suspend fun shutdown()
  suspend fun removeBond()

  // ── AI dialogue / translation ─────────────────────────────────────────────
  suspend fun startTranslation()
  suspend fun pauseTranslation()
  suspend fun stopTranslation()
  suspend fun sendAiDialogueState(type: String)
  suspend fun exitAiDialogue()

  /**
   * Extension seam for anything not modelled above — arbitrary opcode probing, undocumented
   * commands, one-off experiments. This is where tinkering lives.
   */
  suspend fun sendCommand(command: String, params: Map<String, Any?> = emptyMap()): CommandResult
}
