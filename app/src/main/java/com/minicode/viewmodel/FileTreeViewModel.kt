package com.minicode.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minicode.data.repository.SettingsRepository
import com.minicode.model.FileNode
import com.minicode.service.sftp.SftpService
import com.minicode.service.ssh.SshSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

private const val TAG = "FileTreeViewModel"

@HiltViewModel
class FileTreeViewModel @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // Per-session saved state
    private class SessionState(
        var sftpService: SftpService? = null,
        var currentPath: String = "",
        var rootNodes: List<FileNode> = emptyList(),
        var visibleNodes: List<FileNode> = emptyList(),
        var lastSessionId: Long = 0,
    )

    private val sessionStates = HashMap<String, SessionState>()
    private var activeSessionKey: String? = null

    private var sftpService: SftpService? = null

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _visibleNodes = MutableStateFlow<List<FileNode>>(emptyList())
    val visibleNodes: StateFlow<List<FileNode>> = _visibleNodes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _highlightedPath = MutableStateFlow<String?>(null)
    val highlightedPath: StateFlow<String?> = _highlightedPath.asStateFlow()

    private var rootNodes: List<FileNode> = emptyList()
    private var lastSessionId: Long = 0

    /** Save current state for the active session */
    private fun saveCurrentState() {
        val key = activeSessionKey ?: return
        val state = sessionStates.getOrPut(key) { SessionState() }
        state.sftpService = sftpService
        state.currentPath = _currentPath.value
        state.rootNodes = rootNodes
        state.visibleNodes = _visibleNodes.value
        state.lastSessionId = lastSessionId
    }

    /** Restore state for a session */
    private fun restoreState(sessionKey: String) {
        val state = sessionStates[sessionKey]
        if (state != null) {
            sftpService = state.sftpService
            rootNodes = state.rootNodes
            lastSessionId = state.lastSessionId
            _currentPath.value = state.currentPath
            _visibleNodes.value = state.visibleNodes
        } else {
            sftpService = null
            rootNodes = emptyList()
            lastSessionId = 0
            _currentPath.value = ""
            _visibleNodes.value = emptyList()
        }
    }

    fun switchSession(sessionKey: String) {
        if (sessionKey == activeSessionKey) return
        saveCurrentState()
        activeSessionKey = sessionKey
        restoreState(sessionKey)
    }

    /** Restore saved path for a session (navigated to after reconnect) */
    fun restoreSessionPath(sessionKey: String, path: String) {
        val state = sessionStates.getOrPut(sessionKey) { SessionState() }
        state.currentPath = path
    }

    /** Get current path for a session (for persistence) */
    fun getSessionPath(sessionKey: String): String {
        return if (sessionKey == activeSessionKey) {
            _currentPath.value
        } else {
            sessionStates[sessionKey]?.currentPath ?: ""
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
        val isNewSession = currentKey != activeSessionKey
        if (isNewSession) {
            switchSession(currentKey)
        }
        val sessionId = System.identityHashCode(session).toLong()
        if (sftpService != null && sessionId == lastSessionId) {
            // SFTP already initialized — but load directory if nodes are empty
            // (e.g. restored session that hasn't loaded its file tree yet)
            if (_visibleNodes.value.isEmpty() && _currentPath.value.isNotEmpty()) {
                loadDirectory(_currentPath.value)
            }
            return
        }
        lastSessionId = sessionId
        val oldSftp = sftpService
        if (oldSftp != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { oldSftp.close() }
        }
        sftpService = SftpService(session)
        // Load directory for new sessions: use saved path if available, else home
        if (isNewSession || _currentPath.value.isEmpty() || _visibleNodes.value.isEmpty()) {
            val savedPath = _currentPath.value
            if (savedPath.isNotEmpty()) {
                loadDirectory(savedPath)
            } else {
                loadHomeDirectory()
            }
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

    private suspend fun <T> retryWithReconnect(block: suspend (SftpService) -> T): T {
        val sftp = sftpService ?: reconnectSftp() ?: throw IllegalStateException("SFTP not initialized")
        return try {
            block(sftp)
        } catch (e: Exception) {
            Log.d(TAG, "SFTP operation failed, reconnecting: ${e.message}")
            val newSftp = reconnectSftp() ?: throw e
            block(newSftp)
        }
    }

    private fun loadHomeDirectory() {
        viewModelScope.launch {
            try {
                val home = retryWithReconnect { sftp -> sftp.getHomeDirectory() } ?: "/"
                _currentPath.value = home
                loadDirectory(home)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load home directory", e)
                _error.value = "Failed to load home directory: ${e.message}"
            }
        }
    }

    private fun loadDirectory(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val entries = retryWithReconnect { sftp -> sftp.listDirectory(path) }
                rootNodes = entries
                _visibleNodes.value = flattenNodes(rootNodes, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list directory: $path", e)
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        val path = _currentPath.value
        if (path.isNotEmpty()) {
            rootNodes = emptyList()
            _visibleNodes.value = emptyList()
            loadDirectory(path)
        }
    }

    fun toggleExpand(node: FileNode) {
        if (!node.isDirectory) return
        if (node.isExpanded) {
            rootNodes = updateNodeInTree(rootNodes, node.path) { it.copy(isExpanded = false) }
            _visibleNodes.value = flattenNodes(rootNodes, 0)
        } else {
            if (node.children != null) {
                rootNodes = updateNodeInTree(rootNodes, node.path) { it.copy(isExpanded = true) }
                _visibleNodes.value = flattenNodes(rootNodes, 0)
            } else {
                loadChildren(node)
            }
        }
    }

    private fun loadChildren(node: FileNode) {
        viewModelScope.launch {
            rootNodes = updateNodeInTree(rootNodes, node.path) { it.copy(isLoading = true) }
            _visibleNodes.value = flattenNodes(rootNodes, 0)

            try {
                val children = retryWithReconnect { sftp -> sftp.listDirectory(node.path) }
                rootNodes = updateNodeInTree(rootNodes, node.path) {
                    it.copy(isExpanded = true, isLoading = false, children = children)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load children: ${node.path}", e)
                rootNodes = updateNodeInTree(rootNodes, node.path) {
                    it.copy(isLoading = false)
                }
                _error.value = "Failed to load ${node.name}: ${e.message}"
            }
            _visibleNodes.value = flattenNodes(rootNodes, 0)
        }
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current == "/") return
        val parent = current.substringBeforeLast('/', "/")
        val newPath = if (parent.isEmpty()) "/" else parent
        _currentPath.value = newPath
        rootNodes = emptyList()
        loadDirectory(newPath)
    }

    fun navigateTo(path: String) {
        if (path == _currentPath.value) return
        _currentPath.value = path
        rootNodes = emptyList()
        _visibleNodes.value = emptyList()
        loadDirectory(path)
    }

    fun createFile(parentPath: String, name: String) {
        viewModelScope.launch {
            try {
                val fullPath = "$parentPath/$name"
                retryWithReconnect { sftp -> sftp.writeFile(fullPath, ByteArray(0)) }
                refreshNode(parentPath)
            } catch (e: Exception) {
                _error.value = "Failed to create file: ${e.message}"
            }
        }
    }

    /**
     * Upload image bytes to remote server via SFTP.
     * @param dirPath directory to upload into (e.g. /tmp or project/images)
     * @param fileName remote file name
     * @param data image bytes
     * @return remote path on success, null on failure
     */
    suspend fun uploadImage(dirPath: String, fileName: String, data: ByteArray): String? {
        return try {
            // Ensure directory exists (ignore errors for /tmp which already exists)
            try { retryWithReconnect { sftp -> sftp.mkdir(dirPath) } } catch (_: Exception) {}
            val fullPath = "$dirPath/$fileName"
            retryWithReconnect { sftp -> sftp.writeFile(fullPath, data) }
            fullPath
        } catch (e: Exception) {
            _error.value = "Failed to upload image: ${e.message}"
            null
        }
    }

    suspend fun uploadFile(
        dirPath: String,
        fileName: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): String? {
        return try {
            try { retryWithReconnect { sftp -> sftp.mkdir(dirPath) } } catch (_: Exception) {}
            val fullPath = "$dirPath/$fileName"
            retryWithReconnect { sftp ->
                sftp.writeFileStream(fullPath, inputStream, totalSize, onProgress)
            }
            fullPath
        } catch (e: Exception) {
            _error.value = "Failed to upload file: ${e.message}"
            null
        }
    }

    fun createFolder(parentPath: String, name: String) {
        viewModelScope.launch {
            try {
                val fullPath = "$parentPath/$name"
                retryWithReconnect { sftp -> sftp.mkdir(fullPath) }
                refreshNode(parentPath)
            } catch (e: Exception) {
                _error.value = "Failed to create folder: ${e.message}"
            }
        }
    }

    fun deleteNode(node: FileNode) {
        viewModelScope.launch {
            try {
                retryWithReconnect { sftp -> sftp.delete(node.path, node.isDirectory) }
                val parentPath = node.path.substringBeforeLast('/')
                refreshNode(parentPath.ifEmpty { "/" })
            } catch (e: Exception) {
                _error.value = "Failed to delete ${node.name}: ${e.message}"
            }
        }
    }

    fun renameNode(node: FileNode, newName: String) {
        viewModelScope.launch {
            try {
                val parentPath = node.path.substringBeforeLast('/')
                val newPath = "$parentPath/$newName"
                retryWithReconnect { sftp -> sftp.rename(node.path, newPath) }
                refreshNode(parentPath.ifEmpty { "/" })
            } catch (e: Exception) {
                _error.value = "Failed to rename ${node.name}: ${e.message}"
            }
        }
    }

    private fun refreshNode(parentPath: String) {
        if (parentPath == _currentPath.value) {
            viewModelScope.launch {
                try {
                    val entries = retryWithReconnect { sftp -> sftp.listDirectory(parentPath) }
                    rootNodes = mergeExpandedState(entries, rootNodes)
                    _visibleNodes.value = flattenNodes(rootNodes, 0)
                } catch (e: Exception) {
                    _error.value = "Failed to refresh: ${e.message}"
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    val children = retryWithReconnect { sftp -> sftp.listDirectory(parentPath) }
                    rootNodes = updateNodeInTree(rootNodes, parentPath) { node ->
                        val merged = mergeExpandedState(children, node.children ?: emptyList())
                        node.copy(children = merged)
                    }
                    _visibleNodes.value = flattenNodes(rootNodes, 0)
                } catch (e: Exception) {
                    _error.value = "Failed to refresh: ${e.message}"
                }
            }
        }
    }

    private fun mergeExpandedState(newNodes: List<FileNode>, oldNodes: List<FileNode>): List<FileNode> {
        val oldMap = oldNodes.associateBy { it.path }
        return newNodes.map { node ->
            val old = oldMap[node.path]
            if (old != null && old.isDirectory) {
                node.copy(isExpanded = old.isExpanded, children = old.children)
            } else {
                node
            }
        }
    }

    fun revealFile(filePath: String) {
        val root = _currentPath.value
        if (!filePath.startsWith("$root/")) {
            _highlightedPath.value = filePath
            return
        }
        val relative = filePath.removePrefix("$root/")
        val parts = relative.split("/")
        if (parts.size <= 1) {
            _highlightedPath.value = filePath
            return
        }

        viewModelScope.launch {
            var currentPrefix = root
            for (i in 0 until parts.size - 1) {
                currentPrefix = "$currentPrefix/${parts[i]}"
                val dirPath = currentPrefix
                val dirNode = findNodeInTree(rootNodes, dirPath)
                if (dirNode == null || !dirNode.isDirectory) break
                if (!dirNode.isExpanded) {
                    if (dirNode.children == null) {
                        try {
                            val children = retryWithReconnect { sftp -> sftp.listDirectory(dirPath) }
                            rootNodes = updateNodeInTree(rootNodes, dirPath) {
                                it.copy(isExpanded = true, isLoading = false, children = children)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to expand $dirPath for reveal", e)
                            break
                        }
                    } else {
                        rootNodes = updateNodeInTree(rootNodes, dirPath) {
                            it.copy(isExpanded = true)
                        }
                    }
                }
            }
            _visibleNodes.value = flattenNodes(rootNodes, 0)
            _highlightedPath.value = filePath
        }
    }

    private fun findNodeInTree(nodes: List<FileNode>, targetPath: String): FileNode? {
        for (node in nodes) {
            if (node.path == targetPath) return node
            if (node.isDirectory && node.children != null && targetPath.startsWith(node.path + "/")) {
                val found = findNodeInTree(node.children, targetPath)
                if (found != null) return found
            }
        }
        return null
    }

    fun clearError() {
        _error.value = null
    }

    private fun flattenNodes(nodes: List<FileNode>, depth: Int): List<FileNode> {
        val showHidden = settingsRepository.showHiddenFiles
        val result = mutableListOf<FileNode>()
        for (node in nodes) {
            if (!showHidden && node.name.startsWith(".")) continue
            result.add(node.copy(depth = depth))
            if (node.isDirectory && node.isExpanded && node.children != null) {
                result.addAll(flattenNodes(node.children, depth + 1))
            }
        }
        return result
    }

    private fun updateNodeInTree(
        nodes: List<FileNode>,
        targetPath: String,
        transform: (FileNode) -> FileNode,
    ): List<FileNode> {
        return nodes.map { node ->
            if (node.path == targetPath) {
                transform(node)
            } else if (node.isDirectory && node.children != null && targetPath.startsWith(node.path + "/")) {
                node.copy(children = updateNodeInTree(node.children, targetPath, transform))
            } else {
                node
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sftpService?.close()
        for ((_, state) in sessionStates) {
            state.sftpService?.close()
        }
    }
}
