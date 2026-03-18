package com.minicode.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minicode.model.EditorTab
import com.minicode.service.editor.LanguageDetector
import com.minicode.service.sftp.SftpService
import com.minicode.service.ssh.SshSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "EditorViewModel"
private const val MAX_FILE_SIZE = 1024 * 1024 // 1MB

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val sessionManager: SshSessionManager,
) : ViewModel() {

    // Per-session saved state
    private class SessionState(
        var sftpService: SftpService? = null,
        var tabs: List<EditorTab> = emptyList(),
        var activeTabIndex: Int = -1,
        var lastSessionId: Long = 0,
    )

    private val sessionStates = HashMap<String, SessionState>()
    private var activeSessionKey: String? = null

    private var sftpService: SftpService? = null

    private val _tabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(-1)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _saveSuccess = MutableStateFlow<String?>(null)
    val saveSuccess: StateFlow<String?> = _saveSuccess.asStateFlow()

    private val _pendingJump = MutableStateFlow<Pair<Int, Int>?>(null)
    val pendingJump: StateFlow<Pair<Int, Int>?> = _pendingJump.asStateFlow()

    fun clearPendingJump() {
        _pendingJump.value = null
    }

    private var lastSessionId: Long = 0

    /** Save current state for the active session */
    private fun saveCurrentState() {
        val key = activeSessionKey ?: return
        val state = sessionStates.getOrPut(key) { SessionState() }
        state.sftpService = sftpService
        state.tabs = _tabs.value
        state.activeTabIndex = _activeTabIndex.value
        state.lastSessionId = lastSessionId
    }

    /** Restore state for a session */
    private fun restoreState(sessionKey: String) {
        val state = sessionStates[sessionKey]
        if (state != null) {
            sftpService = state.sftpService
            lastSessionId = state.lastSessionId
            _tabs.value = state.tabs
            _activeTabIndex.value = state.activeTabIndex
        } else {
            sftpService = null
            lastSessionId = 0
            _tabs.value = emptyList()
            _activeTabIndex.value = -1
        }
    }

    fun switchSession(sessionKey: String) {
        if (sessionKey == activeSessionKey) return
        saveCurrentState()
        activeSessionKey = sessionKey
        restoreState(sessionKey)
    }

    /** Restore stub editor tabs for a session (paths only, content loaded after reconnect) */
    fun restoreSessionTabs(sessionKey: String, tabs: List<Pair<String, String>>, activeIndex: Int) {
        val state = sessionStates.getOrPut(sessionKey) { SessionState() }
        state.tabs = tabs.map { (path, langId) ->
            val fileName = path.substringAfterLast('/')
            EditorTab(filePath = path, fileName = fileName, languageId = langId)
        }
        state.activeTabIndex = activeIndex
    }

    /** Reload file contents for restored tabs (stubs with empty content) after SSH reconnect */
    fun reloadRestoredTabs(sessionKey: String) {
        val tabs = if (sessionKey == activeSessionKey) _tabs.value else sessionStates[sessionKey]?.tabs ?: emptyList()
        // Only reload stub tabs (empty content = restored from persistence, not yet loaded from SFTP)
        val stubPaths = tabs.filter { it.content.isEmpty() && it.imageBytes == null }.map { it.filePath }
        if (stubPaths.isEmpty()) return

        // Clear stubs so openFile can re-add them with content
        val state = sessionStates[sessionKey]
        if (state != null) {
            state.tabs = emptyList()
            state.activeTabIndex = -1
        }
        if (sessionKey == activeSessionKey) {
            _tabs.value = emptyList()
            _activeTabIndex.value = -1
        }

        for (path in stubPaths) {
            openFile(path)
        }
    }

    /** Get editor tabs for a session (for persistence) */
    fun getSessionTabs(sessionKey: String): List<EditorTab> {
        return if (sessionKey == activeSessionKey) {
            _tabs.value
        } else {
            sessionStates[sessionKey]?.tabs ?: emptyList()
        }
    }

    /** Get active tab index for a session (for persistence) */
    fun getSessionActiveTabIndex(sessionKey: String): Int {
        return if (sessionKey == activeSessionKey) {
            _activeTabIndex.value
        } else {
            sessionStates[sessionKey]?.activeTabIndex ?: -1
        }
    }

    fun removeSession(sessionKey: String) {
        val state = sessionStates.remove(sessionKey)
        if (state?.sftpService != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { state.sftpService?.close() }
        }
    }

    fun initialize() {
        val session = sessionManager.getSession() ?: return
        val currentKey = sessionManager.activeSessionId.value ?: return
        // Switch session context if the active session changed (e.g. new session added)
        if (currentKey != activeSessionKey) {
            switchSession(currentKey)
        }
        val sessionId = System.identityHashCode(session).toLong()
        if (sftpService != null && sessionId == lastSessionId) return
        lastSessionId = sessionId
        val oldSftp = sftpService
        if (oldSftp != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { oldSftp.close() }
        }
        sftpService = SftpService(session)
    }

    private suspend fun <T> retryWithReconnect(block: suspend (SftpService) -> T): T {
        val sftp = getSftpOrReconnect()
        return try {
            block(sftp)
        } catch (e: Exception) {
            Log.d(TAG, "SFTP operation failed, reconnecting: ${e.message}")
            val newSftp = reconnectSftp() ?: throw e
            block(newSftp)
        }
    }

    private fun reconnectSftp(): SftpService? {
        val oldSftp = sftpService
        sftpService = null
        if (oldSftp != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { oldSftp.close() }
        }
        val session = sessionManager.getSession() ?: return null
        val sftp = SftpService(session)
        lastSessionId = System.identityHashCode(session).toLong()
        sftpService = sftp
        return sftp
    }

    private fun getSftpOrReconnect(): SftpService {
        return sftpService ?: reconnectSftp() ?: throw IllegalStateException("SFTP not initialized")
    }

    fun openFile(filePath: String, line: Int? = null, column: Int? = null) {
        _pendingJump.value = null
        val existingIndex = _tabs.value.indexOfFirst { it.filePath == filePath }
        if (existingIndex >= 0) {
            _activeTabIndex.value = existingIndex
            if (line != null) {
                _pendingJump.value = Pair(line, column ?: 1)
            }
            return
        }

        val fileName = filePath.substringAfterLast('/')
        val langInfo = LanguageDetector.detect(fileName)
        val imageExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
        val webViewExts = mapOf("html" to "text/html", "htm" to "text/html", "pdf" to "application/pdf")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImage = ext in imageExts
        val webViewMime = webViewExts[ext]

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val attrs = retryWithReconnect { sftp -> sftp.stat(filePath) }
                val maxSize = if (isImage || webViewMime != null) 10L * 1024 * 1024 else MAX_FILE_SIZE.toLong()
                if (attrs.size > maxSize) {
                    _error.value = "File too large (${attrs.size / 1024}KB). Max: ${maxSize / 1024}KB"
                    return@launch
                }

                val tab = if (isImage) {
                    val bytes = retryWithReconnect { sftp -> sftp.readFile(filePath) }
                    EditorTab(
                        filePath = filePath,
                        fileName = fileName,
                        languageId = "image",
                        imageBytes = bytes,
                    )
                } else if (webViewMime != null) {
                    val bytes = retryWithReconnect { sftp -> sftp.readFile(filePath) }
                    EditorTab(
                        filePath = filePath,
                        fileName = fileName,
                        languageId = ext,
                        webViewBytes = bytes,
                        webViewMimeType = webViewMime,
                    )
                } else {
                    val content = retryWithReconnect { sftp ->
                        String(sftp.readFile(filePath), Charsets.UTF_8)
                    }
                    EditorTab(
                        filePath = filePath,
                        fileName = fileName,
                        content = content,
                        originalContent = content,
                        languageId = langInfo.id,
                    )
                }

                val newTabs = _tabs.value + tab
                _tabs.value = newTabs
                _activeTabIndex.value = newTabs.size - 1
                if (line != null) {
                    _pendingJump.value = Pair(line, column ?: 1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open file: $filePath", e)
                _error.value = "Failed to open file: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getActiveTab(): EditorTab? {
        val idx = _activeTabIndex.value
        val tabs = _tabs.value
        return if (idx in tabs.indices) tabs[idx] else null
    }

    fun switchTab(index: Int) {
        if (index in _tabs.value.indices) {
            _activeTabIndex.value = index
        }
    }

    fun updateContent(content: String) {
        val idx = _activeTabIndex.value
        val tabs = _tabs.value.toMutableList()
        if (idx in tabs.indices) {
            tabs[idx] = tabs[idx].copy(content = content)
            _tabs.value = tabs
        }
    }

    fun updateCursorAndScroll(cursorPosition: Int, scrollY: Int) {
        val idx = _activeTabIndex.value
        val tabs = _tabs.value.toMutableList()
        if (idx in tabs.indices) {
            tabs[idx] = tabs[idx].copy(cursorPosition = cursorPosition, scrollY = scrollY)
            _tabs.value = tabs
        }
    }

    fun saveActiveTab() {
        val tab = getActiveTab() ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                retryWithReconnect { sftp ->
                    sftp.writeFile(tab.filePath, tab.content.toByteArray(Charsets.UTF_8))
                }

                val idx = _activeTabIndex.value
                val tabs = _tabs.value.toMutableList()
                if (idx in tabs.indices) {
                    tabs[idx] = tabs[idx].copy(originalContent = tab.content)
                    _tabs.value = tabs
                }
                _saveSuccess.value = "Saved ${tab.fileName}"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file: ${tab.filePath}", e)
                _error.value = "Failed to save: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeTab(index: Int) {
        val tabs = _tabs.value.toMutableList()
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        _tabs.value = tabs

        val activeIdx = _activeTabIndex.value
        if (tabs.isEmpty()) {
            _activeTabIndex.value = -1
        } else if (index <= activeIdx) {
            _activeTabIndex.value = (activeIdx - 1).coerceAtLeast(0)
        }
    }

    fun hasUnsavedChanges(index: Int): Boolean {
        val tabs = _tabs.value
        return index in tabs.indices && tabs[index].isModified
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSaveSuccess() {
        _saveSuccess.value = null
    }

    override fun onCleared() {
        super.onCleared()
        sftpService?.close()
        for ((_, state) in sessionStates) {
            state.sftpService?.close()
        }
    }
}
