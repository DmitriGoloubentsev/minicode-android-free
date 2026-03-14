package com.minicode.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minicode.data.repository.ConnectionRepository
import com.minicode.model.AuthType
import com.minicode.model.ConnectionProfile
import com.minicode.service.ssh.SshConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class TestConnectionState(
    val isLoading: Boolean = false,
    val result: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val sshService: SshConnectionService,
) : ViewModel() {

    val profiles: StateFlow<List<ConnectionProfile>> = repository.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _testConnectionState = MutableStateFlow(TestConnectionState())
    val testConnectionState: StateFlow<TestConnectionState> = _testConnectionState.asStateFlow()

    fun saveProfile(
        id: String?,
        label: String,
        host: String,
        port: Int,
        username: String,
        authType: AuthType,
        password: String?,
        privateKey: String?,
        passphrase: String?,
        initialDirectory: String?,
        startupCommand: String?,
    ) {
        viewModelScope.launch {
            val profile = ConnectionProfile(
                id = id ?: UUID.randomUUID().toString(),
                label = label,
                host = host,
                port = port,
                username = username,
                authType = authType,
                initialDirectory = initialDirectory?.takeIf { it.isNotBlank() },
                startupCommand = startupCommand?.takeIf { it.isNotBlank() },
                createdAt = if (id != null) {
                    repository.getProfileById(id)?.createdAt ?: Instant.now().toString()
                } else {
                    Instant.now().toString()
                },
            )
            repository.saveProfile(profile, password, privateKey, passphrase)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
        }
    }

    fun testConnection(
        host: String,
        port: Int,
        username: String,
        authType: AuthType,
        password: String?,
        privateKey: String?,
        passphrase: String?,
    ) {
        viewModelScope.launch {
            _testConnectionState.value = TestConnectionState(isLoading = true)
            val profile = ConnectionProfile(
                id = "test",
                label = "test",
                host = host,
                port = port,
                username = username,
                authType = authType,
                createdAt = Instant.now().toString(),
            )
            val result = sshService.testConnection(profile, password, privateKey, passphrase)
            _testConnectionState.value = result.fold(
                onSuccess = { TestConnectionState(result = it, isError = false) },
                onFailure = { TestConnectionState(result = it.message ?: "Connection failed", isError = true) },
            )
        }
    }

    fun clearTestState() {
        _testConnectionState.value = TestConnectionState()
    }

    suspend fun getProfile(id: String): ConnectionProfile? = repository.getProfileById(id)

    fun getPassword(profileId: String): String? = repository.getPassword(profileId)
    fun getPrivateKey(profileId: String): String? = repository.getPrivateKey(profileId)
    fun getPassphrase(profileId: String): String? = repository.getPassphrase(profileId)
}
