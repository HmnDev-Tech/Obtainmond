package io.obtainmond.data.repo

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

@Serializable
data class StoredSecrets(
    val githubTokens: List<String> = emptyList(),
    val gitlabToken: String = "",
    val virusTotalApiKey: String = ""
) {
    fun primaryGitHubToken(): String = githubTokens.firstOrNull { it.isNotBlank() }.orEmpty()
}

interface SecretStore {
    fun load(): StoredSecrets
    fun save(secrets: StoredSecrets)
    fun clear()
}

class InMemorySecretStore(initial: StoredSecrets = StoredSecrets()) : SecretStore {
    private var current = initial

    @Synchronized
    override fun load(): StoredSecrets = current

    @Synchronized
    override fun save(secrets: StoredSecrets) {
        current = normalizeSecrets(secrets)
    }

    @Synchronized
    override fun clear() {
        current = StoredSecrets()
    }
}

class TinkEncryptedSecretStore(context: Context) : SecretStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(SECRETS_PREF_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedAead: Aead? = null

    private fun getOrCreateAead(): Aead {
        cachedAead?.let { return it }
        synchronized(lock) {
            cachedAead?.let { return it }
            ensureAeadRegistered()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java).also {
                cachedAead = it
            }
        }
    }

    override fun load(): StoredSecrets = synchronized(lock) {
        val encoded = prefs.getString(KEY_PAYLOAD, null)?.takeIf { it.isNotBlank() } ?: return StoredSecrets()
        return try {
            val cipher = Base64.decode(encoded, Base64.NO_WRAP)
            val plaintext = getOrCreateAead().decrypt(cipher, AAD)
            normalizeSecrets(json.decodeFromString<StoredSecrets>(String(plaintext, StandardCharsets.UTF_8)))
        } catch (_: GeneralSecurityException) {
            StoredSecrets()
        } catch (_: Exception) {
            StoredSecrets()
        }
    }

    override fun save(secrets: StoredSecrets) = synchronized(lock) {
        val normalized = normalizeSecrets(secrets)
        if (normalized.githubTokens.isEmpty() && normalized.gitlabToken.isEmpty() && normalized.virusTotalApiKey.isEmpty()) {
            prefs.edit().remove(KEY_PAYLOAD).apply()
        } else {
            val plaintext = json.encodeToString(normalized).toByteArray(StandardCharsets.UTF_8)
            val cipher = getOrCreateAead().encrypt(plaintext, AAD)
            val encoded = Base64.encodeToString(cipher, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PAYLOAD, encoded).apply()
        }
    }

    override fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    companion object {
        private const val KEYSET_NAME = "obtainmond_secrets_keyset"
        private const val KEYSET_PREF_NAME = "obtainmond_master_keyset_prefs"
        private const val SECRETS_PREF_NAME = "obtainmond_secrets_enc"
        private const val MASTER_KEY_URI = "android-keystore://obtainmond_master_key"
        private const val KEY_PAYLOAD = "secrets_ciphertext_v1"
        private val AAD = "io.obtainmond.secrets.v1".toByteArray(StandardCharsets.UTF_8)

        @Volatile
        private var initialized = false

        private fun ensureAeadRegistered() {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        AeadConfig.register()
                        initialized = true
                    }
                }
            }
        }
    }
}

private fun normalizeSecrets(secrets: StoredSecrets): StoredSecrets {
    return secrets.copy(
        githubTokens = secrets.githubTokens.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        gitlabToken = secrets.gitlabToken.trim(),
        virusTotalApiKey = secrets.virusTotalApiKey.trim()
    )
}

fun parseTokenPoolInput(raw: String): List<String> {
    return raw.split('\n', '\r', ',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
