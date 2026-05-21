package network.noxy.sdk.identity

import noxy.device.IdentityType

/**
 * EVM-style wallet address (0x...)
 */
typealias WalletAddress = String

/**
 * Relay-facing identity category (`wallet` | `email` | `phone` | `user_id`).
 */
enum class NoxyRelayIdentityType {
    WALLET,
    EMAIL,
    PHONE,
    USER_ID
}

/**
 * Wallet implementation kind (EOA vs SCW). Only applies to wallet relay identities.
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
 * Union of supported identities for the relay (wallet plus logical ids).
 */
sealed class NoxyIdentity {

    data class Eoa(val identity: NoxyEoaWalletIdentity) : NoxyIdentity()

    data class Scw(val identity: NoxyScwWalletIdentity) : NoxyIdentity()

    data class Email(val email: String) : NoxyIdentity()

    data class Phone(val phone: String) : NoxyIdentity()

    data class UserId(val userId: String) : NoxyIdentity()
}

/** Only defined when [relayIdentityTypeOf] is [NoxyRelayIdentityType.WALLET]. */
val NoxyIdentity.walletAddress: WalletAddress
    get() = when (this) {
        is NoxyIdentity.Eoa -> identity.address
        is NoxyIdentity.Scw -> identity.address
        else -> error("walletAddress is only available when identity is Eoa or Scw")
    }

@Deprecated("Use walletAddress for wallet identities", ReplaceWith("walletAddress"))
val NoxyIdentity.address: WalletAddress
    get() = walletAddress

fun relayIdentityTypeOf(identity: NoxyIdentity): NoxyRelayIdentityType = when (identity) {
    is NoxyIdentity.Eoa, is NoxyIdentity.Scw -> NoxyRelayIdentityType.WALLET
    is NoxyIdentity.Email -> NoxyRelayIdentityType.EMAIL
    is NoxyIdentity.Phone -> NoxyRelayIdentityType.PHONE
    is NoxyIdentity.UserId -> NoxyRelayIdentityType.USER_ID
}

fun logicalIdentityIdOf(identity: NoxyIdentity): String = when (identity) {
    is NoxyIdentity.Eoa -> identity.identity.address
    is NoxyIdentity.Scw -> identity.identity.address
    is NoxyIdentity.Email -> identity.email
    is NoxyIdentity.Phone -> identity.phone
    is NoxyIdentity.UserId -> identity.userId
}

fun NoxyRelayIdentityType.toProtoIdentityType(): IdentityType = when (this) {
    NoxyRelayIdentityType.WALLET -> IdentityType.IDENTITY_TYPE_WALLET
    NoxyRelayIdentityType.EMAIL -> IdentityType.IDENTITY_TYPE_EMAIL
    NoxyRelayIdentityType.PHONE -> IdentityType.IDENTITY_TYPE_PHONE
    NoxyRelayIdentityType.USER_ID -> IdentityType.IDENTITY_TYPE_USER_ID
}
