package network.noxy.sdk.network

import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.noxy.sdk.NoxyError
import network.noxy.sdk.device.NoxyDevice
import network.noxy.sdk.identity.WalletAddress
import noxy.device.DecisionAck
import noxy.device.DecisionOutcome
import noxy.device.DecisionOutcomeValue
import noxy.device.DeviceRequest
import noxy.device.DeviceResponse
import noxy.device.DeviceServiceGrpc
import noxy.device.SubscribeDecisions
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Network module: gRPC-based relay communication via bidirectional HandleMessage stream.
 */
class NoxyNetworkModule(
    private val options: NoxyNetworkOptions
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var channel: ManagedChannel? = null

    @Volatile
    private var requestStream: StreamObserver<DeviceRequest>? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var networkDeviceId: String? = null

    private val pendingRequests = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<DeviceResponse>>()
    private val pendingMutex = Mutex()
    private val connectionMutex = Mutex()

    @Volatile
    private var decisionHandler: (suspend (NoxyEncryptedDecision, String?) -> Unit)? = null

    val isConnected: Boolean get() = channel != null
    val isReady: Boolean get() = isConnected && sessionId != null && networkDeviceId != null
    val currentSessionId: String? get() = sessionId
    val currentDeviceId: String? get() = networkDeviceId

    private fun parseRelayURL(urlString: String): Pair<String, Int> {
        val url = URL(urlString)
        val host = url.host ?: throw NoxyError.General("Invalid relay URL: $urlString")
        if (url.protocol.lowercase() != "https") {
            throw NoxyError.General("Relay URL must use HTTPS")
        }
        val port = url.port.takeIf { it > 0 } ?: 443
        return host to port
    }

    /** Connect to relay. Waits for any in-progress disconnect to finish (avoids race conditions). */
    suspend fun connect() = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            val (host, port) = parseRelayURL(options.relayUrl)
            val builder = OkHttpChannelBuilder.forAddress(host, port)
                .useTransportSecurity()

            channel = builder.build()
            val stub = DeviceServiceGrpc.newStub(channel).withWaitForReady()

            requestStream = stub.handleMessage(object : StreamObserver<DeviceResponse> {
                override fun onNext(value: DeviceResponse) {
                    scope.launch {
                        handleResponse(value)
                    }
                }

                override fun onError(t: Throwable) {
                    scope.launch {
                        pendingMutex.withLock {
                            pendingRequests.values.forEach { it.completeExceptionally(t) }
                            pendingRequests.clear()
                        }
                    }
                }

                override fun onCompleted() {}
            })
        }
    }

    private suspend fun handleResponse(response: DeviceResponse) {
        when (response.payloadCase) {
            DeviceResponse.PayloadCase.DECISION_EVENT -> {
                val ev = response.decisionEvent
                val relayMessageId = if (response.hasMessageId()) response.messageId else null
                decisionHandler?.invoke(
                    NoxyEncryptedDecision(
                        kyberCt = ev.kyberCt.toByteArray(),
                        nonce = ev.nonce.toByteArray(),
                        ciphertext = ev.ciphertext.toByteArray()
                    ),
                    relayMessageId
                )
            }
            DeviceResponse.PayloadCase.AUTHENTICATE -> {
                val auth = response.authenticate
                if (auth.hasDeviceId()) networkDeviceId = auth.deviceId
                if (auth.hasSessionId()) sessionId = auth.sessionId
                resumePending(response.requestId, response)
            }
            DeviceResponse.PayloadCase.REGISTER_DEVICE -> {
                val reg = response.registerDevice
                networkDeviceId = reg.deviceId
                sessionId = reg.sessionId
                resumePending(response.requestId, response)
            }
            DeviceResponse.PayloadCase.SUBSCRIBE_DECISIONS,
            DeviceResponse.PayloadCase.REVOKE_DEVICE,
            DeviceResponse.PayloadCase.ROTATE_DEVICE_KEYS,
            DeviceResponse.PayloadCase.DECISION_OUTCOME,
            DeviceResponse.PayloadCase.DECISION_ACK -> {
                resumePending(response.requestId, response)
            }
            DeviceResponse.PayloadCase.DECISION_ROUTED -> {
                // ignore
            }
            DeviceResponse.PayloadCase.ERROR -> {
                val err = response.error
                if (response.requestId.isNotEmpty()) {
                    resumePending(response.requestId, NoxyError.General("Relay error: ${err.code} ${err.message}"))
                }
            }
            else -> {}
        }
    }

    private suspend fun resumePending(requestId: String, response: DeviceResponse) {
        pendingMutex.withLock {
            pendingRequests.remove(requestId)?.complete(response)
        }
    }

    private suspend fun resumePending(requestId: String, error: Throwable) {
        pendingMutex.withLock {
            pendingRequests.remove(requestId)?.completeExceptionally(error)
        }
    }

    private suspend fun sendAndWait(request: DeviceRequest): DeviceResponse = withContext(Dispatchers.IO) {
        val stream = requestStream ?: throw NoxyError.General("Not connected")
        val requestId = if (request.requestId.isEmpty()) UUID.randomUUID().toString() else request.requestId

        val deferred = kotlinx.coroutines.CompletableDeferred<DeviceResponse>()
        pendingMutex.withLock {
            pendingRequests[requestId] = deferred
        }

        val req = request.toBuilder()
            .setRequestId(requestId)
            .setAppId(options.appId)
            .setTimestamp(if (request.timestamp == 0L) System.currentTimeMillis() else request.timestamp)
            .setNonce(ByteString.copyFrom(ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }))
            .build()

        stream.onNext(req)
        deferred.await()
    }

    suspend fun disconnect() = connectionMutex.withLock {
        withContext(Dispatchers.IO) { performDisconnect(timeoutSeconds = 5) }
    }

    /** Quick disconnect for wake-up reconnect; short timeout to re-establish connection ASAP. */
    suspend fun disconnectForReconnect() = connectionMutex.withLock {
        withContext(Dispatchers.IO) { performDisconnect(timeoutSeconds = 1) }
    }

    private suspend fun performDisconnect(timeoutSeconds: Long) = withContext(Dispatchers.IO) {
        pendingMutex.withLock {
            pendingRequests.values.forEach { it.completeExceptionally(NoxyError.General("Disconnected")) }
            pendingRequests.clear()
        }
        requestStream?.onCompleted()
        requestStream = null
        channel?.shutdown()?.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
        channel = null
        sessionId = null
        networkDeviceId = null
        decisionHandler = null
    }

    /**
     * Authenticate device with relay.
     * Returns true if the relay requires registration (device unknown to relay).
     */
    suspend fun authenticateDevice(device: NoxyDevice): Boolean = withContext(Dispatchers.IO) {
        val req = DeviceRequest.newBuilder()
            .setAuthenticate(
                noxy.device.Authenticate.newBuilder()
                    .setDevicePubkeys(
                        noxy.device.DevicePublicKeys.newBuilder()
                            .setPublicKey(ByteString.copyFrom(device.publicKey))
                            .setPqPublicKey(ByteString.copyFrom(device.pqPublicKey))
                    )
            )
            .build()

        val resp = sendAndWait(req)
        when (resp.payloadCase) {
            DeviceResponse.PayloadCase.AUTHENTICATE -> {
                val auth = resp.authenticate
                if (auth.requiresRegistration) return@withContext true
                if (auth.hasDeviceId()) networkDeviceId = auth.deviceId
                if (auth.hasSessionId()) sessionId = auth.sessionId
                false
            }
            DeviceResponse.PayloadCase.ERROR -> throw NoxyError.General("Authenticate failed: ${resp.error.message}")
            else -> throw NoxyError.General("Unexpected authenticate response")
        }
    }

    /**
     * Announce (register) device with relay
     */
    suspend fun announceDevice(
        devicePubkeys: Pair<ByteArray, ByteArray>,
        walletAddress: WalletAddress,
        signature: ByteArray,
        fcmToken: String? = null
    ) = withContext(Dispatchers.IO) {
        val regBuilder = noxy.device.RegisterDevice.newBuilder()
            .setDevicePubkeys(
                noxy.device.DevicePublicKeys.newBuilder()
                    .setPublicKey(ByteString.copyFrom(devicePubkeys.first))
                    .setPqPublicKey(ByteString.copyFrom(devicePubkeys.second))
            )
            .setWalletAddress(walletAddress)
            .setSignature(ByteString.copyFrom(signature))
            .setType("android")
        if (!fcmToken.isNullOrEmpty()) regBuilder.setFcmToken(fcmToken)

        val req = DeviceRequest.newBuilder()
            .setRegisterDevice(regBuilder)
            .build()

        val resp = sendAndWait(req)
        when (resp.payloadCase) {
            DeviceResponse.PayloadCase.REGISTER_DEVICE -> {
                val reg = resp.registerDevice
                networkDeviceId = reg.deviceId
                sessionId = reg.sessionId
            }
            DeviceResponse.PayloadCase.ERROR -> throw NoxyError.General("Register failed: ${resp.error.message}")
            else -> throw NoxyError.General("Unexpected register response")
        }
    }

    /**
     * Revoke device on relay
     */
    suspend fun revokeDevice(walletAddress: WalletAddress, signature: ByteArray) = withContext(Dispatchers.IO) {
        val req = DeviceRequest.newBuilder()
            .setRevokeDevice(
                noxy.device.RevokeDevice.newBuilder()
                    .setWalletAddress(walletAddress)
                    .setSignature(ByteString.copyFrom(signature))
            )
            .build()
        sendAndWait(req)
    }

    /**
     * Rotate device keys on relay
     */
    suspend fun rotateDeviceKeys(
        newPubkeys: Pair<ByteArray, ByteArray>,
        walletAddress: WalletAddress,
        signature: ByteArray
    ) = withContext(Dispatchers.IO) {
        val req = DeviceRequest.newBuilder()
            .setRotateDeviceKeys(
                noxy.device.RotateDeviceKeys.newBuilder()
                    .setNewPubkeys(
                        noxy.device.DevicePublicKeys.newBuilder()
                            .setPublicKey(ByteString.copyFrom(newPubkeys.first))
                            .setPqPublicKey(ByteString.copyFrom(newPubkeys.second))
                    )
                    .setWalletAddress(walletAddress)
                    .setSignature(ByteString.copyFrom(signature))
            )
            .build()
        sendAndWait(req)
    }

    /**
     * Subscribe to encrypted decision events from the relay.
     */
    suspend fun subscribeToDecisions(
        handler: suspend (NoxyEncryptedDecision, String?) -> Unit,
        fcmToken: String? = null
    ) = withContext(Dispatchers.IO) {
        decisionHandler = handler

        val subBuilder = SubscribeDecisions.newBuilder().setSubscribe(true)
        if (!fcmToken.isNullOrEmpty()) subBuilder.setFcmToken(fcmToken)

        val reqBuilder = DeviceRequest.newBuilder()
            .setSubscribeDecisions(subBuilder)
        currentDeviceId?.let { reqBuilder.setDeviceId(it) }
        currentSessionId?.let { reqBuilder.setSessionId(it) }

        sendAndWait(reqBuilder.build())
    }

    /**
     * Sends [DecisionOutcome] (proto): user's **Approve** or **Reject** after they act in the UI.
     */
    suspend fun sendDecisionOutcome(
        decisionId: String,
        outcome: NoxyDecisionChoice,
        receivedAt: Long? = null
    ) = withContext(Dispatchers.IO) {
        val protoOutcome = when (outcome) {
            NoxyDecisionChoice.Approve -> DecisionOutcomeValue.APPROVE
            NoxyDecisionChoice.Reject -> DecisionOutcomeValue.REJECT
        }
        val b = DeviceRequest.newBuilder()
            .setDecisionOutcome(
                DecisionOutcome.newBuilder()
                    .setDecisionId(decisionId)
                    .setOutcome(protoOutcome)
                    .setReceivedAt(receivedAt ?: System.currentTimeMillis())
            )
        currentDeviceId?.let { b.setDeviceId(it) }
        currentSessionId?.let { b.setSessionId(it) }
        sendAndWait(b.build())
    }

    /**
     * Sends [DecisionAck] (proto): relay is notified the device **received** the decision request (decrypt ok).
     * For the user's Approve/Reject use [sendDecisionOutcome].
     */
    suspend fun sendDecisionAck(decisionId: String, receivedAt: Long? = null) = withContext(Dispatchers.IO) {
        val b = DeviceRequest.newBuilder()
            .setDecisionAck(
                DecisionAck.newBuilder()
                    .setDecisionId(decisionId)
                    .setReceivedAt(receivedAt ?: System.currentTimeMillis())
            )
        currentDeviceId?.let { b.setDeviceId(it) }
        currentSessionId?.let { b.setSessionId(it) }
        sendAndWait(b.build())
    }
}
