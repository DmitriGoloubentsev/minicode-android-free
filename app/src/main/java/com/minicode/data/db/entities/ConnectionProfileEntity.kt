package com.minicode.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.minicode.model.AuthType
import com.minicode.model.ConnectionProfile

@Entity(tableName = "connection_profiles")
data class ConnectionProfileEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: String,
    val initialDirectory: String? = null,
    val startupCommand: String? = null,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val sortOrder: Int = 0,
) {
    fun toModel(): ConnectionProfile = ConnectionProfile(
        id = id,
        label = label,
        host = host,
        port = port,
        username = username,
        authType = AuthType.fromString(authType),
        initialDirectory = initialDirectory,
        startupCommand = startupCommand,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        sortOrder = sortOrder,
    )

    companion object {
        fun fromModel(profile: ConnectionProfile): ConnectionProfileEntity =
            ConnectionProfileEntity(
                id = profile.id,
                label = profile.label,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                authType = profile.authType.name,
                initialDirectory = profile.initialDirectory,
                startupCommand = profile.startupCommand,
                createdAt = profile.createdAt,
                lastUsedAt = profile.lastUsedAt,
                sortOrder = profile.sortOrder,
            )
    }
}
