package com.minicode.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minicode.BuildConfig
import com.minicode.R
import com.minicode.service.Changelog
import com.minicode.service.speech.DownloadState
import com.minicode.service.speech.ModelDownloadManager
import com.minicode.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject lateinit var modelDownloadManager: ModelDownloadManager

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        val layoutRoot = contentFrame.getChildAt(0) ?: contentFrame
        ViewCompat.setOnApplyWindowInsetsListener(layoutRoot) { view, windowInsets ->
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBarInsets.left, systemBarInsets.top, systemBarInsets.right, systemBarInsets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<android.widget.ImageView>(R.id.btn_back).setOnClickListener { finish() }

        setupTerminalFontSize()
        setupExtendedScrollRows()
        setupEditorFontSize()
        setupSwitches()
        setupVoice()
        setupAbout()
    }

    private fun setupTerminalFontSize() {
        val seekBar = findViewById<SeekBar>(R.id.seek_terminal_font_size)
        val label = findViewById<TextView>(R.id.text_terminal_font_size)
        val current = viewModel.terminalFontSize.value
        seekBar.progress = current.toInt()
        label.text = "${current.toInt()}sp"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val size = progress.toFloat().coerceIn(8f, 24f)
                    viewModel.setTerminalFontSize(size)
                    label.text = "${size.toInt()}sp"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupExtendedScrollRows() {
        val seekBar = findViewById<SeekBar>(R.id.seek_extended_scroll_rows)
        val label = findViewById<TextView>(R.id.text_extended_scroll_rows)
        val current = viewModel.extendedScrollRows.value
        seekBar.progress = current
        label.text = "$current"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Round to nearest 50
                    val rows = ((progress + 25) / 50) * 50
                    val clamped = rows.coerceIn(100, 2000)
                    viewModel.setExtendedScrollRows(clamped)
                    label.text = "$clamped"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Snap seekbar to the rounded value
                seekBar.progress = viewModel.extendedScrollRows.value
            }
        })
    }

    private fun setupEditorFontSize() {
        val seekBar = findViewById<SeekBar>(R.id.seek_editor_font_size)
        val label = findViewById<TextView>(R.id.text_editor_font_size)
        val current = viewModel.editorFontSize.value
        seekBar.progress = current.toInt()
        label.text = "${current.toInt()}sp"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val size = progress.toFloat().coerceIn(8f, 24f)
                    viewModel.setEditorFontSize(size)
                    label.text = "${size.toInt()}sp"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupSwitches() {
        val switchWordWrap = findViewById<SwitchMaterial>(R.id.switch_word_wrap)
        val switchLineNumbers = findViewById<SwitchMaterial>(R.id.switch_line_numbers)
        val switchAutoReconnect = findViewById<SwitchMaterial>(R.id.switch_auto_reconnect)
        val switchShowHidden = findViewById<SwitchMaterial>(R.id.switch_show_hidden_files)

        switchWordWrap.isChecked = viewModel.wordWrap.value
        switchLineNumbers.isChecked = viewModel.showLineNumbers.value
        switchAutoReconnect.isChecked = viewModel.autoReconnect.value
        switchShowHidden.isChecked = viewModel.showHiddenFiles.value

        switchWordWrap.setOnCheckedChangeListener { _, isChecked -> viewModel.setWordWrap(isChecked) }
        switchLineNumbers.setOnCheckedChangeListener { _, isChecked -> viewModel.setShowLineNumbers(isChecked) }
        switchAutoReconnect.setOnCheckedChangeListener { _, isChecked -> viewModel.setAutoReconnect(isChecked) }
        switchShowHidden.setOnCheckedChangeListener { _, isChecked -> viewModel.setShowHiddenFiles(isChecked) }
    }

    private fun setupVoice() {
        val voiceSection = findViewById<View>(R.id.voice_section)

        // Hide entire Parakeet section in builds without offline voice recognition
        if (BuildConfig.FLAVOR != "play") {
            voiceSection.visibility = View.GONE
            viewModel.setSpeechEngine("google")
            return
        }

        val switchParakeet = findViewById<SwitchMaterial>(R.id.switch_parakeet)
        val statusText = findViewById<TextView>(R.id.text_model_status)
        val downloadBtn = findViewById<MaterialButton>(R.id.btn_download_model)
        val progressBar = findViewById<ProgressBar>(R.id.progress_model_download)

        switchParakeet.isChecked = viewModel.speechEngine.value == "parakeet"
        switchParakeet.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSpeechEngine(if (isChecked) "parakeet" else "google")
        }

        fun updateModelUI(state: DownloadState) {
            when (state) {
                is DownloadState.NotAvailable -> { return }
                is DownloadState.NotDownloaded -> {
                    statusText.text = "Model not downloaded (~640 MB)"
                    downloadBtn.text = "Download"
                    downloadBtn.isEnabled = true
                    downloadBtn.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
                is DownloadState.Downloading -> {
                    val pct = (state.progress * 100).toInt()
                    statusText.text = "Downloading... $pct%"
                    downloadBtn.isEnabled = false
                    downloadBtn.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = false
                    progressBar.progress = pct
                }
                is DownloadState.Extracting -> {
                    statusText.text = "Extracting model..."
                    downloadBtn.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                }
                is DownloadState.Ready -> {
                    statusText.text = "Model ready"
                    downloadBtn.text = "Delete"
                    downloadBtn.isEnabled = true
                    downloadBtn.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
                is DownloadState.Error -> {
                    statusText.text = "Error: ${state.message}"
                    downloadBtn.text = "Retry"
                    downloadBtn.isEnabled = true
                    downloadBtn.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            }
        }

        downloadBtn.setOnClickListener {
            val current = modelDownloadManager.state.value
            if (current is DownloadState.Ready) {
                modelDownloadManager.deleteModel()
            } else {
                lifecycleScope.launch {
                    modelDownloadManager.downloadAndExtract()
                }
            }
        }

        lifecycleScope.launch {
            modelDownloadManager.state.collect { state ->
                updateModelUI(state)
            }
        }
    }

    private fun setupAbout() {
        val switchUpdates = findViewById<SwitchMaterial>(R.id.switch_check_updates)
        switchUpdates.isChecked = viewModel.checkForUpdates.value
        switchUpdates.setOnCheckedChangeListener { _, isChecked -> viewModel.setCheckForUpdates(isChecked) }

        val versionLabel = findViewById<TextView>(R.id.text_app_version)
        val changelogLabel = findViewById<TextView>(R.id.text_about_changelog)

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName ?: "unknown"
        val versionCode = pInfo.longVersionCode.toInt()
        versionLabel.text = "MiniCode v$versionName (build $versionCode)"

        val allChanges = Changelog.releases.joinToString("\n\n") { release ->
            "v${release.versionName}\n" + release.changes.joinToString("\n") { "\u2022 $it" }
        }
        if (allChanges.isNotEmpty()) {
            changelogLabel.text = allChanges
        } else {
            changelogLabel.visibility = View.GONE
        }

        val licenseInfo = findViewById<TextView>(R.id.text_license_info)
        licenseInfo.text = "License: AGPL-3.0 (source code available)\n" +
            "Commercial licensing: minicode@matlogica.com\n" +
            "Certain features are covered by pending patent application(s).\n" +
            "\u00A9 2026 Raganele Consulting. All rights reserved."
    }
}
