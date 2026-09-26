package com.kgycoder.lmarena

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.kgycoder.lmarena.server.LocalServer

/**
 * 안드로이드는 OS가 백그라운드 앱을 언제든 회수할 수 있다(Windows에는 없는 제약).
 * 포그라운드 서비스 + 지속 알림 + WakeLock은 "최대한 안 죽게" 만드는 안드로이드의
 * 공식적인 대응 수단이다(README의 배터리 최적화 관련 한계 설명 참고).
 */
class AutomationForegroundService : Service() {

    private var localServer: LocalServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        if (localServer == null) {
            localServer = LocalServer(applicationContext, PORT).also {
                it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            isRunning = true
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LmArena:session").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { localServer?.stop() } catch (_: Exception) {}
        localServer = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AutomationForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LmArenaApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text_idle))
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.kgycoder.lmarena.STOP"
        const val PORT = 8787
        const val NOTIF_ID = 1001
        @Volatile var isRunning = false
    }
}
