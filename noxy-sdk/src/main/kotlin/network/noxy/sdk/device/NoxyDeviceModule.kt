package network.noxy.sdk.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.noxy.sdk.crypto.NoxyDeviceRegistrationMac
import network.noxy.sdk.crypto.NoxyKyberProvider
import network.noxy.sdk.identity.NoxyIdentity
import network.noxy.sdk.identity.NoxyRelayIdentityType
import network.noxy.sdk.identity.logicalIdentityIdOf
import network.noxy.sdk.identity.relayIdentityTypeOf
import network.noxy.sdk.storage.NoxyStorage
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import org.json.JSONArray
import org.json.JSONObject

private const val DEVICE_VERSION = "noxy-device/v1"

/**
 * Device management: generate keys, register, load, revoke, rotate
 */
class NoxyDeviceModule(
    private val storage: NoxyStorage,
    private val kyber: NoxyKyberProvider = NoxyKyberProvider()
) {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Volatile
    private var currentDevice: NoxyDevice? = null

    val publicKey: ByteArray? get() = currentDevice?.publicKey
    val pqPublicKey: ByteArray? get() = currentDevice?.pqPublicKey
    val isRevoked: Boolean? get() = currentDevice?.isRevoked
    val device: NoxyDevice? get() = currentDevice

    private fun storageKey(device: NoxyDevice) = "${device.appId}_${device.identityId}"
    private fun devicesKey(identityId: String) = "devices_$identityId"

    /**
     * Build hash for identity signature (keccak256 for relay verification).
     */
    fun buildIdentitySignatureHash(device: NoxyDevice): ByteArray {
        val payload = DEVICE_VERSION.toByteArray(Charsets.UTF_8) +
            device.appId.toByteArray(Charsets.UTF_8) +
            device.identityId.toByteArray(Charsets.UTF_8) +
            device.publicKey +
            device.pqPublicKey +
            device.issuedAt.toBigEndianBytes()
        return keccak256(payload)
    }

    private fun generateKeys(): Pair<Pair<ByteArray, ByteArray>, Pair<ByteArray, ByteArray>> {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(java.security.SecureRandom()))
        val kp: AsymmetricCipherKeyPair = kpg.generateKeyPair()
        val edPriv = (kp.private as Ed25519PrivateKeyParameters).encoded
        val edPub = (kp.public as Ed25519PublicKeyParameters).encoded

        val (pqPub, pqPriv) = kyber.keypair()
        return ((edPub to edPriv) to (pqPub to pqPriv))
    }

    /**
     * Load device for identity
     */
    suspend fun load(identityId: String, appId: String? = null): NoxyDevice? = withContext(Dispatchers.IO) {
        val key = devicesKey(identityId)
        val data = storage.load(key) ?: return@withContext null
        val devicesArray = JSONArray(String(data, Charsets.UTF_8))
        for (i in 0 until devicesArray.length()) {
            val obj = devicesArray.getJSONObject(i)
            if (obj.optBoolean("isRevoked", false)) continue
            if (appId != null && obj.optString("appId") != appId) continue
            val device = obj.toDevice()
            currentDevice = device
            return@withContext device
        }
        currentDevice = null
        null
    }

    /**
     * Register new device
     */
    suspend fun register(
        appId: String,
        identity: NoxyIdentity,
        appSigningSecret: String,
    ): NoxyDevice = withContext(Dispatchers.IO) {
        val (edKeys, pqKeys) = generateKeys()
        val (edPub, edPriv) = edKeys
        val (pqPub, pqPriv) = pqKeys
        val issuedAt = System.currentTimeMillis()

        val logicalId = logicalIdentityIdOf(identity)
        val relayType = relayIdentityTypeOf(identity)

        var device = NoxyDevice(
            identityId = logicalId,
            relayIdentityType = relayType,
            appId = appId,
            isRevoked = false,
            issuedAt = issuedAt,
            publicKey = edPub,
            pqPublicKey = pqPub,
            identitySignature = null
        )

        val mac = NoxyDeviceRegistrationMac.sign(
            secret = appSigningSecret,
            appId = appId,
            identityTypeWire = relayType.toStoredKey(),
            logicalIdentityId = logicalId,
            publicKey = edPub,
            pqPublicKey = pqPub,
            deviceType = "android",
        )
        device = device.copy(identitySignature = mac)

        currentDevice = device
        persistDevice(device)
        persistPrivateKeys(NoxyDevicePrivateKeys(privateKey = edPriv, pqPrivateKey = pqPriv))
        device
    }

    /**
     * Load device private keys
     */
    suspend fun loadDevicePrivateKeys(): NoxyDevicePrivateKeys? = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext null
        val key = "keys_${storageKey(device)}"
        val data = storage.load(key) ?: return@withContext null
        val json = JSONObject(String(data, Charsets.UTF_8))
        NoxyDevicePrivateKeys(
            privateKey = json.getString("privateKey").decodeBase64(),
            pqPrivateKey = json.getString("pqPrivateKey").decodeBase64()
        )
    }

    /**
     * Revoke device locally
     */
    suspend fun revoke() = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext
        val updated = device.copy(isRevoked = true)
        currentDevice = updated
        persistDevice(updated)
    }

    /**
     * Rotate device keys
     */
    suspend fun rotateKeys() = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext
        val (edKeys, pqKeys) = generateKeys()
        val (edPub, edPriv) = edKeys
        val (pqPub, pqPriv) = pqKeys
        val updated = device.copy(
            publicKey = edPub,
            pqPublicKey = pqPub
        )
        currentDevice = updated
        persistDevice(updated)
        persistPrivateKeys(NoxyDevicePrivateKeys(privateKey = edPriv, pqPrivateKey = pqPriv))
    }

    /**
     * Get device signature for auth (signs domain + appId + identityId + timestamp + nonce)
     */
    suspend fun getDeviceSignature(): ByteArray? = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext null
        val keys = loadDevicePrivateKeys() ?: return@withContext null

        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val payload = DEVICE_VERSION.toByteArray(Charsets.UTF_8) +
            device.appId.toByteArray(Charsets.UTF_8) +
            device.identityId.toByteArray(Charsets.UTF_8) +
            System.currentTimeMillis().toBigEndianBytes() +
            nonce

        val privateKey = Ed25519PrivateKeyParameters(keys.privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        signer.generateSignature()
    }

    private fun persistDevice(device: NoxyDevice) {
        val key = devicesKey(device.identityId)
        val existing = storage.load(key)?.let { JSONArray(String(it, Charsets.UTF_8)) } ?: JSONArray()
        val newDevices = JSONArray()
        for (i in 0 until existing.length()) {
            val obj = existing.getJSONObject(i)
            if (obj.optString("appId") == device.appId && obj.optString("identityId") == device.identityId) continue
            newDevices.put(obj)
        }
        newDevices.put(device.toJson())
        storage.save(key, newDevices.toString().toByteArray(Charsets.UTF_8))
    }

    private fun persistPrivateKeys(keys: NoxyDevicePrivateKeys) {
        val device = currentDevice ?: return
        val key = "keys_${storageKey(device)}"
        val json = JSONObject().apply {
            put("privateKey", keys.privateKey.encodeBase64())
            put("pqPrivateKey", keys.pqPrivateKey.encodeBase64())
        }
        storage.save(key, json.toString().toByteArray(Charsets.UTF_8))
    }
}

private fun Long.toBigEndianBytes(): ByteArray =
    ByteArray(8) { i -> (this shr (56 - i * 8)).toByte() }

private fun ByteArray.encodeBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
private fun String.decodeBase64(): ByteArray = android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

private fun keccak256(input: ByteArray): ByteArray {
    val digest = org.bouncycastle.jcajce.provider.digest.Keccak.Digest256()
    digest.update(input)
    return digest.digest()
}

private fun NoxyRelayIdentityType.toStoredKey(): String = when (this) {
    NoxyRelayIdentityType.WALLET -> "wallet"
    NoxyRelayIdentityType.EMAIL -> "email"
    NoxyRelayIdentityType.PHONE -> "phone"
    NoxyRelayIdentityType.USER_ID -> "user_id"
}

private fun relayIdentityTypeFromStored(raw: String?): NoxyRelayIdentityType =
    when (raw?.lowercase()) {
        "email" -> NoxyRelayIdentityType.EMAIL
        "phone" -> NoxyRelayIdentityType.PHONE
        "user_id", "userid" -> NoxyRelayIdentityType.USER_ID
        else -> NoxyRelayIdentityType.WALLET
    }

private fun JSONObject.toDevice() = NoxyDevice(
    identityId = getString("identityId"),
    relayIdentityType = relayIdentityTypeFromStored(optString("relayIdentityType")),
    appId = getString("appId"),
    isRevoked = optBoolean("isRevoked"),
    issuedAt = getLong("issuedAt"),
    publicKey = getString("publicKey").decodeBase64(),
    pqPublicKey = getString("pqPublicKey").decodeBase64(),
    identitySignature = optString("identitySignature").takeIf { it.isNotEmpty() }?.decodeBase64()
)

private fun NoxyDevice.toJson() = JSONObject().apply {
    put("identityId", identityId)
    put("relayIdentityType", relayIdentityType.toStoredKey())
    put("appId", appId)
    put("isRevoked", isRevoked)
    put("issuedAt", issuedAt)
    put("publicKey", publicKey.encodeBase64())
    put("pqPublicKey", pqPublicKey.encodeBase64())
    put("identitySignature", identitySignature?.encodeBase64() ?: "")
}
