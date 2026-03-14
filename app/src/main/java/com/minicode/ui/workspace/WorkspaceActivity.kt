package com.minicode.ui.workspace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.res.Configuration
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.minicode.R
import com.minicode.data.repository.SettingsRepository
import com.minicode.service.SshConnectionService
import com.minicode.model.SshSessionState
import com.minicode.ui.editor.EditorPanelView
import com.minicode.ui.filetree.FileTreePanelView
import com.minicode.ui.settings.SettingsActivity
import com.minicode.ui.terminal.KeyboardToolbarView
import com.minicode.ui.terminal.MicFabView
import com.minicode.ui.terminal.TerminalView
import com.minicode.ui.terminal.VoiceInputDialog
import com.minicode.viewmodel.ConnectionListViewModel
import com.minicode.viewmodel.EditorViewModel
import com.minicode.viewmodel.FileTreeViewModel
import com.minicode.viewmodel.WorkspaceViewModel
import com.minicode.service.speech.SherpaRecognizer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "WorkspaceActivity"
private const val SPLIT_MIN_WIDTH_DP = 600

@AndroidEntryPoint
class WorkspaceActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var sherpaRecognizer: SherpaRecognizer

    private val viewModel: WorkspaceViewModel by viewModels()
    private val fileTreeViewModel: FileTreeViewModel by viewModels()
    private val editorViewModel: EditorViewModel by viewModels()
    private val connectionListViewModel: ConnectionListViewModel by viewModels()
    private var profileId: String? = null
    private var hasInitialized = false
    private var showingEditor = false
    private var isSplitMode = false
    private var observerJob: Job? = null
    private var resizeJob: Job? = null
    private var lastResizeCols = 0
    private var lastResizeRows = 0
    private var suppressResize = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var micPendingStart = false
    private var micHolding = false
    private var voiceSentText = false  // true after first voice chunk sent; prepend space before next
    private var micFab: MicFabView? = null
    private var floatingToolbar: FrameLayout? = null
    private var floatingToolbarSavedY: Float = -1f  // saved Y before keyboard pushed it up
    private var timerLabel: TextView? = null
    private var voiceInputDialog: VoiceInputDialog? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && micPendingStart) {
            startSpeechRecognition()
        } else if (!granted) {
            setMicFabActive(false)
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
        micPendingStart = false
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { handleImagePicked(it) } }

    // Session tab bar (both layouts)
    private lateinit var sessionTabBar: SessionTabBar
    private var connectionListPanel: ConnectionListPanel? = null
    private var showingConnectionList = false
    private var connectionListJob: Job? = null

    // Views found in both layouts
    private lateinit var rootView: View
    private lateinit var terminalView: TerminalView
    private lateinit var editorPanel: EditorPanelView
    private lateinit var fileTreePanel: FileTreePanelView
    private lateinit var keyboardToolbar: KeyboardToolbarView
    private lateinit var statusDot: View
    private lateinit var textTitle: TextView
    private lateinit var btnSettings: ImageView
    private lateinit var btnDisconnect: ImageView
    private lateinit var btnToggleKeyboard: ImageView
    private lateinit var btnToggleBell: ImageView
    private lateinit var overlayConnecting: FrameLayout
    private lateinit var textConnectingStatus: TextView

    // Portrait-only views
    private var bottomNavBar: View? = null
    private var navFiles: View? = null
    private var navTerminal: View? = null
    private var navEditor: View? = null
    private var navFilesIcon: ImageView? = null
    private var navTerminalIcon: ImageView? = null
    private var navEditorIcon: ImageView? = null
    private var navFilesLabel: TextView? = null
    private var navTerminalLabel: TextView? = null
    private var navEditorLabel: TextView? = null

    // Split-only views
    private var dividerVertical: View? = null
    private var dividerHorizontal: View? = null
    private var splitHandle: SplitHandleView? = null
    private var btnToggleFileTree: ImageView? = null
    private var btnToggleEditor: ImageView? = null
    private var terminalContainer: View? = null

    // Panel state
    private var fileTreeWidthRatio = 0.25f
    private var editorHeightRatio = 0.55f
    private var fileTreeVisible = true
    private var editorVisible = false

    // Per-session layout state
    private data class LayoutState(
        var fileTreeWidthRatio: Float = 0.25f,
        var editorHeightRatio: Float = 0.55f,
        var fileTreeVisible: Boolean = true,
        var editorVisible: Boolean = false,
        var activePanel: String = "terminal",
        var showingEditor: Boolean = false,
    )
    private val sessionLayouts = HashMap<String, LayoutState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        WindowCompat.setDecorFitsSystemWindows(window, false)

        profileId = intent.getStringExtra("profile_id")
        if (profileId == null) {
            Log.e(TAG, "No profile_id in intent")
            finish()
            return
        }

        savedInstanceState?.let {
            fileTreeWidthRatio = it.getFloat("fileTreeWidthRatio", 0.25f)
            editorHeightRatio = it.getFloat("editorHeightRatio", 0.55f)
            showingEditor = it.getBoolean("showingEditor", false)
            activePanel = it.getString("activePanel", "terminal")
            fileTreeVisible = it.getBoolean("fileTreeVisible", true)
            editorVisible = it.getBoolean("editorVisible", true)
        }

        isSplitMode = resources.configuration.screenWidthDp >= SPLIT_MIN_WIDTH_DP
        inflateLayout()
        setupAll()

        if (savedInstanceState == null) {
            terminalView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        terminalView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (!hasInitialized) {
                            hasInitialized = true
                            val cols = terminalView.calculateColumns()
                            val rows = terminalView.calculateRows()
                            Log.d(TAG, "Terminal size: ${cols}x${rows}")
                            viewModel.connect(profileId!!, cols.coerceAtLeast(20), rows.coerceAtLeast(5))
                        }
                    }
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("fileTreeWidthRatio", fileTreeWidthRatio)
        outState.putFloat("editorHeightRatio", editorHeightRatio)
        outState.putBoolean("showingEditor", showingEditor)
        outState.putString("activePanel", activePanel)
        outState.putBoolean("fileTreeVisible", fileTreeVisible)
        outState.putBoolean("editorVisible", editorVisible)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: widthDp=${newConfig.screenWidthDp} heightDp=${newConfig.screenHeightDp} density=${newConfig.densityDpi} orientation=${newConfig.orientation}")
        val newSplit = newConfig.screenWidthDp >= SPLIT_MIN_WIDTH_DP
        if (newSplit != isSplitMode) {
            isSplitMode = newSplit
            inflateLayout()
            setupAll()
            // Re-attach emulator if already connected
            viewModel.bridge.value?.let { bridge ->
                terminalView.emulator = bridge.emulator
            }
            // Re-apply editor state
            val tabs = editorViewModel.tabs.value
            val activeIdx = editorViewModel.activeTabIndex.value
            editorPanel.updateTabs(tabs, activeIdx)
            // Re-apply file tree state
            fileTreePanel.updateNodes(fileTreeViewModel.visibleNodes.value)
            fileTreePanel.updatePath(fileTreeViewModel.currentPath.value)
        }
    }

    private fun inflateLayout() {
        if (isSplitMode) {
            setContentView(R.layout.activity_workspace_split)
        } else {
            setContentView(R.layout.activity_workspace)
        }
        connectionListPanel = null
        rootView = findViewById(android.R.id.content)
        bindViews()
    }

    private fun bindViews() {
        sessionTabBar = findViewById(R.id.session_tab_bar)
        sessionTabBar.visibility = View.VISIBLE
        terminalView = findViewById(R.id.terminal_view)
        terminalView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !suppressResize) {
                // Re-assert terminal size when gaining focus — another client
                // (e.g. sessio on PC) may have changed the server's terminal size
                val cols = terminalView.calculateColumns()
                val rows = terminalView.calculateRows()
                if (cols > 0 && rows > 0) {
                    viewModel.forceResize(cols, rows)
                }
            }
        }
        editorPanel = findViewById(R.id.editor_panel)
        fileTreePanel = findViewById(R.id.file_tree_panel)
        keyboardToolbar = findViewById(R.id.keyboard_toolbar)
        statusDot = findViewById(R.id.status_dot)
        textTitle = findViewById(R.id.text_title)
        btnSettings = findViewById(R.id.btn_settings)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnToggleKeyboard = findViewById(R.id.btn_toggle_keyboard)
        btnToggleBell = findViewById(R.id.btn_toggle_bell)
        overlayConnecting = findViewById(R.id.overlay_connecting)
        textConnectingStatus = findViewById(R.id.text_connecting_status)

        if (isSplitMode) {
            bottomNavBar = null
            navFiles = null
            navTerminal = null
            navEditor = null
            navFilesIcon = null
            navTerminalIcon = null
            navEditorIcon = null
            navFilesLabel = null
            navTerminalLabel = null
            navEditorLabel = null
            dividerVertical = findViewById(R.id.divider_vertical)
            dividerHorizontal = findViewById(R.id.divider_horizontal)
            splitHandle = findViewById(R.id.split_handle)
            btnToggleFileTree = findViewById(R.id.btn_toggle_file_tree)
            btnToggleEditor = findViewById(R.id.btn_toggle_editor)
            terminalContainer = findViewById(R.id.terminal_container)
        } else {
            bottomNavBar = findViewById(R.id.bottom_nav_bar)
            navFiles = findViewById(R.id.nav_files)
            navTerminal = findViewById(R.id.nav_terminal)
            navEditor = findViewById(R.id.nav_editor)
            navFilesIcon = findViewById(R.id.nav_files_icon)
            navTerminalIcon = findViewById(R.id.nav_terminal_icon)
            navEditorIcon = findViewById(R.id.nav_editor_icon)
            navFilesLabel = findViewById(R.id.nav_files_label)
            navTerminalLabel = findViewById(R.id.nav_terminal_label)
            navEditorLabel = findViewById(R.id.nav_editor_label)
            dividerVertical = null
            dividerHorizontal = null
            splitHandle = null
            btnToggleFileTree = null
            btnToggleEditor = null
            terminalContainer = null
        }
    }

    private fun setupAll() {
        setupSessionTabBar()
        setupKeyboardInsets()
        setupTerminalView()
        setupKeyboardToolbar()
        if (!settingsRepository.showKeyboardToolbar) {
            keyboardToolbar.visibility = View.GONE
        }
        setupMicFab()
        setupHeader()
        setupFileTree()
        setupEditor()

        if (isSplitMode) {
            setupSplitDividers()
            setupSplitToggles()
            applySplitVisibility()
        } else {
            when (activePanel) {
                "files" -> switchToFiles()
                "editor" -> switchToEditor()
                else -> switchToTerminal()
            }
        }

        // Cancel previous observer job to avoid duplicates
        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                this@repeatOnLifecycle.observeState()
            }
        }
    }

    private fun setupSessionTabBar() {
        sessionTabBar.onTabSelected = { sessionId ->
            hideConnectionList()
            switchToSession(sessionId)
        }
        sessionTabBar.onTabClosed = { sessionId ->
            confirmCloseSession(sessionId)
        }
        sessionTabBar.onConnectionsTabSelected = {
            showConnectionList()
        }
        sessionTabBar.onActivityStopped = { sessionId ->
            // Notify when a background session finishes producing output
            if (sessionId != viewModel.activeSessionId.value) {
                handleTerminalBell()
            }
        }
    }

    private fun saveLayoutState(sessionId: String) {
        sessionLayouts[sessionId] = LayoutState(
            fileTreeWidthRatio = fileTreeWidthRatio,
            editorHeightRatio = editorHeightRatio,
            fileTreeVisible = fileTreeVisible,
            editorVisible = editorVisible,
            activePanel = activePanel,
            showingEditor = showingEditor,
        )
    }

    private fun restoreLayoutState(sessionId: String) {
        val state = sessionLayouts[sessionId]
        if (state != null) {
            fileTreeWidthRatio = state.fileTreeWidthRatio
            editorHeightRatio = state.editorHeightRatio
            fileTreeVisible = state.fileTreeVisible
            editorVisible = state.editorVisible
            activePanel = state.activePanel
            showingEditor = state.showingEditor
        } else {
            // Defaults for new session
            fileTreeWidthRatio = 0.25f
            editorHeightRatio = 0.55f
            fileTreeVisible = true
            editorVisible = false
            activePanel = "terminal"
            showingEditor = false
        }
    }

    /** Force-switch to a session even if it's already the active ID.
     *  Used when a session was removed and we need to refresh the terminal view. */
    private fun forceSwitch(sessionId: String) {
        suppressResize = true
        resizeJob?.cancel()
        viewModel.switchSession(sessionId)
        fileTreeViewModel.switchSession(sessionId)
        editorPanel.switchSession(sessionId)
        editorViewModel.switchSession(sessionId)
        restoreLayoutState(sessionId)
        val handle = viewModel.sessionManager.getSessionHandle(sessionId)
        if (handle != null) {
            handle.bridge.extendedRows = settingsRepository.extendedScrollRows
            terminalView.emulator = handle.bridge.emulator
            handle.bridge.emulator.onCwdChanged = { path ->
                runOnUiThread { fileTreeViewModel.navigateTo(path) }
            }
            handle.bridge.emulator.onBell = { handleTerminalBell() }
            terminalView.invalidate()
        }
        val tabs = editorViewModel.tabs.value
        val activeIdx = editorViewModel.activeTabIndex.value
        editorPanel.updateTabs(tabs, activeIdx)
        fileTreePanel.updateNodes(fileTreeViewModel.visibleNodes.value)
        fileTreePanel.updatePath(fileTreeViewModel.currentPath.value)
        if (isSplitMode) applySplitVisibility()
        terminalView.post {
            suppressResize = false
            val cols = terminalView.calculateColumns()
            val rows = terminalView.calculateRows()
            if (cols > 0 && rows > 0) viewModel.forceResize(cols, rows)
        }
    }

    private fun switchToSession(sessionId: String) {
        val currentId = viewModel.activeSessionId.value
        if (sessionId == currentId) return

        // Suppress resize events during session switch to avoid sending
        // unnecessary window-change signals to the server
        suppressResize = true
        resizeJob?.cancel()

        // Save current session's layout
        if (currentId != null) {
            saveLayoutState(currentId)
        }

        viewModel.switchSession(sessionId)
        fileTreeViewModel.switchSession(sessionId)
        editorPanel.switchSession(sessionId)
        editorViewModel.switchSession(sessionId)

        // Restore target session's layout
        restoreLayoutState(sessionId)

        // Swap terminal emulator
        val handle = viewModel.sessionManager.getSessionHandle(sessionId)
        if (handle != null) {
            handle.bridge.extendedRows = settingsRepository.extendedScrollRows
            terminalView.emulator = handle.bridge.emulator
            handle.bridge.emulator.onCwdChanged = { path ->
                runOnUiThread { fileTreeViewModel.navigateTo(path) }
            }
            handle.bridge.emulator.onBell = { handleTerminalBell() }
            terminalView.invalidate()
        }

        // Update editor panel
        val tabs = editorViewModel.tabs.value
        val activeIdx = editorViewModel.activeTabIndex.value
        editorPanel.updateTabs(tabs, activeIdx)

        // Update file tree
        fileTreePanel.updateNodes(fileTreeViewModel.visibleNodes.value)
        fileTreePanel.updatePath(fileTreeViewModel.currentPath.value)

        // Apply layout
        if (isSplitMode) {
            applySplitVisibility()
        } else {
            when (activePanel) {
                "files" -> switchToFiles()
                "editor" -> switchToEditor()
                else -> switchToTerminal()
            }
        }

        // Re-enable resize after layout settles, then check if screen size
        // changed (e.g. session was opened on cover screen, now on inner screen)
        terminalView.post {
            suppressResize = false
            val cols = terminalView.calculateColumns()
            val rows = terminalView.calculateRows()
            if (cols > 0 && rows > 0) {
                val bridge = handle?.bridge ?: viewModel.bridge.value
                if (bridge != null) {
                    lastResizeCols = cols
                    lastResizeRows = rows
                    // Force-send to re-assert size in case another client
                    // (e.g. sessio on PC) changed the server's terminal size
                    bridge.forceResize(cols, rows)
                }
            }
        }
    }

    private fun confirmCloseSession(sessionId: String) {
        val handle = viewModel.sessionManager.getSessionHandle(sessionId) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Close Session")
            .setMessage("Close \"${handle.label.value}\"?")
            .setPositiveButton("Close") { _, _ ->
                sessionLayouts.remove(sessionId)
                fileTreeViewModel.removeSession(sessionId)
                editorViewModel.removeSession(sessionId)
                editorPanel.removeSessionCache(sessionId)
                viewModel.disconnectSession(sessionId)
                // If no sessions left, finish
                if (viewModel.sessionManager.getSessionCount() == 0) {
                    finish()
                } else {
                    // Switch to new active session
                    val newActiveId = viewModel.activeSessionId.value
                    if (newActiveId != null) {
                        switchToSession(newActiveId)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConnectionPicker() {
        val picker = ConnectionPickerSheet()
        picker.onProfileSelected = { profileId ->
            val cols = terminalView.calculateColumns().coerceAtLeast(20)
            val rows = terminalView.calculateRows().coerceAtLeast(5)
            viewModel.connect(profileId, cols, rows)
        }
        picker.show(supportFragmentManager, "connection_picker")
    }

    private fun showConnectionList() {
        if (showingConnectionList) return
        showingConnectionList = true
        sessionTabBar.setConnectionsTabActive(true)
        sessionTabBar.updateSessions(viewModel.sessionList.value, viewModel.activeSessionId.value)

        // Create panel if needed
        if (connectionListPanel == null) {
            connectionListPanel = ConnectionListPanel(this).apply {
                onConnect = { profileId ->
                    hideConnectionList()
                    val cols = terminalView.calculateColumns().coerceAtLeast(20)
                    val rows = terminalView.calculateRows().coerceAtLeast(5)
                    viewModel.connect(profileId, cols, rows)
                }
                onAddNew = {
                    startActivity(android.content.Intent(
                        this@WorkspaceActivity,
                        com.minicode.ui.connection.ConnectionActivity::class.java,
                    ).putExtra("open_form", true))
                }
                onEdit = { profileId ->
                    startActivity(android.content.Intent(
                        this@WorkspaceActivity,
                        com.minicode.ui.connection.ConnectionActivity::class.java,
                    ).putExtra("edit_profile_id", profileId))
                }
            }
            // Add below session tab bar by inserting into the main LinearLayout
            val mainLinear = sessionTabBar.parent as? ViewGroup
            if (mainLinear != null) {
                // Insert after session tab bar (index 1)
                val idx = mainLinear.indexOfChild(sessionTabBar) + 1
                mainLinear.addView(connectionListPanel, idx, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f,
                ))
            }
        }

        connectionListPanel?.visibility = View.VISIBLE
        // Collect profiles flow so Room data is loaded and stays updated
        connectionListJob?.cancel()
        connectionListJob = lifecycleScope.launch {
            connectionListViewModel.profiles.collect { profiles ->
                connectionListPanel?.updateProfiles(profiles)
            }
        }

        // Hide workspace panels
        if (isSplitMode) {
            findViewById<View>(R.id.split_root)?.visibility = View.GONE
            keyboardToolbar.visibility = View.GONE
        } else {
            // Hide the content FrameLayout (parent of terminal/editor/filetree)
            (terminalView.parent as? View)?.visibility = View.GONE
            bottomNavBar?.visibility = View.GONE
            keyboardToolbar.visibility = View.GONE
        }
        // Hide terminal header, floating toolbar, and split handle
        findViewById<View>(R.id.terminal_header)?.visibility = View.GONE
        floatingToolbar?.visibility = View.GONE
        splitHandle?.visibility = View.GONE
    }

    private fun hideConnectionList() {
        if (!showingConnectionList) return
        showingConnectionList = false
        connectionListJob?.cancel()
        connectionListJob = null
        sessionTabBar.setConnectionsTabActive(false)
        connectionListPanel?.visibility = View.GONE

        // Restore terminal header, floating toolbar, and split handle
        findViewById<View>(R.id.terminal_header)?.visibility = View.VISIBLE
        floatingToolbar?.visibility = View.VISIBLE
        splitHandle?.visibility = View.VISIBLE

        // Restore workspace panels
        if (isSplitMode) {
            findViewById<View>(R.id.split_root)?.visibility = View.VISIBLE
            applySplitVisibility()
        } else {
            // Restore the content FrameLayout
            (terminalView.parent as? View)?.visibility = View.VISIBLE
            when (activePanel) {
                "terminal" -> switchToTerminal()
                "editor" -> switchToEditor()
                "files" -> switchToFiles()
                else -> switchToTerminal()
            }
            bottomNavBar?.visibility = View.VISIBLE
        }
        sessionTabBar.updateSessions(viewModel.sessionList.value, viewModel.activeSessionId.value)
    }

    private fun setupKeyboardInsets() {
        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        val layoutRoot = contentFrame.getChildAt(0) ?: contentFrame
        ViewCompat.setOnApplyWindowInsetsListener(layoutRoot) { view, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomPadding = maxOf(imeInsets.bottom, systemBarInsets.bottom)
            Log.d(TAG, "Insets: ime.bottom=${imeInsets.bottom} sys.bottom=${systemBarInsets.bottom} applied=$bottomPadding")
            view.setPadding(
                systemBarInsets.left,
                systemBarInsets.top,
                systemBarInsets.right,
                bottomPadding,
            )
            // Reset to weight-based layout so panels fill the new size
            if (isSplitMode) {
                applySplitVisibility()
            }
            // Adjust floating toolbar so it's not covered by keyboard
            val keyboardOpen = imeInsets.bottom > 0
            adjustFloatingToolbarForKeyboard(view.height - bottomPadding, keyboardOpen)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(layoutRoot)
    }

    private fun adjustFloatingToolbarForKeyboard(availableHeight: Int, keyboardOpen: Boolean) {
        val toolbar = floatingToolbar ?: return
        if (availableHeight <= 0 || toolbar.height <= 0) return
        toolbar.post {
            if (toolbar.height <= 0) return@post
            val maxY = (availableHeight - toolbar.height).toFloat().coerceAtLeast(0f)
            if (keyboardOpen) {
                // Save position before pushing up
                if (floatingToolbarSavedY < 0f) {
                    floatingToolbarSavedY = toolbar.y
                }
                if (toolbar.y > maxY) {
                    toolbar.y = maxY
                }
            } else {
                // Keyboard closed — restore saved position
                if (floatingToolbarSavedY >= 0f) {
                    toolbar.y = floatingToolbarSavedY
                    floatingToolbarSavedY = -1f
                }
            }
        }
    }

    private fun toggleKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val insets = ViewCompat.getRootWindowInsets(terminalView)
        val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (imeVisible) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupTerminalView() {
        terminalView.onKeyInput = { data ->
            // Reset voice space-prefix after Enter (0x0D = CR)
            if (data.any { it == 0x0D.toByte() }) {
                voiceSentText = false
            }
            viewModel.writeInput(data)
        }

        terminalView.onPathTap = { detected, promptCwd ->
            val path = detected.path
            // Resolve relative paths: use prompt CWD if available, else file tree path
            val homePath = fileTreeViewModel.currentPath.value
            val cwd = if (promptCwd != null) {
                // Expand ~ to home directory
                if (promptCwd.startsWith("~")) {
                    homePath + promptCwd.removePrefix("~")
                } else {
                    promptCwd
                }
            } else {
                homePath
            }
            val fullPath = if (path.startsWith("/")) {
                path
            } else {
                "$cwd/$path".replace("/./", "/")
            }
            Log.d(TAG, "Path tapped: $path -> $fullPath (cwd=$cwd, promptCwd=$promptCwd)")
            editorViewModel.openFile(fullPath, detected.line, detected.column)
            if (isSplitMode) {
                if (!editorVisible) {
                    editorVisible = true
                    applySplitVisibility()
                }
            } else {
                switchToEditor()
            }
        }

        terminalView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newW = right - left
            val newH = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (hasInitialized && !suppressResize && (newW != oldW || newH != oldH) && newW > 0 && newH > 0) {
                val cols = terminalView.calculateColumns()
                val rows = terminalView.calculateRows()
                if (cols > 0 && rows > 0 && (cols != lastResizeCols || rows != lastResizeRows)) {
                    // Debounce resize: wait for layout to stabilize before notifying server
                    resizeJob?.cancel()
                    resizeJob = lifecycleScope.launch {
                        delay(150)
                        lastResizeCols = cols
                        lastResizeRows = rows
                        // Bridge.resize() handles extended mode internally
                        viewModel.resize(cols, rows)
                    }
                }
            }
        }
    }

    private fun setupKeyboardToolbar() {
        keyboardToolbar.onSpecialKey = { key ->
            terminalView.sendSpecialKey(key)
        }
        keyboardToolbar.onCharKey = { ch ->
            if (terminalView.ctrlDown && ch.length == 1) {
                val c = ch[0]
                val ctrlByte = when {
                    c in 'a'..'z' -> (c.code - 0x60).toByte()
                    c in 'A'..'Z' -> (c.code - 0x40).toByte()
                    else -> null
                }
                if (ctrlByte != null) {
                    viewModel.writeInput(byteArrayOf(ctrlByte))
                } else {
                    terminalView.sendText(ch)
                }
            } else if (terminalView.altDown) {
                viewModel.writeInput(byteArrayOf(0x1b) + ch.toByteArray(Charsets.UTF_8))
            } else {
                terminalView.sendText(ch)
            }
        }
        keyboardToolbar.onCtrlToggle = { active ->
            terminalView.ctrlDown = active
        }
        keyboardToolbar.onAltToggle = { active ->
            terminalView.altDown = active
        }
        keyboardToolbar.onPaste = {
            terminalView.pasteFromClipboard()
        }
        keyboardToolbar.onImagePaste = {
            showImageUploadOptions()
        }
    }

    private fun showImageUploadOptions() {
        val modes = arrayOf("Upload to /tmp", "Upload to project/images")
        val currentMode = settingsRepository.imageUploadMode
        val checked = if (currentMode == "project") 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Image Upload")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                settingsRepository.imageUploadMode = if (which == 1) "project" else "tmp"
                dialog.dismiss()
                imagePickerLauncher.launch("image/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleImagePicked(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Toast.makeText(this@WorkspaceActivity, "Failed to read image", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val timestamp = System.currentTimeMillis()
                val ext = contentResolver.getType(uri)?.substringAfter("/")?.let {
                    when (it) { "jpeg" -> "jpg"; "png" -> "png"; "gif" -> "gif"; "webp" -> "webp"; else -> "png" }
                } ?: "png"
                val fileName = "minicode-$timestamp.$ext"

                val uploadDir = if (settingsRepository.imageUploadMode == "project") {
                    val currentPath = fileTreeViewModel.currentPath.value
                    if (currentPath.isNotEmpty()) "$currentPath/images" else "/tmp"
                } else {
                    "/tmp"
                }

                Toast.makeText(this@WorkspaceActivity, "Uploading image...", Toast.LENGTH_SHORT).show()

                val remotePath = fileTreeViewModel.uploadImage(uploadDir, fileName, bytes)
                if (remotePath != null) {
                    viewModel.writeInput((remotePath).toByteArray(Charsets.UTF_8))
                    Toast.makeText(this@WorkspaceActivity, "Image uploaded", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image upload failed", e)
                Toast.makeText(this@WorkspaceActivity, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMicFab() {
        val density = resources.displayMetrics.density
        val btnSize = (42 * density).toInt()
        val micSize = (56 * density).toInt()
        val micRecSize = (64 * density).toInt()
        val spacing = (4 * density).toInt()
        val margin = (8 * density).toInt()
        val btnBg = 0x883E3E3E.toInt()
        val btnTextColor = 0xFFD4D4D4.toInt()
        val timerBg = 0xCC1E1E1E.toInt()

        val rootFrame = findViewById<FrameLayout>(android.R.id.content)
            .getChildAt(0) as? FrameLayout
            ?: (findViewById<View>(android.R.id.content) as FrameLayout)

        // Container for the whole floating toolbar
        val container = FrameLayout(this).apply {
            elevation = 8 * density
            clipChildren = false
            clipToPadding = false
        }
        floatingToolbar = container

        // Column of buttons
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Timer label — positioned above the column
        val timer = TextView(this).apply {
            setTextColor(0xFFFF3B30.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(timerBg)
                cornerRadius = 8 * density
            })
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            visibility = View.GONE
        }
        timerLabel = timer

        fun makeIconBtn(iconRes: Int): View {
            val iconPad = (10 * density).toInt()
            return android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(iconPad, iconPad, iconPad, iconPad)
                setBackgroundDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(btnBg)
                })
            }
        }

        // Image upload button
        val imgBtn = makeIconBtn(R.drawable.ic_image)
        column.addView(imgBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
            bottomMargin = spacing
        })

        // Esc button
        val escBtn = makeIconBtn(R.drawable.ic_esc_key)
        column.addView(escBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
            bottomMargin = spacing
        })

        // Backspace button
        val bsBtn = makeIconBtn(R.drawable.ic_backspace)
        column.addView(bsBtn, LinearLayout.LayoutParams(micSize, micSize).apply {
            bottomMargin = spacing
        })

        // Enter button
        val enterBtn = makeIconBtn(R.drawable.ic_enter_key)
        column.addView(enterBtn, LinearLayout.LayoutParams(micSize, micSize).apply {
            bottomMargin = spacing
        })

        // Map buttons to their actions — route to editor when it's active
        val btnActions = mapOf<View, () -> Unit>(
            imgBtn to {
                showImageUploadOptions()
            },
            escBtn to {
                if (isEditorActive()) editorPanel.sendKeyEvent(android.view.KeyEvent.KEYCODE_ESCAPE)
                else terminalView.sendSpecialKey(TerminalView.SpecialKey.ESCAPE)
            },
            bsBtn to {
                if (isEditorActive()) editorPanel.sendKeyEvent(android.view.KeyEvent.KEYCODE_DEL)
                else terminalView.sendSpecialKey(TerminalView.SpecialKey.BACKSPACE)
            },
            enterBtn to {
                if (isEditorActive()) editorPanel.sendKeyEvent(android.view.KeyEvent.KEYCODE_ENTER)
                else terminalView.sendSpecialKey(TerminalView.SpecialKey.ENTER)
            },
        )

        // Mic button
        val mic = MicFabView(this)
        micFab = mic
        mic.onTimerUpdate = { text ->
            timerLabel?.let {
                it.text = text
                if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
            }
        }
        column.addView(mic, LinearLayout.LayoutParams(micSize, micSize))

        container.addView(column)

        // Timer above column — use negative translation
        container.addView(timer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        ))

        // Calculate total container size: imgBtn + escBtn (btnSize) + bsBtn + enterBtn (micSize) + mic (micSize) + 4 spacers
        val totalH = btnSize * 2 + micSize * 2 + spacing * 4 + micSize
        val totalW = micRecSize // wide enough for expanded mic
        val containerLp = FrameLayout.LayoutParams(totalW, totalH + (24 * density).toInt()) // extra for timer
        rootFrame.addView(container, containerLp)

        // Timer sits at top of container, column below it
        column.setPadding(0, (24 * density).toInt(), 0, 0)

        // Position from saved settings
        container.post {
            val parentW = rootFrame.width
            val parentH = rootFrame.height
            // micButtonY is fraction for bottom edge of container
            val bottomY = settingsRepository.micButtonY * parentH
            val savedY = (bottomY - container.height).coerceIn(0f, (parentH - container.height).toFloat())
            container.y = savedY
            if (settingsRepository.micButtonRight) {
                container.x = (parentW - container.width - margin).toFloat()
            } else {
                container.x = margin.toFloat()
            }
        }

        // Dragging the whole container — touch on any child drags
        var downX = 0f
        var downY = 0f
        var downContX = 0f
        var downContY = 0f
        var isDragging = false
        var isRecording = false
        var startRecordingRunnable: Runnable? = null
        var keyRepeatRunnable: Runnable? = null
        var toolbarToggleRunnable: Runnable? = null
        val repeatInitialDelay = 400L
        val repeatInterval = 50L

        fun stopKeyRepeat(v: View) {
            keyRepeatRunnable?.let { v.removeCallbacks(it) }
            keyRepeatRunnable = null
        }

        fun startKeyRepeat(v: View, action: () -> Unit) {
            stopKeyRepeat(v)
            val runnable = object : Runnable {
                override fun run() {
                    if (!isDragging) {
                        action()
                        v.postDelayed(this, repeatInterval)
                    }
                }
            }
            keyRepeatRunnable = runnable
            v.postDelayed(runnable, repeatInitialDelay)
        }

        val dragTouchListener = View.OnTouchListener { v, event ->
            val parentW = rootFrame.width
            val parentH = rootFrame.height
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downContX = container.x
                    downContY = container.y
                    isDragging = false
                    if (v === mic) {
                        val runnable = Runnable {
                            if (!isDragging) {
                                isRecording = true
                                micHolding = true
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                val micLp = mic.layoutParams as LinearLayout.LayoutParams
                                micLp.width = micRecSize
                                micLp.height = micRecSize
                                mic.layoutParams = micLp
                                mic.recording = true
                                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    startSpeechRecognition()
                                } else {
                                    micPendingStart = true
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        startRecordingRunnable = runnable
                        mic.postDelayed(runnable, 150)
                    } else {
                        // Non-mic button: fire immediately + start key repeat
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        btnActions[v]?.let { action ->
                            action()
                            startKeyRepeat(v, action)
                        }
                        // Esc long-press (500ms) toggles keyboard toolbar
                        if (v === escBtn) {
                            val runnable = Runnable {
                                if (!isDragging) {
                                    stopKeyRepeat(v)
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    settingsRepository.showKeyboardToolbar = !settingsRepository.showKeyboardToolbar
                                    keyboardToolbar.visibility = if (settingsRepository.showKeyboardToolbar) View.VISIBLE else View.GONE
                                }
                            }
                            toolbarToggleRunnable = runnable
                            v.postDelayed(runnable, 500L)
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && (dx * dx + dy * dy) > (16 * density * 16 * density)) {
                        isDragging = true
                        startRecordingRunnable?.let { mic.removeCallbacks(it) }
                        stopKeyRepeat(v)
                        toolbarToggleRunnable?.let { v.removeCallbacks(it) }
                        toolbarToggleRunnable = null
                        if (isRecording) {
                            micHolding = false
                            destroySpeechRecognizer()
                            sherpaRecognizer.cancelRecording()
                            // Only dismiss dialog if this was the first recording
                            if (voiceInputDialog?.isCurrentlyRecording() == true) {
                                voiceInputDialog?.onRecordingStopped()
                                // Restore to edit state (cancel this recording segment)
                                voiceInputDialog?.onFinalResult(null)
                            }
                            resetMicState()
                            isRecording = false
                        }
                    }
                    if (isDragging) {
                        container.x = (downContX + dx).coerceIn(0f, (parentW - container.width).toFloat())
                        container.y = (downContY + dy).coerceIn(0f, (parentH - container.height).toFloat())
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    startRecordingRunnable?.let { mic.removeCallbacks(it) }
                    startRecordingRunnable = null
                    stopKeyRepeat(v)
                    toolbarToggleRunnable?.let { v.removeCallbacks(it) }
                    toolbarToggleRunnable = null
                    if (isDragging) {
                        val centerX = container.x + container.width / 2f
                        val onRight = centerX > parentW / 2f
                        container.x = if (onRight) (parentW - container.width - margin).toFloat() else margin.toFloat()
                        settingsRepository.micButtonRight = onRight
                        settingsRepository.micButtonY = (container.y + container.height) / parentH
                    } else if (isRecording && v === mic) {
                        micHolding = false
                        stopSpeechRecognition()
                    } else if (!isRecording && v === mic) {
                        // Quick tap on mic: toggle keyboard (unless voice dialog is open)
                        if (voiceInputDialog == null) {
                            toggleKeyboard()
                        }
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        // Apply drag listener to all buttons
        imgBtn.setOnTouchListener(dragTouchListener)
        escBtn.setOnTouchListener(dragTouchListener)
        bsBtn.setOnTouchListener(dragTouchListener)
        enterBtn.setOnTouchListener(dragTouchListener)
        mic.setOnTouchListener(dragTouchListener)
    }

    private var lastBellTime = 0L

    private fun handleTerminalBell() {
        val mode = settingsRepository.bellMode
        if (mode == 0) return
        // Debounce: ignore bells within 500ms of each other
        val now = System.currentTimeMillis()
        if (now - lastBellTime < 500) return
        lastBellTime = now

        // Vibrate (mode 1 and 2)
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}

        // Chime (mode 2 only — uses ALARM stream to sound even on silent)
        if (mode >= 2) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(this, uri)
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone?.play()
            } catch (_: Exception) {}
        }
    }

    private fun sendVoiceText(text: String) {
        if (editorPanel.hasEditorFocus()) {
            editorPanel.insertTextAtCursor(text + "\n")
        } else {
            // Use \r (carriage return) — this is what the Enter key sends in terminals.
            // \n would be interpreted as a newline, not a submit action.
            terminalView.sendText(text + "\r")
        }
    }

    private fun resetMicState() {
        val mic = micFab ?: return
        val density = resources.displayMetrics.density
        val micSize = (48 * density).toInt()
        mic.recording = false
        val micLp = mic.layoutParams as? LinearLayout.LayoutParams ?: return
        micLp.width = micSize
        micLp.height = micSize
        mic.layoutParams = micLp
        timerLabel?.visibility = View.GONE
    }

    private fun isEditorActive(): Boolean {
        if (isSplitMode) return editorPanel.hasEditorFocus()
        return activePanel == "editor"
    }

    private fun getMicFabScreenTop(): Int {
        val toolbar = floatingToolbar ?: return 0
        val loc = IntArray(2)
        toolbar.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun getTerminalScreenLeft(): Int {
        val loc = IntArray(2)
        terminalView.getLocationOnScreen(loc)
        return loc[0]
    }

    private fun setMicFabActive(active: Boolean) {
        if (!active) {
            resetMicState()
        }
    }

    private fun startSpeechRecognition() {
        val engine = settingsRepository.speechEngine
        Log.d(TAG, "startSpeechRecognition: engine=$engine, dialogOpen=${voiceInputDialog != null}")

        if (engine == "parakeet") {
            startParakeetRecognition()
        } else {
            startGoogleRecognition()
        }
    }

    private fun startGoogleRecognition() {
        Log.d(TAG, "startGoogleRecognition: available=${SpeechRecognizer.isRecognitionAvailable(this)}")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            setMicFabActive(false)
            return
        }

        // Accumulated text across multiple Google recognition sessions
        val accumulatedText = StringBuilder()

        val dialog = VoiceInputDialog(
            context = this,
            offline = false,
            anchorBottomY = getMicFabScreenTop(),
            anchorLeftX = getTerminalScreenLeft(),
            onConfirm = { text ->
                Log.d(TAG, "Voice input confirmed (google): '$text'")
                sendVoiceText(text)
                voiceInputDialog = null
            },
            onCancel = {
                Log.d(TAG, "Voice input cancelled (google)")
                destroySpeechRecognizer()
                voiceInputDialog = null
            },
            onRecordStart = {
                Log.d(TAG, "Google re-record start")
                startGoogleReRecord(voiceInputDialog!!)
            },
            onRecordStop = {
                Log.d(TAG, "Google re-record stop")
                voiceInputDialog?.onRecordingStopped()
                speechRecognizer?.stopListening()
            },
        )
        voiceInputDialog = dialog
        dialog.show()

        destroySpeechRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    Log.d(TAG, "Speech onResults: '$text'")
                    if (!text.isNullOrEmpty()) {
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                        accumulatedText.append(text)
                        dialog.updatePartialText(accumulatedText.toString())
                    }
                    if (micHolding) {
                        destroySpeechRecognizer()
                        terminalView.postDelayed({ if (micHolding) startGoogleRecognitionContinue(dialog, accumulatedText) }, 300)
                    } else {
                        dialog.onFinalResult(accumulatedText.toString().ifEmpty { null })
                        setMicFabActive(false)
                        destroySpeechRecognizer()
                    }
                }
                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        val preview = if (accumulatedText.isNotEmpty()) "$accumulatedText $text" else text
                        dialog.updatePartialText(preview)
                    }
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "Speech onError: $error")
                    if (micHolding && (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)) {
                        destroySpeechRecognizer()
                        terminalView.postDelayed({ if (micHolding) startGoogleRecognitionContinue(dialog, accumulatedText) }, 500)
                    } else {
                        micHolding = false
                        dialog.onFinalResult(accumulatedText.toString().ifEmpty { null })
                        setMicFabActive(false)
                        destroySpeechRecognizer()
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    micFab?.updateRms(rmsdB)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600000L)
            }
            startListening(intent)
            Log.d(TAG, "Speech startListening called")
        }
    }

    private fun startGoogleRecognitionContinue(dialog: VoiceInputDialog, accumulatedText: StringBuilder) {
        destroySpeechRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                        accumulatedText.append(text)
                        dialog.updatePartialText(accumulatedText.toString())
                    }
                    if (micHolding) {
                        destroySpeechRecognizer()
                        terminalView.postDelayed({ if (micHolding) startGoogleRecognitionContinue(dialog, accumulatedText) }, 300)
                    } else {
                        dialog.onFinalResult(accumulatedText.toString().ifEmpty { null })
                        setMicFabActive(false)
                        destroySpeechRecognizer()
                    }
                }
                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        val preview = if (accumulatedText.isNotEmpty()) "$accumulatedText $text" else text
                        dialog.updatePartialText(preview)
                    }
                }
                override fun onError(error: Int) {
                    if (micHolding && (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)) {
                        destroySpeechRecognizer()
                        terminalView.postDelayed({ if (micHolding) startGoogleRecognitionContinue(dialog, accumulatedText) }, 500)
                    } else {
                        micHolding = false
                        dialog.onFinalResult(accumulatedText.toString().ifEmpty { null })
                        setMicFabActive(false)
                        destroySpeechRecognizer()
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { micFab?.updateRms(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600000L)
            }
            startListening(intent)
        }
    }

    private fun startParakeetRecognition() {
        Log.d(TAG, "startParakeetRecognition")
        if (!sherpaRecognizer.isAvailable()) {
            Toast.makeText(this, "Parakeet model not downloaded. Go to Settings to download.", Toast.LENGTH_LONG).show()
            setMicFabActive(false)
            return
        }

        // Create and show voice input dialog
        val dialog = VoiceInputDialog(
            context = this,
            offline = true,
            anchorBottomY = getMicFabScreenTop(),
            anchorLeftX = getTerminalScreenLeft(),
            onConfirm = { text ->
                Log.d(TAG, "Voice input confirmed: '$text'")
                sendVoiceText(text)
                voiceInputDialog = null
            },
            onCancel = {
                Log.d(TAG, "Voice input cancelled")
                sherpaRecognizer.cancelRecording()
                voiceInputDialog = null
            },
            onRecordStart = {
                Log.d(TAG, "Parakeet re-record start")
                startParakeetReRecord()
            },
            onRecordStop = {
                Log.d(TAG, "Parakeet re-record stop")
                voiceInputDialog?.onRecordingStopped()
                lifecycleScope.launch {
                    val text = sherpaRecognizer.stopRecordingAndRecognize()
                    Log.d(TAG, "Parakeet re-record result: '$text'")
                    voiceInputDialog?.onFinalResult(text)
                }
            },
        )
        voiceInputDialog = dialog
        dialog.show()

        sherpaRecognizer.onRmsChanged = { rmsdB ->
            runOnUiThread { micFab?.updateRms(rmsdB) }
        }
        sherpaRecognizer.onPartialResult = { text ->
            runOnUiThread { dialog.updatePartialText(text) }
        }
        if (!sherpaRecognizer.startRecording()) {
            Toast.makeText(this, "Failed to start audio recording", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            voiceInputDialog = null
            setMicFabActive(false)
        }
    }

    private fun stopParakeetRecognition() {
        Log.d(TAG, "stopParakeetRecognition")
        val dialog = voiceInputDialog
        dialog?.onRecordingStopped()
        lifecycleScope.launch {
            val text = sherpaRecognizer.stopRecordingAndRecognize()
            Log.d(TAG, "Parakeet final result: '$text'")
            dialog?.onFinalResult(text)
            setMicFabActive(false)
        }
    }

    private fun startParakeetReRecord() {
        Log.d(TAG, "startParakeetReRecord")
        sherpaRecognizer.onRmsChanged = { rmsdB ->
            runOnUiThread { micFab?.updateRms(rmsdB) }
        }
        sherpaRecognizer.onPartialResult = { text ->
            runOnUiThread { voiceInputDialog?.updatePartialText(text) }
        }
        if (!sherpaRecognizer.startRecording()) {
            Toast.makeText(this, "Failed to start audio recording", Toast.LENGTH_SHORT).show()
            setMicFabActive(false)
        }
    }

    private fun startGoogleReRecord(dialog: VoiceInputDialog) {
        Log.d(TAG, "startGoogleReRecord")
        val accumulatedText = StringBuilder()
        destroySpeechRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                        accumulatedText.append(text)
                        dialog.updatePartialText(accumulatedText.toString())
                    }
                    if (micHolding) {
                        destroySpeechRecognizer()
                        terminalView.postDelayed({ if (micHolding) startGoogleRecognitionContinue(dialog, accumulatedText) }, 300)
                    } else {
                        dialog.onFinalResult(accumulatedText.toString().ifEmpty { null })
                        setMicFabActive(false)
                        destroySpeechRecognizer()
                    }
                }
                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        val preview = if (accumulatedText.isNotEmpty()) "$accumulatedText $text" else text
                        dialog.updatePartialText(preview)
                    }
                }
                override fun onError(error: Int) {
                    if (micHolding && (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)) {
                        destroySpeechRecognizer()
                        terminalView.postDelayed({ if (micHolding) startGoogleRecognitionContinue(dialog, accumulatedText) }, 500)
                    } else {
                        micHolding = false
                        dialog.onFinalResult(accumulatedText.toString().ifEmpty { null })
                        setMicFabActive(false)
                        destroySpeechRecognizer()
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { micFab?.updateRms(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600000L)
            }
            startListening(intent)
        }
    }

    private fun stopSpeechRecognition() {
        if (settingsRepository.speechEngine == "parakeet") {
            stopParakeetRecognition()
        } else {
            voiceInputDialog?.onRecordingStopped()
            speechRecognizer?.let {
                Log.d(TAG, "stopSpeechRecognition: stopListening")
                it.stopListening()
                // onResults callback will call dialog.onFinalResult
            }
        }
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun setupHeader() {
        btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        btnDisconnect.setOnClickListener {
            confirmDisconnect()
        }

        // Toggle soft keyboard
        btnToggleKeyboard.setOnClickListener {
            toggleKeyboard()
        }

        // Cycle bell mode: off → vibrate → vibrate+chime → off
        updateBellIcon()
        btnToggleBell.setOnClickListener {
            settingsRepository.bellMode = (settingsRepository.bellMode + 1) % 3
            updateBellIcon()
            val label = when (settingsRepository.bellMode) {
                0 -> "Bell: off"
                1 -> "Bell: vibrate"
                else -> "Bell: vibrate + chime"
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        if (!isSplitMode) {
            navFiles?.setOnClickListener { switchToFiles() }
            navTerminal?.setOnClickListener { switchToTerminal() }
            navEditor?.setOnClickListener { switchToEditor() }
        }
    }

    private fun updateBellIcon() {
        when (settingsRepository.bellMode) {
            0 -> {
                btnToggleBell.setImageResource(R.drawable.ic_notifications_off)
                btnToggleBell.alpha = 0.4f
            }
            1 -> {
                btnToggleBell.setImageResource(R.drawable.ic_notifications)
                btnToggleBell.alpha = 0.7f
            }
            else -> {
                btnToggleBell.setImageResource(R.drawable.ic_notifications)
                btnToggleBell.alpha = 1.0f
            }
        }
    }

    private fun setupFileTree() {
        fileTreePanel.viewModel = fileTreeViewModel
        fileTreePanel.onFileOpen = { filePath ->
            editorViewModel.openFile(filePath)
            if (isSplitMode) {
                if (!editorVisible) {
                    editorVisible = true
                    applySplitVisibility()
                }
            } else {
                switchToEditor()
            }
        }
    }

    private fun setupEditor() {
        editorPanel.viewModel = editorViewModel
        editorPanel.setCoroutineScope(lifecycleScope)
        // Git service will be set when session is available
    }

    // --- Split mode: dividers ---

    private fun setupSplitDividers() {
        val handle = splitHandle ?: return

        handle.onDragVertical = onDragVertical@{ delta ->
            if (!fileTreeVisible) return@onDragVertical
            val rightPanel = findViewById<View>(R.id.right_panel) ?: return@onDragVertical
            val parent = rightPanel.parent as? View ?: return@onDragVertical
            val divW = dividerVertical?.width ?: 0
            val totalWidth = (parent.width - parent.paddingLeft - parent.paddingRight - divW).toFloat()
            if (totalWidth <= 0) return@onDragVertical

            val currentTreeWidth = fileTreeWidthRatio * totalWidth
            val newTreeWidth = (currentTreeWidth + delta).coerceIn(
                80f * resources.displayMetrics.density,
                totalWidth - 200f * resources.displayMetrics.density,
            )
            fileTreeWidthRatio = newTreeWidth / totalWidth
            applySplitVisibility()
        }

        handle.onDragHorizontal = onDragHorizontal@{ delta ->
            if (!editorVisible) return@onDragHorizontal
            val tc = terminalContainer ?: return@onDragHorizontal
            val parent = tc.parent as? View ?: return@onDragHorizontal
            val divH = dividerHorizontal?.height ?: 0
            val totalHeight = (parent.height - parent.paddingTop - parent.paddingBottom - divH).toFloat()
            if (totalHeight <= 0) return@onDragHorizontal

            val currentEditorHeight = editorHeightRatio * totalHeight
            val newEditorHeight = (currentEditorHeight + delta).coerceIn(
                60f * resources.displayMetrics.density,
                totalHeight - 60f * resources.displayMetrics.density,
            )
            editorHeightRatio = newEditorHeight / totalHeight
            applySplitVisibility()
        }

        handle.onFlingUp = {
            if (editorVisible) {
                editorVisible = false
                applySplitVisibility()
            }
        }
        handle.onFlingLeft = {
            if (fileTreeVisible) {
                fileTreeVisible = false
                applySplitVisibility()
            }
        }
        handle.onFlingDown = {
            if (!editorVisible) {
                editorVisible = true
                applySplitVisibility()
            }
        }
        handle.onFlingRight = {
            if (!fileTreeVisible) {
                fileTreeVisible = true
                applySplitVisibility()
            }
        }
        handle.onTap = {
            if (!fileTreeVisible && !editorVisible) {
                fileTreeVisible = true
                editorVisible = true
                applySplitVisibility()
            } else if (fileTreeVisible && !editorVisible) {
                editorVisible = true
                applySplitVisibility()
            } else if (!fileTreeVisible && editorVisible) {
                fileTreeVisible = true
                applySplitVisibility()
            }
        }
        handle.onDoubleTap = {
            if (fileTreeVisible || editorVisible) {
                fileTreeVisible = false
                editorVisible = false
                applySplitVisibility()
            }
        }
    }

    // --- Split mode: toggle buttons ---

    private fun setupSplitToggles() {
        btnToggleFileTree?.setOnClickListener {
            fileTreeVisible = !fileTreeVisible
            applySplitVisibility()
        }
        btnToggleEditor?.setOnClickListener {
            editorVisible = !editorVisible
            applySplitVisibility()
        }
    }

    private fun applySplitVisibility() {
        val dv = dividerVertical ?: return
        val dh = dividerHorizontal ?: return
        val tc = terminalContainer ?: return
        val rightPanel = findViewById<View>(R.id.right_panel) ?: return

        if (fileTreeVisible) {
            fileTreePanel.visibility = View.VISIBLE
            dv.visibility = View.VISIBLE
            // Reset to weight-based layout
            val ftLp = fileTreePanel.layoutParams as LinearLayout.LayoutParams
            val rpLp = rightPanel.layoutParams as LinearLayout.LayoutParams
            ftLp.width = 0
            ftLp.weight = fileTreeWidthRatio
            rpLp.width = 0
            rpLp.weight = 1f - fileTreeWidthRatio
            fileTreePanel.layoutParams = ftLp
            rightPanel.layoutParams = rpLp
        } else {
            fileTreePanel.visibility = View.GONE
            dv.visibility = View.GONE
            val rpLp = rightPanel.layoutParams as LinearLayout.LayoutParams
            rpLp.width = 0
            rpLp.weight = 1f
            rightPanel.layoutParams = rpLp
        }

        if (editorVisible) {
            editorPanel.visibility = View.VISIBLE
            dh.visibility = View.VISIBLE
            val eLp = editorPanel.layoutParams as LinearLayout.LayoutParams
            val tLp = tc.layoutParams as LinearLayout.LayoutParams
            eLp.height = 0
            eLp.weight = editorHeightRatio
            tLp.height = 0
            tLp.weight = 1f - editorHeightRatio
            editorPanel.layoutParams = eLp
            tc.layoutParams = tLp
        } else {
            editorPanel.visibility = View.GONE
            dh.visibility = View.GONE
            val tLp = tc.layoutParams as LinearLayout.LayoutParams
            tLp.height = 0
            tLp.weight = 1f
            tc.layoutParams = tLp
        }

        // Update toggle button tints
        val activeColor = getColor(R.color.dark_text)
        val inactiveColor = getColor(R.color.dark_text_secondary)
        btnToggleFileTree?.setColorFilter(if (fileTreeVisible) activeColor else inactiveColor)
        btnToggleEditor?.setColorFilter(if (editorVisible) activeColor else inactiveColor)

        // Position the split handle
        positionSplitHandle()
    }

    private fun positionSplitHandle() {
        val handle = splitHandle ?: return
        val dv = dividerVertical ?: return
        val dh = dividerHorizontal ?: return

        handle.visibility = View.VISIBLE

        if (fileTreeVisible && editorVisible) {
            handle.mode = SplitHandleView.Mode.BOTH
        } else if (fileTreeVisible) {
            handle.mode = SplitHandleView.Mode.VERTICAL_ONLY
        } else if (editorVisible) {
            handle.mode = SplitHandleView.Mode.HORIZONTAL_ONLY
        } else {
            handle.mode = SplitHandleView.Mode.NONE
        }

        // Wait for layout to complete before reading positions
        val root = handle.parent as View
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val handleSize = handle.layoutParams.width
                val lp = handle.layoutParams as FrameLayout.LayoutParams
                // getLocationInWindow returns absolute positions, but FrameLayout margins
                // are relative to the content area (after padding), so subtract padding
                val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
                val offsetX = rootLoc[0] + root.paddingLeft
                val offsetY = rootLoc[1] + root.paddingTop

                when (handle.mode) {
                    SplitHandleView.Mode.BOTH -> {
                        val dvLoc = IntArray(2); dv.getLocationInWindow(dvLoc)
                        val dhLoc = IntArray(2); dh.getLocationInWindow(dhLoc)
                        lp.leftMargin = dvLoc[0] + dv.width / 2 - offsetX - handleSize / 2
                        lp.topMargin = dhLoc[1] + dh.height / 2 - offsetY - handleSize / 2
                    }
                    SplitHandleView.Mode.VERTICAL_ONLY -> {
                        val dvLoc = IntArray(2); dv.getLocationInWindow(dvLoc)
                        lp.leftMargin = dvLoc[0] + dv.width / 2 - offsetX - handleSize / 2
                        lp.topMargin = (root.height - root.paddingTop - root.paddingBottom) / 2 - handleSize / 2
                    }
                    SplitHandleView.Mode.HORIZONTAL_ONLY -> {
                        val dhLoc = IntArray(2); dh.getLocationInWindow(dhLoc)
                        lp.leftMargin = (16 * resources.displayMetrics.density).toInt()
                        lp.topMargin = dhLoc[1] + dh.height / 2 - offsetY - handleSize / 2
                    }
                    SplitHandleView.Mode.NONE -> {
                        lp.leftMargin = (16 * resources.displayMetrics.density).toInt()
                        lp.topMargin = (root.height - root.paddingTop - root.paddingBottom) / 2 - handleSize / 2
                    }
                }
                handle.layoutParams = lp
            }
        })
    }

    // --- Portrait mode switching ---

    // Which compact panel is active: "terminal", "editor", or "files"
    private var activePanel = "terminal"

    private fun switchToFiles() {
        if (isSplitMode) return
        activePanel = "files"
        showingEditor = false
        terminalView.visibility = View.GONE
        editorPanel.visibility = View.GONE
        fileTreePanel.visibility = View.VISIBLE
        keyboardToolbar.visibility = View.GONE
        updateBottomNav()
    }

    private fun switchToTerminal() {
        if (isSplitMode) return
        activePanel = "terminal"
        showingEditor = false
        terminalView.visibility = View.VISIBLE
        editorPanel.visibility = View.GONE
        fileTreePanel.visibility = View.GONE
        keyboardToolbar.visibility = if (settingsRepository.showKeyboardToolbar) View.VISIBLE else View.GONE
        updateBottomNav()
        terminalView.requestFocus()
    }

    private fun switchToEditor() {
        if (isSplitMode) return
        activePanel = "editor"
        showingEditor = true
        terminalView.visibility = View.GONE
        editorPanel.visibility = View.VISIBLE
        fileTreePanel.visibility = View.GONE
        keyboardToolbar.visibility = View.GONE
        updateBottomNav()
    }

    private fun updateBottomNav() {
        val activeColor = getColor(R.color.dark_text)
        val inactiveColor = getColor(R.color.dark_text_secondary)

        navFilesIcon?.setColorFilter(if (activePanel == "files") activeColor else inactiveColor)
        navFilesLabel?.setTextColor(if (activePanel == "files") activeColor else inactiveColor)
        navTerminalIcon?.setColorFilter(if (activePanel == "terminal") activeColor else inactiveColor)
        navTerminalLabel?.setTextColor(if (activePanel == "terminal") activeColor else inactiveColor)
        navEditorIcon?.setColorFilter(if (activePanel == "editor") activeColor else inactiveColor)
        navEditorLabel?.setTextColor(if (activePanel == "editor") activeColor else inactiveColor)
    }

    // --- Observers ---

    private fun kotlinx.coroutines.CoroutineScope.observeState() {
        // Session list (tab bar) — combine sessionList + activeSessionId
        // Track per-session label observers so we refresh tabs when titles change
        var labelJobs = listOf<Job>()
        var previousSessionIds = emptySet<String>()
        launch {
            viewModel.sessionList.collect { sessions ->
                val activeId = viewModel.activeSessionId.value
                sessionTabBar.updateSessions(sessions, activeId)

                // Detect sessions removed by server-side logout
                val currentIds = sessions.map { it.id }.toSet()
                val removedIds = previousSessionIds - currentIds
                for (removedId in removedIds) {
                    fileTreeViewModel.removeSession(removedId)
                    editorViewModel.removeSession(removedId)
                    editorPanel.removeSessionCache(removedId)
                }
                previousSessionIds = currentIds

                // If all sessions gone (e.g. last session logged out), return to connections
                if (sessions.isEmpty() && removedIds.isNotEmpty()) {
                    finish()
                    return@collect
                }
                // If sessions were removed, force-switch to the current active session
                // to refresh the terminal view (the old emulator may still be displayed)
                if (removedIds.isNotEmpty() && sessions.isNotEmpty()) {
                    val newActiveId = viewModel.activeSessionId.value
                    if (newActiveId != null) {
                        forceSwitch(newActiveId)
                    }
                }
                // Observe each session's label and activity state for tab updates
                labelJobs.forEach { it.cancel() }
                labelJobs = sessions.flatMap { session ->
                    listOf(
                        launch {
                            session.label.collect {
                                sessionTabBar.updateSessions(
                                    viewModel.sessionList.value,
                                    viewModel.activeSessionId.value
                                )
                            }
                        },
                        launch {
                            session.hasActiveOutput.collect { active ->
                                sessionTabBar.updateActivity(session.id, active)
                            }
                        }
                    )
                }
            }
        }
        launch {
            viewModel.activeSessionId.collect { activeId ->
                val sessions = viewModel.sessionList.value
                if (sessions.isNotEmpty()) {
                    sessionTabBar.updateSessions(sessions, activeId)
                }
            }
        }
        launch {
            viewModel.sessionState.collect { state ->
                Log.d(TAG, "Session state: $state")
                updateConnectionState(state)
            }
        }
        launch {
            viewModel.bridge.collect { bridge ->
                if (bridge != null) {
                    bridge.extendedRows = settingsRepository.extendedScrollRows
                    terminalView.emulator = bridge.emulator
                    bridge.emulator.onCwdChanged = { path ->
                        runOnUiThread { fileTreeViewModel.navigateTo(path) }
                    }
                    // Title callback is set in SshSessionManager.connect() and bound
                    // to the specific session handle — no need to set it here
                    bridge.emulator.onBell = {
                        handleTerminalBell()
                    }
                    // Set up git service for editor
                    viewModel.sessionManager.getSession()?.let { session ->
                        editorPanel.gitService = com.minicode.service.git.GitService(session)
                    }
                    // Apply extended rows immediately if terminal is already laid out
                    // Skip during session switch — switchToSession handles this without
                    // sending unnecessary resize/window-change to the server
                    if (!suppressResize) {
                        val cols = terminalView.calculateColumns()
                        val rows = terminalView.calculateRows()
                        if (cols > 0 && rows > 0) {
                            bridge.resize(cols, rows)
                        }
                        // Initialize SFTP services — skip during session switch since
                        // switchToSession() already handles this
                        fileTreeViewModel.initialize()
                        viewModel.activeSessionId.value?.let { editorPanel.switchSession(it) }
                        editorViewModel.initialize()
                    }
                }
            }
        }
        launch {
            viewModel.profile.collect { profile ->
                if (profile != null) {
                    textTitle.text = "${profile.username}@${profile.host}"
                }
            }
        }
        launch {
            viewModel.sessionError.collect { error ->
                if (error != null) {
                    Snackbar.make(rootView, error, Snackbar.LENGTH_LONG)
                        .setAction("Retry") {
                            val cols = terminalView.calculateColumns()
                            val rows = terminalView.calculateRows()
                            viewModel.connect(profileId!!, cols, rows)
                        }
                        .show()
                }
            }
        }
        // File tree
        launch {
            fileTreeViewModel.visibleNodes.collect { nodes ->
                fileTreePanel.updateNodes(nodes)
            }
        }
        launch {
            fileTreeViewModel.currentPath.collect { path ->
                fileTreePanel.updatePath(path)
            }
        }
        launch {
            fileTreeViewModel.isLoading.collect { loading ->
                fileTreePanel.updateLoading(loading)
            }
        }
        launch {
            fileTreeViewModel.highlightedPath.collect { path ->
                // Delay slightly so the list has time to update after reveal
                fileTreePanel.postDelayed({
                    fileTreePanel.highlightAndScrollTo(path)
                }, 100)
            }
        }
        launch {
            fileTreeViewModel.error.collect { error ->
                if (error != null) {
                    Snackbar.make(rootView, error, Snackbar.LENGTH_SHORT).show()
                    fileTreeViewModel.clearError()
                }
            }
        }
        // Editor
        launch {
            editorViewModel.tabs.collect { tabs ->
                val activeIdx = editorViewModel.activeTabIndex.value
                editorPanel.updateTabs(tabs, activeIdx)
                if (tabs.isEmpty()) {
                    if (isSplitMode) {
                        if (editorVisible) {
                            editorVisible = false
                            applySplitVisibility()
                        }
                    } else if (activePanel == "editor") {
                        switchToTerminal()
                    }
                }
            }
        }
        launch {
            editorViewModel.activeTabIndex.collect { activeIdx ->
                val tabs = editorViewModel.tabs.value
                editorPanel.updateTabs(tabs, activeIdx)
                // Reveal the active file in the file tree
                if (activeIdx in tabs.indices) {
                    fileTreeViewModel.revealFile(tabs[activeIdx].filePath)
                }
            }
        }
        launch {
            editorViewModel.isLoading.collect { loading ->
                editorPanel.updateLoading(loading)
                // When loading finishes and there's a pending jump, apply it
                if (!loading) {
                    val jump = editorViewModel.pendingJump.value
                    if (jump != null) {
                        editorPanel.postDelayed({
                            editorPanel.jumpToLine(jump.first, jump.second)
                            editorViewModel.clearPendingJump()
                        }, 150)
                    }
                }
            }
        }
        launch {
            editorViewModel.pendingJump.collect { jump ->
                // Handle jump for already-open files (no loading transition)
                if (jump != null && !editorViewModel.isLoading.value) {
                    editorPanel.postDelayed({
                        editorPanel.jumpToLine(jump.first, jump.second)
                        editorViewModel.clearPendingJump()
                    }, 150)
                }
            }
        }
        launch {
            editorViewModel.error.collect { error ->
                if (error != null) {
                    Snackbar.make(rootView, error, Snackbar.LENGTH_SHORT).show()
                    editorViewModel.clearError()
                }
            }
        }
        launch {
            editorViewModel.saveSuccess.collect { msg ->
                if (msg != null) {
                    Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
                    editorViewModel.clearSaveSuccess()
                    editorPanel?.loadGutterDiff()
                }
            }
        }
        // Auto-reconnect banner
        launch {
            viewModel.reconnecting.collect { reconnecting ->
                if (reconnecting) {
                    overlayConnecting.visibility = View.VISIBLE
                    textConnectingStatus.text = "Reconnecting..."
                }
            }
        }
    }

    private fun updateConnectionState(state: SshSessionState) {
        try {
            val statusDrawable = statusDot.background as? GradientDrawable
            when (state) {
                SshSessionState.DISCONNECTED -> {
                    statusDrawable?.setColor(getColor(R.color.dark_text_secondary))
                    overlayConnecting.visibility = View.GONE
                    stopService(Intent(this, SshConnectionService::class.java))
                }
                SshSessionState.CONNECTING -> {
                    statusDrawable?.setColor(getColor(R.color.warning))
                    overlayConnecting.visibility = View.VISIBLE
                    textConnectingStatus.text = "Connecting..."
                }
                SshSessionState.AUTHENTICATING -> {
                    statusDrawable?.setColor(getColor(R.color.warning))
                    textConnectingStatus.text = "Authenticating..."
                }
                SshSessionState.CONNECTED -> {
                    statusDrawable?.setColor(getColor(R.color.success))
                    overlayConnecting.visibility = View.GONE
                    terminalView.requestFocus()
                    val sessionCount = viewModel.sessionManager.getSessionCount()
                    val host = viewModel.profile.value?.host ?: ""
                    val notifText = if (sessionCount > 1) "$sessionCount active sessions" else "Connected to $host"
                    startForegroundService(Intent(this, SshConnectionService::class.java).apply {
                        putExtra(SshConnectionService.EXTRA_HOST, notifText)
                    })
                    promptBatteryOptimization()
                }
                SshSessionState.ERROR -> {
                    statusDrawable?.setColor(getColor(R.color.error))
                    overlayConnecting.visibility = View.GONE
                    stopService(Intent(this, SshConnectionService::class.java))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating connection state", e)
        }
    }

    private fun promptBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("minicode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_dismissed", false)) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Disable battery optimization")
            .setMessage(
                "Android may kill SSH connections in the background to save battery. " +
                "To keep your session alive, allow MiniCode to run unrestricted.\n\n" +
                "You can change this later in system Settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    // Direct request dialog (works on stock Android)
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    // Fallback: open battery optimization list (Samsung, etc.)
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        // Last resort: open general app settings
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    }
                }
            }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Don't ask again") { _, _ ->
                prefs.edit().putBoolean("battery_opt_dismissed", true).apply()
            }
            .show()
    }

    private fun confirmDisconnect() {
        val sessionCount = viewModel.sessionManager.getSessionCount()
        if (sessionCount <= 1) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Disconnect")
                .setMessage("Close this SSH session?")
                .setPositiveButton("Disconnect") { _, _ ->
                    viewModel.disconnect()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Multiple sessions: close current, switch to next
            val activeId = viewModel.activeSessionId.value ?: return
            confirmCloseSession(activeId)
        }
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        // Re-show connection list if it was active before leaving (e.g. after adding a new connection)
        if (showingConnectionList) {
            // Force re-collect profiles to pick up newly added connections
            connectionListJob?.cancel()
            connectionListJob = lifecycleScope.launch {
                connectionListViewModel.profiles.collect { profiles ->
                    connectionListPanel?.updateProfiles(profiles)
                }
            }
        }
    }

    private fun applySettings() {
        terminalView.fontSize = settingsRepository.terminalFontSize
        editorPanel.setEditorFontSize(settingsRepository.editorFontSize)
        editorPanel.setWordWrap(settingsRepository.wordWrap)
        editorPanel.setLineNumbersEnabled(settingsRepository.showLineNumbers)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
        destroySpeechRecognizer()
        sherpaRecognizer.cancelRecording()
        editorPanel.release()
        super.onDestroy()
        if (isFinishing) {
            viewModel.sessionManager.shutdown()
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (!isSplitMode && activePanel != "terminal") {
            switchToTerminal()
        } else {
            confirmDisconnect()
        }
    }
}
