package com.otterhub.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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

    fun release() {
        cacheFile?.delete()
    }

    companion object {
        /** Telegram 后端单个分片上限，与 shared/src/types/index.ts 的 MAX_CHUNK_SIZE 一致 */
        const val MAX_CHUNK_SIZE = 20L * 1024 * 1024
        const val MAX_CHUNK_NUM = 50L

        fun from(ctx: Context, uri: Uri): UploadSource {
            var name = queryName(ctx, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload_${System.currentTimeMillis()}"
            name = name.replace('/', '_').take(120)
            var mime = ctx.contentResolver.getType(uri)
            var size = querySize(ctx, uri)

            if (size <= 0L) size = descriptorSize(ctx, uri)

            return if (size > 0L) {
                UploadSource(ctx, name, size, mime, uri, null)
            } else {
                // 少数分享来源（如某些云盘）流长度未知，先落到缓存文件再上传
                val file = copyToCache(ctx, uri, name)
                if (mime == null) mime = "application/octet-stream"
                UploadSource(ctx, file.name, file.length(), mime, uri, file)
            }
        }

        fun fromFile(ctx: Context, file: File): UploadSource =
            UploadSource(ctx, file.name, file.length(), guessMime(file.name), null, file)

        private fun copyToCache(ctx: Context, uri: Uri, name: String): File {
            val dir = File(ctx.cacheDir, "pending").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ApiException("无法读取分享的文件")
            return target
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
            return android.webkit.MimeTypeMap.getMimeTypeFromExtension(ext.lowercase())
        }
    }
}

/** 网盘文件行；/file/list 走 KV list，不含 metadata，因此显示信息从 key 推导 */
data class FileRow(
    val key: String,
    val displayName: String,
    val type: String,
    val size: Long,
    val uploadedAt: Long,
) {
    val isTrash: Boolean get() = key.startsWith("trash:")
}

object KeyParser {
    fun parse(name: String): FileRow {
        val isTrash = name.startsWith("trash:")
        val rest = if (isTrash) name.removePrefix("trash:") else name
        val headType = rest.substringBefore(':', "doc")
        val type = when (headType) {
            "img", "audio", "video", "doc" -> headType
            else -> "doc"
        }
        val ext = rest.substringAfterLast('.', "")
        val fileName = rest.substringAfter(':', rest)
        val display = if (ext.isEmpty()) fileName else fileName.substringBeforeLast('.') + "." + ext
        return FileRow(name, display, type, -1L, 0L)
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
