package expo.modules.moyoung.glass

/**
 * The connection state machine, mirrored 1:1 in JS (see src/native/Glass.ts).
 */
enum class GlassConnectionState {
  IDLE,
  SCANNING,
  CONNECTING,
  CONNECTED,
  DISCONNECTING,
  ERROR
}

/**
 * Integer event codes. Kept in a private range (70000+) so they can't collide with
 * anything the vendor SDK might post on the same bus.
 */
object GlassEventConstants {
  const val MSG_GLASS_DEVICE_FOUND = 70001
  const val MSG_GLASS_STATE_CHANGED = 70002
  const val MSG_GLASS_CONNECTED = 70003
  const val MSG_GLASS_DISCONNECTED = 70004
  const val MSG_GLASS_READY = 70005

  /** Doubles as the generic on-device trace channel (see BaseGlassAdapter.trace). */
  const val MSG_GLASS_ERROR = 70006

  const val MSG_GLASS_BATTERY = 70007
  const val MSG_GLASS_WEAR_STATUS = 70008
  const val MSG_GLASS_WAKEWORD_STATUS = 70009
  const val MSG_GLASS_DEVICE_VERSION = 70010
  const val MSG_GLASS_DEVICE_ID = 70011
  const val MSG_GLASS_FEATURE_STATE = 70012

  const val MSG_GLASS_WIFI_STATE = 70013
  const val MSG_GLASS_MEDIA_COUNT = 70014
  const val MSG_GLASS_DOWNLOAD_PROGRESS = 70015
  const val MSG_GLASS_DOWNLOAD_COMPLETE = 70016

  const val MSG_GLASS_OTA_PROGRESS = 70017
  const val MSG_GLASS_OTA_STATE = 70018

  const val MSG_GLASS_AI_STATE = 70019
  const val MSG_GLASS_AI_AUDIO = 70020
  const val MSG_GLASS_AI_IMAGE = 70021
  const val MSG_GLASS_TRANSLATION_AUDIO = 70022

  const val MSG_GLASS_AUDIO_STATE = 70023
  const val MSG_GLASS_VIDEO_CONFIG = 70024
  const val MSG_GLASS_FIRMWARE_INFO = 70025
}

/**
 * The ONLY object ever posted on the EventBus.
 *
 * A deliberately flat, primitives-only envelope: one data class carries every event type, and
 * [GlassModule.emitEvent] demuxes on [cmd] into a typed Bundle for JS. Keeping it flat avoids
 * nested-object marshalling across the RN bridge — anything structured travels as JSON in [s2].
 *
 * @param cmd  which event (a [GlassEventConstants] value)
 * @param n1   generic int slot 1 (rssi, battery level, percent, ordinal, ...)
 * @param n2   generic int slot 2 (isCharging?1:0, state code, total, ...)
 * @param s1   generic string slot 1 (deviceId, reason, file path, message, ...)
 * @param s2   generic string slot 2 (advertisement JSON, device name, type, ...)
 */
data class GlassEventMsg(
  val cmd: Int,
  val n1: Int = 0,
  val n2: Int = 0,
  val s1: String? = null,
  val s2: String? = null
)
