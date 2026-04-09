package network.noxy.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.noxy.sdk.decision.NoxyDecisionCryptoModule
import network.noxy.sdk.device.NoxyDeviceModule
import network.noxy.sdk.identity.NoxyIdentity
import network.noxy.sdk.identity.WalletAddress
import network.noxy.sdk.network.NoxyDecisionChoice
import network.noxy.sdk.network.NoxyEncryptedDecision
import network.noxy.sdk.network.NoxyNetworkModule
import network.noxy.sdk.network.NoxyNetworkOptions
import network.noxy.sdk.storage.NoxyStorage

/**
 * Main Noxy client for the Noxy Decision Layer: wallet identity, relay connection,
 * encrypted decision requests, and outcomes (approve/reject).
 *
 * When [NoxyNetworkOptions.fcmToken] or [setFcmToken] is set: online + offline (FCM wake-up).
 * When not set: online only.
 */
class NoxyClient(
    private val identity: NoxyIdentity,
    private val networkOptions: NoxyNetworkOptions,
    private val storage: NoxyStorage
) {
    private val deviceModule = NoxyDeviceModule(storage)
    private val networkModule = NoxyNetworkModule(networkOptions)
    private val decisionCryptoModule = NoxyDecisionCryptoModule(deviceModule)
    private val ackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var fcmToken: String? = null

    @Volatile
    private var decisionHandler: (suspend (String?, Map<String, Any?>) -> Unit)? = null

    private val effectiveFcmToken: String?
        get() = fcmToken?.takeIf { it.isNotEmpty() } ?: networkOptions.fcmToken

    val address: WalletAddress get() = identity.address
    val isDeviceActive: Boolean get() = deviceModule.isRevoked != true
    val isRelayConnected: Boolean get() = networkModule.isConnected
    val isNetworkReady: Boolean get() = networkModule.isReady

    /**
     * Register FCM token for wake-up pushes when app is backgrounded.
     */
    fun setFcmToken(token: String?) {
        fcmToken = token
    }

    /**
     * Initialize: load or create device, connect to network, authenticate.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        networkModule.connect()

        var device = deviceModule.load(identity.address, networkOptions.appId)
        if (device == null) {
            device = deviceModule.register(
                appId = networkOptions.appId,
                identityId = identity.address,
                identitySigner = identity.signer
            )
        }

        val dev = device ?: throw NoxyError.InitializationFailed("No device")

        val requiresRegistration = networkModule.authenticateDevice(dev)

        if (requiresRegistration) {
            val sig = dev.identitySignature
                ?: throw NoxyError.InitializationFailed("Device has no identity signature for relay registration")
            networkModule.announceDevice(
                devicePubkeys = dev.publicKey to dev.pqPublicKey,
                walletAddress = dev.identityId,
                signature = sig,
                fcmToken = effectiveFcmToken
            )
        }
    }

    /**
     * Revoke device locally and on relay
     */
    suspend fun revokeDevice() = withContext(Dispatchers.IO) {
        val sig = deviceModule.getDeviceSignature()
            ?: throw NoxyError.General("Unable to revoke device")
        deviceModule.revoke()
        networkModule.revokeDevice(walletAddress = address, signature = sig)
    }

    /**
     * Rotate device keys locally and on relay
     */
    suspend fun rotateKeys() = withContext(Dispatchers.IO) {
        val sig = deviceModule.getDeviceSignature()
            ?: throw NoxyError.General("Unable to rotate device keys")
        deviceModule.rotateKeys()
        val pk = deviceModule.publicKey ?: throw NoxyError.General("Unable to rotate device keys")
        val pqPk = deviceModule.pqPublicKey ?: throw NoxyError.General("Unable to rotate device keys")
        networkModule.rotateDeviceKeys(
            newPubkeys = pk to pqPk,
            walletAddress = address,
            signature = sig
        )
    }

    /**
     * Subscribe to encrypted decision requests from the relay.
     * [handler] receives the relay stream `message_id` and decrypted JSON (use the id for outcomes when JSON has no `decision_id`).
     */
    suspend fun on(handler: suspend (String?, Map<String, Any?>) -> Unit) = withContext(Dispatchers.IO) {
        decisionHandler = handler
        deviceModule.loadDevicePrivateKeys()
        networkModule.subscribeToDecisions(
            fcmToken = effectiveFcmToken,
            handler = { envelope, relayMessageId ->
                deliverDecision(envelope, relayMessageId, handler)
            }
        )
    }

    private suspend fun deliverDecision(
        envelope: NoxyEncryptedDecision,
        relayMessageId: String?,
        notifyUser: suspend (String?, Map<String, Any?>) -> Unit
    ) {
        val decrypted = try {
            decisionCryptoModule.decryptDecision(envelope) ?: return
        } catch (_: Exception) {
            return
        }
        // Deliver first; do not await sendDecisionAck here (same bidi stream deadlock as iOS).
        notifyUser(relayMessageId, decrypted)
        val ackId = deliveryAckDecisionId(decrypted, relayMessageId) ?: return
        ackScope.launch {
            try {
                networkModule.sendDecisionAck(ackId)
            } catch (_: Exception) {
            }
        }
    }

    private fun deliveryAckDecisionId(decision: Map<String, Any?>, relayMessageId: String?): String? {
        if (!relayMessageId.isNullOrEmpty()) return relayMessageId
        (decision["decision_id"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        (decision["decisionId"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        (decision["message_id"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    /**
     * Send the user's choice to the relay: **Approve** or **Reject** ([DecisionOutcome] in proto).
     */
    suspend fun sendDecisionOutcome(
        decisionId: String,
        outcome: NoxyDecisionChoice,
        receivedAt: Long? = null
    ) = withContext(Dispatchers.IO) {
        networkModule.sendDecisionOutcome(decisionId, outcome, receivedAt)
    }

    /**
     * Delivery receipt: tells the relay the device **received** the decision request ([DecisionAck] in proto).
     * Not the user's approve/reject — use [sendDecisionOutcome]. Normally sent automatically after decrypt.
     */
    suspend fun sendDecisionAck(decisionId: String, receivedAt: Long? = null) = withContext(Dispatchers.IO) {
        networkModule.sendDecisionAck(decisionId, receivedAt)
    }

    companion object {
        /**
         * Resolves the decision id to use with [sendDecisionOutcome] from decrypted JSON and the relay stream id.
         * Precedence: `decision_id`, `decisionId`, `message_id` (JSON), then [relayMessageId].
         */
        @JvmStatic
        fun resolveDecisionId(decision: Map<String, Any?>, relayMessageId: String?): String? {
            (decision["decision_id"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
            (decision["decisionId"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
            (decision["message_id"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
            return relayMessageId?.takeIf { it.isNotEmpty() }
        }

        @JvmStatic
        fun isNoxyWakeUp(data: Map<String, String>?): Boolean {
            if (data == null) return false
            return data["noxy"] == "wake"
        }
    }

    /**
     * Handle FCM wake-up: reconnect to relay and resume the decision stream.
     */
    suspend fun handleWakeUpNotification(data: Map<String, String>? = null): NoxyWakeUpResult =
        withContext(Dispatchers.IO) {
            if (data != null && !isNoxyWakeUp(data)) return@withContext NoxyWakeUpResult.NoData
            performWakeUpFetch()
        }

    private suspend fun performWakeUpFetch(): NoxyWakeUpResult {
        val handler = decisionHandler ?: return NoxyWakeUpResult.NoData

        if (networkModule.isConnected) {
            networkModule.disconnectForReconnect()
        }
        val device = deviceModule.load(identity.address, networkOptions.appId)
            ?: return NoxyWakeUpResult.NoData
        if (device.isRevoked == true) return NoxyWakeUpResult.NoData

        deviceModule.loadDevicePrivateKeys()

        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                networkModule.connect()
                if (!networkModule.isConnected) throw Exception("Connection not established")
                networkModule.authenticateDevice(device)
                networkModule.subscribeToDecisions(
                    fcmToken = effectiveFcmToken,
                    handler = { envelope, relayMessageId ->
                        deliverDecision(envelope, relayMessageId, handler)
                    }
                )
                kotlinx.coroutines.delay(20_000)
                return NoxyWakeUpResult.NewData
            } catch (_: Exception) {
                if (attempt < maxAttempts) {
                    networkModule.disconnectForReconnect()
                    kotlinx.coroutines.delay(500)
                } else {
                    return NoxyWakeUpResult.Failed
                }
            }
        }
        return NoxyWakeUpResult.Failed
    }

    /**
     * Disconnect from relay
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        networkModule.disconnect()
    }
}

/** Result for FCM wake-up fetch. Map to result code when reporting to Firebase. */
enum class NoxyWakeUpResult {
    NewData,
    NoData,
    Failed
}
