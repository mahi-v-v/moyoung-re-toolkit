package expo.modules.moyoung.glass

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import expo.modules.moyoung.glass.vendors.moyoung.MoyoungAdapter
import org.greenrobot.eventbus.EventBus

/**
 * Process-wide router between the Expo module and the active vendor adapter.
 *
 * The adapter pool is built once in [init] and never torn down at runtime — vendor SDKs tend to
 * be singletons with global listener registries, so recreating adapters invites stale-state races.
 */
class GlassManager private constructor(private val context: Context) {

  companion object {
    private const val TAG = "GlassManager"
    const val VENDOR_MOYOUNG = "moyoung"

    @Volatile
    private var instance: GlassManager? = null

    fun getInstance(context: Context): GlassManager =
      instance ?: synchronized(this) {
        instance ?: GlassManager(context.applicationContext).also { instance = it }
      }
  }

  private val adapters = mutableMapOf<String, BaseGlassAdapter>()
  private var activeVendor: String? = VENDOR_MOYOUNG
  private var initialized = false

  val scanner = GlassScanner(context)

  private fun createAdapter(vendor: String): BaseGlassAdapter = when (vendor) {
    VENDOR_MOYOUNG -> MoyoungAdapter(context)
    else -> MoyoungAdapter(context) // only one vendor today
  }

  suspend fun init(): GlassManager {
    if (initialized) return this

    listOf(VENDOR_MOYOUNG).forEach { vendor ->
      val adapter = createAdapter(vendor)
      adapter.init(context)
      adapters[vendor] = adapter
    }

    // The generic scanner belongs to the manager, not to any vendor adapter.
    scanner.onDeviceFound = { deviceId, name, rssi, advJson ->
      EventBus.getDefault().post(
        GlassEventMsg(
          GlassEventConstants.MSG_GLASS_DEVICE_FOUND,
          n1 = rssi, s1 = deviceId, s2 = advJson
        )
      )
    }

    initialized = true
    Log.d(TAG, "initialized with adapters: ${adapters.keys}")
    return this
  }

  private fun adapter(): BaseGlassAdapter? = activeVendor?.let { adapters[it] }

  fun currentState(): String = adapter()?.currentState()?.name ?: GlassConnectionState.IDLE.name
  fun isConnected(): Boolean = adapter()?.isConnected() ?: false
  fun capabilities(): Map<String, Boolean> = adapter()?.capabilities ?: emptyMap()

  // ── scanning ──────────────────────────────────────────────────────────────
  // Primary path is the VENDOR scanner (via the adapter): MoYoung's advertisement carries
  // firmwareType + battery + charging, which the generic scanner can't decode.
  // [startGenericScan] keeps the vendor-neutral raw-BLE sweep available for discovery of
  // devices the vendor scanner filters out.

  suspend fun startScan(): Boolean {
    adapter()?.startScan() ?: return false
    return true
  }

  suspend fun stopScan() {
    adapter()?.stopScan()
    scanner.stop()
  }

  /** Raw-BLE sweep that surfaces ALL advertising devices, vendor-agnostic. */
  fun startGenericScan(): Boolean = scanner.start()

  fun stopGenericScan() = scanner.stop()

  suspend fun connect(deviceId: String, vendor: String?, name: String?) {
    activeVendor = vendor ?: VENDOR_MOYOUNG
    scanner.stop()
    startService()
    adapter()?.connect(deviceId)
  }

  suspend fun disconnect() {
    adapter()?.disconnect()
    stopService()
  }

  /** Every other call is a one-line delegate to the active adapter. */
  fun active(): BaseGlassAdapter? = adapter()

  // ── foreground service keeps the BLE link alive when backgrounded ─────────
  private fun startService() {
    try {
      val intent = Intent(context, GlassService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    } catch (e: Exception) {
      Log.w(TAG, "could not start GlassService: ${e.message}")
    }
  }

  private fun stopService() {
    try {
      context.stopService(Intent(context, GlassService::class.java))
    } catch (e: Exception) {
      Log.w(TAG, "could not stop GlassService: ${e.message}")
    }
  }

  fun destroy() {
    scanner.stop()
    adapters.values.forEach { it.destroy() }
    stopService()
  }
}
