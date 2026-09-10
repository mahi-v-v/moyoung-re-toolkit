package expo.modules.moyoung.glass.vendors.moyoung

import android.content.Context
import android.util.Base64
import com.moyoung.glasses.conn.CRPBleConnection
import com.moyoung.glasses.conn.bean.CRPAudioStateInfo
import com.moyoung.glasses.conn.bean.CRPFirmwareRequestInfo
import com.moyoung.glasses.conn.bean.CRPMediaFileInfo
import com.moyoung.glasses.conn.bean.CRPNewFirmwareVersionInfo
import com.moyoung.glasses.conn.callback.CRPAudioStateCallback
import com.moyoung.glasses.conn.callback.CRPCommandCallback
import com.moyoung.glasses.conn.callback.CRPDeviceIdCallback
import com.moyoung.glasses.conn.callback.CRPDeviceVersionCallback
import com.moyoung.glasses.conn.callback.CRPFileDownloadCallback
import com.moyoung.glasses.conn.callback.CRPNewFirmwareVersionCallback
import com.moyoung.glasses.conn.callback.CRPVideoConfigCallback
import com.moyoung.glasses.conn.callback.CRPVoiceWakeUpCallback
import com.moyoung.glasses.conn.callback.CRPWearCheckCallback
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener
import com.moyoung.glasses.conn.listener.CRPFeatureStateListener
import com.moyoung.glasses.conn.listener.CRPMediaFileChangeListener
import com.moyoung.glasses.conn.listener.CRPOtaListener
import com.moyoung.glasses.conn.listener.CRPTranslationListener
import com.moyoung.glasses.conn.listener.CRPWifiChangeListener
import com.moyoung.glasses.conn.protos.FlowStatus
import com.moyoung.glasses.conn.protos.RunningStatus
import com.moyoung.glasses.conn.protos.TakePhoto
import com.moyoung.glasses.conn.protos.VersionInfo
import com.moyoung.glasses.conn.protos.VideoConfig
import com.moyoung.glasses.conn.type.CRPWifiType
import expo.modules.moyoung.glass.BaseGlassAdapter
import expo.modules.moyoung.glass.CommandResult
import expo.modules.moyoung.glass.GlassConnectionState
import expo.modules.moyoung.glass.GlassEventConstants
import expo.modules.moyoung.glass.GlassEventMsg
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * MoYoung (CRP SDK) adapter — the vendor-specific half of the module.
 *
 * Translates the framework's vendor-neutral calls onto the CRP SDK, and vendor callbacks back into
 * framework events. Connection lifecycle lives in [MoyoungBleConnection]; this class owns the
 * command/response and push-listener mapping.
 *
 * All device I/O stays on [scope] (Dispatchers.IO, from BaseGlassAdapter). Results are emitted with
 * the `on*` hooks or [post] — never sendEvent directly.
 *
 * Reference: docs/04_BLE_Protocol_Reference.md
 */
class MoyoungAdapter(context: Context) : BaseGlassAdapter(context) {

  private val ble = MoyoungBleConnection(context)

  /** App context, for ConnectivityManager (network binding during OTA). */
  private val appCtx: Context = context.applicationContext

  /** Observational Wi-Fi lifecycle watch during OTA — logs when the glasses' Wi-Fi drops so we can
   *  see whether the ~37% truncation coincides with the OS tearing down the (no-internet) AP. */
  private var otaNetCallback: android.net.ConnectivityManager.NetworkCallback? = null

  /**
   * When a Vision (Allwinner) OTA is armed, the Wi-Fi callbacks auto-chain the vendor-documented
   * sequence enable→connect→flash (dev-docs/guides/ota.md): enableWifi(OTA) → onWifiStateChange
   * STATE_SUCCESS → connectWifi() → onWifiConnectionStateChanged(true) → startAllWinnerOta(file).
   */
  private var pendingVisionOta: File? = null

  /**
   * EXTERNAL-SERVER (laptop harness) mode. When armed via `startVisionOtaExternal`, the phone brings
   * up the glasses AP over BLE but does **not** join it (no `connectWifi()`), so a LAPTOP can take the
   * 192.168.31.2 station/server slot instead. This takes Android out of the data path and lets us
   * capture who truncates the transfer (Wireshark on the laptop). The chain pauses at
   * "awaitingExternalServer" until `continueVisionOtaExternal` fires `startAllWinnerOta` directly.
   * See tools/vision-ota-harness/. Reset in [finishOta].
   */
  private var visionExternalMode = false

  /**
   * When armed, the Wi-Fi callbacks auto-chain a device **log** pull over the FILE transport
   * (enableWifi(FILE) → connectWifi() → downloadLogFile()). This is the device→phone direction
   * (the device is the HTTP server), so it's the reliable path — unlike the phone→device OTA push.
   * We use it to read any swupdate/OTA abort reason the Core relays from the Vision. See
   * docs/09_Vision_Firmware_V821.md / docs/14_InApp_OTA_and_the_JieLi_lib_gap.md.
   */
  private var pendingLogPull = false

  override val capabilities: Map<String, Boolean> = mapOf(
    "camera" to true,
    "audio" to true,
    "battery" to true,
    "wakeword" to true,
    "wearCheck" to true,
    "wifi" to true,
    "mediaDownload" to true,
    "translation" to true,
    "aiDialogue" to true,
    "otaJieli" to true,
    "otaAllwinner" to true,
    // Not present in Android SDK 0.0.7 — see docs/06_Live_Video_Streaming.md
    "staLiveStreaming" to false
  )

  /** Convenience accessor; traces instead of throwing when there's no link. */
  private fun conn(): CRPBleConnection? {
    val c = ble.connection
    if (c == null) trace("[CRP] no active connection — command ignored")
    return c
  }

  /**
   * Wraps a vendor call so a vendor-side failure can never take down the module.
   *
   * Catches [Throwable], not just [Exception]: the CRP SDK lazily loads optional Jieli
   * components (e.g. the `jl_audio_decode` Opus decoder used by AI dialogue) that aren't bundled
   * in this SDK drop, and a missing class surfaces as [NoClassDefFoundError] — an `Error`, which a
   * `catch (Exception)` would let through and crash the process. Coroutine cancellation is
   * re-thrown so structured concurrency still works.
   */
  private inline fun safe(name: String, block: () -> Unit): CommandResult = try {
    block()
    CommandResult(true)
  } catch (c: kotlinx.coroutines.CancellationException) {
    throw c
  } catch (t: Throwable) {
    val msg = t.message ?: t.javaClass.simpleName
    onError("[CRP][ERR] $name: $msg")
    CommandResult(false, -1, msg)
  }

  /** Like [safe] but for the connect-time setup steps: isolates each step so one failing (e.g. a
   *  missing optional Jieli lib) can't abort the rest of the connect sequence. */
  private inline fun guard(name: String, block: () -> Unit) {
    try {
      block()
    } catch (c: kotlinx.coroutines.CancellationException) {
      throw c
    } catch (t: Throwable) {
      onError("[CRP][ERR] $name: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  private fun commandCallback(name: String) = object : CRPCommandCallback {
    override fun onSuccess() {
      trace("[CRP] $name -> ok")
    }

    override fun onFailure(code: Int) {
      onError("[CRP] $name -> failed ($code)")
    }
  }

  // ── lifecycle ─────────────────────────────────────────────────────────────

  override suspend fun init(context: Context) {
    // Route our vendored NanoHTTPD's OTA file-serving diagnostics (bytes sent / declared length /
    // close reason) to the device log + Metro. The SDK's startAllWinnerOta serves the .swu over
    // this server; the Vision truncates at ~25% and the firmware reports success anyway, so seeing
    // exactly how many bytes we sent + why the stream ended is the whole ballgame (docs log §22).
    fi.iki.elonen.NanoHTTPD.WIRE_LOG = fi.iki.elonen.NanoHTTPD.WireLog { line -> trace("[HTTP] $line") }
    ble.init(object : MoyoungBleConnection.ConnectionCallback {
      override fun onConnectionStateChanged(state: Int) {
        when (state) {
          MoyoungBleConnection.STATE_CONNECTING ->
            updateState(GlassConnectionState.CONNECTING, "CRP connecting")

          MoyoungBleConnection.STATE_CONNECTED -> scope.launch {
            onConnected()
            onDeviceConnected()
          }

          MoyoungBleConnection.STATE_DISCONNECTING ->
            updateState(GlassConnectionState.DISCONNECTING, "CRP disconnecting")

          MoyoungBleConnection.STATE_DISCONNECTED ->
            onDisconnected("CRP disconnected", false)
        }
      }

      override fun onDeviceFound(deviceId: String, deviceName: String, rssi: Int, advJson: String) {
        this@MoyoungAdapter.onDeviceFound(deviceId, deviceName, rssi, advJson)
      }

      override fun onBattery(level: Int, charging: Boolean, voltage: Int) {
        this@MoyoungAdapter.onBattery(level, charging, voltage)
      }

      override fun onTrace(message: String) {
        trace(message)
      }
    })
  }

  /**
   * Runs once the link is up: register push listeners and pull initial state. Listeners hang off
   * CRPBleConnection, which only exists after connect().
   */
  private fun onDeviceConnected() {
    val c = conn() ?: return

    // Each step is isolated: the AI-dialogue listener pulls in the Jieli Opus decoder
    // (com.jieli.jl_audio_decode.opus.OpusManager), which is NOT bundled in this SDK drop and
    // throws NoClassDefFoundError. Guarding per-step means that failure is logged but battery /
    // deviceId / onReady still run, so first-contact and firmware reads work regardless.
    guard("setWifiListener") { c.setWifiListener(wifiListener) }
    guard("setMediaFileChangeListener") { c.setMediaFileChangeListener(mediaListener) }
    guard("setFeatureActiveStateListener") { c.setFeatureActiveStateListener(featureListener) }
    guard("setAiDialogueListener (optional; needs Jieli jl_audio_decode)") {
      c.setAiDialogueListener(aiListener)
    }
    guard("setTranslationListener") { c.setTranslationListener(translationListener) }

    guard("syncTime") { c.syncTime() }
    guard("queryBattery") { c.queryBattery() }
    guard("queryDeviceId") { c.queryDeviceId(deviceIdCallback) }
    guard("queryFeatureActiveState") { c.queryFeatureActiveState() }
    onReady()
  }

  override fun destroy() {
    ble.destroy()
  }

  override suspend fun connect(deviceId: String) {
    currentDeviceId = deviceId
    updateState(GlassConnectionState.CONNECTING, "connecting to $deviceId")
    // The JS layer may prefix ids; the SDK wants a bare MAC.
    val mac = deviceId.removePrefix("moyoung:")
    if (!ble.connect(mac)) {
      updateState(GlassConnectionState.ERROR, "connect failed")
    }
  }

  override suspend fun disconnect() {
    updateState(GlassConnectionState.DISCONNECTING, "disconnecting")
    ble.disconnect()
    onDisconnected("user requested", true)
  }

  override fun isConnected(): Boolean = ble.isConnected()

  override suspend fun startScan() {
    updateState(GlassConnectionState.SCANNING, "scanning")
    ble.startScan()
  }

  override suspend fun stopScan() {
    ble.cancelScan()
    if (currentState() == GlassConnectionState.SCANNING) {
      updateState(GlassConnectionState.IDLE, "scan stopped")
    }
  }

  // ── push listeners ────────────────────────────────────────────────────────

  private val wifiListener = object : CRPWifiChangeListener {
    override fun onWifiStateChange(type: CRPWifiType?, state: Int) {
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_WIFI_STATE,
          n1 = state, s1 = type?.name ?: "", s2 = "stateChange"
        )
      )
      trace("[CRP] onWifiStateChange type=${type?.name} state=$state" +
        if (pendingVisionOta != null) "  (OTA armed)" else "")
      // auto-chain step 1→2: Wi-Fi (OTA) enabled OK → connect the phone to it
      if (pendingVisionOta != null && type == CRPWifiType.OTA) {
        if (state == CRPWifiChangeListener.STATE_SUCCESS) {
          if (visionExternalMode) {
            // EXTERNAL (laptop) harness: do NOT let the phone join the AP — the laptop must be the
            // 192.168.31.2 station/server. Pause here; the user joins the laptop + starts its server,
            // then `continueVisionOtaExternal` fires startAllWinnerOta directly (BLE trigger only).
            otaState("allwinner", "awaitingExternalServer")
            trace("[OTA] ✔ step2 Wi-Fi(OTA) up — EXTERNAL mode: phone is NOT joining. On the LAPTOP now: " +
              "join the OPEN glasses AP, set static 192.168.31.2, start tools/vision-ota-harness/" +
              "vision_ota_server.py + Wireshark (tcp.port==8182), then tap 'Continue — laptop ready'.")
          } else {
            trace("[OTA] ✔ step2 Wi-Fi enabled → calling connectWifi() …")
            guard("connectWifi(ota-chain)") { conn()?.connectWifi() }
            trace("[OTA]   connectWifi() returned; now WAITING for onWifiConnectionStateChanged(true)")
          }
        } else {
          onError("[OTA] ✖ Wi-Fi enable failed: state=$state " +
            "(0=ok,1=lowBattery,2=timeout,3=busy) — aborting chain")
          pendingVisionOta = null
          visionExternalMode = false
        }
      }
      // auto-chain (log pull): Wi-Fi (FILE) enabled OK → connect the phone to it
      if (pendingLogPull && type == CRPWifiType.FILE) {
        if (state == CRPWifiChangeListener.STATE_SUCCESS) {
          trace("[LOG] ✔ Wi-Fi (FILE) enabled → calling connectWifi() …")
          guard("connectWifi(log-chain)") { conn()?.connectWifi() }
        } else {
          onError("[LOG] ✖ Wi-Fi enable failed: state=$state (0=ok,1=lowBattery,2=timeout,3=busy)")
          pendingLogPull = false
        }
      }
    }

    override fun onWifiConnectionStateChanged(connected: Boolean) {
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_WIFI_STATE,
          n1 = if (connected) 1 else 0, n2 = if (connected) 1 else 0, s2 = "connection"
        )
      )
      trace("[CRP] onWifiConnectionStateChanged connected=$connected" +
        if (pendingVisionOta != null) "  (OTA armed)" else "")
      // auto-chain step 2→3: connected → start the file transfer to the device
      val f = pendingVisionOta
      if (f != null) {
        if (connected) {
          // Force our HTTP traffic onto the glasses' (no-internet) Wi-Fi so Android can't route it
          // away to mobile/home-Wi-Fi mid-download — the transfer otherwise EOFs early (see below).
          bindToGlassesWifi()
          startNetworkWatch()
          trace("[OTA] ✔ step3 Wi-Fi connected → calling startAllWinnerOta(${f.name}) …")
          guard("startAllWinnerOta(ota-chain)") {
            conn()?.startAllWinnerOta(f, otaListener("allwinner"))
          }
          trace("[OTA]   startAllWinnerOta() returned; now WAITING for progress / onCompleted")
        } else {
          trace("[OTA]   …still waiting for Wi-Fi to connect (connected=false)")
        }
      }
      // auto-chain (log pull): connected → pull the device log over FILE transport
      if (pendingLogPull && f == null) {
        if (connected) {
          bindToGlassesWifi()
          startNetworkWatch()
          trace("[LOG] ✔ Wi-Fi connected → calling downloadLogFile() …")
          guard("downloadLogFile(log-chain)") { conn()?.downloadLogFile(downloadCallback("log")) }
        } else {
          trace("[LOG]   …still waiting for Wi-Fi to connect (connected=false)")
        }
      }
    }

    override fun onLiveUrlChanged(url: String?) {
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_WIFI_STATE, s1 = url, s2 = "liveUrl"))
      trace("[CRP] live url = $url")
    }
  }

  private val mediaListener = object : CRPMediaFileChangeListener {
    override fun onNewMediaFileChanged(info: CRPMediaFileInfo?) {
      if (info == null) return
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_MEDIA_COUNT,
          n1 = info.photoCount, n2 = info.videoCount, s1 = info.audioCount.toString()
        )
      )
      trace("[CRP] media photo=${info.photoCount} video=${info.videoCount} audio=${info.audioCount}")
    }
  }

  private val featureListener = object : CRPFeatureStateListener {
    override fun onFeatureStateChanged(s: RunningStatus?) {
      if (s == null) return
      val json = JSONObject()
        .put("takePicture", s.takePicture)
        .put("aiVisual", s.aiVisual)
        .put("audioRecording", s.audioRecording)
        .put("videoRecording", s.videoRecording)
        .put("fileSync", s.fileSync)
        .put("livingMode", s.livingMode)
        .put("slaveActive", s.slaveActive)
        .put("simuInterpretation", s.simuInterpretation)
        .put("aiDialogue", s.aiDialogue)
        .put("slaveOta", s.slaveOta)
        .put("jieliOta", s.jieliOta)
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_FEATURE_STATE, s2 = json.toString()))
    }
  }

  private val aiListener = object : CRPAiDialogueListener {
    override fun onDialogueStart() {
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_AI_STATE, n1 = 1, s1 = "start"))
    }

    override fun onDialogueAudioChange(audio: ByteArray?) {
      // PCM. Base64 so it survives the RN bridge; decode/play on the JS side if wanted.
      if (audio == null) return
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_AI_AUDIO,
          n1 = audio.size, s1 = Base64.encodeToString(audio, Base64.NO_WRAP)
        )
      )
    }

    override fun onDialogueImageChange(file: File?) {
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_AI_IMAGE, s1 = file?.absolutePath))
    }

    override fun onDialogueStop(isTimeout: Boolean) {
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_AI_STATE,
          n1 = 0, n2 = if (isTimeout) 1 else 0, s1 = "stop"
        )
      )
    }
  }

  private val translationListener = object : CRPTranslationListener {
    override fun onAudioChange(audio: ByteArray?) {
      if (audio == null) return
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_TRANSLATION_AUDIO,
          n1 = audio.size, s1 = Base64.encodeToString(audio, Base64.NO_WRAP)
        )
      )
    }
  }

  private val deviceIdCallback = object : CRPDeviceIdCallback {
    override fun onDeviceId(id: String?) {
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_DEVICE_ID, s1 = id))
      trace("[CRP] deviceId=$id")
    }
  }

  private fun versionCallback(fallback: String) = object : CRPDeviceVersionCallback {
    override fun onDeviceVersion(info: VersionInfo?) {
      if (info == null) return
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_DEVICE_VERSION,
          n1 = info.tpVersion, s1 = info.ver, s2 = info.type?.name ?: fallback
        )
      )
      trace("[CRP] version ${info.type?.name}=${info.ver}")
    }
  }

  // ── device info ───────────────────────────────────────────────────────────

  override suspend fun syncTime() {
    safe("syncTime") { conn()?.syncTime() }
  }

  override suspend fun queryBattery() {
    safe("queryBattery") { conn()?.queryBattery() }
  }

  override suspend fun queryDeviceVersion(type: String) {
    safe("queryDeviceVersion") {
      val vt = try {
        VersionInfo.VersionType.valueOf(type)
      } catch (e: Exception) {
        VersionInfo.VersionType.VerFirmware
      }
      conn()?.queryDeviceVersion(vt, versionCallback(type))
    }
  }

  override suspend fun queryDeviceId() {
    safe("queryDeviceId") { conn()?.queryDeviceId(deviceIdCallback) }
  }

  override suspend fun queryFeatureState() {
    safe("queryFeatureActiveState") { conn()?.queryFeatureActiveState() }
  }

  // ── settings / toggles ────────────────────────────────────────────────────

  override suspend fun setWakeWord(enabled: Boolean) {
    safe("sendVoiceWakeUpState") { conn()?.sendVoiceWakeUpState(enabled) }
  }

  override suspend fun queryWakeWord() {
    safe("queryVoiceWakeUpState") {
      conn()?.queryVoiceWakeUpState(object : CRPVoiceWakeUpCallback {
        override fun onVoiceWakeUpState(on: Boolean) {
          post(
            GlassEventMsg(GlassEventConstants.MSG_GLASS_WAKEWORD_STATUS, n1 = if (on) 1 else 0)
          )
          trace("[CRP] wakeWord=$on")
        }
      })
    }
  }

  override suspend fun setWearCheck(enabled: Boolean) {
    safe("sendWearCheckState") { conn()?.sendWearCheckState(enabled) }
  }

  override suspend fun queryWearCheck() {
    safe("queryWearCheckState") {
      conn()?.queryWearCheckState(object : CRPWearCheckCallback {
        override fun onWearCheckState(on: Boolean) {
          post(GlassEventMsg(GlassEventConstants.MSG_GLASS_WEAR_STATUS, n1 = if (on) 1 else 0))
          trace("[CRP] wearCheck=$on")
        }
      })
    }
  }

  override suspend fun sendLanguage(language: Int) {
    safe("sendLanguage") { conn()?.sendLanguage(language.toByte()) }
  }

  // ── capture ───────────────────────────────────────────────────────────────

  override suspend fun takePhoto(mode: String): CommandResult = safe("takePhoto") {
    val pm = try {
      TakePhoto.PhotoMode.valueOf(mode)
    } catch (e: Exception) {
      TakePhoto.PhotoMode.ModeNormal
    }
    conn()?.takePhoto(pm)
    trace("[CRP] takePhoto(${pm.name})")
  }

  override suspend fun startAudio(seconds: Int): CommandResult = safe("startAudio") {
    conn()?.startAudio(seconds, commandCallback("startAudio($seconds)"))
  }

  override suspend fun stopAudio() {
    safe("stopAudio") { conn()?.stopAudio() }
  }

  override suspend fun queryAudioState() {
    safe("queryAudioState") {
      conn()?.queryAudioState(object : CRPAudioStateCallback {
        override fun onAudioState(info: CRPAudioStateInfo?) {
          if (info == null) return
          post(
            GlassEventMsg(
              GlassEventConstants.MSG_GLASS_AUDIO_STATE,
              n1 = if (info.isRecording) 1 else 0, n2 = info.duration
            )
          )
        }
      })
    }
  }

  override suspend fun queryVideoConfig() {
    safe("queryVideoConfig") {
      conn()?.queryVideoConfig(object : CRPVideoConfigCallback {
        override fun onVideoConfig(cfg: VideoConfig?) {
          if (cfg == null) return
          post(
            GlassEventMsg(
              GlassEventConstants.MSG_GLASS_VIDEO_CONFIG, n1 = cfg.fps, n2 = cfg.maxDuration
            )
          )
          trace("[CRP] videoConfig fps=${cfg.fps} maxDuration=${cfg.maxDuration}")
        }
      })
    }
  }

  override suspend fun sendVideoConfig(fps: Int, maxDuration: Int): CommandResult =
    safe("sendVideoConfig") {
      val cfg = VideoConfig.newBuilder().setFps(fps).setMaxDuration(maxDuration).build()
      conn()?.sendVideoConfig(cfg, commandCallback("sendVideoConfig($fps,$maxDuration)"))
    }

  // ── Wi-Fi + media ─────────────────────────────────────────────────────────

  private fun wifiType(type: String): CRPWifiType = try {
    CRPWifiType.valueOf(type.uppercase())
  } catch (e: Exception) {
    CRPWifiType.FILE
  }

  override suspend fun enableWifi(type: String) {
    safe("enableWifi") { conn()?.enableWifi(wifiType(type)) }
  }

  override suspend fun disableWifi() {
    safe("disableWifi") { conn()?.disableWifi() }
  }

  override suspend fun connectWifi() {
    safe("connectWifi") { conn()?.connectWifi() }
  }

  override suspend fun queryNewMediaFile() {
    safe("queryNewMediaFile") { conn()?.queryNewMediaFile() }
  }

  private fun downloadCallback(label: String) = object : CRPFileDownloadCallback {
    override fun onStart() {
      trace("[CRP] $label download start")
    }

    override fun onProgress(progress: Int) {
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_DOWNLOAD_PROGRESS, n1 = progress, s2 = label))
    }

    override fun onProgress(total: Int, done: Int) {
      val pct = if (total > 0) done * 100 / total else 0
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_DOWNLOAD_PROGRESS,
          n1 = pct, n2 = total, s1 = done.toString(), s2 = label
        )
      )
    }

    override fun onDownloadFile(dirPath: String?, filePaths: MutableList<String>?) {
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_DOWNLOAD_COMPLETE,
          n1 = filePaths?.size ?: 0, s1 = dirPath,
          s2 = filePaths?.joinToString("|") ?: ""
        )
      )
      trace("[CRP] $label files -> $dirPath (${filePaths?.size ?: 0})")
      // For the diagnostic log pull, dump the file *contents* (text/strings) to the trace channel,
      // not just the path — that's where any relayed swupdate/OTA abort reason would be.
      if (label == "log") filePaths?.forEach { traceFilePreview(it) }
    }

    override fun onSuccess() {
      trace("[CRP] $label download complete")
      if (label == "log") pendingLogPull = false
      // Device Wi-Fi must be turned back off after a transfer or it drains the battery.
      safe("disableWifi(after $label)") { conn()?.disableWifi() }
      unbindNetwork()
      stopNetworkWatch()
    }

    override fun onFail(code: Int) {
      onError("[CRP] $label download failed ($code) " +
        "(1=noNet,2=httpFail,3=respFail,4=respData,5=urlNull)")
      if (label == "log") pendingLogPull = false
      safe("disableWifi(after $label fail)") { conn()?.disableWifi() }
      unbindNetwork()
      stopNetworkWatch()
    }
  }

  /**
   * OTA/failure-relevant tokens. When a pulled device log is text (a Vision kernel/aglink log), we
   * surface only the lines matching this — the swupdate/OTA/error signal — instead of dumping
   * thousands of kernel-boot lines. Broad on purpose: better to show some boot noise than miss the
   * abort reason. (Tuned 2026-08-04 after the first successful Vision-log pull over BLE.)
   */
  private val logKeywords = Regex(
    "(?i)(swupdate|updater|\\bota\\b|upgrade|ai_glass|sw-description|\\bcpio\\b|\\bmagic\\b|" +
      "squashfs|rootfs|mmcblk0p9|\\bmd5\\b|sha256|checksum|verif|corrupt|invalid|abort|panic|" +
      "\\berror\\b|\\bfail|\\bnand\\b|reboot|get mode|mode:|aglink_app|install|download|write)"
  )

  /**
   * Reads a downloaded file and traces a filtered preview to the device log (and so to Metro). For
   * a text log we show only [logKeywords] hits (swupdate/OTA/errors), not kernel-boot spam; for a
   * binary blob, a `strings`-style extract. Reads the WHOLE file (up to [maxBytes]) so the userspace
   * OTA section — which comes long after the kernel boot — isn't missed. Our teardown-free window
   * into the Vision after a failed flash.
   */
  private fun traceFilePreview(path: String, maxBytes: Int = 1_048_576, maxLines: Int = 160) {
    try {
      val f = File(path)
      val size = f.length()
      val cap = minOf(maxBytes.toLong(), size).toInt().coerceAtLeast(0)
      val buf = ByteArray(cap)
      val n = f.inputStream().use { ins ->
        var off = 0
        while (off < cap) {
          val r = ins.read(buf, off, cap - off)
          if (r < 0) break
          off += r
        }
        off
      }
      var printable = 0
      for (i in 0 until n) {
        val c = buf[i].toInt() and 0xff
        if (c == 9 || c == 10 || c == 13 || c in 32..126) printable++
      }
      val ratio = if (n > 0) printable.toDouble() / n else 0.0
      if (ratio > 0.85) {
        val lines = String(buf, 0, n, Charsets.ISO_8859_1).split('\n')
        val hits = ArrayList<Pair<Int, String>>()
        lines.forEachIndexed { i, ln -> if (logKeywords.containsMatchIn(ln)) hits.add(i to ln) }
        trace("[LOG] ${f.name}  size=$size  read=$n  lines=${lines.size}  keyword-hits=${hits.size}")
        if (hits.isEmpty()) {
          trace("[LOG]   (no OTA/error keywords — showing tail)")
          lines.takeLast(20).forEach { if (it.isNotBlank()) trace("[LOG] | ${it.take(300)}") }
        } else {
          var shown = 0
          for ((i, ln) in hits) {
            if (shown >= maxLines) { trace("[LOG]   … +${hits.size - shown} more hits (raise cap if needed)"); break }
            if (ln.isNotBlank()) { trace("[LOG] $i| ${ln.take(300)}"); shown++ }
          }
        }
      } else {
        trace("[LOG] ${f.name}  size=$size  read=$n  textRatio=${"%.2f".format(ratio)} (binary)")
        // binary — extract printable ASCII runs (min length 6), like a mini `strings`
        val sb = StringBuilder()
        var count = 0
        for (idx in 0 until n) {
          val c = buf[idx].toInt() and 0xff
          if (c in 32..126) {
            sb.append(c.toChar())
          } else {
            if (sb.length >= 6) { if (count < maxLines) trace("[LOG] str| $sb"); count++ }
            sb.setLength(0)
          }
        }
        if (sb.length >= 6) { if (count < maxLines) trace("[LOG] str| $sb"); count++ }
        trace("[LOG] (binary) $count strings ≥6 chars" +
          if (count > maxLines) " (showed first $maxLines)" else "")
      }
    } catch (t: Throwable) {
      onError("[LOG] preview failed for $path: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  override suspend fun downloadMediaFile() {
    safe("downloadMediaFile") { conn()?.downloadMediaFile(downloadCallback("media")) }
  }

  override suspend fun downloadLogFile() {
    safe("downloadLogFile") { conn()?.downloadLogFile(downloadCallback("log")) }
  }

  // ── OTA ───────────────────────────────────────────────────────────────────

  private fun otaState(label: String, state: String) {
    post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_STATE, s1 = state, s2 = label))
    trace("[OTA] · $label state=$state")
  }

  /** last progress we logged (throttle) */
  private var lastLoggedProgress = -1

  /** highest/last OTA progress seen this run — reported on the terminal state to pin the stall point */
  private var lastOtaProgress = -1

  private fun otaListener(label: String) = object : CRPOtaListener {
    override fun onDownloadStarting() = otaState(label, "downloadStarting")
    override fun onDownloadComplete() = otaState(label, "downloadComplete")
    override fun onProgressStarting() = otaState(label, "progressStarting")

    override fun onProgressChanged(progress: Int) {
      post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_PROGRESS, n1 = progress, s2 = label))
      lastOtaProgress = progress
      // Full resolution during diagnosis: log every distinct % (the Vision transfer is slow, so this
      // is ≤100 lines and shows the exact point it stalls at ~25%).
      if (progress != lastLoggedProgress) {
        lastLoggedProgress = progress
        trace("[OTA] · $label transferring $progress%")
      }
    }

    override fun onCompleted() = finishOta(label, "completed")
    override fun onAborted() = finishOta(label, "aborted")

    override fun onError(code: Int, msg: String?) {
      post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_OTA_STATE, n1 = code, s1 = "error: $msg", s2 = label
        )
      )
      this@MoyoungAdapter.onError("[CRP] $label OTA error $code: $msg")
      finishOta(label, null)
    }
  }

  /** Terminal OTA cleanup: for the Vision path, disarm the auto-chain and turn the device Wi-Fi
   *  back off (the vendor guide calls disableWifi() on completed/aborted/error). */
  private fun finishOta(label: String, state: String?) {
    if (state != null) otaState(label, state)
    // Pin the stall point: how far did the transfer get before this terminal state? (elapsed-ms so
    // it can be compared against the [NET] onLost/onLosing timestamps.)
    trace("[OTA] ■ t=${android.os.SystemClock.elapsedRealtime()}ms $label finished ($state) — last progress reached = $lastOtaProgress%")
    lastLoggedProgress = -1
    lastOtaProgress = -1
    if (label == "allwinner") {
      trace("[OTA]   (Vision) if this stalled < 100%, pull the device log next " +
        "(OTA screen → Diagnostics → Pull device log) to read any swupdate abort reason.")
      pendingVisionOta = null
      visionExternalMode = false
      guard("disableWifi(after $label ota)") { conn()?.disableWifi() }
      unbindNetwork()
      stopNetworkWatch()
    }
  }

  /**
   * Bind this process's traffic to the glasses' (no-internet) Wi-Fi network so Android keeps our HTTP
   * routing on it for the whole OTA, instead of preferring a mobile / home-Wi-Fi network with real
   * internet. Without this, the OS routes the app away from the glasses AP mid-download and the
   * transfer EOFs early — empirically ~22% with mobile+home-Wi-Fi present, ~45% with them removed.
   * (`bindProcessToNetwork` is the in-app equivalent of the user disabling mobile data + forgetting
   * home Wi-Fi.) Released in [unbindNetwork] on OTA finish.
   */
  private fun bindToGlassesWifi(): Boolean {
    return try {
      val cm = appCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return false
      var target: android.net.Network? = null
      var fallback: android.net.Network? = null
      for (net in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(net) ?: continue
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
        val onApSubnet = cm.getLinkProperties(net)?.linkAddresses?.any {
          it.address?.hostAddress?.startsWith("192.168.31.") == true
        } == true
        if (onApSubnet) { target = net; break }
        // a WIFI network WITHOUT validated internet is very likely the glasses AP
        if (fallback == null &&
          !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
          fallback = net
        }
      }
      val net = target ?: fallback
      if (net != null) {
        val ok = cm.bindProcessToNetwork(net)
        trace("[OTA] ▶ bound app traffic to glasses Wi-Fi ($net, apSubnet=${target != null}) ok=$ok")
        ok
      } else {
        trace("[OTA]   ⚠ no glasses Wi-Fi network found to bind — routing may switch away mid-transfer")
        false
      }
    } catch (t: Throwable) {
      onError("[OTA] bindToGlassesWifi failed: ${t.message ?: t.javaClass.simpleName}")
      false
    }
  }

  private fun unbindNetwork() {
    try {
      (appCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager)?.bindProcessToNetwork(null)
      trace("[OTA] ■ unbound app traffic from glasses Wi-Fi")
    } catch (t: Throwable) {
      // best-effort
    }
  }

  /**
   * Register an OBSERVATIONAL Wi-Fi network watch for the duration of a transfer. Uses
   * registerNetworkCallback (which only *watches*, never *holds*, so it can't change behaviour) to
   * log onAvailable / onLosing / onLost / capability changes with elapsed-ms timestamps. This tells us
   * whether the ~37% truncation lines up with the OS reaping the glasses' no-internet AP (onLosing/
   * onLost right at the stall) or whether the Wi-Fi stays up and the stop is something else.
   */
  private fun startNetworkWatch() {
    try {
      val cm = appCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return
      if (otaNetCallback != null) return
      val cb = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
          trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms onAvailable $network")
        }
        override fun onLosing(network: android.net.Network, maxMsToLive: Int) {
          trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms ⚠ onLosing $network ttl=${maxMsToLive}ms  ← OS about to tear down this Wi-Fi")
        }
        override fun onLost(network: android.net.Network) {
          trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms ✖ onLost $network  ← Wi-Fi network DROPPED")
        }
        override fun onUnavailable() {
          trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms onUnavailable")
        }
        override fun onCapabilitiesChanged(
          network: android.net.Network,
          caps: android.net.NetworkCapabilities
        ) {
          val internet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
          val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
          trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms caps $network internet=$internet validated=$validated")
        }
      }
      // Match Wi-Fi networks WITHOUT requiring internet, so the glasses' no-internet AP is included.
      val req = android.net.NetworkRequest.Builder()
        .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
      cm.registerNetworkCallback(req, cb)
      otaNetCallback = cb
      trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms ▶ watching Wi-Fi lifecycle during transfer")
    } catch (t: Throwable) {
      onError("[NET] startNetworkWatch failed: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  private fun stopNetworkWatch() {
    try {
      val cb = otaNetCallback ?: return
      (appCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager)?.unregisterNetworkCallback(cb)
      otaNetCallback = null
      trace("[NET] t=${android.os.SystemClock.elapsedRealtime()}ms ■ stopped Wi-Fi watch")
    } catch (t: Throwable) {
      // best-effort
    }
  }

  override suspend fun checkFirmware(mac: String, fw1Version: String, fw2Version: String) {
    safe("checkFirmwareVersion") {
      val req = CRPFirmwareRequestInfo(mac, fw1Version, fw2Version)
      conn()?.checkFirmwareVersion(req, object : CRPNewFirmwareVersionCallback {
        override fun onNewVersion(info: CRPNewFirmwareVersionInfo?) {
          if (info == null) return
          val json = JSONObject()
            .put("newVersion", info.newVersion)
            .put("fileUrl", info.fileUrl)
            .put("md5", info.md5)
            .put("otaType", info.otaType)
            .put("firmwareType", info.firmwareType)
            .put("hasUpgrade", info.isHas_upgrade)
          post(
            GlassEventMsg(
              GlassEventConstants.MSG_GLASS_FIRMWARE_INFO,
              n1 = info.firmwareType, n2 = info.otaType,
              s1 = info.newVersion, s2 = json.toString()
            )
          )
          trace("[CRP] new firmware ${info.newVersion} type=${info.firmwareType} url=${info.fileUrl}")
        }

        override fun onLatestVersion() {
          post(GlassEventMsg(GlassEventConstants.MSG_GLASS_FIRMWARE_INFO, n1 = -1, s1 = "latest"))
          trace("[CRP] firmware already latest")
        }
      })
    }
  }

  override suspend fun startJieliOta(filePath: String?) {
    safe("startJieliOta") {
      val c = conn() ?: return@safe
      if (filePath.isNullOrBlank()) {
        c.startOta(otaListener("jieli"))
      } else {
        val f = File(filePath)
        if (!f.exists()) {
          onError("[CRP] Jieli OTA: file not found: $filePath")
          return@safe
        }
        c.startOta(f, otaListener("jieli"))
      }
    }
  }

  override suspend fun abortJieliOta() {
    safe("abortOta") { conn()?.abortOta() }
  }

  override suspend fun startAllwinnerOta(filePath: String) {
    safe("startAllWinnerOta") {
      val f = File(filePath)
      if (!f.exists()) {
        onError("[CRP] Allwinner OTA: file not found: $filePath")
        return@safe
      }
      // Requires enableWifi(OTA) + connectWifi() first — see docs/02_Firmware_and_OTA.md
      conn()?.startAllWinnerOta(f, otaListener("allwinner"))
    }
  }

  override suspend fun resumeAllwinnerOta() {
    safe("resumeAllWinnerOta") { conn()?.resumeAllWinnerOta(otaListener("allwinner")) }
  }

  override suspend fun abortAllwinnerOta() {
    safe("abortAllWinnerOta") { conn()?.abortAllWinnerOta() }
  }

  // ── device management (destructive) ───────────────────────────────────────

  override suspend fun restart() {
    safe("restart") { conn()?.restart(commandCallback("restart")) }
  }

  override suspend fun factoryReset() {
    safe("reset") { conn()?.reset(commandCallback("reset")) }
  }

  override suspend fun shutdown() {
    safe("shutdown") { conn()?.shutdown(commandCallback("shutdown")) }
  }

  override suspend fun removeBond() {
    safe("removeBond") { conn()?.removeBond(commandCallback("removeBond")) }
  }

  // ── AI dialogue / translation ─────────────────────────────────────────────

  override suspend fun startTranslation() {
    safe("startTranslation") { conn()?.startTranslation(commandCallback("startTranslation")) }
  }

  override suspend fun pauseTranslation() {
    safe("pauseTranslation") { conn()?.pauseTranslation() }
  }

  override suspend fun stopTranslation() {
    safe("stopTranslation") { conn()?.stopTranslation() }
  }

  override suspend fun sendAiDialogueState(type: String) {
    safe("sendAIDialogueState") {
      val t = try {
        FlowStatus.FlowStatusType.valueOf(type)
      } catch (e: Exception) {
        FlowStatus.FlowStatusType.FlowStatusStart
      }
      conn()?.sendAIDialogueState(t)
    }
  }

  override suspend fun exitAiDialogue() {
    safe("exitAIDialogue") { conn()?.exitAIDialogue() }
  }

  // ── extension seam ────────────────────────────────────────────────────────

  /**
   * Anything not on the formal interface. Undocumented experiments go here rather than widening
   * [expo.modules.moyoung.glass.GlassAdapter].
   */
  override suspend fun sendCommand(command: String, params: Map<String, Any?>): CommandResult =
    when (command) {
      "stopLive" -> safe("stopLive") { conn()?.stopLive() }

      // Vendor-documented Allwinner OTA in one call: arms the auto-chain and kicks off
      // enableWifi(OTA). The wifiListener then drives connect → startAllWinnerOta. (dev-docs ota.md)
      "startVisionOtaAuto" -> safe("startVisionOtaAuto") {
        val path = params["path"] as? String
        trace("[OTA] ▶ step0 startVisionOtaAuto path=$path")
        val f = if (path.isNullOrBlank()) null else File(path)
        when {
          f == null || !f.exists() -> onError("[OTA] ✖ file not found: $path")
          else -> {
            trace("[OTA] file ok: ${f.name} = ${f.length()} bytes")
            val c = conn()
            if (c == null) {
              onError("[OTA] ✖ no BLE connection — connect on the Connect tab first")
            } else {
              // make sure the wifi callbacks are live (they drive the whole chain)
              guard("setWifiListener(ota)") { c.setWifiListener(wifiListener) }
              pendingVisionOta = f
              visionExternalMode = false
              otaState("allwinner", "wifiEnabling")
              trace("[OTA] ▶ step1 calling enableWifi(OTA) …")
              c.enableWifi(CRPWifiType.OTA)
              trace("[OTA]   enableWifi(OTA) returned; now WAITING for onWifiStateChange(OTA, STATE_SUCCESS=0)")
            }
          }
        }
      }

      // EXTERNAL-SERVER (laptop harness): same as startVisionOtaAuto but the phone does NOT join the
      // AP — it brings up the glasses Wi-Fi and then pauses at "awaitingExternalServer" so a LAPTOP can
      // be the 192.168.31.2 server. This removes Android from the data path and lets Wireshark on the
      // laptop capture who truncates the transfer. Turn the phone's Wi-Fi OFF first so the laptop can
      // take .2. Finish the flow with `continueVisionOtaExternal`. See tools/vision-ota-harness/.
      "startVisionOtaExternal" -> safe("startVisionOtaExternal") {
        val path = params["path"] as? String
        trace("[OTA] ▶ step0 startVisionOtaExternal (LAPTOP harness) path=$path")
        val f = if (path.isNullOrBlank()) null else File(path)
        when {
          f == null || !f.exists() -> onError("[OTA] ✖ file not found: $path")
          else -> {
            trace("[OTA] file ok: ${f.name} = ${f.length()} bytes  " +
              "(the laptop MUST serve this exact file so the size the glasses receive matches)")
            val c = conn()
            if (c == null) {
              onError("[OTA] ✖ no BLE connection — connect on the Connect tab first")
            } else {
              guard("setWifiListener(ota)") { c.setWifiListener(wifiListener) }
              pendingVisionOta = f
              visionExternalMode = true
              otaState("allwinner", "wifiEnabling")
              trace("[OTA] ▶ step1 EXTERNAL: enableWifi(OTA) — bringing up the glasses AP; the phone will " +
                "NOT join. Make sure the phone's Wi-Fi is OFF so the laptop can take 192.168.31.2.")
              c.enableWifi(CRPWifiType.OTA)
              trace("[OTA]   enableWifi(OTA) returned; WAITING for onWifiStateChange(OTA, STATE_SUCCESS)")
            }
          }
        }
      }

      // Second half of the external-server flow: fire the BLE OTA trigger now that the laptop is the
      // server. The glasses should GET the .swu from 192.168.31.2:8182 (the laptop) — watch the laptop
      // server log + Wireshark for who sends the FIN/RST at any stall.
      "continueVisionOtaExternal" -> safe("continueVisionOtaExternal") {
        val f = pendingVisionOta
        val c = conn()
        when {
          !visionExternalMode -> onError("[OTA] ✖ not in external-server mode — tap the laptop-server flash first")
          f == null -> onError("[OTA] ✖ no armed Vision OTA — tap the laptop-server flash first")
          c == null -> onError("[OTA] ✖ no BLE connection")
          else -> {
            trace("[OTA] ▶ EXTERNAL step3 → startAllWinnerOta(${f.name}). The glasses should now GET from " +
              "the LAPTOP (192.168.31.2:8182). Watch the laptop server + Wireshark (tcp.port==8182).")
            guard("startAllWinnerOta(external)") { c.startAllWinnerOta(f, otaListener("allwinner")) }
            trace("[OTA]   startAllWinnerOta() returned; WAITING for the device to fetch / progress")
          }
        }
      }

      // Diagnostic: pull the device log over the FILE Wi-Fi transport (device→phone, the reliable
      // direction) and dump its contents to the trace channel. Reads any swupdate/OTA abort reason
      // the Core relays from the Vision — our teardown-free window after a failed Vision flash.
      "pullDeviceLogAuto" -> safe("pullDeviceLogAuto") {
        val c = conn()
        if (c == null) {
          onError("[LOG] ✖ no BLE connection — connect on the Connect tab first")
        } else {
          guard("setWifiListener(log)") { c.setWifiListener(wifiListener) }
          pendingLogPull = true
          trace("[LOG] ▶ pulling device log: enableWifi(FILE) → connectWifi() → downloadLogFile()")
          c.enableWifi(CRPWifiType.FILE)
          trace("[LOG]   enableWifi(FILE) returned; WAITING for onWifiStateChange(FILE, STATE_SUCCESS)")
        }
      }

      "isBluetoothEnabled" -> {
        val on = ble.isBluetoothEnabled()
        trace("[CRP] bluetoothEnabled=$on")
        CommandResult(on, 0, on.toString())
      }

      // Pull every version type at once — a useful first-contact fingerprint.
      // These enums are GeneratedMessage (not proto3-lite), so there's no synthetic
      // UNRECOGNIZED member to filter out — all six values are real.
      "queryAllVersions" -> safe("queryAllVersions") {
        val c = conn() ?: return@safe
        VersionInfo.VersionType.values().forEach { vt ->
          c.queryDeviceVersion(vt, versionCallback(vt.name))
        }
      }

      else -> {
        trace("[CRP] unknown command: $command")
        CommandResult(false, -1, "unknown command: $command")
      }
    }
}
