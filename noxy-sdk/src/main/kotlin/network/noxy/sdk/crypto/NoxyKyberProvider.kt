package network.noxy.sdk.crypto

import network.noxy.sdk.NoxyError
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.security.SecureRandom

/**
 * Post-quantum keypair provider (ML-KEM-768 / Kyber768).
 * PK=1184, SK=2400, CT=1088, SS=32 bytes.
 */
class NoxyKyberProvider {

    companion object {
        private const val PK_BYTES = 1184
        private const val SK_BYTES = 2400
        private const val CT_BYTES = 1088
        private const val SS_BYTES = 32

        init {
            if (java.security.Security.getProvider(BouncyCastlePQCProvider.PROVIDER_NAME) == null) {
                java.security.Security.addProvider(BouncyCastlePQCProvider())
            }
        }
    }

    private val params = MLKEMParameters.ml_kem_768
    private val random = SecureRandom()

    /**
     * Generate a post-quantum keypair (ML-KEM-768)
     */
    fun keypair(): Pair<ByteArray, ByteArray> {
        val kpg = MLKEMKeyPairGenerator()
        kpg.init(org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters(random, params))
        val kp = kpg.generateKeyPair()
        val pubKey = kp.public as MLKEMPublicKeyParameters
        val privKey = kp.private as MLKEMPrivateKeyParameters
        return (pubKey.encoded to privKey.encoded)
    }

    /**
     * Decapsulate: recover shared secret from ciphertext using secret key
     */
    fun decapsulate(secretKey: ByteArray, ciphertext: ByteArray): ByteArray {
        if (secretKey.size != SK_BYTES || ciphertext.size != CT_BYTES) {
            throw NoxyError.General("Invalid Kyber key/ciphertext size")
        }
        val privateKey = MLKEMPrivateKeyParameters(params, secretKey)
        val extractor = MLKEMExtractor(privateKey)
        return extractor.extractSecret(ciphertext)
    }

    /**
     * Encapsulate: generate ciphertext and shared secret from public key (for testing)
     */
    internal fun encapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        if (publicKey.size != PK_BYTES) {
            throw NoxyError.General("Invalid Kyber public key size")
        }
        val pubKey = MLKEMPublicKeyParameters(params, publicKey)
        val generator = MLKEMGenerator(random)
        val encapsulated = generator.generateEncapsulated(pubKey)
        return (encapsulated.encapsulation to encapsulated.secret)
    }
}
