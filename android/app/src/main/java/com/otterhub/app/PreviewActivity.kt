package com.otterhub.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.otterhub.app.databinding.ActivityPreviewBinding

/** 应用内预览：WebView 直接加载 /file/{key}，私有文件靠注入 auth Cookie 通过校验 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val api by lazy { OtterApi(this) }
    private var key = ""
    private var trash = false
    private var targetUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        trash = intent.getBooleanExtra(EXTRA_TRASH, false)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()

        binding.toolbar.title = name.ifEmpty { getString(R.string.preview_title) }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menuPreviewReload -> {
                    load()
                    true
                }

                R.id.menuPreviewExternal -> {
                    openExternally()
                    true
                }

                else -> false
            }
        }
        binding.errorText.setOnClickListener { load() }

        binding.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (binding.errorText.visibility != View.VISIBLE) binding.progress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(error.description?.toString().orEmpty())
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                val hint = when (errorResponse.statusCode) {
                    401, 403 -> getString(R.string.preview_need_login)
                    404 -> getString(R.string.preview_missing)
                    else -> "HTTP ${errorResponse.statusCode}"
                }
                showError(hint)
            }
        }
        binding.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
            }
        }
        // zip / 安装包这类浏览器才处理得了的内容，交回系统打开
        binding.web.setDownloadListener { url, _, _, _, _ -> openInBrowser(url) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.web.canGoBack()) binding.web.goBack() else finish()
            }
        })

        load()
    }

    private fun load() {
        if (key.isEmpty()) {
            showError(getString(R.string.preview_no_key))
            return
        }
        binding.errorText.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.web.visibility = View.INVISIBLE
        Thread {
            val jwt = runCatching { api.sessionJwt() }.getOrNull()
            val url = if (trash) api.trashUrl(key) else api.previewUrl(key)
            if (jwt != null) {
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setCookie(url, "auth=$jwt; path=/")
                cookies.flush()
            }
            targetUrl = url
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.web.visibility = View.VISIBLE
                binding.web.loadUrl(url)
            }
        }.start()
    }

    private fun showError(message: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            binding.progress.visibility = View.GONE
            binding.web.visibility = View.GONE
            binding.errorText.text = getString(R.string.preview_failed, message.ifEmpty { "unknown" })
            binding.errorText.visibility = View.VISIBLE
        }
    }

    private fun openExternally() {
        val url = targetUrl.ifEmpty { if (trash) api.trashUrl(key) else api.previewUrl(key) }
        openInBrowser(url)
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }.onFailure { showError(getString(R.string.preview_no_browser)) }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_NAME = "name"
        const val EXTRA_TRASH = "trash"
    }
}
