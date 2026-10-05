package com.otterhub.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import kotlin.math.ceil

/** 待上传文件：名字、大小、MIME 与可重复打开的数据流 */
class UploadSource private constructor(
    private val ctx: Context,
    val name: String,
    val size: Long,
    val mime: String?,
    private val uri: Uri?,
    private val cacheFile: File?,
) {
    fun open(): InputStream =
        cacheFile?.inputStream()
            ?: ctx.contentResolver.openInputStream(uri!!)
            ?: throw ApiException("无法读取文件：$name")

    /** 复制出来的临时文件用完即删，顺带清掉空的批次目录 */
    fun release() {
        val file = cacheFile ?: return
        if (!file.delete()) return
        file.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    }

    companion object {
        /** Telegram 后端单个分片上限，与 shared/src/types/index.ts 的 MAX_CHUNK_SIZE 一致 */
        const val MAX_CHUNK_SIZE = 20L * 1024 * 1024
        const val MAX_CHUNK_NUM = 50L

        private const val SHARED_DIR = "shared"
        private const val STALE_MILLIS = 24L * 60 * 60 * 1000

        fun from(ctx: Context, uri: Uri): UploadSource {
            val name = displayNameOf(ctx, uri)
            val mime = ctx.contentResolver.getType(uri)
            var size = querySize(ctx, uri)
            if (size <= 0L) size = descriptorSize(ctx, uri)

            return if (size > 0L) {
                UploadSource(ctx, name, size, mime, uri, null)
            } else {
                // 少数来源（某些云盘/即时通讯）流长度未知，先落到缓存文件再上传
                val file = copyInto(ctx, newBatchDir(ctx), uri, name)
                UploadSource(ctx, file.name, file.length(), mime ?: "application/octet-stream", uri, file)
            }
        }

        fun fromFile(ctx: Context, file: File): UploadSource =
            UploadSource(ctx, file.name, file.length(), guessMime(file.name), null, file)

        /** 分享入口在授权还有效时把内容复制成本地文件，返回可直接上传的路径 */
        fun newBatchDir(ctx: Context): File =
            File(ctx.cacheDir, "$SHARED_DIR/${System.currentTimeMillis()}").apply { mkdirs() }

        fun displayNameOf(ctx: Context, uri: Uri): String {
            val raw = queryName(ctx, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "upload_${System.currentTimeMillis()}"
            return ensureExtension(sanitize(raw), ctx.contentResolver.getType(uri))
        }

        fun copyInto(ctx: Context, dir: File, uri: Uri, name: String): File {
            val target = uniqueTarget(dir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ApiException("无法读取文件，分享授权可能已失效")
            return target
        }

        /** 同一次分享里可能有两个同名文件，别互相覆盖 */
        private fun uniqueTarget(dir: File, name: String): File {
            val ext = name.substringAfterLast('.', "")
            val stem = if (ext.isEmpty()) name else name.substringBeforeLast('.')
            var candidate = File(dir, name)
            var seq = 1
            while (candidate.exists() && seq < 100) {
                candidate = File(dir, if (ext.isEmpty()) "$stem-$seq" else "$stem-$seq.$ext")
                seq++
            }
            return candidate
        }

        /** 崩溃或中断留下的临时副本，超过一天就清掉 */
        fun purgeStale(ctx: Context) {
            val root = File(ctx.cacheDir, SHARED_DIR)
            if (!root.isDirectory) return
            val deadline = System.currentTimeMillis() - STALE_MILLIS
            root.listFiles()?.forEach { batch ->
                if (batch.lastModified() < deadline) batch.deleteRecursively()
            }
        }

        /** 网盘靠扩展名判断类型与 Content-Type，缺扩展名会让预览失效 */
        private fun ensureExtension(name: String, mime: String?): String {
            if (name.substringAfterLast('.', "").isNotEmpty()) return name
            val ext = mime?.substringBefore(';')?.trim()?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            return if (ext.isNullOrEmpty()) "$name.bin" else "$name.$ext"
        }

        private fun sanitize(raw: String): String {
            val cleaned = raw.replace('/', '_').replace('\\', '_').trim().ifEmpty { "upload" }
            if (cleaned.length <= 120) return cleaned
            val ext = cleaned.substringAfterLast('.', "")
            return if (ext.isEmpty() || ext.length > 8) cleaned.take(120)
            else cleaned.take(120 - ext.length - 1) + "." + ext
        }

        private fun queryName(ctx: Context, uri: Uri): String? {
            var cursor: Cursor? = null
            try {
                cursor = ctx.contentResolver.query(uri, null, null, null, null)
                val idx = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
                if (cursor != null && idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
            } catch (_: Exception) {
            } finally {
                cursor?.close()
            }
            return null
        }

        private fun querySize(ctx: Context, uri: Uri): Long {
            var cursor: Cursor? = null
            try {
                cursor = ctx.contentResolver.query(uri, null, null, null, null)
                val idx = cursor?.getColumnIndex(OpenableColumns.SIZE) ?: -1
                if (cursor != null && idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    return cursor.getLong(idx)
                }
            } catch (_: Exception) {
            } finally {
                cursor?.close()
            }
            return -1L
        }

        private fun descriptorSize(ctx: Context, uri: Uri): Long = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }

        fun guessMime(name: String): String? {
            val ext = name.substringAfterLast('.', "")
            if (ext.isEmpty()) return null
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        }
    }
}

/** 网盘文件行；/file/list 的每个 key 都带 KV metadata（fileName/fileSize/uploadedAt/tags） */
data class FileRow(
    val key: String,
    val displayName: String,
    val type: String,
    val size: Long,
    val uploadedAt: Long,
    val isPrivate: Boolean,
) {
    val isTrash: Boolean get() = key.startsWith("trash:")
}

object KeyParser {
    fun parse(name: String, meta: JSONObject? = null): FileRow {
        val rest = if (name.startsWith("trash:")) name.removePrefix("trash:") else name
        val headType = rest.substringBefore(':', "doc")
        val type = when (headType) {
            "img", "video", "audio", "doc" -> headType
            else -> "doc"
        }
        val stored = meta?.optString("fileName")?.takeIf { it.isNotEmpty() && it != "null" }
        val fileName = stored ?: rest.substringAfter(':', rest)
        val size = meta?.optLong("fileSize")?.takeIf { it > 0L } ?: -1L
        val uploadedAt = meta?.optLong("uploadedAt")?.takeIf { it > 0L } ?: 0L
        val tags = meta?.optJSONArray("tags")
        val isPrivate = tags != null && (0 until tags.length()).any { tags.optString(it) == "private" }
        return FileRow(name, fileName, type, size, uploadedAt, isPrivate)
    }

    fun fileTypeOf(name: String, mime: String?): String {
        val resolved = mime ?: UploadSource.guessMime(name) ?: ""
        return when {
            resolved.startsWith("image/") -> "img"
            resolved.startsWith("video/") -> "video"
            resolved.startsWith("audio/") -> "audio"
            else -> "doc"
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "未知大小"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun chunkSizeFor(size: Long): Long {
    val minNeeded = ceil(size.toDouble() / UploadSource.MAX_CHUNK_NUM).toLong()
    return maxOf(UploadSource.MAX_CHUNK_SIZE, minNeeded)
}
