package expo.modules.moyoung.glass

import android.bluetooth.BluetoothAdapter
import android.os.Bundle
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject

/**
 * The JS↔native boundary.
 *
 * Deliberately thin: every AsyncFunction just launches a coroutine, delegates to [GlassManager],
 * and resolves/rejects a Promise. It never touches the vendor SDK directly. Events arrive from
 * the adapter layer over EventBus as a flat [GlassEventMsg] and are demuxed here into typed
 * Bundles for JS.
 */
class GlassModule : Module() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var manager: GlassManager? = null

  private fun mgr(): GlassManager? = manager

  override fun definition() = ModuleDefinition {
    Name("GlassModule")

    Events(
      "GLASS_DEVICE_FOUND", "GLASS_STATE_CHANGED", "GLASS_CONNECTED", "GLASS_DISCONNECTED",
      "GLASS_READY", "GLASS_ERROR",
      "GLASS_BATTERY", "GLASS_WEAR_STATUS", "GLASS_WAKEWORD_STATUS",
      "GLASS_DEVICE_VERSION", "GLASS_DEVICE_ID", "GLASS_FEATURE_STATE",
      "GLASS_WIFI_STATE", "GLASS_MEDIA_COUNT", "GLASS_DOWNLOAD_PROGRESS", "GLASS_DOWNLOAD_COMPLETE",
      "GLASS_OTA_PROGRESS", "GLASS_OTA_STATE",
      "GLASS_AI_STATE", "GLASS_AI_AUDIO", "GLASS_AI_IMAGE", "GLASS_TRANSLATION_AUDIO",
      "GLASS_AUDIO_STATE", "GLASS_VIDEO_CONFIG", "GLASS_FIRMWARE_INFO"
    )

    OnCreate {
      // Vendor callbacks run on binder/dispatcher threads we can't wrap; this keeps a missing
      // vendor component (Jieli Opus, mltcode PayCertification) from killing the process.
      VendorCrashGuard.install()
      EventBus.getDefault().register(this@GlassModule)
      scope.launch {
        val ctx = appContext.reactContext?.applicationContext ?: return@launch
        manager = GlassManager.getInstance(ctx).init()
      }
    }

    OnDestroy {
      EventBus.getDefault().unregister(this@GlassModule)
      manager?.destroy()
    }

    // ── environment ─────────────────────────────────────────────────────────
    AsyncFunction("isBluetoothEnabled") {
      BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
    }
    // Pops the system "allow this app to turn on Bluetooth?" dialog. Returns true if BT is
    // already on (or the dialog was shown), false if there's no adapter/activity or the launch
    // was refused. On Android 12+ this needs BLUETOOTH_CONNECT — request permissions in JS first.
    AsyncFunction("requestEnableBluetooth") {
      val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@AsyncFunction false
      if (adapter.isEnabled) return@AsyncFunction true
      val activity = appContext.currentActivity ?: return@AsyncFunction false
      try {
        activity.startActivity(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        true
      } catch (t: Throwable) {
        false
      }
    }
    // The Vision OTA joins the glasses' Wi-Fi AP, which needs the PHONE's Wi-Fi radio ON. Expose a
    // check + a way to surface the system Wi-Fi toggle (apps can't enable Wi-Fi programmatically since
    // Android 10). Without Wi-Fi on, the SDK's connectWifi() silently never connects and the OTA hangs.
    AsyncFunction("isWifiEnabled") {
      val wm = appContext.reactContext?.applicationContext
        ?.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
      wm?.isWifiEnabled ?: false
    }
    AsyncFunction("requestEnableWifi") {
      val activity = appContext.currentActivity
      val ctx = appContext.reactContext?.applicationContext
      try {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 29)
          android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
        else
          android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        when {
          activity != null -> activity.startActivity(intent)
          ctx != null -> {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
          }
          else -> return@AsyncFunction false
        }
        true
      } catch (t: Throwable) {
        false
      }
    }

    AsyncFunction("getCurrentState") { mgr()?.currentState() ?: GlassConnectionState.IDLE.name }
    AsyncFunction("isConnected") { mgr()?.isConnected() ?: false }
    AsyncFunction("getCapabilities") { mgr()?.capabilities() ?: emptyMap<String, Boolean>() }

    // ── discovery / connection ──────────────────────────────────────────────
    AsyncFunction("startScan") { dispatch { mgr()?.startScan() } }
    AsyncFunction("stopScan") { dispatch { mgr()?.stopScan() } }

    // Vendor-agnostic raw-BLE sweep: surfaces EVERY advertising device, unfiltered.
    // Use when the vendor scanner doesn't show a device you expect to be there.
    AsyncFunction("startGenericScan") { mgr()?.startGenericScan() ?: false }
    AsyncFunction("stopGenericScan") { mgr()?.stopGenericScan(); true }

    AsyncFunction("connect") { deviceId: String, vendor: String?, name: String? ->
      dispatch { mgr()?.connect(deviceId, vendor, name) }
    }
    AsyncFunction("disconnect") { dispatch { mgr()?.disconnect() } }

    // ── device info ─────────────────────────────────────────────────────────
    AsyncFunction("syncTime") { call { it.syncTime() } }
    AsyncFunction("queryBattery") { call { it.queryBattery() } }
    AsyncFunction("queryDeviceVersion") { type: String -> call { it.queryDeviceVersion(type) } }
    AsyncFunction("queryDeviceId") { call { it.queryDeviceId() } }
    AsyncFunction("queryFeatureState") { call { it.queryFeatureState() } }

    // ── settings ────────────────────────────────────────────────────────────
    AsyncFunction("setWakeWord") { enabled: Boolean -> call { it.setWakeWord(enabled) } }
    AsyncFunction("queryWakeWord") { call { it.queryWakeWord() } }
    AsyncFunction("setWearCheck") { enabled: Boolean -> call { it.setWearCheck(enabled) } }
    AsyncFunction("queryWearCheck") { call { it.queryWearCheck() } }
    AsyncFunction("sendLanguage") { language: Int -> call { it.sendLanguage(language) } }

    // ── capture ─────────────────────────────────────────────────────────────
    AsyncFunction("takePhoto") { mode: String -> callResult { it.takePhoto(mode) } }
    AsyncFunction("startAudio") { seconds: Int -> callResult { it.startAudio(seconds) } }
    AsyncFunction("stopAudio") { call { it.stopAudio() } }
    AsyncFunction("queryAudioState") { call { it.queryAudioState() } }
    AsyncFunction("queryVideoConfig") { call { it.queryVideoConfig() } }
    AsyncFunction("sendVideoConfig") { fps: Int, maxDuration: Int ->
      callResult { it.sendVideoConfig(fps, maxDuration) }
    }

    // ── Wi-Fi + media ───────────────────────────────────────────────────────
    AsyncFunction("enableWifi") { type: String -> call { it.enableWifi(type) } }
    AsyncFunction("disableWifi") { call { it.disableWifi() } }
    AsyncFunction("connectWifi") { call { it.connectWifi() } }
    AsyncFunction("queryNewMediaFile") { call { it.queryNewMediaFile() } }
    AsyncFunction("downloadMediaFile") { call { it.downloadMediaFile() } }
    AsyncFunction("downloadLogFile") { call { it.downloadLogFile() } }

    // ── OTA ─────────────────────────────────────────────────────────────────
    AsyncFunction("checkFirmware") { mac: String, fw1: String, fw2: String ->
      call { it.checkFirmware(mac, fw1, fw2) }
    }
    AsyncFunction("startJieliOta") { filePath: String? -> call { it.startJieliOta(filePath) } }
    AsyncFunction("abortJieliOta") { call { it.abortJieliOta() } }
    AsyncFunction("startAllwinnerOta") { filePath: String -> call { it.startAllwinnerOta(filePath) } }
    AsyncFunction("resumeAllwinnerOta") { call { it.resumeAllwinnerOta() } }
    AsyncFunction("abortAllwinnerOta") { call { it.abortAllwinnerOta() } }

    // ── bundled firmware (tinker builds shipped inside the APK) ───────────────
    // The patched .swu / dry-run .ufw live in the module's android assets/firmware/.
    // OTA takes a real filesystem path, so we copy the chosen asset into filesDir and
    // hand back the absolute path. Copy is explicit (a flash tap), never on a hot path.
    AsyncFunction("listBundledFirmware") {
      appContext.reactContext?.applicationContext?.assets?.list("firmware")?.toList()
        ?: emptyList<String>()
    }
    AsyncFunction("resolveBundledFirmware") { name: String ->
      val ctx = appContext.reactContext?.applicationContext
        ?: throw IllegalStateException("resolveBundledFirmware: no application context")
      // never let a name escape the firmware dir
      val safe = name.substringAfterLast('/').substringAfterLast('\\')
      val outDir = java.io.File(ctx.filesDir, "firmware").apply { mkdirs() }
      val out = java.io.File(outDir, safe)
      ctx.assets.open("firmware/$safe").use { input ->
        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
      }
      out.absolutePath
    }

    // ── device management (destructive) ─────────────────────────────────────
    AsyncFunction("restart") { call { it.restart() } }
    AsyncFunction("factoryReset") { call { it.factoryReset() } }
    AsyncFunction("shutdown") { call { it.shutdown() } }
    AsyncFunction("removeBond") { call { it.removeBond() } }

    // ── AI / translation ────────────────────────────────────────────────────
    AsyncFunction("startTranslation") { call { it.startTranslation() } }
    AsyncFunction("pauseTranslation") { call { it.pauseTranslation() } }
    AsyncFunction("stopTranslation") { call { it.stopTranslation() } }
    AsyncFunction("sendAiDialogueState") { type: String -> call { it.sendAiDialogueState(type) } }
    AsyncFunction("exitAiDialogue") { call { it.exitAiDialogue() } }

    // ── extension seam ──────────────────────────────────────────────────────
    AsyncFunction("sendCommand") { command: String, params: Map<String, Any?> ->
      callResult { it.sendCommand(command, params) }
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Fire-and-forget delegate to the active adapter. */
  private fun call(block: suspend (GlassAdapter) -> Unit): Boolean {
    val adapter = mgr()?.active() ?: return false
    scope.launch { runCatching { block(adapter) } }
    return true
  }

  /** Delegate that returns a CommandResult as a Bundle. */
  private fun callResult(block: suspend (GlassAdapter) -> CommandResult): Boolean {
    val adapter = mgr()?.active() ?: return false
    scope.launch { runCatching { block(adapter) } }
    return true
  }

  /** Fire-and-forget on the module scope (used for manager-level calls). */
  private fun dispatch(block: suspend () -> Unit): Boolean {
    scope.launch { runCatching { block() } }
    return true
  }

  // ── event fan-out: EventBus (native) → sendEvent (JS) ─────────────────────

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onGlassEvent(event: GlassEventMsg) = emitEvent(event)

  private fun emitEvent(event: GlassEventMsg) {
    val data = Bundle()
    when (event.cmd) {
      GlassEventConstants.MSG_GLASS_DEVICE_FOUND -> {
        val advJson = event.s2 ?: "{}"
        data.putString("deviceId", event.s1)
        data.putString("deviceName", runCatching { JSONObject(advJson).optString("name", "") }.getOrDefault(""))
        data.putString("advertisement", advJson) // JSON string; parsed in JS
        data.putInt("rssi", event.n1)
        sendEvent("GLASS_DEVICE_FOUND", data)
      }

      GlassEventConstants.MSG_GLASS_STATE_CHANGED -> {
        data.putString("newState", event.s1)
        data.putString("reason", event.s2)
        sendEvent("GLASS_STATE_CHANGED", data)
      }

      GlassEventConstants.MSG_GLASS_CONNECTED -> {
        data.putString("deviceId", event.s1)
        data.putString("deviceName", event.s2)
        sendEvent("GLASS_CONNECTED", data)
      }

      GlassEventConstants.MSG_GLASS_DISCONNECTED -> {
        data.putString("reason", event.s1)
        data.putBoolean("wasExpected", event.n1 == 1)
        sendEvent("GLASS_DISCONNECTED", data)
      }

      GlassEventConstants.MSG_GLASS_READY -> {
        data.putString("deviceId", event.s1)
        sendEvent("GLASS_READY", data)
      }

      GlassEventConstants.MSG_GLASS_ERROR -> {
        data.putString("message", event.s1)
        data.putBoolean("isFatal", event.n1 == 1)
        sendEvent("GLASS_ERROR", data)
      }

      GlassEventConstants.MSG_GLASS_BATTERY -> {
        data.putInt("level", event.n1)
        data.putBoolean("isCharging", event.n2 == 1)
        data.putString("voltage", event.s1)
        sendEvent("GLASS_BATTERY", data)
      }

      GlassEventConstants.MSG_GLASS_WEAR_STATUS -> {
        data.putBoolean("enabled", event.n1 == 1)
        sendEvent("GLASS_WEAR_STATUS", data)
      }

      GlassEventConstants.MSG_GLASS_WAKEWORD_STATUS -> {
        data.putBoolean("enabled", event.n1 == 1)
        sendEvent("GLASS_WAKEWORD_STATUS", data)
      }

      GlassEventConstants.MSG_GLASS_DEVICE_VERSION -> {
        data.putString("type", event.s2)
        data.putString("version", event.s1)
        data.putInt("tpVersion", event.n1)
        sendEvent("GLASS_DEVICE_VERSION", data)
      }

      GlassEventConstants.MSG_GLASS_DEVICE_ID -> {
        data.putString("deviceId", event.s1)
        sendEvent("GLASS_DEVICE_ID", data)
      }

      GlassEventConstants.MSG_GLASS_FEATURE_STATE -> {
        data.putString("state", event.s1) // JSON of RunningStatus
        sendEvent("GLASS_FEATURE_STATE", data)
      }

      GlassEventConstants.MSG_GLASS_WIFI_STATE -> {
        data.putString("wifiType", event.s2)
        data.putInt("state", event.n1)
        data.putBoolean("connected", event.n2 == 1)
        data.putString("message", event.s1)
        sendEvent("GLASS_WIFI_STATE", data)
      }

      GlassEventConstants.MSG_GLASS_MEDIA_COUNT -> {
        data.putInt("photoCount", event.n1)
        data.putInt("videoCount", event.n2)
        data.putString("detail", event.s1)
        sendEvent("GLASS_MEDIA_COUNT", data)
      }

      GlassEventConstants.MSG_GLASS_DOWNLOAD_PROGRESS -> {
        data.putInt("progress", event.n1)
        data.putInt("total", event.n2)
        sendEvent("GLASS_DOWNLOAD_PROGRESS", data)
      }

      GlassEventConstants.MSG_GLASS_DOWNLOAD_COMPLETE -> {
        data.putString("dirPath", event.s1)
        data.putString("files", event.s2) // JSON array
        sendEvent("GLASS_DOWNLOAD_COMPLETE", data)
      }

      GlassEventConstants.MSG_GLASS_OTA_PROGRESS -> {
        data.putInt("progress", event.n1)
        data.putString("phase", event.s1)
        sendEvent("GLASS_OTA_PROGRESS", data)
      }

      GlassEventConstants.MSG_GLASS_OTA_STATE -> {
        data.putString("state", event.s1)
        data.putInt("errorCode", event.n1)
        data.putString("message", event.s2)
        sendEvent("GLASS_OTA_STATE", data)
      }

      GlassEventConstants.MSG_GLASS_AI_STATE -> {
        data.putString("state", event.s1)
        data.putBoolean("isTimeout", event.n1 == 1)
        sendEvent("GLASS_AI_STATE", data)
      }

      GlassEventConstants.MSG_GLASS_AI_AUDIO -> {
        data.putInt("bytes", event.n1)
        data.putString("path", event.s1)
        sendEvent("GLASS_AI_AUDIO", data)
      }

      GlassEventConstants.MSG_GLASS_AI_IMAGE -> {
        data.putString("path", event.s1)
        sendEvent("GLASS_AI_IMAGE", data)
      }

      GlassEventConstants.MSG_GLASS_TRANSLATION_AUDIO -> {
        data.putInt("bytes", event.n1)
        sendEvent("GLASS_TRANSLATION_AUDIO", data)
      }

      GlassEventConstants.MSG_GLASS_AUDIO_STATE -> {
        data.putBoolean("recording", event.n1 == 1)
        data.putInt("duration", event.n2)
        sendEvent("GLASS_AUDIO_STATE", data)
      }

      GlassEventConstants.MSG_GLASS_VIDEO_CONFIG -> {
        data.putInt("fps", event.n1)
        data.putInt("maxDuration", event.n2)
        sendEvent("GLASS_VIDEO_CONFIG", data)
      }

      GlassEventConstants.MSG_GLASS_FIRMWARE_INFO -> {
        data.putString("info", event.s1) // JSON of CRPNewFirmwareVersionInfo
        sendEvent("GLASS_FIRMWARE_INFO", data)
      }
    }
  }
}
