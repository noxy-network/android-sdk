package network.noxy.example

import android.content.Context
import android.content.SharedPreferences
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Demo wallet for example app. Uses Ed25519 keypair stored locally.
 * Replace with real wallet integration (e.g. Web3, WalletConnect) for production.
 */
class DemoWallet(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("noxy_demo_wallet", Context.MODE_PRIVATE)
    }

    val address: String
        get() = prefs.getString(KEY_ADDRESS, null) ?: run {
            val addr = "0x" + ByteArray(20) { 0 }.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_ADDRESS, addr).apply()
            addr
        }

    private val privateKey: ByteArray
        get() = prefs.getString(KEY_PRIVATE, null)?.let { decodeHex(it) } ?: run {
            val kpg = Ed25519KeyPairGenerator()
            kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val kp = kpg.generateKeyPair()
            val priv = (kp.private as Ed25519PrivateKeyParameters).encoded
            prefs.edit()
                .putString(KEY_PRIVATE, encodeHex(priv))
                .putString(KEY_ADDRESS, "0x${encodeHex(priv.take(20).toByteArray()).take(40)}")
                .apply()
            priv
        }

    fun sign(data: ByteArray): ByteArray {
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private companion object {
        const val KEY_ADDRESS = "demo_address"
        const val KEY_PRIVATE = "demo_private_key"

        fun encodeHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun decodeHex(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
