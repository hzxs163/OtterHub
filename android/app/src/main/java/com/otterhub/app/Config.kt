package com.otterhub.app

import android.content.Context

object Config {
    private const val PREF = "otterhub_config"
    const val DEFAULT_BASE_URL = "https://tctg.pages.dev"
    private const val JWT_USABLE_MILLIS = 6L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String {
        val raw = prefs(ctx).getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val normalized = if (raw.startsWith("http")) raw else "https://$raw"
        return normalized.trimEnd('/')
    }

    fun password(ctx: Context): String = prefs(ctx).getString("password", "").orEmpty()

    fun apiToken(ctx: Context): String = prefs(ctx).getString("api_token", "").orEmpty()

    fun privateByDefault(ctx: Context): Boolean = prefs(ctx).getBoolean("private_tag", false)

    fun savedUrl(ctx: Context): String = prefs(ctx).getString("base_url", "").orEmpty()

    fun save(ctx: Context, url: String, password: String, apiToken: String, privateTag: Boolean) {
        prefs(ctx).edit()
            .putString("base_url", url.trim())
            .putString("password", password.trim())
            .putString("api_token", apiToken.trim())
            .putBoolean("private_tag", privateTag)
            .remove("jwt")
            .apply()
    }

    /** JWT 服务端有效期 7 天，提前 1 天视为过期以便自动重新登录 */
    fun cachedJwt(ctx: Context): String? {
        val store = prefs(ctx)
        val jwt = store.getString("jwt", null) ?: return null
        val issuedAt = store.getLong("jwt_at", 0L)
        val age = System.currentTimeMillis() - issuedAt
        if (jwt.isEmpty() || age > JWT_USABLE_MILLIS) {
            clearJwt(ctx)
            return null
        }
        return jwt
    }

    fun storeJwt(ctx: Context, jwt: String) {
        // JWT 有效期 7 天，按 6 天提前重新登录
        prefs(ctx).edit()
            .putString("jwt", jwt)
            .putLong("jwt_at", System.currentTimeMillis())
            .apply()
    }

    fun clearJwt(ctx: Context) {
        prefs(ctx).edit().remove("jwt").remove("jwt_at").apply()
    }

    fun isConfigured(ctx: Context): Boolean = apiToken(ctx).isNotEmpty() || password(ctx).isNotEmpty()
}
