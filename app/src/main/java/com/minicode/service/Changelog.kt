package com.minicode.service

import android.content.Context

/**
 * In-app changelog. Add an entry for each versionCode before release.
 * The release script will remind you to update this.
 */
object Changelog {

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val changes: List<String>,
    )

    val releases = listOf(
        Release(12, "1.2.9", listOf(
            "Redesigned landing page: AI agent workflow story",
            "Connection form: keyboard no longer covers password field",
            "Larger Enter/Backspace floating buttons, more transparent",
        )),
        Release(11, "1.2.8", listOf(
            "Image upload: paste images from gallery via SFTP to remote server",
            "Image viewer: open image files in editor panel",
            "Settings icon on connection list screen",
            "Idle resize: fix terminal size after using sessio from PC",
        )),
        Release(10, "1.2.7", listOf(
            "Text selection auto-scroll: drag handles beyond viewport edges",
            "Force-switch on session close: proper terminal swap when tab removed",
            "New file auto-opens in editor after creation in file tree",
        )),
        Release(9, "1.2.6", listOf(
            "Natural scrolling: swipe up moves text up into history",
        )),
        Release(8, "1.2.5", listOf(
            "Yellow pulsing dot for active output, green when idle",
            "Bell notification when background session finishes work",
            "Auto-close tab on session logout/exit",
        )),
        Release(7, "1.2.4", listOf(
            "Session activity indicator: pulsing dot shows background output",
            "Auto-close tab on session logout/exit",
            "Fix tab highlight not updating on switch",
        )),
        Release(6, "1.2.3", listOf(
            "Truncate long session tab names with ellipsis",
            "Smart resize on session switch: only when screen size changed",
            "No unnecessary terminal reloads on keyboard show/hide",
        )),
        Release(5, "1.2.2", listOf(
            "Update checker with opt-out setting in Settings",
            "No unnecessary terminal reloads on session switch or keyboard",
            "Editor panel auto-hides when all tabs closed",
        )),
        Release(4, "1.2.1", listOf(
            "Fix Parakeet model persistence across reinstalls",
            "Full changelog in About section",
        )),
        Release(3, "1.2.0", listOf(
            "Infinite SSH persistence: connections survive hours-long network outages",
            "WiFi lock keeps connection alive when screen is off",
            "Persistent model storage: Parakeet model survives app reinstall",
            "APK size reduced from 103MB to 30MB (arm64-only)",
            "Split handle shows T-junction icon based on visible panels",
        )),
        Release(2, "1.1.0", listOf(
            "Multi-session SSH: open multiple connections with tab switching",
            "Per-session layout: splitter positions and panel state saved per session",
            "Connection list in workspace: tap + to add sessions without leaving terminal",
            "Voice input improvements: re-record, better positioning on foldable",
            "Offline speech recognition via NVIDIA Parakeet",
            "Bell notifications with vibration and chime",
            "Floating toolbar with Esc/Backspace/Enter buttons",
            "Fold/unfold stability fixes",
        )),
        Release(1, "1.0.0", listOf(
            "SSH terminal with xterm-256color support",
            "Code editor with syntax highlighting for 20+ languages",
            "SFTP file browser",
            "Split-panel layout for Samsung foldables",
        )),
    )

    fun getChangesForVersion(versionCode: Int): Release? =
        releases.find { it.versionCode == versionCode }

    fun showWhatsNewIfNeeded(context: Context, onShow: (Release) -> Unit) {
        val prefs = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE)
        val lastSeen = prefs.getInt("last_seen_version", 0)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) { 0 }

        if (currentVersion > lastSeen) {
            val release = getChangesForVersion(currentVersion)
            if (release != null) {
                onShow(release)
            }
            prefs.edit().putInt("last_seen_version", currentVersion).apply()
        }
    }
}
