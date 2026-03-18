package com.minicode.ui.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minicode.R
import com.minicode.databinding.ViewEditorPanelBinding
import com.minicode.model.EditorTab
import com.minicode.service.editor.TextMateHelper
import com.minicode.service.git.DiffLineType
import com.minicode.service.git.GitCommit
import com.minicode.service.git.GitService
import com.minicode.service.git.InlineLineType
import com.minicode.service.git.buildInlineDiff
import io.github.rosemoe.sora.lang.styling.line.LineBackground
import com.minicode.viewmodel.EditorViewModel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.lang.styling.color.ResolvableColor
import io.github.rosemoe.sora.lang.styling.line.LineGutterBackground
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewEditorPanelBinding
    private val codeEditor: CodeEditor
    var viewModel: EditorViewModel? = null
    private var suppressContentChange = false
    private var currentLanguageId = "text"
    private var currentFilePath: String? = null
    private var cursorCache = mutableMapOf<String, Pair<Int, Int>>()
    private val sessionCursorCaches = HashMap<String, MutableMap<String, Pair<Int, Int>>>()
    private val sessionFontSizes = HashMap<String, Float>()
    private var activeSessionKey: String? = null

    // Git
    var gitService: GitService? = null
    private var gitRepoRoot: String? = null
    private var gitJob: Job? = null
    private var currentDiffMarkers: Map<Int, DiffLineType> = emptyMap()
    private var coroutineScope: CoroutineScope? = null
    private var inlineDiffActive = false
    private var savedContentBeforeDiff: String? = null
    private var inlineDiffLineTypes: Map<Int, InlineLineType> = emptyMap()

    fun setCoroutineScope(scope: CoroutineScope) {
        coroutineScope = scope
    }

    init {
        binding = ViewEditorPanelBinding.inflate(LayoutInflater.from(context), this, true)
        orientation = VERTICAL

        codeEditor = binding.codeEditor
        setupCodeEditor()

        binding.btnSave.setOnClickListener {
            if (!inlineDiffActive) viewModel?.saveActiveTab()
        }

        binding.btnFind.setOnClickListener {
            toggleSearchBar()
        }

        binding.btnGitDiff.setOnClickListener {
            showGitDiff()
        }

        binding.btnGitLog.setOnClickListener {
            showGitLog()
        }

        binding.btnGitPanelClose.setOnClickListener {
            closeGitPanel()
        }

        binding.btnGitLogClose.setOnClickListener {
            closeGitLogPanel()
        }

        setupSearchBar()
    }

    // --- Git Diff ---

    private fun showGitDiff() {
        // If inline diff is active, close it
        if (inlineDiffActive) {
            closeInlineDiff()
            return
        }

        val filePath = currentFilePath ?: return
        val git = gitService ?: return
        val scope = coroutineScope ?: return

        gitJob?.cancel()
        gitJob = scope.launch {
            val root = gitRepoRoot ?: git.getRepoRoot(filePath)
            if (root == null) {
                withContext(Dispatchers.Main) {
                    showGitPanelWithText("Git Diff", "Not a git repository")
                }
                return@launch
            }
            gitRepoRoot = root

            val diff = git.diffFileFull(root, filePath)
            withContext(Dispatchers.Main) {
                if (diff.isBlank()) {
                    showGitPanelWithText("Git Diff", "No changes (file matches HEAD)")
                } else {
                    showInlineDiff(diff)
                }
            }
        }
    }

    private fun showInlineDiff(fullDiff: String) {
        val result = buildInlineDiff(fullDiff)
        if (result.lineTypes.isEmpty()) {
            showGitPanelWithText("Git Diff", "No changes (file matches HEAD)")
            return
        }

        // Save current content so we can restore
        savedContentBeforeDiff = codeEditor.text.toString()
        inlineDiffActive = true

        // Set merged content in editor
        suppressContentChange = true
        codeEditor.setText(result.text)
        codeEditor.isEditable = false
        suppressContentChange = false

        // Apply line backgrounds after editor settles (styles get recreated on setText)
        inlineDiffLineTypes = result.lineTypes
        postDelayed({ applyDiffLineStyles() }, 300)

        // Update UI to show diff mode is active
        binding.btnGitDiff.alpha = 1.0f
        binding.btnSave.visibility = View.GONE
        binding.textPath.text = "${currentFilePath} [DIFF]"
    }

    private fun applyDiffLineStyles() {
        if (!inlineDiffActive) return
        val styles = codeEditor.styles ?: return
        styles.eraseAllLineStyles()
        for ((lineIdx, type) in inlineDiffLineTypes) {
            val colorInt = when (type) {
                InlineLineType.ADDED -> 0x4022AA22.toInt()
                InlineLineType.DELETED -> 0x40AA2222.toInt()
                InlineLineType.CONTEXT -> continue
            }
            val bgColor = object : ResolvableColor {
                override fun resolve(scheme: EditorColorScheme) = colorInt
            }
            val gutterColor = object : ResolvableColor {
                override fun resolve(scheme: EditorColorScheme) = when (type) {
                    InlineLineType.ADDED -> 0xFF4EC959.toInt()
                    InlineLineType.DELETED -> 0xFFE05252.toInt()
                    else -> 0
                }
            }
            styles.addLineStyle(LineBackground(lineIdx, bgColor))
            styles.addLineStyle(LineGutterBackground(lineIdx, gutterColor))
        }
        codeEditor.invalidate()
    }

    private fun closeInlineDiff() {
        if (!inlineDiffActive) return
        inlineDiffActive = false
        codeEditor.isEditable = true
        savedContentBeforeDiff = null

        // Reload actual file content from ViewModel (not stale snapshot)
        val content = viewModel?.getActiveTab()?.content
        if (content != null) {
            suppressContentChange = true
            codeEditor.setText(content)
            suppressContentChange = false
        }

        // Clear line backgrounds and restore gutter markers
        codeEditor.styles?.eraseAllLineStyles()
        codeEditor.invalidate()
        binding.textPath.text = currentFilePath
        binding.btnGitDiff.alpha = 0.6f
        binding.btnSave.visibility = View.GONE

        // Reload gutter diff markers
        loadGutterDiff()
    }

    private fun showGitPanelWithText(title: String, text: String) {
        binding.gitPanel.visibility = View.VISIBLE
        binding.gitLogPanel.visibility = View.GONE
        binding.gitPanelTitle.text = title
        binding.gitPanelContent.text = text
    }

    private fun showGitPanelWithDiff(title: String, diff: String) {
        binding.gitPanel.visibility = View.VISIBLE
        binding.gitLogPanel.visibility = View.GONE
        binding.gitPanelTitle.text = title
        binding.gitPanelContent.text = colorizeDiff(diff)
    }

    private fun colorizeDiff(diff: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (line in diff.lines()) {
            val start = sb.length
            sb.append(line)
            sb.append('\n')
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> 0xFF4EC959.toInt() // green
                line.startsWith("-") && !line.startsWith("---") -> 0xFFE05252.toInt() // red
                line.startsWith("@@") -> 0xFF569CD6.toInt() // blue
                line.startsWith("diff ") || line.startsWith("index ") -> 0xFFD4D4D4.toInt() // white
                else -> 0xFF808080.toInt() // gray context
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun closeGitPanel() {
        binding.gitPanel.visibility = View.GONE
    }

    // --- Git Log ---

    private fun showGitLog() {
        val filePath = currentFilePath ?: return
        val git = gitService ?: return
        val scope = coroutineScope ?: return

        gitJob?.cancel()
        gitJob = scope.launch {
            val root = gitRepoRoot ?: git.getRepoRoot(filePath)
            if (root == null) {
                withContext(Dispatchers.Main) {
                    showGitPanelWithText("Git Log", "Not a git repository")
                }
                return@launch
            }
            gitRepoRoot = root

            val commits = git.log(root)
            withContext(Dispatchers.Main) {
                if (commits.isEmpty()) {
                    showGitPanelWithText("Git Log", "No commits found")
                } else {
                    showGitLogList(commits)
                }
            }
        }
    }

    private fun showGitLogList(commits: List<GitCommit>) {
        binding.gitLogPanel.visibility = View.VISIBLE
        binding.gitPanel.visibility = View.GONE

        binding.gitLogList.layoutManager = LinearLayoutManager(context)
        binding.gitLogList.adapter = GitCommitAdapter(commits) { commit ->
            showCommitDiff(commit)
        }
    }

    private fun showCommitDiff(commit: GitCommit) {
        val git = gitService ?: return
        val root = gitRepoRoot ?: return
        val scope = coroutineScope ?: return

        gitJob?.cancel()
        gitJob = scope.launch {
            val diff = git.showCommit(root, commit.hash)
            withContext(Dispatchers.Main) {
                binding.gitLogPanel.visibility = View.GONE
                showGitPanelWithDiff("${commit.shortHash} — ${commit.subject}", diff)
            }
        }
    }

    private fun closeGitLogPanel() {
        binding.gitLogPanel.visibility = View.GONE
    }

    // --- Gutter Diff Markers ---

    fun loadGutterDiff() {
        val filePath = currentFilePath ?: return
        val git = gitService ?: return
        val scope = coroutineScope ?: return

        scope.launch {
            val root = gitRepoRoot ?: git.getRepoRoot(filePath)
            if (root == null) {
                currentDiffMarkers = emptyMap()
                return@launch
            }
            gitRepoRoot = root

            val markers = git.diffLineStatus(root, filePath)
            withContext(Dispatchers.Main) {
                currentDiffMarkers = markers
                applyGutterMarkers()
            }
        }
    }

    private fun applyGutterMarkers() {
        val styles = codeEditor.styles ?: return
        // Clear existing gutter backgrounds
        styles.eraseAllLineStyles()
        for ((lineNum, type) in currentDiffMarkers) {
            val colorInt = when (type) {
                DiffLineType.ADDED -> 0xFF4EC959.toInt()      // green
                DiffLineType.MODIFIED -> 0xFFE5C07B.toInt()   // yellow
                DiffLineType.DELETED_BELOW -> 0xFFE05252.toInt() // red
            }
            val color = object : ResolvableColor {
                override fun resolve(scheme: EditorColorScheme) = colorInt
            }
            styles.addLineStyle(LineGutterBackground(lineNum - 1, color)) // 0-based line
        }
        codeEditor.invalidate()
    }

    // --- Search ---

    private fun toggleSearchBar() {
        if (binding.searchBar.visibility == View.VISIBLE) {
            closeSearchBar()
        } else {
            openSearchBar()
        }
    }

    private fun openSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.searchInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.searchInput, 0)
    }

    private fun closeSearchBar() {
        binding.searchBar.visibility = View.GONE
        codeEditor.searcher.stopSearch()
        binding.searchInput.setText("")
        binding.searchMatchCount.text = "0/0"
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        codeEditor.requestFocus()
    }

    private fun updateMatchCount() {
        val searcher = codeEditor.searcher
        if (!searcher.hasQuery()) {
            binding.searchMatchCount.text = "0/0"
            return
        }
        val current = searcher.currentMatchedPositionIndex + 1
        val total = searcher.matchedPositionCount
        binding.searchMatchCount.text = "$current/$total"
    }

    private fun setupSearchBar() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty()) {
                    codeEditor.searcher.search(query, EditorSearcher.SearchOptions(false, false))
                    codeEditor.searcher.gotoNext()
                    postDelayed({ updateMatchCount() }, 200)
                } else {
                    codeEditor.searcher.stopSearch()
                    binding.searchMatchCount.text = "0/0"
                }
            }
        })

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                codeEditor.searcher.gotoNext()
                updateMatchCount()
                true
            } else false
        }

        binding.btnSearchNext.setOnClickListener {
            if (codeEditor.searcher.hasQuery()) {
                codeEditor.searcher.gotoNext()
                updateMatchCount()
            }
        }

        binding.btnSearchPrev.setOnClickListener {
            if (codeEditor.searcher.hasQuery()) {
                codeEditor.searcher.gotoPrevious()
                updateMatchCount()
            }
        }

        binding.btnSearchClose.setOnClickListener {
            closeSearchBar()
        }

        codeEditor.subscribeAlways(PublishSearchResultEvent::class.java) { _ ->
            post { updateMatchCount() }
        }
    }

    // --- Editor Setup ---

    private fun setupCodeEditor() {
        TextMateHelper.initialize(context)
        TextMateHelper.applyTheme(codeEditor)

        codeEditor.apply {
            typefaceText = android.graphics.Typeface.MONOSPACE
            typefaceLineNumber = android.graphics.Typeface.MONOSPACE
            setTextSize(13f)
            isLineNumberEnabled = true
            isWordwrap = false
            setInterceptParentHorizontalScrollIfNeeded(true)
        }

        codeEditor.subscribeAlways(ContentChangeEvent::class.java) { _ ->
            if (!suppressContentChange) {
                viewModel?.updateContent(codeEditor.text.toString())
            }
        }
    }

    fun switchSession(sessionKey: String) {
        if (sessionKey == activeSessionKey) return
        // Save current session's state
        if (activeSessionKey != null) {
            saveCursorState()
            sessionCursorCaches[activeSessionKey!!] = cursorCache
            sessionFontSizes[activeSessionKey!!] = codeEditor.textSizePx
        }
        activeSessionKey = sessionKey
        // Restore target session's state
        cursorCache = sessionCursorCaches.getOrPut(sessionKey) { mutableMapOf() }
        sessionFontSizes[sessionKey]?.let { codeEditor.setTextSizePx(it) }
        currentFilePath = null
    }

    fun removeSessionCache(sessionKey: String) {
        sessionCursorCaches.remove(sessionKey)
        sessionFontSizes.remove(sessionKey)
    }

    fun updateTabs(tabs: List<EditorTab>, activeIndex: Int) {
        binding.tabContainer.removeAllViews()

        if (tabs.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            codeEditor.visibility = View.GONE
            binding.imageScroll.visibility = View.GONE
            binding.webViewer.visibility = View.GONE
            binding.pdfRecycler.visibility = View.GONE
            binding.textPath.visibility = View.GONE
            binding.btnSave.visibility = View.GONE
            binding.btnFind.visibility = View.GONE
            binding.btnGitDiff.visibility = View.GONE
            binding.btnGitLog.visibility = View.GONE
            closeGitPanel()
            closeGitLogPanel()
            return
        }

        binding.textEmpty.visibility = View.GONE

        for ((index, tab) in tabs.withIndex()) {
            val tabView = LayoutInflater.from(context).inflate(R.layout.item_editor_tab, binding.tabContainer, false)
            val nameView = tabView.findViewById<TextView>(R.id.tab_name)
            val closeView = tabView.findViewById<View>(R.id.tab_close)
            val dirtyDot = tabView.findViewById<View>(R.id.dirty_dot)

            nameView.text = tab.fileName

            if (index == activeIndex) {
                nameView.setTextColor(0xFFFFFFFF.toInt())
                val accentH = (2 * resources.displayMetrics.density).toInt()
                tabView.background = android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.ColorDrawable(0xFF4CAF50.toInt()),
                    android.graphics.drawable.ColorDrawable(0xFF2D2D2D.toInt()),
                )).apply {
                    // Accent line at bottom: inset the top layer so accent peeks through at bottom
                    setLayerInset(1, 0, 0, 0, accentH)
                }
            } else {
                nameView.setTextColor(0xFF858585.toInt())
                tabView.setBackgroundColor(0xFF1E1E1E.toInt())
            }

            if (tab.isModified) {
                dirtyDot.visibility = View.VISIBLE
                (dirtyDot.background as? GradientDrawable)?.setColor(0xFFD4D4D4.toInt())
            } else {
                dirtyDot.visibility = View.GONE
            }

            tabView.setOnClickListener {
                viewModel?.switchTab(index)
            }

            closeView.setOnClickListener {
                if (tab.isModified) {
                    showUnsavedDialog(index, tab)
                } else {
                    viewModel?.closeTab(index)
                }
            }

            binding.tabContainer.addView(tabView)
        }

        // Auto-scroll tab bar to make the active tab visible
        if (activeIndex in tabs.indices) {
            binding.tabContainer.post {
                val activeTab = binding.tabContainer.getChildAt(activeIndex)
                if (activeTab != null) {
                    binding.tabScroll.smoothScrollTo(activeTab.left, 0)
                }
            }
        }

        if (activeIndex in tabs.indices) {
            val tab = tabs[activeIndex]
            binding.textPath.text = tab.filePath
            binding.textPath.visibility = View.VISIBLE

            if (tab.isWebView) {
                // Show WebView/PDF, hide everything else
                codeEditor.visibility = View.GONE
                binding.imageScroll.visibility = View.GONE
                binding.pdfRecycler.visibility = View.GONE
                binding.webViewer.visibility = View.VISIBLE
                binding.btnSave.visibility = View.GONE
                binding.btnFind.visibility = View.GONE
                binding.btnGitDiff.visibility = View.GONE
                binding.btnGitLog.visibility = View.GONE
                closeGitPanel()
                closeGitLogPanel()

                val bytes = tab.webViewBytes
                if (bytes != null) {
                    binding.webViewer.setBackgroundColor(android.graphics.Color.WHITE)
                    binding.webViewer.settings.apply {
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        javaScriptEnabled = true
                        @Suppress("DEPRECATION")
                        allowFileAccess = true
                    }
                    if (tab.webViewMimeType == "application/pdf") {
                        // Render PDF pages lazily with RecyclerView + PdfRenderer
                        binding.webViewer.visibility = View.GONE
                        binding.pdfRecycler.visibility = View.VISIBLE
                        closePdfRenderer()
                        val tmpFile = java.io.File(context.cacheDir, "viewer_${tab.fileName}")
                        tmpFile.writeBytes(bytes)
                        try {
                            val fd = android.os.ParcelFileDescriptor.open(tmpFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = android.graphics.pdf.PdfRenderer(fd)
                            activePdfRenderer = renderer
                            activePdfFd = fd
                            val displayWidth = binding.pdfRecycler.width.takeIf { it > 0 }
                                ?: resources.displayMetrics.widthPixels
                            binding.pdfRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                            binding.pdfRecycler.adapter = PdfPageAdapter(renderer, displayWidth)
                        } catch (e: Exception) {
                            binding.textPath.text = "${tab.filePath} (PDF error: ${e.message})"
                        }
                    } else {
                        val html = String(bytes, Charsets.UTF_8)
                        binding.webViewer.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                }
                currentFilePath = tab.filePath
            } else if (tab.isImage) {
                // Show image viewer, hide code editor
                codeEditor.visibility = View.GONE
                binding.imageScroll.visibility = View.VISIBLE
                binding.webViewer.visibility = View.GONE
                binding.pdfRecycler.visibility = View.GONE
                binding.btnSave.visibility = View.GONE
                binding.btnFind.visibility = View.GONE
                binding.btnGitDiff.visibility = View.GONE
                binding.btnGitLog.visibility = View.GONE
                closeGitPanel()
                closeGitLogPanel()

                val bytes = tab.imageBytes
                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        binding.imageViewer.setImageBitmap(bitmap)
                    } else {
                        binding.imageViewer.setImageDrawable(null)
                        binding.textPath.text = "${tab.filePath} (unsupported format)"
                    }
                }
                currentFilePath = tab.filePath
            } else {
                // Show code editor, hide image/web/pdf viewers
                codeEditor.visibility = View.VISIBLE
                binding.imageScroll.visibility = View.GONE
                binding.webViewer.visibility = View.GONE
                binding.pdfRecycler.visibility = View.GONE
                binding.btnFind.visibility = View.VISIBLE
                binding.btnGitDiff.visibility = View.VISIBLE
                binding.btnGitLog.visibility = View.VISIBLE
                binding.btnSave.visibility = if (tab.isModified) View.VISIBLE else View.GONE

                val switchingFile = currentFilePath != tab.filePath

                if (switchingFile && currentFilePath != null) {
                    saveCursorState()
                }

                if (tab.languageId != currentLanguageId) {
                    currentLanguageId = tab.languageId
                    val lang = TextMateHelper.createLanguage(tab.languageId)
                    if (lang != null) {
                        codeEditor.setEditorLanguage(lang)
                    }
                }

                val currentText = codeEditor.text.toString()
                if (currentText != tab.content) {
                    suppressContentChange = true
                    codeEditor.setText(tab.content)
                    suppressContentChange = false
                }

                if (switchingFile) {
                    currentFilePath = tab.filePath
                    restoreCursorState(tab.filePath, tab.content.length)
                    // Reset repo root when switching files (may be different repo)
                    gitRepoRoot = null
                    currentDiffMarkers = emptyMap()
                    codeEditor.styles?.eraseAllLineStyles()
                    codeEditor.invalidate()
                    // Close git panels
                    closeGitPanel()
                    closeGitLogPanel()
                    // Load gutter markers for new file
                    loadGutterDiff()
                }
            }
        }
    }

    private fun saveCursorState() {
        val path = currentFilePath ?: return
        val cursor = codeEditor.cursor
        val pos = codeEditor.text.getIndexer().getCharIndex(cursor.leftLine, cursor.leftColumn)
        cursorCache[path] = Pair(pos, codeEditor.offsetY)
    }

    private fun restoreCursorState(filePath: String, contentLength: Int) {
        val cached = cursorCache[filePath] ?: return
        val pos = cached.first.coerceIn(0, contentLength)
        val scrollY = cached.second
        val charPos = codeEditor.text.getIndexer().getCharPosition(pos)
        codeEditor.setSelection(charPos.line, charPos.column)
        if (scrollY > 0) {
            codeEditor.scroller.forceFinished(true)
            codeEditor.scroller.startScroll(
                codeEditor.offsetX, codeEditor.offsetY,
                0, scrollY - codeEditor.offsetY,
                0
            )
            codeEditor.invalidate()
        }
    }

    fun jumpToLine(line: Int, column: Int = 1) {
        val lineIndex = (line - 1).coerceAtLeast(0)
        val colIndex = (column - 1).coerceAtLeast(0)
        val lineCount = codeEditor.text.lineCount
        if (lineIndex < lineCount) {
            val lineLength = codeEditor.text.getColumnCount(lineIndex)
            val col = colIndex.coerceAtMost(lineLength)

            val rowHeight = codeEditor.rowHeight
            val visibleHeight = codeEditor.height
            val targetY = lineIndex * rowHeight
            val centeredY = (targetY - visibleHeight / 2 + rowHeight / 2).coerceIn(0, codeEditor.scrollMaxY)
            codeEditor.scroller.forceFinished(true)
            codeEditor.scroller.startScroll(
                codeEditor.offsetX, codeEditor.offsetY,
                0, centeredY - codeEditor.offsetY,
                0
            )

            codeEditor.setSelection(lineIndex, col)
            codeEditor.requestFocus()
            codeEditor.invalidate()

            val charIndex = codeEditor.text.getIndexer().getCharIndex(lineIndex, col)
            currentFilePath?.let { cursorCache[it] = Pair(charIndex, centeredY) }
        }
    }

    fun updateLoading(loading: Boolean) {
        binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    fun setEditorFontSize(size: Float) {
        codeEditor.setTextSize(size)
    }

    fun setWordWrap(enabled: Boolean) {
        codeEditor.isWordwrap = enabled
    }

    fun sendKeyEvent(keyCode: Int) {
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        codeEditor.dispatchKeyEvent(down)
        codeEditor.dispatchKeyEvent(up)
    }

    fun setLineNumbersEnabled(enabled: Boolean) {
        codeEditor.isLineNumberEnabled = enabled
    }

    fun hasEditorFocus(): Boolean {
        return visibility == View.VISIBLE && codeEditor.hasFocus()
    }

    fun insertTextAtCursor(text: String) {
        val cursor = codeEditor.cursor
        codeEditor.text.insert(cursor.leftLine, cursor.leftColumn, text)
    }

    fun release() {
        codeEditor.release()
    }

    private fun showUnsavedDialog(index: Int, tab: EditorTab) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Unsaved Changes")
            .setMessage("\"${tab.fileName}\" has unsaved changes.")
            .setPositiveButton("Save") { _, _ ->
                viewModel?.saveActiveTab()
                viewModel?.closeTab(index)
            }
            .setNegativeButton("Discard") { _, _ ->
                viewModel?.closeTab(index)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // --- Git Commit Adapter ---

    private class GitCommitAdapter(
        private val commits: List<GitCommit>,
        private val onClick: (GitCommit) -> Unit,
    ) : RecyclerView.Adapter<GitCommitAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val hash: TextView = view.findViewById(R.id.text_hash)
            val date: TextView = view.findViewById(R.id.text_date)
            val subject: TextView = view.findViewById(R.id.text_subject)
            val author: TextView = view.findViewById(R.id.text_author)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_git_commit, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val commit = commits[position]
            holder.hash.text = commit.shortHash
            holder.subject.text = commit.subject
            holder.author.text = commit.author
            // Show just date part
            holder.date.text = commit.date.substringBefore(' ')
            holder.itemView.setOnClickListener { onClick(commit) }
        }

        override fun getItemCount() = commits.size
    }

    // ── PDF rendering ─────────────────────────────────────────────────

    private var activePdfRenderer: android.graphics.pdf.PdfRenderer? = null
    private var activePdfFd: android.os.ParcelFileDescriptor? = null

    private fun closePdfRenderer() {
        activePdfRenderer?.close()
        activePdfRenderer = null
        activePdfFd?.close()
        activePdfFd = null
    }

    private class PdfPageAdapter(
        private val renderer: android.graphics.pdf.PdfRenderer,
        private val displayWidth: Int,
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

        class PageVH(val imageView: android.widget.ImageView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageVH {
            val iv = android.widget.ImageView(parent.context).apply {
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            return PageVH(iv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val page = renderer.openPage(position)
            val scale = displayWidth.toFloat() / page.width
            val bmpW = displayWidth
            val bmpH = (page.height * scale).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            holder.imageView.setImageBitmap(bitmap)
        }

        override fun getItemCount() = renderer.pageCount
    }
}
