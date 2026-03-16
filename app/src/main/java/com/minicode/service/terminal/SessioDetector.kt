package com.minicode.service.terminal

/**
 * Detects sessio session listings in terminal output within a 5-second window
 * after SSH shell start. Parses "Available sessions:" marker followed by session
 * entries in the format: `  <name> (pid <number>)  [optional cwd]  [optional — title]`
 */
class SessioDetector {
    enum class State { WAITING, COLLECTING, DONE }

    private var state = State.WAITING
    private val sessions = mutableListOf<SessioSession>()
    private var deadline: Long = 0

    data class SessioSession(val name: String, val cwd: String?)

    /**
     * Called on every line of terminal output during the detection window.
     * Returns a non-null list of sessions when the session list is complete.
     */
    fun onLine(line: String): List<SessioSession>? {
        if (state == State.DONE) return null
        if (System.currentTimeMillis() > deadline) {
            state = State.DONE
            return null
        }

        when (state) {
            State.WAITING -> {
                if (line.trim() == "Available sessions:") {
                    state = State.COLLECTING
                }
            }
            State.COLLECTING -> {
                val match = SESSION_REGEX.find(line)
                if (match != null) {
                    val name = match.groupValues[1]
                    val rest = line.substringAfter(")").trim()
                    val cwd = rest.substringBefore("\u2014").trim()
                        .ifEmpty { null }
                    sessions.add(SessioSession(name, cwd))
                } else if (sessions.isNotEmpty()) {
                    // Non-matching line after sessions = end of list
                    state = State.DONE
                    return sessions.toList()
                } else if ("no active sessions" in line) {
                    state = State.DONE
                    return null
                }
            }
            else -> {}
        }
        return null
    }

    fun start() {
        state = State.WAITING
        sessions.clear()
        deadline = System.currentTimeMillis() + 5000
    }

    val isDone: Boolean get() = state == State.DONE

    companion object {
        val SESSION_REGEX = Regex("""^\s+(\S+)\s+\(pid\s+\d+\)""")
    }
}
