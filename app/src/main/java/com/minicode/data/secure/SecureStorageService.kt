package com.minicode.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorageService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "minicode_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun savePassword(profileId: String, password: String) {
        prefs.edit().putString(passwordKey(profileId), password).apply()
    }

    fun getPassword(profileId: String): String? =
        prefs.getString(passwordKey(profileId), null)

    fun deletePassword(profileId: String) {
        prefs.edit().remove(passwordKey(profileId)).apply()
    }

    fun savePrivateKey(profileId: String, privateKey: String) {
        prefs.edit().putString(privateKeyKey(profileId), privateKey).apply()
    }

    fun getPrivateKey(profileId: String): String? =
        prefs.getString(privateKeyKey(profileId), null)

    fun deletePrivateKey(profileId: String) {
        prefs.edit().remove(privateKeyKey(profileId)).apply()
    }

    fun savePassphrase(profileId: String, passphrase: String) {
        prefs.edit().putString(passphraseKey(profileId), passphrase).apply()
    }

    fun getPassphrase(profileId: String): String? =
        prefs.getString(passphraseKey(profileId), null)

    fun deletePassphrase(profileId: String) {
        prefs.edit().remove(passphraseKey(profileId)).apply()
    }

    fun deleteAllForProfile(profileId: String) {
        prefs.edit()
            .remove(passwordKey(profileId))
            .remove(privateKeyKey(profileId))
            .remove(passphraseKey(profileId))
            .apply()
    }

    private fun passwordKey(profileId: String) = "password_$profileId"
    private fun privateKeyKey(profileId: String) = "private_key_$profileId"
    private fun passphraseKey(profileId: String) = "passphrase_$profileId"
}
