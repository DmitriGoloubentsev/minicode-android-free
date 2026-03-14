package com.minicode.ui.connection

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minicode.R
import com.minicode.data.repository.SettingsRepository
import com.minicode.service.Changelog
import com.minicode.service.UpdateChecker
import com.minicode.service.speech.ModelDownloadManager
import com.minicode.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ConnectionActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var modelDownloadManager: ModelDownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_connection)

        // Handle keyboard insets — apply bottom padding so ScrollView can scroll above keyboard
        val rootView = findViewById<View>(R.id.nav_host_fragment)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBarInsets.left,
                systemBarInsets.top,
                systemBarInsets.right,
                maxOf(imeInsets.bottom, systemBarInsets.bottom)
            )
            insets
        }

        // Navigate directly to form if requested; finish activity when form pops back
        if (savedInstanceState == null && intent.getBooleanExtra("open_form", false)) {
            val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            val navController = navHost?.navController
            navController?.navigate(R.id.action_list_to_form)
            navController?.addOnDestinationChangedListener { _, dest, _ ->
                // When form pops back to list, finish instead
                if (dest.id == R.id.connectionListFragment) {
                    finish()
                }
            }
        }

        // Show "What's New" on first launch after update, then check for newer updates
        if (savedInstanceState == null && !intent.getBooleanExtra("open_form", false)) {
            showWhatsNew()
        }
    }

    private fun showWhatsNew() {
        var shown = false
        Changelog.showWhatsNewIfNeeded(this) { release ->
            shown = true
            val changes = release.changes.joinToString("\n") { "• $it" }
            MaterialAlertDialogBuilder(this)
                .setTitle("What's New in ${release.versionName}")
                .setMessage(changes)
                .setPositiveButton("OK") { _, _ ->
                    checkForUpdate()
                }
                .setOnCancelListener {
                    checkForUpdate()
                }
                .show()
        }
        if (!shown) {
            checkForUpdate()
        }
    }

    private fun checkForUpdate() {
        if (!settingsRepository.checkForUpdates) {
            suggestParakeetDownload()
            return
        }

        lifecycleScope.launch {
            val update = UpdateChecker.check(this@ConnectionActivity) ?: run {
                suggestParakeetDownload()
                return@launch
            }
            if (UpdateChecker.getLastDismissedVersion(this@ConnectionActivity) >= update.versionCode) {
                suggestParakeetDownload()
                return@launch
            }

            val changelogText = if (update.changelog.isNotEmpty()) {
                "\n\nWhat's new:\n" + update.changelog.joinToString("\n") { "• $it" }
            } else ""

            MaterialAlertDialogBuilder(this@ConnectionActivity)
                .setTitle("Update Available")
                .setMessage("Version ${update.versionName} is available.${changelogText}")
                .setPositiveButton("Download") { _, _ ->
                    downloadApk(update.apkUrl, update.versionName)
                    suggestParakeetDownload()
                }
                .setNeutralButton("Don't ask again") { _, _ ->
                    settingsRepository.checkForUpdates = false
                    suggestParakeetDownload()
                }
                .setNegativeButton("Later") { _, _ ->
                    UpdateChecker.dismissVersion(this@ConnectionActivity, update.versionCode)
                    suggestParakeetDownload()
                }
                .setCancelable(true)
                .setOnCancelListener { suggestParakeetDownload() }
                .show()
        }
    }

    private fun suggestParakeetDownload() {
        // Only suggest once, and only if model is not already downloaded
        val prefs = getSharedPreferences("update", MODE_PRIVATE)
        if (prefs.getBoolean("parakeet_suggested", false)) return
        if (modelDownloadManager.isModelReady()) return

        prefs.edit().putBoolean("parakeet_suggested", true).apply()

        MaterialAlertDialogBuilder(this)
            .setTitle("Offline Voice Recognition")
            .setMessage(
                "MiniCode includes NVIDIA Parakeet for high-quality offline speech recognition.\n\n" +
                "Benefits:\n" +
                "• Works without internet\n" +
                "• Better accuracy than online recognition\n" +
                "• Full privacy — audio never leaves your device\n\n" +
                "The model is ~640 MB. Would you like to download it now?"
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Not now", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadApk(url: String, versionName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("MiniCode $versionName")
                .setDescription("Downloading update...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "minicode-${versionName}.apk"
                )
                .setMimeType("application/vnd.android.package-archive")

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (_: Exception) {
            // Fallback: open URL in browser
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
