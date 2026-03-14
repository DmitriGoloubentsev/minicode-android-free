package com.minicode.model

data class EditorTab(
    val filePath: String,
    val fileName: String,
    val content: String = "",
    val originalContent: String = "",
    val languageId: String = "text",
    val cursorPosition: Int = 0,
    val scrollY: Int = 0,
    val imageBytes: ByteArray? = null,
) {
    val isModified: Boolean get() = content != originalContent
    val isImage: Boolean get() = imageBytes != null
}
