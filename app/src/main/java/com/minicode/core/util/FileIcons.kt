package com.minicode.core.util

import android.graphics.Color

object FileIcons {

    data class FileTypeInfo(val icon: String, val color: Int)

    private val extensionMap = mapOf(
        // Languages
        "kt" to FileTypeInfo("K", Color.parseColor("#7F52FF")),
        "kts" to FileTypeInfo("K", Color.parseColor("#7F52FF")),
        "java" to FileTypeInfo("J", Color.parseColor("#B07219")),
        "dart" to FileTypeInfo("D", Color.parseColor("#00B4AB")),
        "py" to FileTypeInfo("P", Color.parseColor("#3572A5")),
        "js" to FileTypeInfo("J", Color.parseColor("#F1E05A")),
        "ts" to FileTypeInfo("T", Color.parseColor("#3178C6")),
        "jsx" to FileTypeInfo("J", Color.parseColor("#F1E05A")),
        "tsx" to FileTypeInfo("T", Color.parseColor("#3178C6")),
        "go" to FileTypeInfo("G", Color.parseColor("#00ADD8")),
        "rs" to FileTypeInfo("R", Color.parseColor("#DEA584")),
        "rb" to FileTypeInfo("R", Color.parseColor("#CC342D")),
        "php" to FileTypeInfo("P", Color.parseColor("#4F5D95")),
        "c" to FileTypeInfo("C", Color.parseColor("#555555")),
        "h" to FileTypeInfo("H", Color.parseColor("#555555")),
        "cpp" to FileTypeInfo("C", Color.parseColor("#F34B7D")),
        "cc" to FileTypeInfo("C", Color.parseColor("#F34B7D")),
        "hpp" to FileTypeInfo("H", Color.parseColor("#F34B7D")),
        "cs" to FileTypeInfo("C", Color.parseColor("#178600")),
        "swift" to FileTypeInfo("S", Color.parseColor("#F05138")),
        "scala" to FileTypeInfo("S", Color.parseColor("#C22D40")),
        "lua" to FileTypeInfo("L", Color.parseColor("#000080")),
        "r" to FileTypeInfo("R", Color.parseColor("#198CE7")),
        "R" to FileTypeInfo("R", Color.parseColor("#198CE7")),
        "sql" to FileTypeInfo("S", Color.parseColor("#E38C00")),
        // Web
        "html" to FileTypeInfo("H", Color.parseColor("#E34C26")),
        "htm" to FileTypeInfo("H", Color.parseColor("#E34C26")),
        "css" to FileTypeInfo("C", Color.parseColor("#563D7C")),
        "scss" to FileTypeInfo("S", Color.parseColor("#C6538C")),
        "less" to FileTypeInfo("L", Color.parseColor("#1D365D")),
        "vue" to FileTypeInfo("V", Color.parseColor("#41B883")),
        "svelte" to FileTypeInfo("S", Color.parseColor("#FF3E00")),
        // Data / Config
        "json" to FileTypeInfo("{", Color.parseColor("#A0A0A0")),
        "yaml" to FileTypeInfo("Y", Color.parseColor("#CB171E")),
        "yml" to FileTypeInfo("Y", Color.parseColor("#CB171E")),
        "toml" to FileTypeInfo("T", Color.parseColor("#9C4221")),
        "xml" to FileTypeInfo("X", Color.parseColor("#0060AC")),
        "csv" to FileTypeInfo(",", Color.parseColor("#237346")),
        // Docs
        "md" to FileTypeInfo("M", Color.parseColor("#083FA1")),
        "txt" to FileTypeInfo("T", Color.parseColor("#A0A0A0")),
        "rst" to FileTypeInfo("R", Color.parseColor("#A0A0A0")),
        // Shell / DevOps
        "sh" to FileTypeInfo("$", Color.parseColor("#89E051")),
        "bash" to FileTypeInfo("$", Color.parseColor("#89E051")),
        "zsh" to FileTypeInfo("$", Color.parseColor("#89E051")),
        "fish" to FileTypeInfo("$", Color.parseColor("#89E051")),
        "bat" to FileTypeInfo(">", Color.parseColor("#C1F12E")),
        "ps1" to FileTypeInfo(">", Color.parseColor("#012456")),
        // Build / Config files
        "gradle" to FileTypeInfo("G", Color.parseColor("#02303A")),
        "cmake" to FileTypeInfo("C", Color.parseColor("#DA3434")),
        "lock" to FileTypeInfo("L", Color.parseColor("#858585")),
        "env" to FileTypeInfo("E", Color.parseColor("#ECD53F")),
        "conf" to FileTypeInfo("C", Color.parseColor("#A0A0A0")),
        "cfg" to FileTypeInfo("C", Color.parseColor("#A0A0A0")),
        "ini" to FileTypeInfo("I", Color.parseColor("#A0A0A0")),
        "properties" to FileTypeInfo("P", Color.parseColor("#A0A0A0")),
        // Images
        "png" to FileTypeInfo("I", Color.parseColor("#A074C4")),
        "jpg" to FileTypeInfo("I", Color.parseColor("#A074C4")),
        "jpeg" to FileTypeInfo("I", Color.parseColor("#A074C4")),
        "gif" to FileTypeInfo("I", Color.parseColor("#A074C4")),
        "svg" to FileTypeInfo("I", Color.parseColor("#FFB13B")),
        "ico" to FileTypeInfo("I", Color.parseColor("#A074C4")),
        // Archives
        "zip" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        "tar" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        "gz" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        "bz2" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        "xz" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        "7z" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        "rar" to FileTypeInfo("Z", Color.parseColor("#E38C00")),
        // Binary
        "so" to FileTypeInfo("B", Color.parseColor("#858585")),
        "o" to FileTypeInfo("B", Color.parseColor("#858585")),
        "exe" to FileTypeInfo("B", Color.parseColor("#858585")),
        "dll" to FileTypeInfo("B", Color.parseColor("#858585")),
        "class" to FileTypeInfo("B", Color.parseColor("#B07219")),
    )

    private val specialNames = mapOf(
        "Dockerfile" to FileTypeInfo("D", Color.parseColor("#384D54")),
        "Makefile" to FileTypeInfo("M", Color.parseColor("#427819")),
        "CMakeLists.txt" to FileTypeInfo("C", Color.parseColor("#DA3434")),
        "Gemfile" to FileTypeInfo("G", Color.parseColor("#CC342D")),
        "Rakefile" to FileTypeInfo("R", Color.parseColor("#CC342D")),
        "Vagrantfile" to FileTypeInfo("V", Color.parseColor("#1563FF")),
        ".gitignore" to FileTypeInfo("G", Color.parseColor("#F05032")),
        ".gitmodules" to FileTypeInfo("G", Color.parseColor("#F05032")),
        ".editorconfig" to FileTypeInfo("E", Color.parseColor("#A0A0A0")),
    )

    fun getFileTypeInfo(filename: String): FileTypeInfo {
        specialNames[filename]?.let { return it }
        val ext = filename.substringAfterLast('.', "")
        return extensionMap[ext] ?: FileTypeInfo("F", Color.parseColor("#858585"))
    }

    val FOLDER_COLOR: Int = Color.parseColor("#C09553")
    val FOLDER_EXPANDED_COLOR: Int = Color.parseColor("#C09553")
    val SYMLINK_COLOR: Int = Color.parseColor("#75BEFF")
}
