package com.minicode.model

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedTime: Long = 0,
    val isSymlink: Boolean = false,
    // UI state
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val children: List<FileNode>? = null,
    val depth: Int = 0,
)
