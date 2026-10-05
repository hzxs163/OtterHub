package com.otterhub.app

import android.content.Context
import java.util.Collections

/** 上传进度对 UI 的共享状态（Service 与 Activity 同进程） */
object UploadState {

    @Volatile
    var running = false

    @Volatile
    var currentName = ""

    @Volatile
    var currentItem = 0

    @Volatile
    var totalItems = 0

    @Volatile
    var percent = 0

    @Volatile
    var mergedChunks = -1

    @Volatile
    var totalChunks = 0

    @Volatile
    var succeeded = 0

    @Volatile
    var failed = 0

    @Volatile
    var revision = 0L

    val failures = Collections.synchronizedList(ArrayList<String>())

    fun beginBatch(size: Int) {
        running = true
        currentName = ""
        currentItem = 0
        totalItems = size
        percent = 0
        mergedChunks = -1
        totalChunks = 0
        succeeded = 0
        failed = 0
        failures.clear()
        bump()
    }

    fun startItem(name: String, index: Int) {
        currentName = name
        currentItem = index
        percent = 0
        mergedChunks = -1
        bump()
    }

    fun setProgress(value: Int) {
        percent = value.coerceIn(0, 100)
        bump()
    }

    fun setMerge(done: Int, total: Int) {
        mergedChunks = done
        totalChunks = total
        bump()
    }

    fun ok(name: String) {
        succeeded++
        currentName = name
        percent = 100
        bump()
    }

    fun fail(name: String, message: String?) {
        failed++
        if (failures.size < 20) failures.add("$name：${message ?: "上传失败"}")
        bump()
    }

    fun endBatch() {
        running = false
        mergedChunks = -1
        bump()
    }

    private fun bump() {
        revision++
    }

    /** 状态面板与通知栏共用同一行文案 */
    fun describe(ctx: Context): String {
        if (running) {
            val merging = mergedChunks >= 0
            val head = if (merging) {
                ctx.getString(R.string.merging, mergedChunks, totalChunks)
            } else {
                ctx.getString(R.string.uploading, currentName, currentItem, totalItems)
            }
            return if (merging) "$head…" else "$head $percent%"
        }
        val done = succeeded + failed
        return when {
            done == 0 -> ctx.getString(R.string.idle)
            failures.isEmpty() -> ctx.getString(R.string.batch_done, succeeded, failed)
            else -> ctx.getString(R.string.batch_done, succeeded, failed) + "；" + failures.first()
        }
    }

    fun showProgress(): Boolean = running
}
