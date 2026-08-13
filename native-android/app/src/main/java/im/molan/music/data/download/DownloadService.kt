package im.molan.music.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import im.molan.music.MainActivity
import im.molan.music.QingyinApplication
import im.molan.music.R

/**
 * 下载前台服务：只要还有排队/下载中的任务，就以 dataSync 前台服务保活，
 * 避免 App 退到后台后下载队列被系统回收而“下载不可用”。
 */
class DownloadService : Service() {
    private companion object {
        const val CHANNEL_ID = "qingyin_downloads"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "im.molan.music.action.STOP_DOWNLOAD_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val active = (application as QingyinApplication).downloadRepository.activeDownloadCount()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("轻音下载中")
            .setContentText(if (active > 0) "正在下载 $active 首歌曲" else "轻音下载任务进行中")
            .setSmallIcon(R.drawable.ic_qingyin)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止下载服务", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "轻音下载", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
