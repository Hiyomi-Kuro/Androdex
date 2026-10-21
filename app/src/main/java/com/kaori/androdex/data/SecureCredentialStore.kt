package com.kaori.androdex.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES/GCM secrets encrypted with a non-exportable Android Keystore key. */
class SecureCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("androdex_encrypted_credentials", Context.MODE_PRIVATE)

    @Synchronized
    fun saveRuntimeApiKey(value: String) = save(KEY_RUNTIME_API, value)

    @Synchronized
    fun runtimeApiKey(): String? = load(KEY_RUNTIME_API)

    @Synchronized
    fun saveMcpBearer(value: String) = save(KEY_MCP_BEARER, value)

    @Synchronized
    fun mcpBearer(): String? = load(KEY_MCP_BEARER)

    @Synchronized
    fun hasRuntimeApiKey(): Boolean = !runtimeApiKey().isNullOrBlank()

    @Synchronized
    fun hasMcpBearer(): Boolean = !mcpBearer().isNullOrBlank()

    private fun save(name: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + ciphertext.size)
        cipher.iv.copyInto(packed)
        ciphertext.copyInto(packed, cipher.iv.size)
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun load(name: String): String? {
        val packed = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        require(packed.size > GCM_IV_BYTES) { "凭据密文格式无效" }
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.kaori.androdex.tunnel.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_RUNTIME_API = "runtime_api_key"
        const val KEY_MCP_BEARER = "mcp_bearer"
    }
}
