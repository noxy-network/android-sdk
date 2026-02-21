package network.noxy.sdk.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.noxy.sdk.NoxyError
import network.noxy.sdk.crypto.NoxyKyberProvider
import network.noxy.sdk.device.NoxyDeviceModule
import network.noxy.sdk.network.NoxyEncryptedNotification
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts encrypted notifications using Kyber decapsulation, HKDF key derivation, and AES-GCM.
 */
class NoxyNotificationModule(
    private val deviceModule: NoxyDeviceModule,
    private val kyber: NoxyKyberProvider = NoxyKyberProvider()
) {

    /**
     * Decrypt notification envelope to plain payload (JSON object as Map)
     */
    suspend fun decryptNotification(envelope: NoxyEncryptedNotification): Map<String, Any?>? = withContext(Dispatchers.IO) {
        val keys = deviceModule.loadDevicePrivateKeys()
            ?: throw NoxyError.General("Device cannot decrypt notification")

        val sharedSecret = try {
            kyber.decapsulate(keys.pqPrivateKey, envelope.kyberCt)
        } catch (e: Exception) {
            throw NoxyError.General("Kyber decapsulation failed: ${e.message}")
        }

        val salts = listOf(ByteArray(32), ByteArray(0))
        var plaintext: ByteArray? = null

        for (salt in salts) {
            val aesKey = hkdfSha256(sharedSecret, salt, ByteArray(0), 32)
            try {
                plaintext = aesGcmDecrypt(
                    envelope.ciphertextWithoutTag,
                    envelope.tag,
                    envelope.nonce,
                    aesKey
                )
                break
            } catch (_: Exception) {
                continue
            }
        }

        plaintext ?: throw NoxyError.General("AES-GCM decrypt failed: authenticationFailure")

        try {
            JSONObject(String(plaintext, Charsets.UTF_8)).toMap()
        } catch (_: Exception) {
            null
        }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        hmac.init(javax.crypto.spec.SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        hmac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        var okm = ByteArray(0)
        var prev = ByteArray(0)
        var i = 1
        while (okm.size < length) {
            hmac.reset()
            hmac.update(prev)
            hmac.update(info)
            hmac.update(i.toByte())
            prev = hmac.doFinal()
            okm = okm + prev
            i++
        }
        return okm.copyOf(length)
    }

    private fun aesGcmDecrypt(ciphertext: ByteArray, tag: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext + tag)
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        map[key] = when (val v = get(key)) {
            is JSONObject -> (v as JSONObject).toMap()
            is org.json.JSONArray -> v.toList()
            else -> v
        }
    }
    return map
}

private fun org.json.JSONArray.toList(): List<Any?> =
    (0 until length()).map { get(it) }
