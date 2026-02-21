package network.noxy.sdk.identity

/**
 * EVM-style wallet address (0x...)
 */
typealias WalletAddress = String

/**
 * Supported identity types
 */
enum class NoxyIdentityType {
    EOA,
    SCW
}

/**
 * Cryptographic key types for identity
 */
enum class NoxyIdentityCryptoKeyType {
    ED25519,
    ED448,
    SR25519,
    SECP256K1,
    SECP256K1_SCHNORR
}

/**
 * Signature result from wallet signer
 */
data class Signature(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Signature
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * Signer function: signs arbitrary data and returns signature bytes
 */
typealias SignerClosure = suspend (ByteArray) -> Signature

/**
 * EOA (Externally Owned Account) wallet identity
 */
data class NoxyEoaWalletIdentity(
    val chainId: String? = null,
    val address: WalletAddress,
    val publicKey: ByteArray? = null,
    val publicKeyType: NoxyIdentityCryptoKeyType? = null,
    val signer: SignerClosure
) {
    val type: NoxyIdentityType = NoxyIdentityType.EOA

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NoxyEoaWalletIdentity
        return chainId == other.chainId &&
            address == other.address &&
            (publicKey?.contentEquals(other.publicKey) ?: (other.publicKey == null)) &&
            publicKeyType == other.publicKeyType
    }

    override fun hashCode(): Int {
        var result = chainId?.hashCode() ?: 0
        result = 31 * result + address.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + (publicKeyType?.hashCode() ?: 0)
        return result
    }
}

/**
 * Smart Contract Wallet identity
 */
data class NoxyScwWalletIdentity(
    val chainId: String? = null,
    val address: WalletAddress,
    val publicKey: ByteArray? = null,
    val publicKeyType: NoxyIdentityCryptoKeyType? = null,
    val signer: SignerClosure
) {
    val type: NoxyIdentityType = NoxyIdentityType.SCW

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NoxyScwWalletIdentity
        return chainId == other.chainId &&
            address == other.address &&
            (publicKey?.contentEquals(other.publicKey) ?: (other.publicKey == null)) &&
            publicKeyType == other.publicKeyType
    }

    override fun hashCode(): Int {
        var result = chainId?.hashCode() ?: 0
        result = 31 * result + address.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + (publicKeyType?.hashCode() ?: 0)
        return result
    }
}

/**
 * Union of supported identity types
 */
sealed class NoxyIdentity {
    abstract val address: WalletAddress
    abstract val signer: SignerClosure
    abstract val type: NoxyIdentityType

    data class Eoa(val identity: NoxyEoaWalletIdentity) : NoxyIdentity() {
        override val address: WalletAddress get() = identity.address
        override val signer: SignerClosure get() = identity.signer
        override val type: NoxyIdentityType get() = NoxyIdentityType.EOA
    }

    data class Scw(val identity: NoxyScwWalletIdentity) : NoxyIdentity() {
        override val address: WalletAddress get() = identity.address
        override val signer: SignerClosure get() = identity.signer
        override val type: NoxyIdentityType get() = NoxyIdentityType.SCW
    }
}
