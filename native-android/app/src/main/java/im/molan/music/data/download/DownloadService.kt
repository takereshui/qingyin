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
        // Android 12+ 后台受限或通知被拒等场景可能抛 ForegroundServiceStartNotAllowedException；
        // 捕获后停止自身，避免 onCreate 抛异常导致整个进程闪退，下载队列仍由仓库在后台推进。
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
        }
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
        // 通知通道仅在 Android 8.0（API 26）及以上存在；较低版本仍可直接使用兼容通知。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "轻音下载", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "qingyin_downloads"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "im.molan.music.action.STOP_DOWNLOAD_SERVICE"

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
