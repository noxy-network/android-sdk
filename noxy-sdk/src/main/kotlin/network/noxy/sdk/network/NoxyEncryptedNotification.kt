package network.noxy.sdk.network

/**
 * Encrypted notification envelope. Ciphertext = encrypted_data || tag (last 16 bytes are GCM auth tag).
 */
data class NoxyEncryptedNotification(
    val kyberCt: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray
) {
    /** Split ciphertext into encrypted part and tag (last 16 bytes) */
    val ciphertextWithoutTag: ByteArray get() = ciphertext.copyOf(ciphertext.size - 16)
    val tag: ByteArray get() = ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NoxyEncryptedNotification
        return kyberCt.contentEquals(other.kyberCt) &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = kyberCt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}
