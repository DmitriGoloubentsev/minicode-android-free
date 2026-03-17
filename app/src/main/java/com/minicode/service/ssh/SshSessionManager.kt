package com.minicode.service.ssh

import android.util.Log
import com.minicode.model.AuthType
import com.minicode.model.ConnectionProfile
import com.minicode.model.SessionHandle
import com.minicode.model.SshSessionState
import com.minicode.service.terminal.BridgeDebugLog
import com.minicode.service.terminal.SessioDetector
import com.minicode.service.terminal.TerminalEmulator
import com.minicode.service.terminal.TerminalSessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SshSessionManager"

@Singleton
class SshSessionManager @Inject constructor() {

    private var client: SshClient? = null

    // All active sessions
    private val sessions = ConcurrentHashMap<String, SessionEntry>()
    private val sessionOrder = mutableListOf<String>()

    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-idle-timer").apply { isDaemon = true }
    }
    private val idleTimers = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private val _sessionList = MutableStateFlow<List<SessionHandle>>(emptyList())
    val sessionList: StateFlow<List<SessionHandle>> = _sessionList.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    // Active session state (for backward compat with observeState)
    private val _state = MutableStateFlow(SshSessionState.DISCONNECTED)
    val state: StateFlow<SshSessionState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private class SessionEntry(
        val handle: SessionHandle,
        var sshSession: ClientSession?,
    )

    private fun getOrCreateClient(): SshClient {
        return client ?: run {
            Log.d(TAG, "Creating SSH client")
            if (System.getProperty("user.home").isNullOrEmpty()) {
                System.setProperty("user.home", "/data/local/tmp")
            }
            val c = SshClient.setUpDefaultClient()
            try {
                org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_INTERVAL.set(
                    c, java.time.Duration.ofSeconds(15)
                )
                // No reply timeout — let TCP handle dead connections naturally.
                // This allows surviving long network interruptions (VPN reconnect, etc.)
                org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_REPLY_WAIT.set(
                    c, java.time.Duration.ZERO
                )
                org.apache.sshd.core.CoreModuleProperties.IDLE_TIMEOUT.set(
                    c, java.time.Duration.ZERO
                )
                org.apache.sshd.core.CoreModuleProperties.NIO2_READ_TIMEOUT.set(
                    c, java.time.Duration.ZERO
                )
                org.apache.sshd.core.CoreModuleProperties.SOCKET_KEEPALIVE.set(c, true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set heartbeat interval", e)
            }
            c.start()
            client = c
            Log.d(TAG, "SSH client created and started")
            c
        }
    }

    suspend fun connect(
        profile: ConnectionProfile,
        password: String?,
        privateKey: String?,
        passphrase: String?,
        initialCols: Int,
        initialRows: Int,
        scope: CoroutineScope,
    ): SessionHandle = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        _state.value = SshSessionState.CONNECTING
        _error.value = null

        try {
            Log.d(TAG, "Connecting to ${profile.host}:${profile.port} (session=$sessionId)")
            val sshClient = getOrCreateClient()
            val sess = sshClient.connect(profile.username, profile.host, profile.port)
                .verify(15, TimeUnit.SECONDS)
                .session

            // Maximize TCP persistence — survive long network outages
            configureTcpPersistence(sess)

            _state.value = SshSessionState.AUTHENTICATING
            Log.d(TAG, "Authenticating with ${profile.authType}")

            when (profile.authType) {
                AuthType.PASSWORD -> sess.addPasswordIdentity(password ?: "")
                AuthType.PRIVATE_KEY -> {
                    if (privateKey != null) {
                        sess.addPublicKeyIdentity(loadKeyPair(privateKey, passphrase))
                    }
                }
            }
            sess.auth().verify(15, TimeUnit.SECONDS)
            Log.d(TAG, "Authenticated")

            val channel = sess.createShellChannel()
            channel.setPtyType("xterm-256color")
            channel.setPtyColumns(initialCols)
            channel.setPtyLines(initialRows)
            channel.setPtyWidth(0)
            channel.setPtyHeight(0)

            channel.open().verify(10, TimeUnit.SECONDS)
            Log.d(TAG, "Shell channel opened ${initialCols}x${initialRows}")

            val emulator = TerminalEmulator(initialCols, initialRows)
            val termBridge = TerminalSessionBridge(channel, emulator, scope)
            val sessionState = MutableStateFlow(SshSessionState.CONNECTED)

            val label = profile.label.ifBlank { "${profile.username}@${profile.host}" }
            val handle = SessionHandle(
                id = sessionId,
                profileId = profile.id,
                label = MutableStateFlow(label),
                bridge = termBridge,
                state = sessionState,
            )

            termBridge.onDisconnect = {
                markDisconnected(sessionId)
            }
            // Store detected sessio sessions on handle so picker shows once UI is ready
            termBridge.onSessioDetected = { detected ->
                handle.pendingSessioSessions = detected
                Log.d(TAG, "Sessio sessions detected during connect (session=$sessionId, count=${detected.size})")
            }

            // Bind title change to this specific session handle
            emulator.onTitleChanged = { title ->
                handle.label.value = title
            }

            // Track output activity
            termBridge.onOutput = {
                if (!handle.hasActiveOutput.value) {
                    handle.hasActiveOutput.value = true
                }
                if (sessionId != _activeSessionId.value && !handle.hasUnreadOutput) {
                    handle.hasUnreadOutput = true
                    publishSessionList()
                }
                idleTimers[sessionId]?.cancel(false)
                idleTimers[sessionId] = idleScheduler.schedule({
                    handle.hasActiveOutput.value = false
                }, 2, TimeUnit.SECONDS)
            }

            // Start reader AFTER all callbacks are set
            termBridge.start()

            if (!profile.startupCommand.isNullOrBlank()) {
                termBridge.writeBytes((profile.startupCommand + "\n").toByteArray(Charsets.UTF_8))
            } else if (!profile.initialDirectory.isNullOrBlank()) {
                termBridge.writeBytes(("cd ${profile.initialDirectory}\n").toByteArray(Charsets.UTF_8))
            }

            sessions[sessionId] = SessionEntry(handle, sess)
            synchronized(sessionOrder) { sessionOrder.add(sessionId) }
            _activeSessionId.value = sessionId
            _state.value = SshSessionState.CONNECTED
            publishSessionList()
            Log.d(TAG, "Connected successfully (session=$sessionId, total=${sessions.size})")

            handle
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _state.value = SshSessionState.ERROR
            _error.value = e.message ?: "Connection failed"
            throw e
        }
    }

    fun setActive(sessionId: String) {
        if (sessions.containsKey(sessionId)) {
            sessions[sessionId]?.handle?.hasUnreadOutput = false
            _activeSessionId.value = sessionId
            val entry = sessions[sessionId]
            _state.value = entry?.handle?.state?.value ?: SshSessionState.DISCONNECTED
            publishSessionList()
        }
    }

    fun getActiveSession(): SessionHandle? {
        val activeId = _activeSessionId.value ?: return null
        return sessions[activeId]?.handle
    }

    fun getSessionHandle(sessionId: String): SessionHandle? {
        return sessions[sessionId]?.handle
    }

    fun getSshSession(sessionId: String): ClientSession? {
        return sessions[sessionId]?.sshSession
    }

    /** Backward compat: get the SSH ClientSession for the active session */
    fun getSession(): ClientSession? {
        val activeId = _activeSessionId.value ?: return null
        return sessions[activeId]?.sshSession
    }

    /** Backward compat: get bridge for active session */
    val bridge: TerminalSessionBridge?
        get() {
            val activeId = _activeSessionId.value ?: return null
            return sessions[activeId]?.handle?.bridge
        }

    /** Create a disconnected placeholder session for restore after app kill */
    fun addPlaceholderSession(
        sessionId: String,
        profileId: String,
        label: String,
        cols: Int,
        rows: Int,
    ): SessionHandle {
        val bridge = TerminalSessionBridge.createDisconnected(cols, rows)
        val handle = SessionHandle(
            id = sessionId,
            profileId = profileId,
            label = MutableStateFlow(label),
            bridge = bridge,
            state = MutableStateFlow(SshSessionState.DISCONNECTED),
        )
        sessions[sessionId] = SessionEntry(handle, null)
        synchronized(sessionOrder) { sessionOrder.add(sessionId) }
        publishSessionList()
        return handle
    }

    /** Replace a placeholder session's bridge with a real SSH connection */
    suspend fun reconnectSession(
        sessionId: String,
        profile: ConnectionProfile,
        password: String?,
        privateKey: String?,
        passphrase: String?,
        cols: Int,
        rows: Int,
        scope: CoroutineScope,
    ): SessionHandle = withContext(Dispatchers.IO) {
        val entry = sessions[sessionId] ?: throw IllegalStateException("Session $sessionId not found")
        val handle = entry.handle
        handle.state.value = SshSessionState.CONNECTING

        try {
            Log.d(TAG, "Reconnecting ${profile.host}:${profile.port} (session=$sessionId)")
            val sshClient = getOrCreateClient()
            val sess = sshClient.connect(profile.username, profile.host, profile.port)
                .verify(15, TimeUnit.SECONDS)
                .session

            configureTcpPersistence(sess)
            handle.state.value = SshSessionState.AUTHENTICATING

            when (profile.authType) {
                AuthType.PASSWORD -> sess.addPasswordIdentity(password ?: "")
                AuthType.PRIVATE_KEY -> {
                    if (privateKey != null) {
                        sess.addPublicKeyIdentity(loadKeyPair(privateKey, passphrase))
                    }
                }
            }
            sess.auth().verify(15, TimeUnit.SECONDS)

            val channel = sess.createShellChannel()
            channel.setPtyType("xterm-256color")
            channel.setPtyColumns(cols)
            channel.setPtyLines(rows)
            channel.setPtyWidth(0)
            channel.setPtyHeight(0)
            channel.open().verify(10, TimeUnit.SECONDS)

            val emulator = TerminalEmulator(cols, rows)
            val newBridge = TerminalSessionBridge(channel, emulator, scope)
            newBridge.onDisconnect = {
                markDisconnected(sessionId)
            }

            // Set up sessio callback before start() so detection fires during initial output
            val savedSessioName = handle.attachedSessioSession
            if (savedSessioName != null) {
                newBridge.onSessioDetected = { sessions ->
                    if (sessions.any { it.name == savedSessioName }) {
                        newBridge.writeBytes("sessio attach $savedSessioName\n".toByteArray())
                        // Force resize after short delay to reassert MiniCode's dimensions
                        // (sessio may change server terminal size to match the PC)
                        newBridge.forceResize(cols, rows)
                        Log.d(TAG, "Auto-attached to sessio session: $savedSessioName (session=$sessionId)")
                    } else {
                        handle.attachedSessioSession = null
                        // Store pending sessions so picker can be shown when user switches to this tab
                        handle.pendingSessioSessions = sessions
                        Log.d(TAG, "Sessio session $savedSessioName no longer exists (session=$sessionId)")
                    }
                }
            } else {
                // No saved sessio name — store detected sessions for later picker display
                newBridge.onSessioDetected = { sessions ->
                    handle.pendingSessioSessions = sessions
                    Log.d(TAG, "Sessio sessions detected for session=$sessionId (no saved name, pending picker)")
                }
            }

            newBridge.start()

            if (!profile.startupCommand.isNullOrBlank()) {
                newBridge.writeBytes((profile.startupCommand + "\n").toByteArray(Charsets.UTF_8))
            } else if (!profile.initialDirectory.isNullOrBlank()) {
                newBridge.writeBytes(("cd ${profile.initialDirectory}\n").toByteArray(Charsets.UTF_8))
            }

            // Update the entry with the real SSH session and bridge
            entry.sshSession = sess
            handle.bridge = newBridge

            // Bind title change
            emulator.onTitleChanged = { title ->
                handle.label.value = title
            }

            // Track output activity
            newBridge.onOutput = {
                if (!handle.hasActiveOutput.value) {
                    handle.hasActiveOutput.value = true
                }
                if (sessionId != _activeSessionId.value && !handle.hasUnreadOutput) {
                    handle.hasUnreadOutput = true
                    publishSessionList()
                }
                idleTimers[sessionId]?.cancel(false)
                idleTimers[sessionId] = idleScheduler.schedule({
                    handle.hasActiveOutput.value = false
                }, 2, TimeUnit.SECONDS)
            }

            handle.state.value = SshSessionState.CONNECTED
            if (_activeSessionId.value == sessionId) {
                _state.value = SshSessionState.CONNECTED
            }
            publishSessionList()
            Log.d(TAG, "Reconnected successfully (session=$sessionId)")

            handle
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection failed for session $sessionId", e)
            handle.state.value = SshSessionState.ERROR
            if (_activeSessionId.value == sessionId) {
                _state.value = SshSessionState.ERROR
                _error.value = e.message ?: "Reconnection failed"
            }
            throw e
        }
    }

    /** Mark a session as disconnected but keep the tab (for reconnect on tap).
     *  Replaces the bridge with a disconnected stub so the emulator stays visible. */
    fun markDisconnected(sessionId: String) {
        val entry = sessions[sessionId] ?: return
        val handle = entry.handle
        idleTimers.remove(sessionId)?.cancel(false)
        handle.bridge.stop()
        try { entry.sshSession?.close() } catch (_: Exception) {}
        entry.sshSession = null
        // Replace bridge with disconnected stub so UI still has an emulator to display
        val cols = handle.bridge.emulator.columns
        val rows = handle.bridge.emulator.rows
        handle.bridge = TerminalSessionBridge.createDisconnected(cols, rows)
        handle.state.value = SshSessionState.DISCONNECTED
        if (_activeSessionId.value == sessionId) {
            _state.value = SshSessionState.DISCONNECTED
        }
        publishSessionList()
        BridgeDebugLog.log("markDisconnected: session=$sessionId")
    }

    /** Get session IDs in tab order */
    fun getSessionOrder(): List<String> = synchronized(sessionOrder) { sessionOrder.toList() }

    fun disconnect(sessionId: String) {
        val entry = sessions.remove(sessionId) ?: return
        synchronized(sessionOrder) { sessionOrder.remove(sessionId) }
        idleTimers.remove(sessionId)?.cancel(false)
        entry.handle.bridge.stop()
        try { entry.sshSession?.close() } catch (_: Exception) {}
        entry.handle.state.value = SshSessionState.DISCONNECTED

        if (_activeSessionId.value == sessionId) {
            val nextId = synchronized(sessionOrder) { sessionOrder.lastOrNull() }
            _activeSessionId.value = nextId
            _state.value = if (nextId != null) {
                sessions[nextId]?.handle?.state?.value ?: SshSessionState.DISCONNECTED
            } else {
                SshSessionState.DISCONNECTED
            }
        }
        publishSessionList()
    }

    /** Disconnect the currently active session */
    fun disconnect() {
        val activeId = _activeSessionId.value ?: return
        disconnect(activeId)
    }

    fun isConnected(): Boolean {
        val activeId = _activeSessionId.value ?: return false
        val entry = sessions[activeId] ?: return false
        return entry.sshSession?.isOpen == true
    }

    fun getSessionCount(): Int = sessions.size

    fun getAllSessions(): List<SessionHandle> {
        return synchronized(sessionOrder) {
            sessionOrder.mapNotNull { sessions[it]?.handle }
        }
    }

    private fun publishSessionList() {
        _sessionList.value = getAllSessions()
    }

    fun markUnread(sessionId: String) {
        if (sessionId != _activeSessionId.value) {
            sessions[sessionId]?.handle?.hasUnreadOutput = true
            publishSessionList()
        }
    }

    /**
     * Set TCP_USER_TIMEOUT to 30 minutes so the kernel keeps retrying
     * for a long time before declaring the connection dead.
     * Also sets TCP keepalive parameters for faster dead-peer detection
     * once the connection is truly gone.
     */
    private fun configureTcpPersistence(session: ClientSession) {
        try {
            val ioSession = session.ioSession ?: return
            // Try to get the underlying NIO channel and socket
            val channel = ioSession.javaClass.methods
                .firstOrNull { it.name == "getSocket" || it.name == "getChannel" }
                ?.invoke(ioSession) ?: return

            val socket = when (channel) {
                is java.nio.channels.SocketChannel -> channel.socket()
                is java.net.Socket -> channel
                else -> {
                    // Try to get socket from channel via reflection
                    channel.javaClass.methods
                        .firstOrNull { it.name == "socket" }
                        ?.invoke(channel) as? java.net.Socket ?: return
                }
            }

            socket.keepAlive = true

            // TCP_USER_TIMEOUT (option 18 on Linux) = 30 minutes in milliseconds
            // This tells the kernel how long to wait for ACKs before giving up
            try {
                val fd = socket.javaClass.getDeclaredMethod("getFileDescriptor\$")
                    .invoke(socket) as? java.io.FileDescriptor
                if (fd != null) {
                    val os = android.system.Os::class.java
                    // SOL_TCP=6, TCP_USER_TIMEOUT=18
                    // TCP_USER_TIMEOUT=18 — 0 disables, use max int for "infinite"
                    os.getMethod("setsockoptInt", java.io.FileDescriptor::class.java,
                        Int::class.java, Int::class.java, Int::class.java)
                        .invoke(null, fd, 6, 18, Int.MAX_VALUE) // ~24.8 days
                    // TCP_KEEPIDLE=4 — start keepalive probes after 60s idle
                    os.getMethod("setsockoptInt", java.io.FileDescriptor::class.java,
                        Int::class.java, Int::class.java, Int::class.java)
                        .invoke(null, fd, 6, 4, 60)
                    // TCP_KEEPINTVL=5 — send probes every 30s
                    os.getMethod("setsockoptInt", java.io.FileDescriptor::class.java,
                        Int::class.java, Int::class.java, Int::class.java)
                        .invoke(null, fd, 6, 5, 30)
                    // TCP_KEEPCNT=6 — max probes (kernel will clamp to 127)
                    os.getMethod("setsockoptInt", java.io.FileDescriptor::class.java,
                        Int::class.java, Int::class.java, Int::class.java)
                        .invoke(null, fd, 6, 6, 127)
                    Log.d(TAG, "TCP persistence configured: infinite timeout, keepalive 60/30/127")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set TCP_USER_TIMEOUT (non-fatal)", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure TCP persistence (non-fatal)", e)
        }
    }

    private fun loadKeyPair(privateKeyPem: String, passphrase: String?): KeyPair {
        val lines = privateKeyPem.lines()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        val keyBytes = Base64.getDecoder().decode(lines)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privKey = keyFactory.generatePrivate(keySpec)
        return KeyPair(null, privKey)
    }

    fun shutdown() {
        val allIds = sessions.keys.toList()
        allIds.forEach { disconnect(it) }
        client?.stop()
        client = null
    }
}
