package network.noxy.sdk.network

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
import noxy.device.DeviceRequest
import noxy.device.DeviceResponse
import noxy.device.DeviceServiceGrpc
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

    @Volatile
    private var pushHandler: (suspend (NoxyEncryptedNotification) -> Unit)? = null

    val isConnected: Boolean get() = channel != null
    val isReady: Boolean get() = isConnected && sessionId != null && networkDeviceId != null
    val currentSessionId: String? get() = sessionId
    val currentDeviceId: String? get() = networkDeviceId

    private fun parseRelayURL(urlString: String): Triple<String, Int, Boolean> {
        val url = URL(urlString)
        val host = url.host ?: throw NoxyError.General("Invalid relay URL: $urlString")
        val port = url.port.takeIf { it > 0 } ?: when (url.protocol.lowercase()) {
            "https" -> 443
            else -> 50051
        }
        val useTLS = url.protocol.lowercase() == "https"
        return Triple(host, port, useTLS)
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        val (host, port, useTLS) = parseRelayURL(options.relayUrl)

        val builder = OkHttpChannelBuilder.forAddress(host, port)
        if (useTLS) {
            builder.useTransportSecurity()
        } else {
            builder.usePlaintext()
        }

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

    private suspend fun handleResponse(response: DeviceResponse) {
        when (response.payloadCase) {
            DeviceResponse.PayloadCase.PUSH_EVENT -> {
                val push = response.pushEvent
                pushHandler?.invoke(
                    NoxyEncryptedNotification(
                        kyberCt = push.kyberCt.toByteArray(),
                        nonce = push.nonce.toByteArray(),
                        ciphertext = push.ciphertext.toByteArray()
                    )
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
            DeviceResponse.PayloadCase.SUBSCRIBE_NOTIFICATIONS,
            DeviceResponse.PayloadCase.REVOKE_DEVICE,
            DeviceResponse.PayloadCase.ROTATE_DEVICE_KEYS,
            DeviceResponse.PayloadCase.CLIENT_ACK -> {
                resumePending(response.requestId, response)
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
            .setNonce(com.google.protobuf.ByteString.copyFrom(ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }))
            .build()

        stream.onNext(req)
        deferred.await()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        pendingMutex.withLock {
            pendingRequests.values.forEach { it.completeExceptionally(NoxyError.General("Disconnected")) }
            pendingRequests.clear()
        }
        requestStream?.onCompleted()
        requestStream = null
        channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        channel = null
        sessionId = null
        networkDeviceId = null
        pushHandler = null
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
                            .setPublicKey(com.google.protobuf.ByteString.copyFrom(device.publicKey))
                            .setPqPublicKey(com.google.protobuf.ByteString.copyFrom(device.pqPublicKey))
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
        signature: ByteArray
    ) = withContext(Dispatchers.IO) {
        val req = DeviceRequest.newBuilder()
            .setRegisterDevice(
                noxy.device.RegisterDevice.newBuilder()
                    .setDevicePubkeys(
                        noxy.device.DevicePublicKeys.newBuilder()
                            .setPublicKey(com.google.protobuf.ByteString.copyFrom(devicePubkeys.first))
                            .setPqPublicKey(com.google.protobuf.ByteString.copyFrom(devicePubkeys.second))
                    )
                    .setWalletAddress(walletAddress)
                    .setSignature(com.google.protobuf.ByteString.copyFrom(signature))
            )
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
                    .setSignature(com.google.protobuf.ByteString.copyFrom(signature))
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
                            .setPublicKey(com.google.protobuf.ByteString.copyFrom(newPubkeys.first))
                            .setPqPublicKey(com.google.protobuf.ByteString.copyFrom(newPubkeys.second))
                    )
                    .setWalletAddress(walletAddress)
                    .setSignature(com.google.protobuf.ByteString.copyFrom(signature))
            )
            .build()
        sendAndWait(req)
    }

    /**
     * Subscribe to notifications stream
     */
    suspend fun subscribeToNotifications(
        handler: suspend (NoxyEncryptedNotification) -> Unit
    ) = withContext(Dispatchers.IO) {
        pushHandler = handler

        val reqBuilder = DeviceRequest.newBuilder()
            .setSubscribeNotifications(
                noxy.device.SubscribeNotifications.newBuilder().setSubscribe(true)
            )
        currentDeviceId?.let { reqBuilder.setDeviceId(it) }
        currentSessionId?.let { reqBuilder.setSessionId(it) }

        sendAndWait(reqBuilder.build())
    }
}
