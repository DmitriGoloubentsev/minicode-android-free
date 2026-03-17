package com.minicode.service.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.sshd.client.channel.ChannelShell
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "TerminalSessionBridge"

/** File-based debug log for Honor devices that suppress logcat */
internal object BridgeDebugLog {
    private var file: java.io.File? = null
    fun init(context: android.content.Context) {
        file = java.io.File(context.filesDir, "bridge_debug.log")
    }
    fun log(msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            file?.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }
}

class TerminalSessionBridge(
    private val shellChannel: ChannelShell?,
    val emulator: TerminalEmulator,
    private val scope: CoroutineScope,
) {
    val isDisconnected: Boolean get() = shellChannel == null

    companion object {
        /** Create a placeholder bridge with no SSH channel for session restore */
        fun createDisconnected(cols: Int, rows: Int): TerminalSessionBridge {
            val emulator = TerminalEmulator(cols, rows)
            val scope = CoroutineScope(Dispatchers.IO)
            return TerminalSessionBridge(null, emulator, scope)
        }
    }

    private var readerJob: Job? = null
    private val outputStream: OutputStream? = shellChannel?.invertedIn
    private val inputStream: InputStream? = shellChannel?.invertedOut

    var onDisconnect: (() -> Unit)? = null
    var onOutput: (() -> Unit)? = null
    var onSessioDetected: ((List<SessioDetector.SessioSession>) -> Unit)? = null
    var sessioWasDetected = false

    val sessioDetector = SessioDetector()

    // Normal mode uses extended rows; alt buffer uses actual screen rows
    var extendedRows = 500

    // Idle resize: if no input for 60s, force resize before next character
    // (handles sessio PC usage changing terminal dimensions)
    private var lastInputTime = System.currentTimeMillis()
    private val idleThresholdMs = 60_000L
    private var actualCols = emulator.columns
    private var actualRows = emulator.rows
    private var lastSentCols = emulator.columns
    private var lastSentRows = emulator.rows

    fun start() {
        if (shellChannel == null) return
        sessioDetector.start()

        emulator.onAltBufferChanged = { isAlt ->
            handleAltBufferChanged(isAlt)
        }

        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            val lineBuffer = StringBuilder()
            try {
                while (isActive && shellChannel.isOpen) {
                    val n = inputStream!!.read(buffer)
                    if (n < 0) {
                        Log.d(TAG, "Read returned EOF (-1), shell exited")
                        break
                    }
                    if (n > 0) {
                        // Feed lines to sessio detector while it's active
                        if (!sessioDetector.isDone) {
                            val text = String(buffer, 0, n, Charsets.UTF_8)
                            for (ch in text) {
                                if (ch == '\n' || ch == '\r') {
                                    val detected = sessioDetector.onLine(lineBuffer.toString())
                                    lineBuffer.clear()
                                    if (detected != null) {
                                        onSessioDetected?.invoke(detected)
                                        sessioWasDetected = true
                                    }
                                    if (sessioDetector.isDone) break
                                } else {
                                    lineBuffer.append(ch)
                                }
                            }
                            // Force timeout check even without a newline
                            if (!sessioDetector.isDone) {
                                val detected = sessioDetector.onLine("")
                                if (detected != null) {
                                    onSessioDetected?.invoke(detected)
                                    sessioWasDetected = true
                                }
                            }
                        }
                        emulator.processBytes(buffer, 0, n)
                        onOutput?.invoke()
                    }
                }
                BridgeDebugLog.log("Reader loop ended: isActive=$isActive, channelOpen=${shellChannel.isOpen}")
            } catch (e: IOException) {
                BridgeDebugLog.log("Reader IOException: ${e.message}")
            } catch (e: Exception) {
                BridgeDebugLog.log("Reader Exception: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                BridgeDebugLog.log("Invoking onDisconnect")
                onDisconnect?.invoke()
            }
        }
    }

    private fun handleAltBufferChanged(isAlt: Boolean) {
        if (isAlt) {
            // Alt buffer activated — TUI apps need actual screen size
            emulator.resize(actualCols, actualRows)
            sendWindowChange(actualCols, actualRows)
        } else {
            // Alt buffer deactivated — restore extended rows for internal buffer
            emulator.resize(actualCols, extendedRows)
            sendWindowChange(actualCols, actualRows)
        }
    }

    fun writeBytes(data: ByteArray) {
        if (shellChannel == null) return
        val now = System.currentTimeMillis()
        if (now - lastInputTime > idleThresholdMs) {
            Log.d(TAG, "Idle >60s, forcing resize ${actualCols}x${actualRows}")
            forceResize(actualCols, actualRows)
        }
        lastInputTime = now
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (_: IOException) {
                // Connection lost
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        actualCols = cols
        actualRows = rows
        if (emulator.buffer.isAltBuffer) {
            // In alt buffer: TUI apps need actual screen size
            if (cols != emulator.columns || rows != emulator.rows) {
                emulator.resize(cols, rows)
                sendWindowChange(cols, rows)
            }
            return
        }
        // Normal mode: extended rows for internal buffer, actual rows for remote
        emulator.resize(cols, extendedRows)
        // Only send window change when columns change. Row-only changes (keyboard
        // show/hide, editor panel toggle) don't affect remote line wrapping and
        // would cause unnecessary terminal redraws on the server.
        if (cols != lastSentCols) {
            sendWindowChange(cols, rows)
        }
    }

    /**
     * Always send window-change to server, even if size matches what we last sent.
     * Used when another client (e.g. sessio on PC) may have changed the terminal
     * size, so we need to re-assert MiniCode's dimensions.
     */
    fun forceResize(cols: Int, rows: Int) {
        actualCols = cols
        actualRows = rows
        if (emulator.buffer.isAltBuffer) {
            emulator.resize(cols, rows)
        } else {
            emulator.resize(cols, extendedRows)
        }
        sendWindowChange(cols, rows)
    }

    private fun sendWindowChange(cols: Int, rows: Int) {
        if (shellChannel == null) return
        lastSentCols = cols
        lastSentRows = rows
        scope.launch(Dispatchers.IO) {
            try {
                if (shellChannel.isOpen) {
                    shellChannel.sendWindowChange(cols, rows, 0, 0)
                }
            } catch (_: IOException) {
            }
        }
    }

    fun stop() {
        readerJob?.cancel()
        try {
            shellChannel?.close()
        } catch (_: Exception) {
        }
    }
}
