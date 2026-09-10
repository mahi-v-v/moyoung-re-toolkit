package expo.modules.moyoung.glass

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Vendor-neutral BLE scanner built on the raw Android LE APIs.
 *
 * Deliberately scans with NULL filters so every advertiser shows up — for a tinker app you want
 * to see the whole neighbourhood, not just what a vendor filter admits. Device classification is
 * done in JS (src/util/detectVendor.ts) so the rule can change without a native rebuild.
 *
 * This works WITHOUT the vendor SDK, which is why scanning is live in the skeleton even though
 * the CRP commands are not yet wired. (The CRP SDK also offers `CRPBleClient.scanDevice`, which
 * additionally parses the MoYoung scan record — see the wire-up guide if you want to switch.)
 */
class GlassScanner(private val context: Context) {

  private val tag = "GlassScanner"
  private var scanner: BluetoothLeScanner? = null
  private var scanning = false
  private val seen = mutableSetOf<String>()

  /** (deviceId, name, rssi, advertisementJson) */
  var onDeviceFound: ((String, String, Int, String) -> Unit)? = null

  private val callback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result ?: return
      handle(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
      results?.forEach { handle(it) }
    }

    override fun onScanFailed(errorCode: Int) {
      Log.e(tag, "scan failed: $errorCode")
    }
  }

  @SuppressLint("MissingPermission")
  private fun handle(result: ScanResult) {
    val mac = result.device?.address ?: return
    if (!seen.add(mac)) return // dedupe per scan session

    val name = result.scanRecord?.deviceName ?: result.device?.name ?: ""
    onDeviceFound?.invoke(mac, name, result.rssi, buildAdvertisementJson(result, name))
  }

  @SuppressLint("MissingPermission")
  private fun buildAdvertisementJson(result: ScanResult, name: String): String {
    val json = JSONObject()
    try {
      json.put("name", name)
      val record = result.scanRecord

      val uuids = org.json.JSONArray()
      record?.serviceUuids?.forEach { uuids.put(it.uuid.toString()) }
      json.put("serviceUuids", uuids)

      val serviceData = JSONObject()
      record?.serviceData?.forEach { (uuid, bytes) ->
        serviceData.put(uuid.uuid.toString(), bytes.toHex())
      }
      json.put("serviceData", serviceData)

      val mfg = JSONObject()
      val mfgData = record?.manufacturerSpecificData
      if (mfgData != null) {
        for (i in 0 until mfgData.size()) {
          mfg.put(String.format("%04x", mfgData.keyAt(i)), mfgData.valueAt(i).toHex())
        }
      }
      json.put("manufacturerData", mfg)

      // The full raw record: MoYoung encodes firmwareType/battery/charging in here
      // (CRPScanRecordParser). Kept raw so JS or the wire-up can parse it.
      json.put("rawAdvertisement", record?.bytes?.toHex() ?: "")
    } catch (e: Exception) {
      Log.w(tag, "advertisement serialization failed: ${e.message}")
    }
    return json.toString()
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

  private fun canScan(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_SCAN
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        Log.w(tag, "BLUETOOTH_SCAN not granted — request it in JS before scanning")
        return false
      }
    }
    return true
  }

  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (scanning) return true
    if (!canScan()) return false

    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null || !adapter.isEnabled) {
      Log.w(tag, "bluetooth adapter unavailable or disabled")
      return false
    }

    scanner = adapter.bluetoothLeScanner ?: return false
    seen.clear()

    val settings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()

    return try {
      scanner?.startScan(null, settings, callback) // null filters = show everything
      scanning = true
      true
    } catch (e: Exception) {
      Log.e(tag, "startScan threw: ${e.message}")
      false
    }
  }

  @SuppressLint("MissingPermission")
  fun stop() {
    if (!scanning) return
    try {
      scanner?.stopScan(callback)
    } catch (e: Exception) {
      Log.w(tag, "stopScan threw: ${e.message}")
    }
    scanning = false
  }

  fun isScanning(): Boolean = scanning
}
