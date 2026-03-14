package com.minicode

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class MiniCodeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("MiniCode", "Uncaught exception in ${thread.name}", throwable)
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== MiniCode Crash ===")
                pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                pw.println("Thread: ${thread.name}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.flush()

                val crashDir = File(getExternalFilesDir(null), "crashes")
                crashDir.mkdirs()
                val crashFile = File(crashDir, "crash_latest.txt")
                crashFile.writeText(sw.toString())

                // Also write to a shared prefs flag so CrashActivity knows to show it
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString("last_crash", sw.toString())
                    .commit() // commit() not apply() - we need sync write before process dies
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
