package com.otterhub.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 侧载应用默认会被丢进「受限」待机档，熄屏后 CPU 和网络都可能被冻结：
 * 分享上传就停在 1%，非要打开 App 才继续。代码能做的只有两件——
 * 传输期间自己持有唤醒锁，以及把省电/自启动设置页递到用户面前（厂商冻结只能手动放开）。
 */
object BackgroundGuard {

    private const val MIUI_PERMISSION_ACTION = "miui.intent.action.APP_PERM_EDITOR"
    private const val MIUI_SECURITY_PACKAGE = "com.miui.securitycenter"
    private const val MIUI_AUTO_START_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

    /** 传输期间防止 CPU 随熄屏休眠；超时只是兜底，正常路径走 finally 释放 */
    @SuppressLint("WakeLock")
    fun acquireWakeLock(ctx: Context): PowerManager.WakeLock? = runCatching {
        ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OtterHub:upload")
            .apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60_000L)
            }
    }.getOrNull()

    fun needsBatteryExemption(ctx: Context): Boolean =
        !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** 依次尝试：厂商后台设置页 -> 系统电池优化对话框 -> 电池优化列表，返回是否跳出去了 */
    fun openPermissions(ctx: Context): Boolean {
        vendorSettingsIntent(ctx)?.let { if (launch(ctx, it)) return true }
        if (needsBatteryExemption(ctx) && launch(ctx, exemptionDialogIntent(ctx))) return true
        return launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun exemptionDialogIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 小米/红米的后台冻结与自启动不归电池优化管，能跳厂商页就优先跳 */
    private fun vendorSettingsIntent(ctx: Context): Intent? {
        val intent = Intent(MIUI_PERMISSION_ACTION)
            .setClassName(MIUI_SECURITY_PACKAGE, MIUI_AUTO_START_ACTIVITY)
            .putExtra("extra_pkgname", ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { canHandle(ctx, it) }
    }

    private fun canHandle(ctx: Context, intent: Intent): Boolean =
        ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty()

    private fun launch(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.startActivity(intent) }.isSuccess
}
