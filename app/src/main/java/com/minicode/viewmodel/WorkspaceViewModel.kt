package com.minicode.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minicode.data.repository.ConnectionRepository
import com.minicode.data.repository.SettingsRepository
import com.minicode.model.ConnectionProfile
import com.minicode.model.SessionHandle
import com.minicode.model.SshSessionState
import com.minicode.service.ssh.SshSessionManager
import com.minicode.service.terminal.TerminalSessionBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WorkspaceViewModel"

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    val sessionManager: SshSessionManager,
    private val repository: ConnectionRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val sessionState: StateFlow<SshSessionState> = sessionManager.state
    val sessionError: StateFlow<String?> = sessionManager.error
    val sessionList: StateFlow<List<SessionHandle>> = sessionManager.sessionList
    val activeSessionId: StateFlow<String?> = sessionManager.activeSessionId

    private val _profile = MutableStateFlow<ConnectionProfile?>(null)
    val profile: StateFlow<ConnectionProfile?> = _profile.asStateFlow()

    private val _bridge = MutableStateFlow<TerminalSessionBridge?>(null)
    val bridge: StateFlow<TerminalSessionBridge?> = _bridge.asStateFlow()

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    // Track per-session profile for title display
    private val profileCache = HashMap<String, ConnectionProfile>()

    private var lastCols: Int = 80
    private var lastRows: Int = 24
    private var intentionalDisconnect = false

    init {
        viewModelScope.launch {
            sessionManager.state.collect { state ->
                if (state == SshSessionState.DISCONNECTED && !intentionalDisconnect) {
                    if (settingsRepository.autoReconnect && sessionManager.getSessionCount() == 0) {
                        // Auto-reconnect only if all sessions dropped
                    }
                }
            }
        }
    }

    fun connect(profileId: String, initialCols: Int, initialRows: Int) {
        lastCols = initialCols
        lastRows = initialRows
        intentionalDisconnect = false
        viewModelScope.launch {
            val prof = repository.getProfileById(profileId) ?: return@launch
            _profile.value = prof
            profileCache[profileId] = prof

            val password = repository.getPassword(profileId)
            val privateKey = repository.getPrivateKey(profileId)
            val passphrase = repository.getPassphrase(profileId)

            try {
                val handle = sessionManager.connect(
                    profile = prof,
                    password = password,
                    privateKey = privateKey,
                    passphrase = passphrase,
                    initialCols = initialCols,
                    initialRows = initialRows,
                    scope = viewModelScope,
                )
                _bridge.value = handle.bridge
                repository.updateLastUsed(profileId)
            } catch (_: Exception) {
                // Error is exposed via sessionManager.error
            }
        }
    }

    fun switchSession(sessionId: String) {
        val handle = sessionManager.getSessionHandle(sessionId) ?: return
        sessionManager.setActive(sessionId)
        _bridge.value = handle.bridge
        // Update profile display
        val prof = profileCache[handle.profileId]
        if (prof != null) {
            _profile.value = prof
        } else {
            viewModelScope.launch {
                val p = repository.getProfileById(handle.profileId)
                if (p != null) {
                    profileCache[handle.profileId] = p
                    _profile.value = p
                }
            }
        }
    }

    fun disconnectSession(sessionId: String) {
        intentionalDisconnect = true
        sessionManager.disconnect(sessionId)
        // Update bridge to the new active session (if any)
        val activeHandle = sessionManager.getActiveSession()
        _bridge.value = activeHandle?.bridge
        if (activeHandle != null) {
            val prof = profileCache[activeHandle.profileId]
            if (prof != null) _profile.value = prof
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        val activeId = sessionManager.activeSessionId.value ?: return
        disconnectSession(activeId)
    }

    fun resize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        _bridge.value?.resize(cols, rows)
    }

    fun forceResize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        _bridge.value?.forceResize(cols, rows)
    }

    fun writeInput(data: ByteArray) {
        _bridge.value?.writeBytes(data)
    }

    fun getLastCols(): Int = lastCols
    fun getLastRows(): Int = lastRows

    override fun onCleared() {
        super.onCleared()
        sessionManager.shutdown()
    }
}
