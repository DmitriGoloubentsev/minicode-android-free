package com.minicode.service.git

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import java.util.concurrent.TimeUnit

private const val TAG = "GitService"
private const val EXEC_TIMEOUT = 10L

class GitService(private val session: ClientSession) {

    suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            val channel = session.createExecChannel(command)
            channel.open().verify(EXEC_TIMEOUT, TimeUnit.SECONDS)
            val stdout = channel.invertedOut.readBytes().toString(Charsets.UTF_8)
            val stderr = channel.invertedErr.readBytes().toString(Charsets.UTF_8)
            val exitCode = channel.exitStatus ?: -1
            channel.close()
            ExecResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            ExecResult(-1, "", e.message ?: "Unknown error")
        }
    }

    /** Get the git repo root for a file path, or null if not in a repo */
    suspend fun getRepoRoot(filePath: String): String? {
        val dir = filePath.substringBeforeLast('/')
        val result = exec("cd ${shellEscape(dir)} && git rev-parse --show-toplevel 2>/dev/null")
        return if (result.exitCode == 0) result.stdout.trim() else null
    }

    /** Get unified diff of a file vs HEAD */
    suspend fun diffFile(repoRoot: String, filePath: String): String {
        val relPath = filePath.removePrefix("$repoRoot/")
        val result = exec("cd ${shellEscape(repoRoot)} && git diff HEAD -- ${shellEscape(relPath)}")
        return result.stdout
    }

    /** Get unified diff with full file context (for inline diff view) */
    suspend fun diffFileFull(repoRoot: String, filePath: String): String {
        val relPath = filePath.removePrefix("$repoRoot/")
        val result = exec("cd ${shellEscape(repoRoot)} && git diff -U999999 HEAD -- ${shellEscape(relPath)}")
        return result.stdout
    }

    /** Get line-level diff status: map of line number (1-based) to DiffLineType */
    suspend fun diffLineStatus(repoRoot: String, filePath: String): Map<Int, DiffLineType> {
        val relPath = filePath.removePrefix("$repoRoot/")
        // Use git diff with unified=0 for minimal context, then parse hunk headers
        val result = exec("cd ${shellEscape(repoRoot)} && git diff HEAD -- ${shellEscape(relPath)}")
        if (result.exitCode != 0 || result.stdout.isBlank()) return emptyMap()
        return parseDiffForLineStatus(result.stdout)
    }

    /** Get recent commit log */
    suspend fun log(repoRoot: String, count: Int = 50): List<GitCommit> {
        val result = exec(
            "cd ${shellEscape(repoRoot)} && git log --format='%H%x00%h%x00%s%x00%an%x00%ai' -$count"
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\u0000')
            if (parts.size >= 5) {
                GitCommit(
                    hash = parts[0],
                    shortHash = parts[1],
                    subject = parts[2],
                    author = parts[3],
                    date = parts[4],
                )
            } else null
        }
    }

    /** Get diff for a specific commit */
    suspend fun showCommit(repoRoot: String, hash: String): String {
        val result = exec("cd ${shellEscape(repoRoot)} && git show --stat --patch $hash")
        return if (result.exitCode == 0) result.stdout else result.stderr
    }

    /** Get diff for a specific commit on a specific file */
    suspend fun showCommitFile(repoRoot: String, hash: String, filePath: String): String {
        val relPath = filePath.removePrefix("$repoRoot/")
        val result = exec("cd ${shellEscape(repoRoot)} && git show $hash -- ${shellEscape(relPath)}")
        return if (result.exitCode == 0) result.stdout else result.stderr
    }

    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val date: String,
)

enum class DiffLineType {
    ADDED, MODIFIED, DELETED_BELOW
}

enum class InlineLineType {
    CONTEXT, ADDED, DELETED
}

data class InlineDiffResult(
    val text: String,
    val lineTypes: Map<Int, InlineLineType>, // 0-based line index -> type
)

/**
 * Build an inline diff document: interleave deleted lines into the current file content.
 * Returns the merged text and a map of line types for coloring.
 */
fun buildInlineDiff(unifiedDiff: String): InlineDiffResult {
    val lineTypes = mutableMapOf<Int, InlineLineType>()
    val outputLines = mutableListOf<String>()
    val diffLines = unifiedDiff.lines()
    var outputLineIdx = 0

    for (line in diffLines) {
        if (line.startsWith("@@")) continue
        if (line.startsWith("diff ") || line.startsWith("index ") ||
            line.startsWith("---") || line.startsWith("+++")) continue
        if (line.startsWith("\\")) continue // "\ No newline at end of file"

        when {
            line.startsWith("-") -> {
                // Deleted line — insert inline with content (strip the '-' prefix)
                outputLines.add(line.substring(1))
                lineTypes[outputLineIdx] = InlineLineType.DELETED
                outputLineIdx++
            }
            line.startsWith("+") -> {
                outputLines.add(line.substring(1))
                lineTypes[outputLineIdx] = InlineLineType.ADDED
                outputLineIdx++
            }
            else -> {
                // Context line (starts with ' ' or is empty in the diff)
                val content = if (line.startsWith(" ")) line.substring(1) else line
                outputLines.add(content)
                outputLineIdx++
            }
        }
    }
    return InlineDiffResult(outputLines.joinToString("\n"), lineTypes)
}

fun parseDiffForLineStatus(unifiedDiff: String): Map<Int, DiffLineType> {
    val result = mutableMapOf<Int, DiffLineType>()
    val lines = unifiedDiff.lines()
    var newLine = 0

    for (line in lines) {
        if (line.startsWith("@@")) {
            // Parse hunk header: @@ -oldStart,oldCount +newStart,newCount @@
            val match = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
            if (match != null) {
                newLine = match.groupValues[1].toInt()
            }
            continue
        }
        if (newLine == 0) continue // Before first hunk

        when {
            line.startsWith("+") && !line.startsWith("+++") -> {
                result[newLine] = DiffLineType.ADDED
                newLine++
            }
            line.startsWith("-") && !line.startsWith("---") -> {
                // Deleted line: mark the current new-file line as having a deletion
                if (!result.containsKey(newLine)) {
                    result[newLine] = DiffLineType.DELETED_BELOW
                } else if (result[newLine] == DiffLineType.ADDED) {
                    result[newLine] = DiffLineType.MODIFIED
                }
                // Don't increment newLine - deleted lines don't exist in new file
            }
            line.startsWith(" ") -> {
                newLine++
            }
            line.startsWith("\\") -> {
                // "\ No newline at end of file" — skip
            }
        }
    }
    return result
}
