package network.noxy.sdk.device

import network.noxy.sdk.identity.WalletAddress

/**
 * Device descriptor and public keys
 */
data class NoxyDevice(
    val identityId: WalletAddress,
    val appId: String,
    var isRevoked: Boolean,
    val issuedAt: Long,
    val publicKey: ByteArray,
    val pqPublicKey: ByteArray,
    var identitySignature: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NoxyDevice
        return identityId == other.identityId &&
            appId == other.appId &&
            isRevoked == other.isRevoked &&
            issuedAt == other.issuedAt &&
            publicKey.contentEquals(other.publicKey) &&
            pqPublicKey.contentEquals(other.pqPublicKey) &&
            (identitySignature?.contentEquals(other.identitySignature) ?: (other.identitySignature == null))
    }

    override fun hashCode(): Int {
        var result = identityId.hashCode()
        result = 31 * result + appId.hashCode()
        result = 31 * result + isRevoked.hashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + pqPublicKey.contentHashCode()
        result = 31 * result + (identitySignature?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Device private keys (kept in secure storage)
 */
data class NoxyDevicePrivateKeys(
    val privateKey: ByteArray,
    val pqPrivateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NoxyDevicePrivateKeys
        return privateKey.contentEquals(other.privateKey) &&
            pqPrivateKey.contentEquals(other.pqPrivateKey)
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + pqPrivateKey.contentHashCode()
        return result
    }
}
