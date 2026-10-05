package com.otterhub.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.otterhub.app.databinding.ActivityShareBinding
import java.io.File

/**
 * 接收系统「发送/分享」。
 * 分享给的 content:// 读取授权只在本 Activity 存活期间有效，一 finish() 就被回收，
 * 所以必须在这里把内容复制进应用缓存，再把本地文件交给上传服务。
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uris = collectUris()
        val text = if (uris.isEmpty()) intent?.getStringExtra(Intent.EXTRA_TEXT) else null
        if (uris.isEmpty() && text.isNullOrBlank()) {
            toast(getString(R.string.toast_no_file))
            finish()
            return
        }
        if (!Config.isConfigured(this)) {
            toast(getString(R.string.not_configured))
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        binding.shareText.setText(R.string.share_preparing)
        Thread { handle(uris, text) }.start()
    }

    private fun handle(uris: List<Uri>, text: String?) {
        val dir = UploadSource.newBatchDir(this)
        val ready = ArrayList<String>()
        val unreadable = ArrayList<Uri>()

        uris.forEachIndexed { position, uri ->
            val index = position + 1
            val name = runCatching { UploadSource.displayNameOf(this, uri) }
                .getOrNull() ?: "shared_${System.currentTimeMillis()}"
            label(index, uris.size, name)
            val copied = runCatching { UploadSource.copyInto(this, dir, uri, name) }
            copied.fold(
                onSuccess = { ready.add(it.absolutePath) },
                onFailure = {
                    label(index, uris.size, it.message ?: "读取失败")
                    unreadable.add(uri)
                }
            )
        }

        if (!text.isNullOrBlank()) {
            label(uris.size + 1, uris.size + 1, "分享文本")
            val target = runCatching {
                File(dir, "shared_text_${System.currentTimeMillis()}.txt").apply { writeText(text) }
            }.getOrNull()
            target?.let { ready.add(it.absolutePath) }
        }

        if (ready.isNotEmpty()) UploadService.enqueueFiles(this, ready)
        if (unreadable.isNotEmpty()) UploadService.enqueue(this, unreadable)

        runOnUiThread {
            toast(
                when {
                    ready.isNotEmpty() && unreadable.isEmpty() -> getString(R.string.toast_queued)
                    ready.isNotEmpty() -> getString(R.string.share_partial, ready.size, unreadable.size)
                    else -> getString(R.string.share_failed)
                }
            )
            finish()
        }
    }

    private fun label(index: Int, total: Int, name: String) {
        runOnUiThread {
            binding.shareProgress.max = total.coerceAtLeast(1)
            binding.shareProgress.progress = index
            binding.shareText.text = getString(R.string.share_copying, index, total, name)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectUris(): List<Uri> {
        val result = ArrayList<Uri>()
        val action = intent?.action ?: return result
        val bundle = intent.extras ?: return result
        if (action == Intent.ACTION_SEND) {
            // 用 Bundle.get 取，避免某些应用把 String 塞进 EXTRA_STREAM 时强转崩溃
            streamUri(runCatching { bundle.get(Intent.EXTRA_STREAM) }.getOrNull())?.let { result.add(it) }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val raw = runCatching { bundle[Intent.EXTRA_STREAM] as? List<*> }.getOrNull().orEmpty()
            raw.forEach { item -> streamUri(item)?.let { result.add(it) } }
        }
        return result.distinct()
    }

    /** 有些文件管理器直接给 file:// 路径，ContentResolver 同样能读 */
    private fun streamUri(raw: Any?): Uri? = when (raw) {
        is Uri -> raw
        is String -> runCatching { Uri.parse(raw) }.getOrNull()
        else -> null
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
