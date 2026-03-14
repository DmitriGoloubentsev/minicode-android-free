package com.minicode.model

data class ConnectionProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType,
    val initialDirectory: String? = null,
    val startupCommand: String? = null,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val sortOrder: Int = 0,
)
