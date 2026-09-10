package expo.modules.moyoung.glass

import android.os.Looper
import android.util.Log

/**
 * Last-resort net for vendor-SDK code that runs on threads we don't own.
 *
 * The MoYoung AAR references components that its own SDK drop doesn't bundle — so far the Jieli
 * Opus decoder (`com.jieli.jl_audio_decode.opus.OpusManager`) and the mltcode PayCertification
 * module. When one of those resolves for the first time inside a vendor callback — e.g.
 * `BluetoothGattCallback.onCharacteristicChanged`, which the Android BT stack invokes on a binder
 * thread — the resulting [NoClassDefFoundError] is thrown on a thread no try/catch of ours wraps,
 * and an uncaught throwable on any thread kills the whole process.
 *
 * This guard intercepts exactly that case and lets the app survive with a degraded feature instead
 * of dying. It is deliberately narrow:
 *   - only missing-class errors ([NoClassDefFoundError] / [ClassNotFoundException]),
 *   - only when the stack implicates a known vendor package,
 *   - only off the main thread (a broken main thread should still fail loudly).
 * Everything else is handed to the previous handler untouched, so real bugs still crash.
 */
object VendorCrashGuard {

  private const val TAG = "VendorCrashGuard"

  private val VENDOR_PACKAGES = listOf(
    "com.moyoung",
    "com.jieli",
    "com.android.mltcode"
  )

  @Volatile
  private var installed = false

  /** Names of vendor components we've seen go missing, for surfacing in the debug screen. */
  val missing = java.util.Collections.synchronizedSet(linkedSetOf<String>())

  fun install() {
    if (installed) return
    installed = true

    val previous = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      if (isSurvivableVendorMiss(thread, error)) {
        val what = missingClassName(error) ?: error.message ?: error.javaClass.simpleName
        missing.add(what)
        Log.e(
          TAG,
          "Swallowed missing vendor component on thread '${thread.name}': $what — " +
            "that feature is unavailable, the app keeps running.",
          error
        )
      } else {
        previous?.uncaughtException(thread, error)
      }
    }
  }

  private fun isSurvivableVendorMiss(thread: Thread, error: Throwable): Boolean {
    if (thread === Looper.getMainLooper().thread) return false

    var t: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (t != null && seen.add(t)) {
      val isMissingClass = t is NoClassDefFoundError || t is ClassNotFoundException
      if (isMissingClass && implicatesVendor(t)) return true
      t = t.cause
    }
    return false
  }

  private fun implicatesVendor(t: Throwable): Boolean {
    val message = t.message ?: ""
    if (VENDOR_PACKAGES.any { message.contains(it.replace('.', '/')) || message.contains(it) }) {
      return true
    }
    return t.stackTrace.any { frame ->
      VENDOR_PACKAGES.any { frame.className.startsWith(it) }
    }
  }

  private fun missingClassName(error: Throwable): String? {
    var t: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (t != null && seen.add(t)) {
      if (t is NoClassDefFoundError || t is ClassNotFoundException) return t.message
      t = t.cause
    }
    return null
  }
}
