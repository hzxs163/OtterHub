package com.otterhub.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 接收系统「发送/分享」，把文件交给前台上传服务后立刻退出 */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = collectUris()

        if (uris.isEmpty()) {
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

        UploadService.enqueue(this, uris)
        toast(getString(R.string.toast_queued))
        finish()
    }

    private fun collectUris(): List<Uri> {
        val result = ArrayList<Uri>()
        val action = intent?.action ?: return result
        if (action == Intent.ACTION_SEND) {
            streamUri(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { result.add(it) }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val extras = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            extras?.forEach { uri -> streamUri(uri)?.let { result.add(it) } }
        }
        return result.distinct()
    }

    /** 有些文件管理器直接给 file:// 路径，ContentResolver 同样能读 */
    private fun streamUri(raw: Any?): Uri? = when (raw) {
        is Uri -> raw
        is String -> Uri.parse(raw)
        else -> null
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
