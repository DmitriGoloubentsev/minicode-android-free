package com.minicode.service

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: List<String>,
)

object UpdateChecker {

    private const val UPDATE_URL = "https://matlogica.com/minicode/minicode.txt"
    private const val TIMEOUT_MS = 10_000

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val text = fetchText(UPDATE_URL) ?: return@withContext null
            val info = parse(text) ?: return@withContext null
            val currentVersionCode = getCurrentVersionCode(context)
            if (info.versionCode > currentVersionCode) info else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchText(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): UpdateInfo? {
        var versionCode = -1
        var versionName = ""
        var apkUrl = ""
        val changelog = mutableListOf<String>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            when (key) {
                "version" -> versionCode = value.toIntOrNull() ?: -1
                "versionName" -> versionName = value
                "url" -> apkUrl = value
                "changelog" -> if (value.isNotEmpty()) changelog.add(value)
            }
        }

        if (versionCode < 0 || apkUrl.isEmpty()) return null
        return UpdateInfo(versionCode, versionName, apkUrl, changelog)
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.longVersionCode.toInt()
        } catch (_: PackageManager.NameNotFoundException) {
            0
        }
    }

    fun getLastDismissedVersion(context: Context): Int {
        return context.getSharedPreferences("update", Context.MODE_PRIVATE)
            .getInt("dismissed_version", -1)
    }

    fun dismissVersion(context: Context, versionCode: Int) {
        context.getSharedPreferences("update", Context.MODE_PRIVATE)
            .edit().putInt("dismissed_version", versionCode).apply()
    }
}
