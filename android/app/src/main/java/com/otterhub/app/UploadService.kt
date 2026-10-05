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
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class UploadService : Service() {

    /** 队列项：本地文件（分享入口复制出来的），或选择器给的带持久授权的 Uri */
    private data class Job(val file: File?, val uri: Uri?, val label: String)

    private val pending = ConcurrentLinkedQueue<Job>()
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        UploadSource.purgeStale(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = readJobs(intent)

        if (jobs.isEmpty()) {
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(getString(R.string.idle), 0, ongoing = true))

        val batchStarted = worker?.isAlive != true
        if (batchStarted) UploadState.beginBatch(jobs.size) else UploadState.totalItems += jobs.size
        pending.addAll(jobs)

        if (batchStarted) {
            worker = Thread { runQueue() }.apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun readJobs(intent: Intent?): List<Job> {
        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val uris = intent?.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val jobs = ArrayList<Job>(paths.size + uris.size)
        paths.forEach { path -> jobs.add(Job(File(path), null, path.substringAfterLast('/'))) }
        uris.forEach { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
            jobs.add(Job(null, uri, uri.lastPathSegment ?: "文件"))
        }
        return jobs
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
            val job = pending.poll()
            if (job == null) {
                // 稍等几轮，让并发的 onStartCommand 有机会加入新任务
                if (++idleRounds > 6) break
                Thread.sleep(500)
                continue
            }
            idleRounds = 0
            index++
            UploadState.startItem(job.label, index)
            publish()

            var source: UploadSource? = null
            try {
                val created = job.file?.let { UploadSource.fromFile(this, it) }
                    ?: UploadSource.from(this, job.uri!!)
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
                UploadState.fail(job.label, error.message)
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
        private const val EXTRA_PATHS = "paths"

        fun enqueue(ctx: Context, uris: List<Uri>) {
            start(ctx, uris.map { it.toString() }, emptyList())
        }

        /** 分享入口复制好的本地文件，路径直接传给服务，避免 file:// 授权问题 */
        fun enqueueFiles(ctx: Context, paths: List<String>) {
            start(ctx, emptyList(), paths)
        }

        private fun start(ctx: Context, uris: List<String>, paths: List<String>) {
            val intent = Intent(ctx, UploadService::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
            ContextCompat.startForegroundService(ctx, intent)
        }
    }
}
