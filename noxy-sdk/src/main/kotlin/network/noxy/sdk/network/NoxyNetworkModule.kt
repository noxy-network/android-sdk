package network.noxy.sdk.network

import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.noxy.sdk.NoxyError
import network.noxy.sdk.device.NoxyDevice
import network.noxy.sdk.identity.NoxyRelayIdentityType
import network.noxy.sdk.identity.toProtoIdentityType
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
import kotlin.math.min
import kotlin.math.pow

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
    private val transportMutex = Mutex()

    @Volatile
    private var decisionHandler: (suspend (NoxyEncryptedDecision, String?) -> Unit)? = null

    /** Runs after each new transport (initial connect and every reconnect). */
    private var sessionRestore: (suspend () -> Unit)? = null

    @Volatile
    private var userInitiatedDisconnect = false

    private var maintainSupervisor: Job? = null
    private var maintainJob: Job? = null
    private var firstConnectDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var streamEndedSignal: CompletableDeferred<Unit>? = null

    fun setSessionRestore(handler: (suspend () -> Unit)?) {
        sessionRestore = handler
    }

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

    /**
     * Connect to relay and start the reconnect loop until [disconnect].
     * Suspends until the first successful [sessionRestore] (or fails if [disconnect] happens while waiting).
     */
    suspend fun connect() {
        val waitFirst = connectionMutex.withLock {
            if (maintainJob?.isActive == true) return@withLock null
            userInitiatedDisconnect = false
            val d = CompletableDeferred<Unit>()
            firstConnectDeferred = d
            val sup = SupervisorJob()
            maintainSupervisor = sup
            val coroutineScope = CoroutineScope(sup + Dispatchers.Default)
            maintainJob = coroutineScope.launch {
                try {
                    reconnectLoop()
                } catch (_: CancellationException) {
                    // expected when disconnect() cancels the supervisor
                }
            }
            d
        }
        waitFirst?.await()
    }

    /**
     * Infinite reconnect: backoff only after failed open or failed session restore; immediate retry after the
     * response stream ends cleanly or with error. Delay: min(2^(failureStreak-1) seconds, 30s).
     */
    private suspend fun reconnectLoop() {
        var failureStreak = 0
        var didCompleteFirstRestore = false

        while (!userInitiatedDisconnect && currentCoroutineContext().isActive) {
            if (failureStreak > 0) {
                val delaySec = min(2.0.pow(failureStreak - 1), 30.0)
                try {
                    delay((delaySec * 1000).toLong())
                } catch (_: CancellationException) {
                    return
                }
            }
            if (userInitiatedDisconnect || !currentCoroutineContext().isActive) return

            try {
                openTransportAndStream()
                sessionRestore?.invoke()

                failureStreak = 0

                if (!didCompleteFirstRestore) {
                    didCompleteFirstRestore = true
                    connectionMutex.withLock {
                        firstConnectDeferred?.complete(Unit)
                        firstConnectDeferred = null
                    }
                }

                waitForResponseStreamToComplete()

                if (userInitiatedDisconnect || !currentCoroutineContext().isActive) return

                teardownTransportForReconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (userInitiatedDisconnect || !currentCoroutineContext().isActive) return
                failureStreak++
                teardownTransportForReconnect()
            }
        }
    }

    private suspend fun openTransportAndStream() {
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                val (host, port) = parseRelayURL(options.relayUrl)
                val builder = OkHttpChannelBuilder.forAddress(host, port)
                    .useTransportSecurity()

                val ch = builder.build()
                val streamSignal = CompletableDeferred<Unit>()
                streamEndedSignal = streamSignal

                val stub = DeviceServiceGrpc.newStub(ch).withWaitForReady()

                val stream = stub.handleMessage(object : StreamObserver<DeviceResponse> {
                    override fun onNext(value: DeviceResponse) {
                        scope.launch {
                            handleResponse(value)
                        }
                    }

                    override fun onError(t: Throwable) {
                        streamSignal.complete(Unit)
                    }

                    override fun onCompleted() {
                        streamSignal.complete(Unit)
                    }
                })

                channel = ch
                requestStream = stream
            }
        }
    }

    private suspend fun waitForResponseStreamToComplete() {
        val signal = streamEndedSignal ?: return
        try {
            signal.await()
        } catch (e: CancellationException) {
            throw e
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

    suspend fun disconnect() {
        val sup: Job?
        val job: Job?
        connectionMutex.withLock {
            userInitiatedDisconnect = true
            firstConnectDeferred?.completeExceptionally(NoxyError.General("Disconnected"))
            firstConnectDeferred = null
            job = maintainJob
            sup = maintainSupervisor
            maintainJob = null
            maintainSupervisor = null
        }
        sup?.cancel()
        if (job != null) job.join()
        performFullDisconnect()
    }

    /** Drop the current transport so the reconnect loop reconnects; does not stop the loop or clear handlers. */
    suspend fun disconnectForReconnect() {
        teardownTransportForReconnect()
    }

    private suspend fun teardownTransportForReconnect() {
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                pendingMutex.withLock {
                    pendingRequests.values.forEach {
                        it.completeExceptionally(NoxyError.General("Reconnecting"))
                    }
                    pendingRequests.clear()
                }
                try {
                    requestStream?.onCompleted()
                } catch (_: Exception) {
                }
                requestStream = null
                try {
                    channel?.shutdown()?.awaitTermination(1, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
                channel = null
                sessionId = null
                networkDeviceId = null
            }
            streamEndedSignal = null
        }
    }

    private suspend fun performFullDisconnect() {
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                pendingMutex.withLock {
                    pendingRequests.values.forEach {
                        it.completeExceptionally(NoxyError.General("Disconnected"))
                    }
                    pendingRequests.clear()
                }
                try {
                    requestStream?.onCompleted()
                } catch (_: Exception) {
                }
                requestStream = null
                try {
                    channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
                channel = null
                sessionId = null
                networkDeviceId = null
                decisionHandler = null
            }
            streamEndedSignal = null
        }
        connectionMutex.withLock {
            userInitiatedDisconnect = false
        }
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
     * Register device with relay (announce), including wallet vs non-wallet identity fields.
     */
    suspend fun announceRegister(
        device: NoxyDevice,
        signature: ByteArray,
        fcmToken: String? = null
    ) = withContext(Dispatchers.IO) {
        val regBuilder = noxy.device.RegisterDevice.newBuilder()
            .setDevicePubkeys(
                noxy.device.DevicePublicKeys.newBuilder()
                    .setPublicKey(ByteString.copyFrom(device.publicKey))
                    .setPqPublicKey(ByteString.copyFrom(device.pqPublicKey))
            )
            .setSignature(ByteString.copyFrom(signature))
            .setType("android")
            .setIdentityType(device.relayIdentityType.toProtoIdentityType())

        when (device.relayIdentityType) {
            NoxyRelayIdentityType.WALLET -> regBuilder.setWalletAddress(device.identityId)
            else -> regBuilder.setIdentityId(device.identityId)
        }

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
     * Revoke device on relay. [logicalIdentityId] is sent in the `wallet_address` proto field (semantic logical id).
     */
    suspend fun revokeDevice(logicalIdentityId: String, signature: ByteArray) = withContext(Dispatchers.IO) {
        val req = DeviceRequest.newBuilder()
            .setRevokeDevice(
                noxy.device.RevokeDevice.newBuilder()
                    .setWalletAddress(logicalIdentityId)
                    .setSignature(ByteString.copyFrom(signature))
            )
            .build()
        sendAndWait(req)
    }

    /**
     * Rotate device keys on relay
     */
    suspend fun rotateDeviceKeys(
        device: NoxyDevice,
        newPubkeys: Pair<ByteArray, ByteArray>,
        signature: ByteArray
    ) = withContext(Dispatchers.IO) {
        val rot = noxy.device.RotateDeviceKeys.newBuilder()
            .setNewPubkeys(
                noxy.device.DevicePublicKeys.newBuilder()
                    .setPublicKey(ByteString.copyFrom(newPubkeys.first))
                    .setPqPublicKey(ByteString.copyFrom(newPubkeys.second))
            )
            .setSignature(ByteString.copyFrom(signature))
            .setIdentityType(device.relayIdentityType.toProtoIdentityType())

        when (device.relayIdentityType) {
            NoxyRelayIdentityType.WALLET -> rot.setWalletAddress(device.identityId)
            else -> rot.setIdentityId(device.identityId)
        }

        val req = DeviceRequest.newBuilder()
            .setRotateDeviceKeys(rot)
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
