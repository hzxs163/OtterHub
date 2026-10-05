package com.otterhub.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.otterhub.app.databinding.ActivityMainBinding

/**
 * 主界面直接渲染网盘网页，搜索、筛选、排序、三种视图与网页移动端完全一致；
 * 原生侧负责登录态注入、系统文件选择器、下载落盘和上传进度提示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val api by lazy { OtterApi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var watchedRevision = -1L
    private var retriedLogin = false
    private var loadedUrl = ""
    private var chooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = chooserCallback
            chooserCallback = null
            val picked = result.data
                ?.let { WebChromeClient.FileChooserParams.parseResult(result.resultCode, it) }
                ?.takeIf { it.isNotEmpty() }
                ?.filter { keepReadable(it) }
            callback?.onReceiveValue(picked?.toTypedArray())
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 网页在后台会被系统限速甚至挂起，大文件仍建议走这条原生前台服务队列 */
    private val nativeUpload =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            uris.forEach { keepReadable(it) }
            UploadService.enqueue(this, uris)
            toast(getString(R.string.toast_queued))
        }

    private val poller = object : Runnable {
        override fun run() {
            if (UploadState.revision != watchedRevision) {
                val wasTracked = watchedRevision >= 0L
                watchedRevision = UploadState.revision
                renderStatus()
                if (wasTracked && !UploadState.running) reloadContent()
            }
            handler.postDelayed(this, 600)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        binding.web.addJavascriptInterface(ClipboardBridge(), "OtterHubBridge")
        binding.web.webViewClient = siteClient()
        binding.web.webChromeClient = siteChrome()
        binding.web.setDownloadListener { url, _, contentDisposition, _, _ ->
            startDownload(url, contentDisposition)
        }
        binding.errorText.setOnClickListener { loadPage() }
        binding.fabMenu.setOnClickListener { showMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.web.canGoBack()) binding.web.goBack() else finish()
            }
        })

        requestNotificationPermission()
        warmUpSession()
        loadPage()
    }

    private fun siteClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.isSitePage()) return false
            openExternally(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.errorText.visibility = View.GONE
            binding.web.visibility = View.VISIBLE
            binding.pageProgress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.pageProgress.visibility = View.GONE
            val loaded = url ?: return
            if (!loaded.toUri().isSitePage()) return
            enableClipboard()
            recoverFromLogin(loaded)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) showError(error.description?.toString().orEmpty())
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            val hint = when (errorResponse.statusCode) {
                401, 403 -> getString(R.string.web_need_login)
                404 -> getString(R.string.web_not_found)
                else -> "HTTP " + errorResponse.statusCode
            }
            showError(hint)
        }
    }

    private fun siteChrome(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.pageProgress.progress = newProgress
            if (newProgress >= 100) binding.pageProgress.visibility = View.GONE
        }

        /** 网页里的文件选择框交给系统文档选择器，放开多选和全部类型 */
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams,
        ): Boolean {
            chooserCallback?.onReceiveValue(null)
            chooserCallback = callback
            val type = params.acceptType?.takeIf { it.isNotBlank() } ?: "*/*"
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val started = runCatching { fileChooser.launch(intent) }.isSuccess
            if (!started) {
                chooserCallback = null
                callback.onReceiveValue(null)
                toast(getString(R.string.web_picker_failed))
            }
            return started
        }

        /** 网页用 window.open 打开预览，接住弹窗后转成应用内预览页 */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                var captured: String? = null

                fun take(url: String?) {
                    if (captured != null || url.isNullOrEmpty() || url.startsWith("about:")) return
                    captured = url
                    openInsideApp(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    take(request.url.toString())
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    take(url)
                }
            }
            transport.setWebView(popup)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread { request.deny() }
        }
    }

    private fun enableClipboard() {
        binding.web.evaluateJavascript(CLIPBOARD_SHIM, null)
    }

    /** 服务端 401 时网页会跳到 /login，这里用配置的密码补一次登录再回原页 */
    private fun recoverFromLogin(url: String) {
        val uri = url.toUri()
        if (!uri.path.orEmpty().startsWith("/login")) {
            retriedLogin = false
            return
        }
        if (retriedLogin || Config.password(this).isEmpty()) return
        retriedLogin = true
        val redirect = uri.getQueryParameter("redirect")
            ?.takeIf { it.startsWith("/") || it.startsWith(Config.baseUrl(this)) }
            ?: "/"
        val target = if (redirect.startsWith("http")) redirect else Config.baseUrl(this) + redirect
        Config.clearJwt(this)
        toast(getString(R.string.web_relogin))
        Thread {
            val jwt = runCatching { api.sessionJwt() }.getOrNull()
            handler.post {
                if (isFinishing) return@post
                applyCookie(target, jwt)
                loadedUrl = target
                binding.web.loadUrl(target)
            }
        }.start()
    }

    private fun loadPage() {
        binding.errorText.visibility = View.GONE
        binding.pageProgress.visibility = View.VISIBLE
        retriedLogin = false
        if (!Config.isConfigured(this)) {
            showSetupHint()
            return
        }
        binding.web.visibility = View.VISIBLE
        val url = Config.baseUrl(this) + "/"
        Thread {
            val jwt = runCatching { api.sessionJwt() }.getOrNull()
            applyCookie(url, jwt)
            handler.post {
                if (isFinishing) return@post
                loadedUrl = url
                binding.web.loadUrl(url)
            }
        }.start()
    }

    private fun showSetupHint() {
        binding.pageProgress.visibility = View.GONE
        binding.web.visibility = View.GONE
        binding.errorText.setText(R.string.tap_to_setup)
        binding.errorText.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.errorText.visibility = View.VISIBLE
    }

    private fun applyCookie(url: String, jwt: String?) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        if (jwt != null) manager.setCookie(url, "auth=$jwt; path=/")
        manager.flush()
    }

    /** 上传批次结束后刷新当前网页，新文件才会出现在列表里 */
    private fun reloadContent() {
        handler.postDelayed({
            if (!isFinishing && loadedUrl.isNotEmpty()) binding.web.reload()
        }, 800)
    }

    private fun startDownload(url: String, contentDisposition: String?) {
        val uri = url.toUri()
        if (!uri.isSitePage()) {
            openExternally(uri)
            return
        }
        val name = displayNameOf(uri)
        if (name.isNotEmpty()) toast(getString(R.string.web_download_started, name))
        Downloader.start(this, api, url, name) { message ->
            handler.post { toast(message) }
        }
    }

    private fun openInsideApp(url: String) {
        val uri = url.toUri()
        if (!uri.isSitePage()) {
            openExternally(uri)
            return
        }
        startActivity(
            Intent(this, PreviewActivity::class.java)
                .putExtra(PreviewActivity.EXTRA_URL, url)
                .putExtra(PreviewActivity.EXTRA_NAME, displayNameOf(uri))
        )
    }

    /** 下载链接的末段是 download，真正的文件名藏在 key 段里 */
    private fun displayNameOf(uri: Uri): String {
        val segments = uri.pathSegments
        val pick = segments.lastOrNull { it != "download" && it != "file" } ?: return ""
        val decoded = Uri.decode(pick)
        return decoded.substringAfter(':', decoded)
    }

    private fun isOwnHost(uri: Uri): Boolean {
        val host = Config.baseUrl(this).toUri().host ?: return false
        return uri.host == host && (uri.scheme == "https" || uri.scheme == "http")
    }

    private fun Uri.isSitePage(): Boolean = isOwnHost(this)

    private fun openExternally(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { toast(getString(R.string.preview_no_browser)) }
    }

    /** 临时授权可能被回收，能持久化就持久化，让 WebView 读完整个文件 */
    private fun keepReadable(uri: Uri): Boolean {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return true
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.web_menu_reload),
            getString(R.string.web_menu_native_upload),
            getString(R.string.web_menu_native),
            getString(R.string.web_menu_settings)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> loadPage()
                    1 -> nativeUpload.launch(arrayOf("*/*"))
                    2 -> startActivity(Intent(this, FilesActivity::class.java))
                    3 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun warmUpSession() {
        if (Config.password(this).isEmpty() || Config.cachedJwt(this) != null) return
        Thread { runCatching { api.signIn() } }.start()
    }

    override fun onStart() {
        super.onStart()
        handler.post(poller)
    }

    override fun onStop() {
        handler.removeCallbacks(poller)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        val jwt = Config.cachedJwt(this)
        if (jwt != null) applyCookie(Config.baseUrl(this), jwt)
        if (loadedUrl.isNotEmpty() && !loadedUrl.startsWith(Config.baseUrl(this))) loadPage()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        chooserCallback?.onReceiveValue(null)
        chooserCallback = null
        binding.web.destroy()
        super.onDestroy()
    }

    private fun renderStatus() {
        binding.statusPanel.visibility = if (UploadState.running) View.VISIBLE else View.GONE
        binding.statusText.text = UploadState.describe(this)
        if (UploadState.running) binding.statusProgress.progress = UploadState.percent
    }

    private fun showError(message: String) {
        handler.post {
            if (isFinishing) return@post
            binding.pageProgress.visibility = View.GONE
            binding.web.visibility = View.GONE
            binding.errorText.text = getString(R.string.web_failed, message.ifEmpty { "unknown" })
            binding.errorText.setOnClickListener { loadPage() }
            binding.errorText.visibility = View.VISIBLE
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    inner class ClipboardBridge {
        @JavascriptInterface
        fun copy(text: String) {
            val manager = getSystemService(ClipboardManager::class.java)
            manager?.setPrimaryClip(ClipData.newPlainText("OtterHub", text))
            handler.post { toast(getString(R.string.toast_copied)) }
        }
    }

    companion object {
        private const val CLIPBOARD_SHIM =
            "if(window.OtterHubBridge){try{Object.defineProperty(navigator,'clipboard'," +
                "{configurable:true,value:{writeText:function(t){" +
                "window.OtterHubBridge.copy(String(t));return Promise.resolve();}}});}catch(e){}}"
    }
}
