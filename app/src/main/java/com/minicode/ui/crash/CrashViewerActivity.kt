package com.minicode.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.minicode.data.repository.ConnectionRepository
import com.minicode.databinding.ActivityCrashViewerBinding
import com.minicode.model.AuthType
import com.minicode.model.ConnectionProfile
import com.minicode.ui.connection.ConnectionActivity
import com.minicode.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class CrashViewerActivity : AppCompatActivity() {

    @Inject lateinit var connectionRepo: ConnectionRepository

    private lateinit var binding: ActivityCrashViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        seedDebugConnection()

        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val crashLog = prefs.getString("last_crash", null)

        if (crashLog.isNullOrBlank()) {
            // No crash to show, go straight to main app
            launchMain()
            return
        }

        binding.textCrashLog.text = crashLog
        binding.textCrashLog.setTextIsSelectable(true)

        binding.buttonCopy.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MiniCode Crash Log", crashLog))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.buttonDismiss.setOnClickListener {
            clearCrash()
            launchMain()
        }
    }

    private fun clearCrash() {
        getSharedPreferences("crash", MODE_PRIVATE).edit()
            .remove("last_crash")
            .apply()
    }

    private fun launchMain() {
        startActivity(Intent(this, ConnectionActivity::class.java))
        finish()
    }

    private fun seedDebugConnection() {
        if (!BuildConfig.DEBUG) return
        val prefs = getSharedPreferences("debug", MODE_PRIVATE)
        if (prefs.getBoolean("seeded_v3", false)) return
        runBlocking {
            if (!prefs.getBoolean("seeded", false)) {
                val profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    label = "apptest",
                    host = "10.10.10.7",
                    port = 22,
                    username = "apptest",
                    authType = AuthType.PASSWORD,
                    createdAt = Instant.now().toString(),
                )
                connectionRepo.saveProfile(profile, password = "12345")
            }
            if (!prefs.getBoolean("seeded_v2", false)) {
                val dimachProfile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    label = "dimach",
                    host = "10.10.10.7",
                    port = 22,
                    username = "dimach",
                    authType = AuthType.PASSWORD,
                    createdAt = Instant.now().toString(),
                )
                connectionRepo.saveProfile(dimachProfile, password = "")
            }
            val natashaProfile = ConnectionProfile(
                id = UUID.randomUUID().toString(),
                label = "natashamanito",
                host = "10.10.10.7",
                port = 22,
                username = "natashamanito",
                authType = AuthType.PASSWORD,
                createdAt = Instant.now().toString(),
            )
            connectionRepo.saveProfile(natashaProfile, password = "")
        }
        prefs.edit().putBoolean("seeded", true).putBoolean("seeded_v2", true).putBoolean("seeded_v3", true).apply()
    }
}
