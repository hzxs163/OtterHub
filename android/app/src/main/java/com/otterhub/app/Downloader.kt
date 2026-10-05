package com.otterhub.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.Response
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/** 网页里点下载时把字节落到手机下载目录，私有文件同样带上认证头 */
object Downloader {

    fun start(
        ctx: Context,
        api: OtterApi,
        url: String,
        suggestedName: String,
        onResult: (String) -> Unit,
    ) {
        Thread {
            val message = runCatching { save(ctx, api, url, suggestedName) }.fold(
                onSuccess = { ctx.getString(R.string.download_done, it) },
                onFailure = { ctx.getString(R.string.download_failed) + "：" + (it.message ?: "") }
            )
            onResult(message)
        }.start()
    }

    private fun save(ctx: Context, api: OtterApi, url: String, suggestedName: String): String {
        val first = execute(api, url)
        if (first.code == 401) {
            first.close()
            api.invalidateSession()
            return save(ctx, api, url, suggestedName)
        }
        first.use { response ->
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}")
            val body = response.body ?: throw ApiException("服务端没有返回内容")
            val name = fileName(url, response.header("Content-Disposition"), suggestedName)
            val size = maxOf(body.contentLength(), response.header("Content-Length")?.toLongOrNull() ?: -1L)
            val mime = response.header("Content-Type").orEmpty().substringBefore(';')
                .ifEmpty { "application/octet-stream" }
            body.byteStream().use { input -> write(ctx, name, size, mime, input) }
        }
    }

    private fun execute(api: OtterApi, url: String): Response =
        api.httpClient().newCall(api.authedRequest(url)).execute()

    private fun write(ctx: Context, name: String, size: Long, mime: String, input: InputStream): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStore(ctx, name, size, mime, input)
        } else {
            writeToAppDir(ctx, name, input)
        }

    @SuppressLint("NewApi")
    private fun writeToMediaStore(
        ctx: Context,
        name: String,
        size: Long,
        mime: String,
        input: InputStream,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            if (size > 0L) put(MediaStore.Downloads.SIZE, size)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/OtterHub")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException("无法在下载目录创建文件")
        try {
            resolver.openOutputStream(target)?.use { input.copyTo(it) }
                ?: throw ApiException("无法写入下载文件")
        } finally {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/OtterHub/$name"
    }

    private fun writeToAppDir(ctx: Context, name: String, input: InputStream): String {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OtterHub")
        if (!dir.isDirectory) dir.mkdirs()
        val file = uniqueFile(dir, name)
        file.outputStream().use { input.copyTo(it) }
        return file.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var index = 0
        while (index < 100) {
            val candidate = if (index == 0) File(dir, name) else File(dir, numbered(name, index))
            if (!candidate.exists()) return candidate
            index++
        }
        return File(dir, "${System.currentTimeMillis()}_$name")
    }

    private fun numbered(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name($index)" else "${name.take(dot)}($index)${name.substring(dot)}"
    }

    private fun fileName(url: String, disposition: String?, fallback: String): String {
        val candidates = listOfNotNull(
            parameter(disposition, "filename\\*\\s*=\\s*([^;]+)", decode = true),
            parameter(disposition, "filename\\s*=\\s*\"?([^\";]+)\"?", decode = false),
            fallback.trim(),
            url.substringBefore('?').substringAfterLast('/').trim(),
        ).filter { it.isNotEmpty() }
        return sanitize(candidates.firstOrNull() ?: "download")
    }

    private fun parameter(header: String?, pattern: String, decode: Boolean): String? {
        if (header == null) return null
        val raw = Regex(pattern, RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim() ?: return null
        if (!decode) return raw
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    /** key 里带冒号等文件系统非法字符，落到文件名前先清洗 */
    private fun sanitize(raw: String): String {
        val cleaned = raw.replace('/', '_').replace('\\', '_').replace(':', '_').trim()
        if (cleaned.isEmpty()) return "download"
        if (cleaned.length <= 120) return cleaned
        val dot = cleaned.lastIndexOf('.')
        return if (dot > 0) cleaned.take(110) + cleaned.substring(dot).take(10) else cleaned.take(120)
    }
}
