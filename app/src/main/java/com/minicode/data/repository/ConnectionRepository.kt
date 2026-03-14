package com.minicode.data.repository

import com.minicode.data.db.ConnectionProfileDao
import com.minicode.data.db.entities.ConnectionProfileEntity
import com.minicode.data.secure.SecureStorageService
import com.minicode.model.AuthType
import com.minicode.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val dao: ConnectionProfileDao,
    private val secureStorage: SecureStorageService,
) {
    fun getAllProfiles(): Flow<List<ConnectionProfile>> =
        dao.getAllProfiles().map { entities -> entities.map { it.toModel() } }

    suspend fun getProfileById(id: String): ConnectionProfile? =
        dao.getProfileById(id)?.toModel()

    suspend fun saveProfile(
        profile: ConnectionProfile,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ) {
        dao.insertProfile(ConnectionProfileEntity.fromModel(profile))
        when (profile.authType) {
            AuthType.PASSWORD -> {
                if (password != null) secureStorage.savePassword(profile.id, password)
            }
            AuthType.PRIVATE_KEY -> {
                if (privateKey != null) secureStorage.savePrivateKey(profile.id, privateKey)
                if (passphrase != null) secureStorage.savePassphrase(profile.id, passphrase)
            }
        }
    }

    suspend fun deleteProfile(id: String) {
        dao.deleteProfileById(id)
        secureStorage.deleteAllForProfile(id)
    }

    suspend fun updateLastUsed(id: String) {
        dao.updateLastUsed(id, Instant.now().toString())
    }

    fun getPassword(profileId: String): String? = secureStorage.getPassword(profileId)
    fun getPrivateKey(profileId: String): String? = secureStorage.getPrivateKey(profileId)
    fun getPassphrase(profileId: String): String? = secureStorage.getPassphrase(profileId)
}
