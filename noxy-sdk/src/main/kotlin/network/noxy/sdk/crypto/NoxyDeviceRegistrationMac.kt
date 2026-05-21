package network.noxy.sdk.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Canonical registration HMAC — must match relay `device_registration_mac_message_utf8`.
 */
object NoxyDeviceRegistrationMac {
    private const val PREFIX = "NOXY_DEVICE_REGISTER_V1"
    private const val SEP = '\u001f'

    fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { b -> "%02x".format(b) }
    }

    fun sign(
        secret: String,
        appId: String,
        identityTypeWire: String,
        logicalIdentityId: String,
        publicKey: ByteArray,
        pqPublicKey: ByteArray,
        deviceType: String,
    ): ByteArray {
        val msg =
            "$PREFIX$SEP$appId$SEP$identityTypeWire$SEP$logicalIdentityId$SEP${sha256Hex(publicKey)}$SEP${sha256Hex(pqPublicKey)}$SEP$deviceType"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
    }
}
