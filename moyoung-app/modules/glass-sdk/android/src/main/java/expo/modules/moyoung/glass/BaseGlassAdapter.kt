package expo.modules.moyoung.glass

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.greenrobot.eventbus.EventBus

/**
 * Shared implementation for every vendor adapter.
 *
 * Provides:
 *  - an IO [scope] so all device I/O stays off the main thread,
 *  - the connection state machine + [updateState],
 *  - protected `on*` hooks that are the ONLY sanctioned way to emit events upward,
 *  - a [trace] channel for on-device diagnostics that surface in the app's Debug tab,
 *  - safe no-op defaults for every [GlassAdapter] method, so a partially-wired adapter
 *    still compiles and runs (it just reports "not wired").
 */
abstract class BaseGlassAdapter(protected val context: Context) : GlassAdapter {

  protected val tag: String = this::class.java.simpleName
  protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  protected var currentDeviceId: String? = null
  protected var currentDeviceName: String? = null
  protected var state: GlassConnectionState = GlassConnectionState.IDLE
    private set

  override val capabilities: Map<String, Boolean> = emptyMap()

  // ── event emission hooks ──────────────────────────────────────────────────

  protected fun post(msg: GlassEventMsg) = EventBus.getDefault().post(msg)

  /**
   * Generic diagnostic trace. Rides the GLASS_ERROR event so that every native log line shows
   * up live in the app's Debug tab — the single most useful thing when poking at a device.
   */
  protected fun trace(message: String) {
    Log.d(tag, message)
    post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, n1 = 0, s1 = message))
  }

  protected fun onError(message: String, fatal: Boolean = false) {
    Log.e(tag, message)
    post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, n1 = if (fatal) 1 else 0, s1 = message))
  }

  protected fun updateState(newState: GlassConnectionState, reason: String? = null) {
    state = newState
    post(
      GlassEventMsg(
        GlassEventConstants.MSG_GLASS_STATE_CHANGED,
        n1 = newState.ordinal,
        s1 = newState.name,
        s2 = reason
      )
    )
  }

  fun currentState(): GlassConnectionState = state

  protected fun onDeviceFound(deviceId: String, name: String, rssi: Int, advJson: String? = null) {
    post(
      GlassEventMsg(
        GlassEventConstants.MSG_GLASS_DEVICE_FOUND,
        n1 = rssi, s1 = deviceId, s2 = advJson ?: """{"name":"$name"}"""
      )
    )
  }

  protected fun onConnected() {
    updateState(GlassConnectionState.CONNECTED, "connected")
    post(
      GlassEventMsg(
        GlassEventConstants.MSG_GLASS_CONNECTED,
        s1 = currentDeviceId, s2 = currentDeviceName
      )
    )
  }

  protected fun onDisconnected(reason: String, wasExpected: Boolean) {
    updateState(GlassConnectionState.IDLE, reason)
    post(
      GlassEventMsg(
        GlassEventConstants.MSG_GLASS_DISCONNECTED,
        n1 = if (wasExpected) 1 else 0, s1 = reason
      )
    )
  }

  protected fun onReady() = post(GlassEventMsg(GlassEventConstants.MSG_GLASS_READY, s1 = currentDeviceId))

  protected fun onBattery(level: Int, charging: Boolean, voltage: Int = 0) =
    post(
      GlassEventMsg(
        GlassEventConstants.MSG_GLASS_BATTERY,
        n1 = level, n2 = if (charging) 1 else 0, s1 = voltage.toString()
      )
    )

  // ── safe defaults ─────────────────────────────────────────────────────────
  // Every method below reports "not wired" instead of throwing, so the app stays usable
  // while you wire the vendor SDK incrementally. See moyoung-app/README.md.

  private fun notWired(name: String): CommandResult {
    trace("[not wired] $name — this adapter method has no override (MoyoungAdapter implements all)")
    return CommandResult(false, -1, "$name is not wired yet")
  }

  override suspend fun init(context: Context) { trace("init (base no-op)") }
  override fun destroy() {}

  override suspend fun startScan() { notWired("startScan") }
  override suspend fun stopScan() { notWired("stopScan") }
  override suspend fun connect(deviceId: String) { notWired("connect") }
  override suspend fun disconnect() { notWired("disconnect") }
  override fun isConnected(): Boolean = state == GlassConnectionState.CONNECTED

  override suspend fun syncTime() { notWired("syncTime") }
  override suspend fun queryBattery() { notWired("queryBattery") }
  override suspend fun queryDeviceVersion(type: String) { notWired("queryDeviceVersion") }
  override suspend fun queryDeviceId() { notWired("queryDeviceId") }
  override suspend fun queryFeatureState() { notWired("queryFeatureState") }

  override suspend fun setWakeWord(enabled: Boolean) { notWired("setWakeWord") }
  override suspend fun queryWakeWord() { notWired("queryWakeWord") }
  override suspend fun setWearCheck(enabled: Boolean) { notWired("setWearCheck") }
  override suspend fun queryWearCheck() { notWired("queryWearCheck") }
  override suspend fun sendLanguage(language: Int) { notWired("sendLanguage") }

  override suspend fun takePhoto(mode: String): CommandResult = notWired("takePhoto")
  override suspend fun startAudio(seconds: Int): CommandResult = notWired("startAudio")
  override suspend fun stopAudio() { notWired("stopAudio") }
  override suspend fun queryAudioState() { notWired("queryAudioState") }
  override suspend fun queryVideoConfig() { notWired("queryVideoConfig") }
  override suspend fun sendVideoConfig(fps: Int, maxDuration: Int): CommandResult =
    notWired("sendVideoConfig")

  override suspend fun enableWifi(type: String) { notWired("enableWifi") }
  override suspend fun disableWifi() { notWired("disableWifi") }
  override suspend fun connectWifi() { notWired("connectWifi") }
  override suspend fun queryNewMediaFile() { notWired("queryNewMediaFile") }
  override suspend fun downloadMediaFile() { notWired("downloadMediaFile") }
  override suspend fun downloadLogFile() { notWired("downloadLogFile") }

  override suspend fun checkFirmware(mac: String, fw1Version: String, fw2Version: String) {
    notWired("checkFirmware")
  }
  override suspend fun startJieliOta(filePath: String?) { notWired("startJieliOta") }
  override suspend fun abortJieliOta() { notWired("abortJieliOta") }
  override suspend fun startAllwinnerOta(filePath: String) { notWired("startAllwinnerOta") }
  override suspend fun resumeAllwinnerOta() { notWired("resumeAllwinnerOta") }
  override suspend fun abortAllwinnerOta() { notWired("abortAllwinnerOta") }

  override suspend fun restart() { notWired("restart") }
  override suspend fun factoryReset() { notWired("factoryReset") }
  override suspend fun shutdown() { notWired("shutdown") }
  override suspend fun removeBond() { notWired("removeBond") }

  override suspend fun startTranslation() { notWired("startTranslation") }
  override suspend fun pauseTranslation() { notWired("pauseTranslation") }
  override suspend fun stopTranslation() { notWired("stopTranslation") }
  override suspend fun sendAiDialogueState(type: String) { notWired("sendAiDialogueState") }
  override suspend fun exitAiDialogue() { notWired("exitAiDialogue") }

  override suspend fun sendCommand(command: String, params: Map<String, Any?>): CommandResult =
    notWired("sendCommand($command)")
}
