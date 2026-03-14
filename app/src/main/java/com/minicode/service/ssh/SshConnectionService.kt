package com.minicode.service.ssh

import com.minicode.model.AuthType
import com.minicode.model.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyPairProvider
import java.io.StringReader
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshConnectionService @Inject constructor() {

    private var client: SshClient? = null

    private fun getOrCreateClient(): SshClient {
        if (System.getProperty("user.home").isNullOrEmpty()) {
            System.setProperty("user.home", "/data/local/tmp")
        }
        return client ?: SshClient.setUpDefaultClient().also {
            it.start()
            client = it
        }
    }

    suspend fun testConnection(
        profile: ConnectionProfile,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        var session: ClientSession? = null
        try {
            val sshClient = getOrCreateClient()
            session = sshClient.connect(profile.username, profile.host, profile.port)
                .verify(10, TimeUnit.SECONDS)
                .session

            when (profile.authType) {
                AuthType.PASSWORD -> {
                    session.addPasswordIdentity(password ?: "")
                }
                AuthType.PRIVATE_KEY -> {
                    if (privateKey != null) {
                        session.addPublicKeyIdentity(loadKeyPair(privateKey, passphrase))
                    }
                }
            }

            session.auth().verify(10, TimeUnit.SECONDS)

            val serverVersion = session.serverVersion ?: "Unknown"
            Result.success("Connected successfully.\nServer: $serverVersion")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun connect(
        profile: ConnectionProfile,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ): ClientSession = withContext(Dispatchers.IO) {
        val sshClient = getOrCreateClient()
        val session = sshClient.connect(profile.username, profile.host, profile.port)
            .verify(15, TimeUnit.SECONDS)
            .session

        when (profile.authType) {
            AuthType.PASSWORD -> {
                session.addPasswordIdentity(password ?: "")
            }
            AuthType.PRIVATE_KEY -> {
                if (privateKey != null) {
                    session.addPublicKeyIdentity(loadKeyPair(privateKey, passphrase))
                }
            }
        }

        session.auth().verify(15, TimeUnit.SECONDS)
        session
    }

    private fun loadKeyPair(privateKeyPem: String, passphrase: String?): KeyPair {
        // Simple PEM key loading - supports unencrypted RSA keys
        val lines = privateKeyPem.lines()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        val keyBytes = Base64.getDecoder().decode(lines)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privKey = keyFactory.generatePrivate(keySpec)
        return KeyPair(null, privKey)
    }

    fun shutdown() {
        client?.stop()
        client = null
    }
}
