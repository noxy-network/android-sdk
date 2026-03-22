package network.noxy.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.noxy.sdk.device.NoxyDeviceModule
import network.noxy.sdk.identity.NoxyIdentity
import network.noxy.sdk.identity.WalletAddress
import network.noxy.sdk.network.NoxyNetworkModule
import network.noxy.sdk.network.NoxyNetworkOptions
import network.noxy.sdk.notification.NoxyNotificationModule
import network.noxy.sdk.storage.NoxyStorage

/**
 * Main Noxy client. Lightweight orchestrator.
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
    private val notificationModule = NoxyNotificationModule(deviceModule)

    @Volatile
    private var fcmToken: String? = null

    @Volatile
    private var notificationHandler: ((Map<String, Any?>) -> Unit)? = null

    private val effectiveFcmToken: String?
        get() = fcmToken?.takeIf { it.isNotEmpty() } ?: networkOptions.fcmToken

    val address: WalletAddress get() = identity.address
    val isDeviceActive: Boolean get() = deviceModule.isRevoked != true
    val isRelayConnected: Boolean get() = networkModule.isConnected
    val isNetworkReady: Boolean get() = networkModule.isReady

    /**
     * Register FCM token for wake-up pushes when app is backgrounded.
     * Call when FirebaseMessaging.getInstance().token addsOnCompleteListener fires.
     */
    fun setFcmToken(token: String?) {
        fcmToken = token
    }

    /**
     * Initialize: load or create device, connect to network, authenticate.
     * Only registers (announces) the device on the relay when the authenticate response
     * contains requires_registration: true.
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
     * Subscribe to notifications. Loads device private keys first.
     */
    suspend fun on(handler: (Map<String, Any?>) -> Unit) = withContext(Dispatchers.IO) {
        notificationHandler = handler
        deviceModule.loadDevicePrivateKeys()
        networkModule.subscribeToNotifications(
            fcmToken = effectiveFcmToken,
            handler = { envelope ->
                try {
                    val decrypted = notificationModule.decryptNotification(envelope)
                    if (decrypted != null) {
                        handler(decrypted)
                    }
                } catch (_: Exception) {
                    // Decryption failed; silently ignored
                }
            }
        )
    }

    /**
     * Check if FCM data payload is a Noxy wake-up.
     * Relay sends data with `noxy: "wake"` in the data map.
     */
    companion object {
        @JvmStatic
        fun isNoxyWakeUp(data: Map<String, String>?): Boolean {
            if (data == null) return false
            return data["noxy"] == "wake"
        }
    }

    /**
     * Handle FCM wake-up: reconnect to relay and fetch notifications.
     * Call from FirebaseMessagingService.onMessageReceived when a data message indicates
     * a Noxy wake (e.g. noxy: "wake" in data).
     * If [data] is provided, only proceeds when it matches relay wake format.
     */
    suspend fun handleWakeUpNotification(data: Map<String, String>? = null): NoxyWakeUpResult =
        withContext(Dispatchers.IO) {
            if (data != null && !isNoxyWakeUp(data)) return@withContext NoxyWakeUpResult.NoData
            performWakeUpFetch()
        }

    private suspend fun performWakeUpFetch(): NoxyWakeUpResult {
        val handler = notificationHandler ?: return NoxyWakeUpResult.NoData

        // Only disconnect if we have a live connection (avoids no-op when app was terminated)
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
                // 1. Establish live gRPC connection (reconnect)
                networkModule.connect()
                if (!networkModule.isConnected) throw Exception("Connection not established")
                // 2. Authenticate device again to establish session
                networkModule.authenticateDevice(device)
                // 3. Subscribe for notifications over the live connection
                networkModule.subscribeToNotifications(
                    fcmToken = effectiveFcmToken,
                    handler = { envelope ->
                        try {
                            val decrypted = notificationModule.decryptNotification(envelope)
                            if (decrypted != null) handler(decrypted)
                        } catch (_: Exception) {}
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
