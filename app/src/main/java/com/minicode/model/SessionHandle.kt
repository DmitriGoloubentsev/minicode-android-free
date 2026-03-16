package com.minicode.model

import com.minicode.service.terminal.TerminalSessionBridge
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Represents a single active SSH session with all its associated state.
 */
data class SessionHandle(
    val id: String,
    val profileId: String,
    val label: MutableStateFlow<String>,
    var bridge: TerminalSessionBridge,
    val state: MutableStateFlow<SshSessionState>,
    var hasUnreadOutput: Boolean = false,
    /** True when the session is actively receiving output (auto-resets after idle). */
    val hasActiveOutput: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Sessio session this tab is attached to (in-memory, per-tab). */
    var attachedSessioSession: String? = null,
)
