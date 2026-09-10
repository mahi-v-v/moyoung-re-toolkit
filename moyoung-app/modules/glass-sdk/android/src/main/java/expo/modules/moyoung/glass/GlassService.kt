package expo.modules.moyoung.glass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Minimal `connectedDevice` foreground service so an active BLE session survives the app going
 * to the background.
 *
 * NOTE: `startForeground()` must be called promptly inside [onStartCommand] or Android 12+ throws
 * ForegroundServiceDidNotStartInTimeException — hence the guard/fallback below.
 */
class GlassService : Service() {

  companion object {
    private const val TAG = "GlassService"
    private const val CHANNEL_ID = "glass_connection"
    private const val NOTIFICATION_ID = 7001
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      startForeground(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Log.e(TAG, "startForeground failed: ${e.message}")
      stopSelf()
      return START_NOT_STICKY
    }
    return START_STICKY
  }

  private fun buildNotification(): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java)
      if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
        manager?.createNotificationChannel(
          NotificationChannel(
            CHANNEL_ID,
            "Glasses connection",
            NotificationManager.IMPORTANCE_LOW
          )
        )
      }
    }

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }

    return builder
      .setContentTitle("Glasses connected")
      .setContentText("Maintaining the BLE link")
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setOngoing(true)
      .build()
  }

  @Suppress("unused")
  private fun foregroundType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    } else 0
}
