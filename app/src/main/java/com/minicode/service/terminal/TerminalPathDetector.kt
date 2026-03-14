package com.minicode.service.terminal

object TerminalPathDetector {

    data class DetectedPath(
        val path: String,
        val line: Int? = null,
        val column: Int? = null,
        val matchStart: Int,
        val matchEnd: Int,
    )

    private val fileExtensions = setOf(
        "js", "mjs", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "less",
        "vue", "svelte", "json", "yaml", "yml", "toml", "xml", "csv",
        "py", "rb", "java", "kt", "kts", "go", "rs", "c", "h", "cpp", "cc",
        "hpp", "cs", "swift", "dart", "php", "lua", "r", "scala", "ex", "exs",
        "zig", "nim", "sh", "bash", "zsh", "fish", "ps1",
        "dockerfile", "tf", "hcl", "md", "markdown", "rst", "tex",
        "sql", "graphql", "gql", "proto", "gradle", "groovy",
        "makefile", "cmake", "ini", "conf", "cfg", "properties", "env",
        "gitignore", "txt", "log", "lock",
    )

    // Match paths like: ./foo/bar.py:42:10, /home/user/file.rs:10, src/main.go, ../lib/util.js
    private val pathRegex = Regex(
        """((?:\.{0,2}/)?(?:[a-zA-Z0-9._@\-]+/)*[a-zA-Z0-9._@\-]+\.[a-zA-Z0-9]+)(?::(\d+)(?::(\d+))?)?"""
    )

    fun detectAll(lineText: String): List<DetectedPath> {
        val results = mutableListOf<DetectedPath>()
        for (match in pathRegex.findAll(lineText)) {
            val fullPath = match.groupValues[1]
            val ext = fullPath.substringAfterLast('.', "").lowercase()
            if (ext !in fileExtensions) continue
            // Skip URLs
            val before = if (match.range.first >= 2) lineText.substring(match.range.first - 2, match.range.first) else ""
            if (before.endsWith("//")) continue

            val lineNum = match.groupValues[2].toIntOrNull()
            val colNum = match.groupValues[3].toIntOrNull()
            results.add(
                DetectedPath(
                    path = fullPath,
                    line = lineNum,
                    column = colNum,
                    matchStart = match.range.first,
                    matchEnd = match.range.last + 1,
                )
            )
        }
        return results
    }

    fun detectAtPosition(lineText: String, col: Int): DetectedPath? {
        return detectAll(lineText).firstOrNull { col >= it.matchStart && col < it.matchEnd }
    }
}
