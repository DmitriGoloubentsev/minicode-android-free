package com.minicode.model

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY;

    companion object {
        fun fromString(value: String): AuthType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PASSWORD
    }
}
