package com.aliahad.wovoice.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.aliahad.wovoice.account.SecretNames
import com.aliahad.wovoice.account.SecretsVault
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores account credentials and vault material with a non-exportable Android Keystore key. */
class SecretStore(context: Context) : SecretsVault {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun putString(name: String, value: String?) {
        require(NAME_PATTERN.matches(name))
        if (value.isNullOrBlank()) {
            remove(name)
            return
        }
        val encrypted = encryptWithRecovery(name, value.toByteArray(Charsets.UTF_8)) ?: return
        preferences.edit()
            .putString(ivKey(name), Base64.encodeToString(encrypted.first, Base64.NO_WRAP))
            .putString(ciphertextKey(name), Base64.encodeToString(encrypted.second, Base64.NO_WRAP))
            .apply()
        presenceCache[name] = true
    }

    override fun getString(name: String): String? {
        require(NAME_PATTERN.matches(name))
        val ivRaw = preferences.getString(ivKey(name), null)
        val ciphertextRaw = preferences.getString(ciphertextKey(name), null)
        if (ivRaw == null || ciphertextRaw == null) {
            presenceCache[name] = false
            return null
        }
        val iv = runCatching { Base64.decode(ivRaw, Base64.NO_WRAP) }.getOrNull() ?: return null
        val ciphertext = runCatching { Base64.decode(ciphertextRaw, Base64.NO_WRAP) }.getOrNull() ?: return null
        val key = runCatching { getOrCreateKey() }.getOrNull() ?: return null
        // Try AAD-bound decryption first; fall back transparently to legacy ciphertexts
        // written before key-name AAD binding so existing signed-in users stay signed in.
        val plaintext = decrypt(key, iv, ciphertext, aad = name.toByteArray(Charsets.UTF_8))
            ?: decrypt(key, iv, ciphertext, aad = null)
        val decoded = plaintext?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
        presenceCache[name] = decoded != null
        return decoded
    }

    override fun contains(name: String): Boolean {
        require(NAME_PATTERN.matches(name))
        presenceCache[name]?.let { return it }
        val present = preferences.contains(ivKey(name)) && preferences.contains(ciphertextKey(name))
        presenceCache[name] = present
        return present
    }

    override fun remove(name: String) {
        require(NAME_PATTERN.matches(name))
        preferences.edit().remove(ivKey(name)).remove(ciphertextKey(name)).apply()
        presenceCache[name] = false
    }

    fun clearLegacyCredential() {
        remove(LEGACY_TOKEN)
        preferences.edit().remove(V1_IV).remove(V1_CIPHERTEXT).apply()
        synchronized(KEYSTORE_LOCK) {
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(V1_KEY_ALIAS)) keyStore.deleteEntry(V1_KEY_ALIAS)
            }
        }
    }

    fun clearAccount() {
        listOf(
            REFRESH_TOKEN,
            PENDING_PKCE_VERIFIER,
            PENDING_PKCE_STATE,
            PENDING_AUTH_INTENT,
            SecretNames.PENDING_PKCE_CREATED_AT,
            VAULT_KEY,
            RECOVERY_SECRET,
        ).forEach(::remove)
    }

    fun clearAll() {
        preferences.edit().clear().apply()
        presenceCache.clear()
        synchronized(KEYSTORE_LOCK) {
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                listOf(KEY_ALIAS, V1_KEY_ALIAS).forEach { alias ->
                    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                }
            }
        }
    }

    private fun encryptWithRecovery(name: String, plaintext: ByteArray): Pair<ByteArray, ByteArray>? {
        val aad = name.toByteArray(Charsets.UTF_8)
        encryptOnce(plaintext, aad)?.let { return it }
        // Key may have been invalidated by a lockscreen/OS upgrade; regenerate once and retry.
        synchronized(KEYSTORE_LOCK) {
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            }
        }
        return encryptOnce(plaintext, aad)
    }

    private fun encryptOnce(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        cipher.iv to encrypted
    }.getOrNull()

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun ivKey(name: String) = "${name}_iv"
    private fun ciphertextKey(name: String) = "${name}_ciphertext"

    companion object {
        const val REFRESH_TOKEN = "refresh_token"
        const val PENDING_PKCE_VERIFIER = "pending_pkce_verifier"
        const val PENDING_PKCE_STATE = "pending_pkce_state"
        const val PENDING_AUTH_INTENT = "pending_auth_intent"
        const val VAULT_KEY = "vault_key"
        const val RECOVERY_SECRET = "recovery_secret"

        private val KEYSTORE_LOCK = Any()
        private val presenceCache = ConcurrentHashMap<String, Boolean>()

        private const val PREFERENCES = "secure_settings"
        private const val KEY_ALIAS = "wovoice_account_secrets_v2"
        private const val LEGACY_TOKEN = "legacy_device_token"
        private const val V1_KEY_ALIAS = "wovoice_device_token_v1"
        private const val V1_IV = "token_iv"
        private const val V1_CIPHERTEXT = "token_ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val NAME_PATTERN = Regex("[a-z0-9_]{1,64}")
    }
}
