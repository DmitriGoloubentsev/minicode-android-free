package com.minicode.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("minicode_settings", Context.MODE_PRIVATE)

    var terminalFontSize: Float
        get() = prefs.getFloat("terminal_font_size", 14f)
        set(value) = prefs.edit().putFloat("terminal_font_size", value).apply()

    var editorFontSize: Float
        get() = prefs.getFloat("editor_font_size", 13f)
        set(value) = prefs.edit().putFloat("editor_font_size", value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit().putBoolean("auto_reconnect", value).apply()

    var maxReconnectRetries: Int
        get() = prefs.getInt("max_reconnect_retries", 5)
        set(value) = prefs.edit().putInt("max_reconnect_retries", value).apply()

    var wordWrap: Boolean
        get() = prefs.getBoolean("word_wrap", true)
        set(value) = prefs.edit().putBoolean("word_wrap", value).apply()

    var showLineNumbers: Boolean
        get() = prefs.getBoolean("show_line_numbers", true)
        set(value) = prefs.edit().putBoolean("show_line_numbers", value).apply()

    var extendedScrollRows: Int
        get() = prefs.getInt("extended_scroll_rows", 500)
        set(value) = prefs.edit().putInt("extended_scroll_rows", value).apply()

    var showHiddenFiles: Boolean
        get() = prefs.getBoolean("show_hidden_files", false)
        set(value) = prefs.edit().putBoolean("show_hidden_files", value).apply()

    /** Mic FAB Y position as fraction of screen height (0.0 = top, 1.0 = bottom) */
    var micButtonY: Float
        get() = prefs.getFloat("mic_button_y", 0.92f)
        set(value) = prefs.edit().putFloat("mic_button_y", value).apply()

    /** Mic FAB side: true = right, false = left */
    var micButtonRight: Boolean
        get() = prefs.getBoolean("mic_button_right", true)
        set(value) = prefs.edit().putBoolean("mic_button_right", value).apply()

    /** Show keyboard toolbar (Ctrl/Alt/Tab/Esc bar) */
    var showKeyboardToolbar: Boolean
        get() = prefs.getBoolean("show_keyboard_toolbar", true)
        set(value) = prefs.edit().putBoolean("show_keyboard_toolbar", value).apply()

    /** Bell notification mode: 0=off, 1=vibrate only, 2=vibrate+chime */
    var bellMode: Int
        get() = prefs.getInt("bell_mode", 0)
        set(value) = prefs.edit().putInt("bell_mode", value).apply()

    /** Speech engine: "google" or "parakeet" */
    var speechEngine: String
        get() = prefs.getString("speech_engine", "google") ?: "google"
        set(value) = prefs.edit().putString("speech_engine", value).apply()

    /** Check for app updates on launch */
    var checkForUpdates: Boolean
        get() = prefs.getBoolean("check_for_updates", true)
        set(value) = prefs.edit().putBoolean("check_for_updates", value).apply()

    /** Image upload path: "tmp" for /tmp, "project" for current dir/images */
    var imageUploadMode: String
        get() = prefs.getString("image_upload_mode", "tmp") ?: "tmp"
        set(value) = prefs.edit().putString("image_upload_mode", value).apply()

    /** Per-connection last used upload path */
    fun getUploadPath(profileId: String): String? =
        prefs.getString("upload_path_$profileId", null)

    fun setUploadPath(profileId: String, path: String) =
        prefs.edit().putString("upload_path_$profileId", path).apply()
}
