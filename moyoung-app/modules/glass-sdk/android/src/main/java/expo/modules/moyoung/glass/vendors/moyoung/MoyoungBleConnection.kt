package expo.modules.moyoung.glass.vendors.moyoung

import android.content.Context
import android.util.Log
import com.moyoung.glasses.CRPBleClient
import com.moyoung.glasses.conn.CRPBleConnection
import com.moyoung.glasses.conn.CRPBleDevice
import com.moyoung.glasses.conn.listener.CRPBatteryListener
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener
import com.moyoung.glasses.conn.protos.BatteryInfo
import com.moyoung.glasses.scan.CRPScanRecordParser
import com.moyoung.glasses.scan.bean.CRPScanDevice
import com.moyoung.glasses.scan.callback.CRPScanCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The ONE file that imports `com.moyoung.glasses.*`.
 *
 * Keeping every vendor-SDK touchpoint behind this class means the rest of the module stays
 * portable: to add a second vendor you write a sibling of this file and nothing else changes.
 *
 * Reference: docs/04_BLE_Protocol_Reference.md for the full command catalog.
 */
class MoyoungBleConnection(private val context: Context) {

  private val tag = "MoyoungBleConn"

  interface ConnectionCallback {
    fun onConnectionStateChanged(state: Int)
    fun onDeviceFound(deviceId: String, deviceName: String, rssi: Int, advJson: String)
    fun onBattery(level: Int, charging: Boolean, voltage: Int)
    fun onTrace(message: String)
  }

  private var callback: ConnectionCallback? = null

  /** Mirrors CRPBleConnectionStateListener's constants. */
  companion object {
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_CONNECTED = 2
    const val STATE_DISCONNECTING = 3

    /** Default scan window, ms. */
    const val SCAN_TIMEOUT_MS = 15_000L

    // Connect tuning — ported from the sibling MADRIMs/Cyan app, which fought the same CRP stack.
    /** Android's first GATT connect after idle often drops instantly (status-133 style). */
    const val MAX_CONNECT_ATTEMPTS = 5

    /** Per-attempt safety net; genuine failures fire within milliseconds. */
    const val CONNECT_TIMEOUT_MS = 5_000L

    /** Linear backoff between attempts: 1s, 2s, 3s, 4s. */
    const val CONNECT_RETRY_BASE_DELAY_MS = 1_000L

    /** Pre-connect warm scan window. */
    const val WARM_SCAN_MS = 5_000L
  }

  private var client: CRPBleClient? = null
  private var device: CRPBleDevice? = null

  /** The live command surface. Null until [connect] has run. */
  var connection: CRPBleConnection? = null
    private set

  /** MAC of the device we last asked to connect. */
  var connectedMac: String? = null
    private set

  // ── lifecycle ─────────────────────────────────────────────────────────────

  /** One-time SDK bootstrap. CRPBleClient is a singleton; create it once. */
  fun init(cb: ConnectionCallback) {
    callback = cb
    try {
      client = CRPBleClient.create(context.applicationContext)
      cb.onTrace("[CRP] CRPBleClient created")
    } catch (e: Exception) {
      Log.e(tag, "CRPBleClient.create failed", e)
      cb.onTrace("[CRP][ERR] CRPBleClient.create failed: ${e.message}")
    }
  }

  fun isBluetoothEnabled(): Boolean = try {
    client?.isBluetoothEnable ?: false
  } catch (e: Exception) {
    false
  }

  // ── scanning (vendor scanner: yields the parsed advert) ───────────────────

  private val scanCallback = object : CRPScanCallback {
    override fun onScanning(d: CRPScanDevice) {
      try {
        val dev = d.device ?: return
        val mac = dev.address ?: return
        val name = try { dev.name ?: "" } catch (e: SecurityException) { "" }

        // The MoYoung advert carries firmware type + battery + charging.
        val adv = JSONObject().put("name", name)
        try {
          d.scanRecord?.let { raw ->
            CRPScanRecordParser.parseScanRecord(raw)?.let { info ->
              adv.put("firmwareType", info.firmwareType ?: "")
              adv.put("battery", info.battery)
              adv.put("isCharging", info.isCharging)
            }
          }
        } catch (e: Exception) {
          // A device that isn't a MoYoung glass will fail to parse — that's expected.
        }
        callback?.onDeviceFound(mac, name, d.rssi, adv.toString())
      } catch (e: Exception) {
        Log.w(tag, "onScanning parse failed: ${e.message}")
      }
    }

    override fun onScanComplete(list: MutableList<CRPScanDevice>?) {
      callback?.onTrace("[CRP] scan complete (${list?.size ?: 0} devices)")
    }
  }

  fun startScan(timeoutMs: Long = SCAN_TIMEOUT_MS): Boolean {
    val c = client ?: run {
      callback?.onTrace("[CRP][ERR] startScan: client not initialised")
      return false
    }
    return try {
      cancelScan() // avoid ALREADY_STARTED
      val ok = c.scanDevice(scanCallback, timeoutMs)
      callback?.onTrace("[CRP] scanDevice($timeoutMs ms) -> $ok")
      ok
    } catch (e: Exception) {
      Log.e(tag, "startScan failed", e)
      callback?.onTrace("[CRP][ERR] startScan: ${e.message}")
      false
    }
  }

  fun cancelScan() {
    try {
      client?.cancelScan()
    } catch (e: Exception) {
      Log.w(tag, "cancelScan: ${e.message}")
    }
  }

  // ── connection ────────────────────────────────────────────────────────────

  private val batteryListener = object : CRPBatteryListener {
    override fun onBatteryChange(info: BatteryInfo?) {
      if (info == null) return
      callback?.onBattery(info.lvl, info.charging, info.volt)
    }
  }

  /**
   * Warm the controller cache before connecting: confirms the device is actually in range so we
   * don't burn connect attempts on a device that isn't there. Early-exits the moment it advertises.
   */
  private suspend fun warmScanFor(mac: String, timeoutMs: Long): Boolean {
    val c = client ?: return false
    val seen = withTimeoutOrNull(timeoutMs) {
      suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { c.cancelScan() } }
        val started = try {
          c.scanDevice(object : CRPScanCallback {
            override fun onScanning(d: CRPScanDevice) {
              if (d.device?.address.equals(mac, ignoreCase = true)) {
                runCatching { c.cancelScan() }
                if (cont.isActive) cont.resume(true)
              }
            }

            override fun onScanComplete(list: MutableList<CRPScanDevice>?) {
              if (cont.isActive) {
                cont.resume(list?.any { it.device?.address.equals(mac, true) } == true)
              }
            }
          }, timeoutMs)
        } catch (e: Exception) {
          false
        }
        if (!started && cont.isActive) cont.resume(false) // scan failed to start
      }
    } ?: false
    runCatching { c.cancelScan() }
    callback?.onTrace("[CRP] warm scan for $mac: seen=$seen")
    return seen
  }

  /**
   * The two-step connect — the #1 gotcha in this SDK. `device.connect()` returns the connection
   * object but does NOT dial; you must then call `connection.connect()`.
   *
   * Hardened against two behaviours of this stack (learned the hard way in the sibling
   * MADRIMs/Cyan app against the same CRP SDK):
   *  1. Android's first GATT connect after an idle period frequently drops instantly — an
   *     immediate retry succeeds — so we retry up to [MAX_CONNECT_ATTEMPTS] times.
   *  2. `connection.connect()`'s boolean is **unreliable**: it can return false while the link
   *     still comes up asynchronously and the state listener reports CONNECTED. So the returned
   *     value is only logged; the state listener drives the outcome via a gate.
   */
  suspend fun connect(mac: String): Boolean {
    val c = client ?: run {
      callback?.onTrace("[CRP][ERR] connect: client not initialised")
      return false
    }

    cancelScan()
    connectedMac = mac
    warmScanFor(mac, WARM_SCAN_MS)

    for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
      val gate = CompletableDeferred<Boolean>()

      val conn = try {
        val dev = c.getBleDevice(mac)
        if (dev == null) {
          callback?.onTrace("[CRP][ERR] getBleDevice($mac) returned null")
          return false
        }
        device = dev
        // Wire listeners BEFORE dialing, or early callbacks are lost.
        dev.connect()
      } catch (e: Exception) {
        Log.e(tag, "connect attempt $attempt failed", e)
        callback?.onTrace("[CRP][ERR] connect attempt $attempt: ${e.message}")
        null
      }

      if (conn == null) {
        callback?.onTrace("[CRP][ERR] device.connect() returned null (attempt $attempt)")
      } else {
        connection = conn
        conn.setConnectionStateListener(object : CRPBleConnectionStateListener {
          override fun onConnectionStateChange(state: Int) {
            // Ignore callbacks from a superseded attempt (we null `connection` between tries).
            if (conn !== connection) return
            callback?.onTrace("[CRP] connection state -> $state")
            if (state == STATE_CONNECTED && gate.isActive) gate.complete(true)
            callback?.onConnectionStateChanged(state)
          }
        })
        conn.setBatteryListener(batteryListener)

        val dialing = try {
          conn.connect()
        } catch (e: Exception) {
          callback?.onTrace("[CRP][ERR] conn.connect(): ${e.message}")
          false
        }
        if (!dialing) {
          callback?.onTrace("[CRP] conn.connect() returned false (attempt $attempt) — relying on state listener")
        }

        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { gate.await() } == true) {
          callback?.onTrace("[CRP] connected to $mac on attempt $attempt")
          return true
        }
      }

      callback?.onTrace("[CRP] connect attempt $attempt/$MAX_CONNECT_ATTEMPTS failed")
      // Neutralize late callbacks from this attempt. Do NOT call device.disconnect() here — the
      // SDK's gatt callback already ran refresh()+close() on the failed gatt; an extra disconnect
      // just races that cleanup. Back off so the peripheral can free its slot, then retry fresh.
      connection = null
      if (attempt < MAX_CONNECT_ATTEMPTS) delay(CONNECT_RETRY_BASE_DELAY_MS * attempt)
    }

    callback?.onTrace("[CRP][ERR] connect failed after $MAX_CONNECT_ATTEMPTS attempts")
    return false
  }

  fun disconnect() {
    try {
      device?.disconnect()
      callback?.onTrace("[CRP] disconnect()")
    } catch (e: Exception) {
      Log.w(tag, "disconnect: ${e.message}")
    }
  }

  fun isConnected(): Boolean = try {
    device?.isConnected ?: false
  } catch (e: Exception) {
    false
  }

  fun destroy() {
    try {
      cancelScan()
      disconnect()
    } catch (e: Exception) {
      // best effort
    }
    connection = null
    device = null
    callback = null
  }
}
