package com.otterhub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
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

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastActivityAt = 0L

    @Volatile
    private var stalled = false

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
        if (batchStarted) {
            UploadState.beginBatch(jobs.size)
            lastActivityAt = System.currentTimeMillis()
        } else {
            UploadState.totalItems += jobs.size
        }
        pending.addAll(jobs)

        if (batchStarted) {
            worker = Thread { runQueue() }.apply { start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        worker?.interrupt()
        super.onDestroy()
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
            touch()
        }

        wakeLock = BackgroundGuard.acquireWakeLock(this)
        val monitor = Thread { watchForFreeze() }.apply { start() }
        try {
            drainQueue(api)
        } finally {
            monitor.interrupt()
            runCatching { wakeLock?.release() }
            wakeLock = null
            stalled = false
            UploadState.endBatch()
            publish()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun drainQueue(api: OtterApi) {
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
                        touch()
                    }
                }
                UploadState.ok(created.name)
            } catch (error: Exception) {
                UploadState.fail(job.label, error.message)
            } finally {
                source?.release()
                touch()
                publish()
            }
        }
    }

    /**
     * 熄屏后系统可能把进程整个冻结，socket 不再有任何字节进出。
     * 卡住时在通知里说明原因，并让点击直达后台设置，而不是让用户以为还在传。
     */
    private fun watchForFreeze() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(STALL_CHECK_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
            val quiet = System.currentTimeMillis() - lastActivityAt
            // 队列空转时没字节是正常的，那会儿不算冻结
            val frozen = UploadState.running && pending.isEmpty() && quiet > STALL_MILLIS
            if (frozen != stalled) {
                stalled = frozen
                publish()
            }
        }
    }

    private fun touch() {
        lastActivityAt = System.currentTimeMillis()
        if (stalled) {
            stalled = false
            publish()
        }
    }

    private fun publish() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(UploadState.describe(this), UploadState.percent, UploadState.running))
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): android.app.Notification {
        val shown = if (stalled) "$text\n${getString(R.string.upload_stalled)}" else text
        val contentIntent = if (stalled) {
            android.app.PendingIntent.getActivity(
                this,
                REQUEST_BACKGROUND,
                Intent(this, SettingsActivity::class.java).putExtra(EXTRA_OPEN_BACKGROUND, true),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(shown)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
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
        private const val STALL_CHECK_MILLIS = 5_000L
        private const val STALL_MILLIS = 30_000L
        private const val REQUEST_BACKGROUND = 2002

        /** 设置页被通知点击唤起时，直接跳到后台权限设置 */
        const val EXTRA_OPEN_BACKGROUND = "open_background"

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
