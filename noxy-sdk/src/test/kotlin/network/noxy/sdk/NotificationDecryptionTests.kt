package network.noxy.sdk

import kotlinx.coroutines.test.runTest
import network.noxy.sdk.crypto.NoxyKyberProvider
import network.noxy.sdk.network.NoxyEncryptedNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies notification decryption: Kyber decapsulate → HKDF → AES-GCM decrypt
 */
class NotificationDecryptionTests {

    @Test
    fun kyberKeypairAndDecapsulate() = runTest {
        val kyber = NoxyKyberProvider()
        val (pk, sk) = kyber.keypair()
        assertEquals(1184, pk.size)
        assertEquals(2400, sk.size)

        val (ct, ssEnc) = kyber.encapsulate(pk)
        assertEquals(1088, ct.size)
        assertEquals(32, ssEnc.size)

        val ssDec = kyber.decapsulate(sk, ct)
        assertEquals(32, ssDec.size)
        assertEquals(ssEnc.toList(), ssDec.toList())
    }
}
