package com.otterhub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue

class UploadService : Service() {

    private val pending = ConcurrentLinkedQueue<Uri>()
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = intent?.extras?.let { bundle ->
            runCatching { bundle.getParcelableArrayList<Uri>(EXTRA_URIS) }.getOrNull()
        }.orEmpty()

        if (uris.isEmpty()) {
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(getString(R.string.idle), 0, ongoing = true))

        val batchStarted = worker?.isAlive != true
        if (batchStarted) UploadState.beginBatch(uris.size) else UploadState.totalItems += uris.size
        pending.addAll(uris)

        if (batchStarted) {
            worker = Thread { runQueue() }.apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        val api = OtterApi(this)
        api.onMergeProgress = { done, total ->
            UploadState.setMerge(done, total)
            publish()
        }

        var index = 0
        var idleRounds = 0
        while (true) {
            val uri = pending.poll()
            if (uri == null) {
                // 稍等几轮，让并发的 onStartCommand 有机会加入新任务
                if (++idleRounds > 6) break
                Thread.sleep(500)
                continue
            }
            idleRounds = 0
            index++
            val hint = uri.lastPathSegment ?: "文件"
            UploadState.startItem(hint, index)
            publish()

            var source: UploadSource? = null
            try {
                val created = UploadSource.from(this, uri)
                source = created
                UploadState.startItem(created.name, index)
                var lastPercent = -1
                api.upload(created) { bytes ->
                    val percent = if (created.size > 0) ((bytes * 100) / created.size).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        UploadState.setProgress(percent)
                        publish()
                    }
                }
                UploadState.ok(created.name)
            } catch (error: Exception) {
                UploadState.fail(hint, error.message)
            } finally {
                source?.release()
                publish()
            }
        }

        UploadState.endBatch()
        publish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(UploadState.describe(this), UploadState.percent, UploadState.running))
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): android.app.Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress, false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val CHANNEL_ID = "otterhub_upload"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_URIS = "uris"

        fun enqueue(ctx: Context, uris: List<Uri>) {
            val intent = Intent(ctx, UploadService::class.java)
            intent.putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
            ContextCompat.startForegroundService(ctx, intent)
        }
    }
}
