package com.minicode.service.editor

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

object TextMateHelper {

    private const val TAG = "TextMateHelper"
    private var initialized = false

    private val scopeNameMap = mapOf(
        "javascript" to "source.js",
        "jsx" to "source.js",
        "typescript" to "source.ts",
        "tsx" to "source.ts",
        "python" to "source.python",
        "java" to "source.java",
        "kotlin" to "source.kotlin",
        "go" to "source.go",
        "rust" to "source.rust",
        "c" to "source.c",
        "cpp" to "source.cpp",
        "csharp" to "source.cs",
        "html" to "text.html.basic",
        "css" to "source.css",
        "scss" to "source.css",
        "json" to "source.json",
        "yaml" to "source.yaml",
        "markdown" to "text.html.markdown",
        "shell" to "source.shell",
        "sql" to "source.sql",
        "php" to "source.php",
        "ruby" to "source.ruby",
        "xml" to "text.xml",
        "vue" to "text.html.basic",
        "svelte" to "text.html.basic",
    )

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        try {
            FileProviderRegistry.getInstance().addFileProvider(
                AssetsFileResolver(context.applicationContext.assets)
            )

            val themeRegistry = ThemeRegistry.getInstance()
            val themeSource = IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream("textmate/themes/darcula.json"),
                "textmate/themes/darcula.json",
                null
            )
            themeRegistry.loadTheme(ThemeModel(themeSource, "darcula").apply { isDark = true })
            themeRegistry.setTheme("darcula")

            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            initialized = true
            Log.d(TAG, "TextMate initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextMate", e)
        }
    }

    fun applyTheme(editor: CodeEditor) {
        try {
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply TextMate theme", e)
        }
    }

    fun createLanguage(languageId: String): TextMateLanguage? {
        val scopeName = scopeNameMap[languageId] ?: run {
            Log.w(TAG, "No scope name mapping for languageId: $languageId")
            return null
        }
        return try {
            val lang = TextMateLanguage.create(scopeName, true)
            Log.d(TAG, "Created TextMateLanguage for $languageId ($scopeName)")
            lang
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TextMateLanguage for $languageId ($scopeName)", e)
            null
        }
    }
}
