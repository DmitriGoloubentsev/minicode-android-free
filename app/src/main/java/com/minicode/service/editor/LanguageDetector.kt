package com.minicode.service.editor

object LanguageDetector {

    data class LanguageInfo(
        val id: String,
        val displayName: String,
        val color: Int,
    )

    private val extensionMap = mapOf(
        // Web
        "js" to LanguageInfo("javascript", "JavaScript", 0xFFCBCB41.toInt()),
        "mjs" to LanguageInfo("javascript", "JavaScript", 0xFFCBCB41.toInt()),
        "jsx" to LanguageInfo("jsx", "React JSX", 0xFF61DAFB.toInt()),
        "ts" to LanguageInfo("typescript", "TypeScript", 0xFF3178C6.toInt()),
        "tsx" to LanguageInfo("tsx", "React TSX", 0xFF3178C6.toInt()),
        "html" to LanguageInfo("html", "HTML", 0xFFE44D26.toInt()),
        "htm" to LanguageInfo("html", "HTML", 0xFFE44D26.toInt()),
        "css" to LanguageInfo("css", "CSS", 0xFF563D7C.toInt()),
        "scss" to LanguageInfo("scss", "SCSS", 0xFFCD6799.toInt()),
        "less" to LanguageInfo("less", "Less", 0xFF1D365D.toInt()),
        "vue" to LanguageInfo("vue", "Vue", 0xFF41B883.toInt()),
        "svelte" to LanguageInfo("svelte", "Svelte", 0xFFFF3E00.toInt()),
        // Data
        "json" to LanguageInfo("json", "JSON", 0xFFCBCB41.toInt()),
        "yaml" to LanguageInfo("yaml", "YAML", 0xFFCB171E.toInt()),
        "yml" to LanguageInfo("yaml", "YAML", 0xFFCB171E.toInt()),
        "toml" to LanguageInfo("toml", "TOML", 0xFF9C4121.toInt()),
        "xml" to LanguageInfo("xml", "XML", 0xFFE44D26.toInt()),
        "csv" to LanguageInfo("csv", "CSV", 0xFF89D185.toInt()),
        // Languages
        "py" to LanguageInfo("python", "Python", 0xFF3572A5.toInt()),
        "rb" to LanguageInfo("ruby", "Ruby", 0xFFCC342D.toInt()),
        "java" to LanguageInfo("java", "Java", 0xFFB07219.toInt()),
        "kt" to LanguageInfo("kotlin", "Kotlin", 0xFF7F52FF.toInt()),
        "kts" to LanguageInfo("kotlin", "Kotlin", 0xFF7F52FF.toInt()),
        "go" to LanguageInfo("go", "Go", 0xFF00ADD8.toInt()),
        "rs" to LanguageInfo("rust", "Rust", 0xFFDEA584.toInt()),
        "c" to LanguageInfo("c", "C", 0xFF555555.toInt()),
        "h" to LanguageInfo("c", "C Header", 0xFF555555.toInt()),
        "cpp" to LanguageInfo("cpp", "C++", 0xFFF34B7D.toInt()),
        "cc" to LanguageInfo("cpp", "C++", 0xFFF34B7D.toInt()),
        "hpp" to LanguageInfo("cpp", "C++ Header", 0xFFF34B7D.toInt()),
        "cs" to LanguageInfo("csharp", "C#", 0xFF178600.toInt()),
        "swift" to LanguageInfo("swift", "Swift", 0xFFF05138.toInt()),
        "dart" to LanguageInfo("dart", "Dart", 0xFF00B4AB.toInt()),
        "php" to LanguageInfo("php", "PHP", 0xFF4F5D95.toInt()),
        "lua" to LanguageInfo("lua", "Lua", 0xFF000080.toInt()),
        "r" to LanguageInfo("r", "R", 0xFF198CE7.toInt()),
        "scala" to LanguageInfo("scala", "Scala", 0xFFDC322F.toInt()),
        "ex" to LanguageInfo("elixir", "Elixir", 0xFF6E4A7E.toInt()),
        "exs" to LanguageInfo("elixir", "Elixir", 0xFF6E4A7E.toInt()),
        "zig" to LanguageInfo("zig", "Zig", 0xFFEC915C.toInt()),
        "nim" to LanguageInfo("nim", "Nim", 0xFFFFE953.toInt()),
        // Shell/Config
        "sh" to LanguageInfo("shell", "Shell", 0xFF89E051.toInt()),
        "bash" to LanguageInfo("shell", "Bash", 0xFF89E051.toInt()),
        "zsh" to LanguageInfo("shell", "Zsh", 0xFF89E051.toInt()),
        "fish" to LanguageInfo("shell", "Fish", 0xFF89E051.toInt()),
        "ps1" to LanguageInfo("powershell", "PowerShell", 0xFF012456.toInt()),
        // DevOps
        "dockerfile" to LanguageInfo("dockerfile", "Dockerfile", 0xFF384D54.toInt()),
        "tf" to LanguageInfo("terraform", "Terraform", 0xFF5C4EE5.toInt()),
        "hcl" to LanguageInfo("hcl", "HCL", 0xFF5C4EE5.toInt()),
        // Docs
        "md" to LanguageInfo("markdown", "Markdown", 0xFF083FA1.toInt()),
        "markdown" to LanguageInfo("markdown", "Markdown", 0xFF083FA1.toInt()),
        "rst" to LanguageInfo("rst", "reStructuredText", 0xFF141414.toInt()),
        "tex" to LanguageInfo("latex", "LaTeX", 0xFF3D6117.toInt()),
        // Database
        "sql" to LanguageInfo("sql", "SQL", 0xFFE38C00.toInt()),
        // Other
        "graphql" to LanguageInfo("graphql", "GraphQL", 0xFFE10098.toInt()),
        "gql" to LanguageInfo("graphql", "GraphQL", 0xFFE10098.toInt()),
        "proto" to LanguageInfo("protobuf", "Protocol Buffers", 0xFF4285F4.toInt()),
        "gradle" to LanguageInfo("groovy", "Groovy", 0xFF4298B8.toInt()),
        "groovy" to LanguageInfo("groovy", "Groovy", 0xFF4298B8.toInt()),
        "makefile" to LanguageInfo("makefile", "Makefile", 0xFF427819.toInt()),
        "cmake" to LanguageInfo("cmake", "CMake", 0xFF064F8C.toInt()),
        "ini" to LanguageInfo("ini", "INI", 0xFF858585.toInt()),
        "conf" to LanguageInfo("conf", "Config", 0xFF858585.toInt()),
        "cfg" to LanguageInfo("conf", "Config", 0xFF858585.toInt()),
        "properties" to LanguageInfo("properties", "Properties", 0xFF858585.toInt()),
        "env" to LanguageInfo("env", "Env", 0xFFECD53F.toInt()),
        "gitignore" to LanguageInfo("gitignore", "Git Ignore", 0xFFF44D27.toInt()),
    )

    // Filename-based detection (no extension)
    private val filenameMap = mapOf(
        "Dockerfile" to LanguageInfo("dockerfile", "Dockerfile", 0xFF384D54.toInt()),
        "Makefile" to LanguageInfo("makefile", "Makefile", 0xFF427819.toInt()),
        "CMakeLists.txt" to LanguageInfo("cmake", "CMake", 0xFF064F8C.toInt()),
        "Vagrantfile" to LanguageInfo("ruby", "Vagrantfile", 0xFFCC342D.toInt()),
        "Gemfile" to LanguageInfo("ruby", "Gemfile", 0xFFCC342D.toInt()),
        "Rakefile" to LanguageInfo("ruby", "Rakefile", 0xFFCC342D.toInt()),
        ".gitignore" to LanguageInfo("gitignore", "Git Ignore", 0xFFF44D27.toInt()),
        ".env" to LanguageInfo("env", "Env", 0xFFECD53F.toInt()),
        ".bashrc" to LanguageInfo("shell", "Bash RC", 0xFF89E051.toInt()),
        ".zshrc" to LanguageInfo("shell", "Zsh RC", 0xFF89E051.toInt()),
        ".profile" to LanguageInfo("shell", "Profile", 0xFF89E051.toInt()),
    )

    private val defaultLang = LanguageInfo("text", "Plain Text", 0xFFD4D4D4.toInt())

    fun detect(fileName: String): LanguageInfo {
        // Check full filename first
        filenameMap[fileName]?.let { return it }
        // Check extension
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            extensionMap[ext]?.let { return it }
        }
        return defaultLang
    }
}
