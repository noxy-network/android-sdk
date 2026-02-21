package network.noxy.sdk

import android.content.Context
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
 */
class NoxyClient(
    private val identity: NoxyIdentity,
    private val networkOptions: NoxyNetworkOptions,
    private val storage: NoxyStorage
) {
    private val deviceModule = NoxyDeviceModule(storage)
    private val networkModule = NoxyNetworkModule(networkOptions)
    private val notificationModule = NoxyNotificationModule(deviceModule)

    val address: WalletAddress get() = identity.address
    val isDeviceActive: Boolean get() = deviceModule.isRevoked != true
    val isRelayConnected: Boolean get() = networkModule.isConnected
    val isNetworkReady: Boolean get() = networkModule.isReady

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
                signature = sig
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
        deviceModule.loadDevicePrivateKeys()
        networkModule.subscribeToNotifications { envelope ->
            try {
                val decrypted = notificationModule.decryptNotification(envelope)
                if (decrypted != null) {
                    handler(decrypted)
                }
            } catch (_: Exception) {
                // Decryption failed; silently ignored
            }
        }
    }

    /**
     * Disconnect from relay
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        networkModule.disconnect()
    }
}
