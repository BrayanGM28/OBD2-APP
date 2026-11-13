package com.nguevara.obdpulse.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PowerManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nguevara.obdpulse.MainActivity

class TripRecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val recordGps = intent?.getBooleanExtra(EXTRA_RECORD_GPS, false) == true
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (recordGps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(recordGps), serviceType)
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:trip-recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Registro de viaje", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene activa la lectura OBD durante un viaje grabado."
            },
        )
    }

    private fun notification(recordGps: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Grabando viaje OBD")
            .setContentText(if (recordGps) "Lecturas OBD y ruta GPS activas" else "Lecturas OBD activas con pantalla apagada")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1201
        private const val EXTRA_RECORD_GPS = "record_gps"

        fun start(context: Context, recordGps: Boolean) {
            val intent = Intent(context, TripRecordingService::class.java)
                .putExtra(EXTRA_RECORD_GPS, recordGps)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripRecordingService::class.java))
        }
    }
}
