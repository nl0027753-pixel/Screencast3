package com.screencast.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Serviço em primeiro plano exigido pelo Android para usar MediaProjection (captura de tela). */
class ScreenShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transmissão de tela", NotificationManager.IMPORTANCE_LOW))
        }
        val tap = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ScreenCast")
            .setContentText("Sua tela está sendo transmitida")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // Só agora (já em primeiro plano) é seguro iniciar a captura.
        val cb = onReady; onReady = null
        cb?.invoke()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "screencast_capture"
        private const val NOTIF_ID = 4101
        private const val ACTION_STOP = "com.screencast.mobile.STOP"
        @Volatile var onReady: (() -> Unit)? = null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ScreenShareService::class.java))
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, ScreenShareService::class.java)) } catch (_: Exception) {} }
    }
}
