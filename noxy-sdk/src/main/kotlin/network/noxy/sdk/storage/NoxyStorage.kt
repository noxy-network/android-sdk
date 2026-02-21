package network.noxy.sdk.storage

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for device data and private keys.
 * Uses Android EncryptedSharedPreferences backed by AndroidKeystore.
 */
class NoxyStorage(
    private val context: Context,
    private val storageName: String = "network.noxy.sdk"
) {
    private val sharedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            storageName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(key: String, data: ByteArray) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        sharedPrefs.edit().putString(key, encoded).apply()
    }

    fun load(key: String): ByteArray? {
        val encoded = sharedPrefs.getString(key, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(key: String) {
        sharedPrefs.edit().remove(key).apply()
    }

    fun loadAll(prefix: String): List<ByteArray> {
        return sharedPrefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { load(it) }
    }
}
