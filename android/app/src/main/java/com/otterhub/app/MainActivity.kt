package com.otterhub.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.otterhub.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileListAdapter
    private val api by lazy { OtterApi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var watchedRevision = -1L
    private var loadedAt = 0L

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        UploadService.enqueue(this, uris)
        toast(getString(R.string.toast_queued))
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val poller = object : Runnable {
        override fun run() {
            if (UploadState.revision != watchedRevision) {
                watchedRevision = UploadState.revision
                renderStatus()
                if (!UploadState.running) reloadSoon()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = FileListAdapter(
            onClick = { row -> openInBrowser(if (row.isTrash) api.trashUrl(row.key) else api.previewUrl(row.key)) },
            onLongClick = { row -> showActions(row) }
        )
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadFiles() }
        binding.filterChips.setOnCheckedStateChangeListener { _, _ -> loadFiles() }
        binding.fabUpload.setOnClickListener {
            if (!ensureConfigured()) return@setOnClickListener
            picker.launch(arrayOf("*/*"))
        }

        requestNotificationPermission()
        if (!Config.isConfigured(this)) toast(getString(R.string.not_configured))
        loadFiles()
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
        if (System.currentTimeMillis() - loadedAt > 3000) loadFiles()
        renderStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menuRefresh -> {
            loadFiles()
            true
        }
        R.id.menuSettings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun selectedType(): String? = when (binding.filterChips.checkedChipId) {
        R.id.chipImg -> "img"
        R.id.chipVideo -> "video"
        R.id.chipAudio -> "audio"
        R.id.chipDoc -> "doc"
        R.id.chipTrash -> "trash"
        else -> null
    }

    private fun reloadSoon() {
        handler.postDelayed({ loadFiles() }, 1500)
    }

    private fun loadFiles() {
        if (!Config.isConfigured(this)) {
            binding.swipeRefresh.isRefreshing = false
            binding.emptyText.visibility = android.view.View.VISIBLE
            return
        }
        binding.swipeRefresh.isRefreshing = true
        val type = selectedType()
        Thread {
            val result = runCatching { api.list(type) }
            handler.post {
                binding.swipeRefresh.isRefreshing = false
                loadedAt = System.currentTimeMillis()
                result.fold(
                    onSuccess = { rows ->
                        adapter.submit(rows)
                        binding.emptyText.visibility =
                            if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    },
                    onFailure = { error ->
                        binding.emptyText.visibility = android.view.View.VISIBLE
                        toast(error.message ?: "加载失败")
                    }
                )
            }
        }.start()
    }

    private fun showActions(row: FileRow) {
        val options = mutableListOf(
            getString(R.string.menu_open),
            getString(R.string.menu_copy_link),
            getString(R.string.menu_download)
        )
        if (row.isTrash) {
            options.add(getString(R.string.menu_restore))
        } else {
            options.add(getString(R.string.menu_trash))
        }
        options.add(getString(R.string.menu_delete))

        MaterialAlertDialogBuilder(this)
            .setTitle(row.displayName)
            .setItems(options.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                handleAction(row, options[which])
            }
            .show()
    }

    private fun handleAction(row: FileRow, action: String) {
        val viewUrl = if (row.isTrash) api.trashUrl(row.key) else api.previewUrl(row.key)
        when (action) {
            getString(R.string.menu_open) -> openInBrowser(viewUrl)

            getString(R.string.menu_copy_link) -> {
                val manager = getSystemService(ClipboardManager::class.java)
                manager.setPrimaryClip(ClipData.newPlainText("OtterHub", viewUrl))
                toast(getString(R.string.toast_copied))
            }

            getString(R.string.menu_download) -> openInBrowser(
                if (row.isTrash) viewUrl else api.downloadUrl(row.key)
            )

            getString(R.string.menu_trash) -> confirm(
                getString(R.string.confirm_trash_title),
                getString(R.string.confirm_trash_msg, row.displayName)
            ) { api.moveToTrash(row.key) }

            getString(R.string.menu_restore) -> confirm(
                getString(R.string.confirm_restore_title),
                row.displayName
            ) { api.restore(row.key) }

            getString(R.string.menu_delete) -> confirm(
                getString(R.string.confirm_delete_title),
                getString(R.string.confirm_delete_msg, row.displayName)
            ) { api.deleteForever(row.key) }
        }
    }

    private fun confirm(title: String, message: String, run: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.dialog_cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                Thread {
                    val result = runCatching(run)
                    handler.post {
                        result.fold(
                            onSuccess = {
                                toast(getString(R.string.op_done))
                                loadFiles()
                            },
                            onFailure = { toast(it.message ?: "操作失败") }
                        )
                    }
                }.start()
            }
            .show()
    }

    private fun openInBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("无法打开：$url") }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun renderStatus() {
        binding.statusText.text = UploadState.describe(this)
        binding.statusProgress.visibility = if (UploadState.running) android.view.View.VISIBLE else android.view.View.GONE
        if (UploadState.running) binding.statusProgress.progress = UploadState.percent
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
