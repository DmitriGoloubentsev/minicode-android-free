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

class TerminalSessionBridge(
    private val shellChannel: ChannelShell,
    val emulator: TerminalEmulator,
    private val scope: CoroutineScope,
) {
    private var readerJob: Job? = null
    private val outputStream: OutputStream = shellChannel.invertedIn
    private val inputStream: InputStream = shellChannel.invertedOut

    var onDisconnect: (() -> Unit)? = null
    var onOutput: (() -> Unit)? = null

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
        emulator.onAltBufferChanged = { isAlt ->
            handleAltBufferChanged(isAlt)
        }

        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                while (isActive && shellChannel.isOpen) {
                    val n = inputStream.read(buffer)
                    if (n < 0) {
                        Log.d(TAG, "Read returned EOF (-1), shell exited")
                        break
                    }
                    if (n > 0) {
                        emulator.processBytes(buffer, 0, n)
                        onOutput?.invoke()
                    }
                }
                Log.d(TAG, "Reader loop ended, isActive=$isActive, channelOpen=${shellChannel.isOpen}")
            } catch (e: IOException) {
                Log.d(TAG, "Reader IOException: ${e.message}")
            } finally {
                Log.d(TAG, "Invoking onDisconnect")
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
        val now = System.currentTimeMillis()
        if (now - lastInputTime > idleThresholdMs) {
            Log.d(TAG, "Idle >60s, forcing resize ${actualCols}x${actualRows}")
            forceResize(actualCols, actualRows)
        }
        lastInputTime = now
        scope.launch(Dispatchers.IO) {
            try {
                outputStream.write(data)
                outputStream.flush()
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
            shellChannel.close()
        } catch (_: Exception) {
        }
    }
}
