package com.otterhub.app

import android.content.Context
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ApiException(message: String) : Exception(message)

class OtterApi(private val ctx: Context) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val base: String get() = Config.baseUrl(ctx)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 服务端合并分片时的进度回调（已合并数, 总数） */
    var onMergeProgress: ((Int, Int) -> Unit)? = null

    // ---------------- 认证 ----------------

    /** 填了密码就以密码登录为准（自动获取并缓存 token）；只有密码为空时才用 API Token 框的内容 */
    private fun passwordMode(): Boolean = Config.password(ctx).isNotEmpty()

    /** 兼容他从浏览器复制的 `auth=eyJ...`：这种值只能当 Cookie 发，不能当 Bearer */
    private fun pastedCookie(): String? {
        val token = Config.apiToken(ctx)
        if (token.isEmpty()) return null
        val bare = token.removePrefix("auth=").trim()
        return if (bare.startsWith("eyJ")) bare else null
    }

    private fun login(): String {
        val password = Config.password(ctx)
        if (password.isEmpty()) throw ApiException("请先在设置里填写网盘密码")
        val body = JSONObject().put("password", password).toString().toRequestBody(jsonType)
        val request = Request.Builder().url("$base/auth/login").post(body).build()
        val response = http.newCall(request).execute()
        val status = response.code
        val text = response.use { it.body?.string().orEmpty() }
        if (status == 401) throw ApiException("访问密码不正确，服务端拒绝了登录请求")
        val parsed = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ApiException("登录响应不是 JSON（HTTP $status）：${text.take(120)}")
        if (!parsed.optBoolean("success", false)) {
            val reason = parsed.optString("message").takeIf { it.isNotEmpty() && it != "null" }
            throw ApiException(reason ?: "登录失败（HTTP $status）")
        }
        // /auth/login 返回 data = { token: "<JWT>" }
        val jwt = parsed.optJSONObject("data")?.optString("token").orEmpty()
        if (jwt.isEmpty() || jwt == "null") throw ApiException("登录成功但响应里没有 token")
        Config.storeJwt(ctx, jwt)
        return jwt
    }

    private fun applyAuth(builder: Request.Builder) {
        if (passwordMode()) {
            builder.header("Cookie", "auth=${Config.cachedJwt(ctx) ?: login()}")
            return
        }
        val pasted = pastedCookie()
        val token = Config.apiToken(ctx)
        when {
            pasted != null -> builder.header("Cookie", "auth=$pasted")
            token.isNotEmpty() -> builder.header("Authorization", "Bearer $token")
            else -> throw ApiException("请先在设置里填写网盘访问密码")
        }
    }

    private fun canRetryAuth(status: Int): Boolean {
        if (status != 401) return false
        if (passwordMode()) {
            Config.clearJwt(ctx)
            return true
        }
        if (pastedCookie() != null) {
            throw ApiException("从网页复制的 token 已过期（有效期 7 天），请在设置里改填访问密码")
        }
        throw ApiException("API Token 不被接受：网盘可能没有设置 API_TOKEN 环境变量，或该值不对")
    }

    // ---------------- 请求底座 ----------------

    private fun build(path: String, method: String, body: RequestBody?, attachAuth: Boolean): Request {
        val builder = Request.Builder().url(base + path)
        if (attachAuth) applyAuth(builder)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(body)
            else -> builder.post(body ?: FormBody.Builder().build())
        }
        return builder.build()
    }

    /** 401 时丢掉缓存的 JWT，重新登录并重放一次；请求体是可重复打开的流，重放安全 */
    private fun send(path: String, method: String, body: RequestBody?, attachAuth: Boolean = true): JSONObject {
        var response: Response = http.newCall(build(path, method, body, attachAuth)).execute()
        if (attachAuth && canRetryAuth(response.code)) {
            response.close()
            response = http.newCall(build(path, method, body, attachAuth = true)).execute()
        }
        val status = response.code
        val text = response.use { it.body?.string().orEmpty() }
        val parsed = runCatching { JSONObject(text) }.getOrNull()
        val message = parsed?.optString("message")?.takeIf { it.isNotEmpty() && it != "null" }
        val succeeded = status in 200..299 && parsed?.optBoolean("success", false) == true
        if (!succeeded || parsed == null) {
            throw ApiException(message ?: "HTTP $status ${text.take(120)}")
        }
        return parsed
    }

    // ---------------- 上传 ----------------

    fun upload(src: UploadSource, onProgress: (Long) -> Unit): String {
        val tags = JSONArray()
        if (Config.privateByDefault(ctx)) tags.put("private")
        return if (src.size <= UploadSource.MAX_CHUNK_SIZE) {
            singleUpload(src, tags, onProgress)
        } else {
            chunkedUpload(src, tags, onProgress)
        }
    }

    private fun singleUpload(src: UploadSource, tags: JSONArray, onProgress: (Long) -> Unit): String {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", src.name, streamBody(src, 0L, src.size) { onProgress(it) })
            .addFormDataPart("nsfw", "false")
            .addFormDataPart("tags", tags.toString())
            .build()
        return send("/upload", "POST", form).opt("data") as? String
            ?: throw ApiException("上传响应中没有文件 key")
    }

    private fun chunkedUpload(src: UploadSource, tags: JSONArray, onProgress: (Long) -> Unit): String {
        val maxFile = UploadSource.MAX_CHUNK_SIZE * UploadSource.MAX_CHUNK_NUM
        if (src.size > maxFile) {
            throw ApiException("文件 ${formatSize(src.size)} 超过网盘 1 GB 的单文件上限")
        }
        val chunkSize = chunkSizeFor(src.size)
        val total = ((src.size + chunkSize - 1) / chunkSize).toInt()
        val fileType = KeyParser.fileTypeOf(src.name, src.mime)

        val initBody = JSONObject()
            .put("fileType", fileType)
            .put("fileName", src.name)
            .put("fileSize", src.size)
            .put("totalChunks", total)
            .put("tags", tags)
            .toString()
            .toRequestBody(jsonType)
        val key = send("/upload/chunk/init", "POST", initBody).opt("data") as? String
            ?: throw ApiException("初始化分片上传失败")

        for (index in 0 until total) {
            val start = index.toLong() * chunkSize
            val length = min(chunkSize, src.size - start)
            var attempt = 0
            while (true) {
                try {
                    val form = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("key", key)
                        .addFormDataPart("chunkIndex", index.toString())
                        .addFormDataPart("chunkFile", "$key.part$index", streamBody(src, start, length) {
                            onProgress(start + it)
                        })
                        .build()
                    send("/upload/chunk", "POST", form)
                    break
                } catch (error: Exception) {
                    attempt++
                    if (attempt >= 3) throw ApiException("第 ${index + 1}/$total 个分片失败：${error.message}")
                    Thread.sleep(1500L * attempt)
                }
            }
        }
        awaitMerge(key, total)
        return key
    }

    /** 分片接口立即返回，服务端在后台把分片推给 Telegram，需轮询到 complete */
    private fun awaitMerge(key: String, total: Int) {
        val deadline = System.currentTimeMillis() + 5 * 60_000L
        var lastError = ""
        while (System.currentTimeMillis() < deadline) {
            val data = runCatching {
                send("/upload/chunk/progress?key=${enc(key)}", "GET", null).optJSONObject("data")
            }.getOrElse {
                lastError = it.message ?: "unknown"
                null
            }
            if (data != null) {
                if (data.optBoolean("complete", false)) return
                onMergeProgress?.invoke(data.optJSONArray("uploadedIndices")?.length() ?: 0, total)
            }
            Thread.sleep(1200)
        }
        throw ApiException("等待服务端合并分片超时${if (lastError.isEmpty()) "" else "（$lastError）"}")
    }

    private fun streamBody(
        src: UploadSource,
        offset: Long,
        length: Long,
        onBytes: (Long) -> Unit,
    ): RequestBody = object : RequestBody() {
        override fun contentType() = (src.mime ?: "").toMediaTypeOrNull()
            ?: "application/octet-stream".toMediaType()
        override fun contentLength() = length

        override fun writeTo(sink: BufferedSink) {
            src.open().use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val advanced = input.skip(offset - skipped)
                    if (advanced <= 0L) break
                    skipped += advanced
                }
                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                    onBytes(read.toLong())
                }
            }
        }
    }

    // ---------------- 浏览 / 删除 ----------------

    fun list(type: String?): List<FileRow> {
        val rows = ArrayList<FileRow>()
        var cursor: String? = null
        var page = 0
        while (page < 3) {
            page++
            val query = StringBuilder("?limit=200")
            if (type != null) query.append("&fileType=").append(enc(type))
            if (cursor != null) query.append("&cursor=").append(enc(cursor))
            val data = send("/file/list$query", "GET", null).optJSONObject("data") ?: break
            val keys = data.optJSONArray("keys") ?: JSONArray()
            for (i in 0 until keys.length()) {
                val item = keys.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isEmpty()) continue
                if (type == null && name.startsWith("trash:")) continue
                rows.add(KeyParser.parse(name, item.optJSONObject("metadata")))
            }
            cursor = if (data.optBoolean("list_complete", true)) {
                null
            } else {
                data.optString("cursor").takeIf { it.isNotEmpty() && it != "null" }
            }
            if (cursor == null) break
        }
        // KV list 按 key 字典序返回，这里改成按上传时间倒序
        return rows.sortedByDescending { it.uploadedAt }
    }

    fun moveToTrash(key: String) {
        send("/trash/$key/move", "POST", null)
    }

    fun restore(trashKey: String) {
        send("/trash/$trashKey/restore", "POST", null)
    }

    fun deleteForever(key: String) {
        send("/file/$key", "DELETE", null)
    }

    fun previewUrl(key: String): String = "$base/file/$key"

    fun trashUrl(key: String): String = "$base/trash/$key"

    fun downloadUrl(key: String): String = "$base/file/$key/download"

    /** 给 WebView 用的凭证：确保已登录并返回可写进 Cookie 的 JWT；纯 API Token 模式返回 null */
    fun sessionJwt(): String? = when {
        passwordMode() -> Config.cachedJwt(ctx) ?: login()
        pastedCookie() != null -> pastedCookie()
        else -> null
    }

    /** WebView/下载器复用同一份 HTTP 客户端与认证头 */
    fun httpClient(): OkHttpClient = http

    fun authedRequest(url: String): Request = applyAuth(Request.Builder().url(url)).build()

    fun invalidateSession() {
        Config.clearJwt(ctx)
    }

    /** 设置页「保存并登录」：填了密码就立刻换取并缓存 token */
    fun signIn(): String = when {
        passwordMode() -> {
            login()
            "已用密码登录，token 已自动保存（服务端有效期 7 天，过期会自动重新登录）"
        }

        pastedCookie() != null -> "将使用你粘贴的网页 token（7 天后会过期，建议改填访问密码）"

        Config.apiToken(ctx).isNotEmpty() -> "将使用 API Token 认证"

        else -> throw ApiException("请先填写网盘访问密码")
    }

    /** 设置页自检：/health 看后端，/file/list 验证凭证可用 */
    fun selfCheck(): String {
        val checks = send("/health", "GET", null, attachAuth = false)
            .optJSONObject("data")
            ?.optJSONObject("checks")
        val backend = when {
            checks?.optBoolean("tg") == true -> "Telegram 存储"
            checks?.optBoolean("r2") == true -> "R2 存储"
            else -> "未知存储后端"
        }
        val mode = when {
            passwordMode() -> "密码登录"
            pastedCookie() != null -> "网页 token"
            else -> "API Token"
        }
        val total = list(null).size
        return "连接成功（$backend / $mode），当前可见 $total 个文件"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
