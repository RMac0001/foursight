package io.github.rmac0001.foursight.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] on Android: values are encrypted with an AES-GCM key that lives in the Android
 * Keystore (it never leaves secure hardware where available) and the ciphertext is kept in a
 * private SharedPreferences file excluded from backups.
 */
class AndroidKeystoreSecretStore(
    context: Context,
) : SecretStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(name: SecretName): String? {
        val stored = prefs.getString(name.name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    override fun write(
        name: SecretName,
        value: String,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name.name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun delete(name: SecretName) {
        prefs.edit().remove(name.name).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "foursight-secrets"
        const val PREFS = "foursight_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
