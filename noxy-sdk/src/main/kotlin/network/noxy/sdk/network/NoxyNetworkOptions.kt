package network.noxy.sdk.network

/**
 * Network configuration for relay connection
 *
 * @param fcmToken Optional FCM token for wake-up pushes when app is backgrounded.
 *   When set, app works online and offline. When null, online-only.
 */
data class NoxyNetworkOptions(
    val appId: String,
    /** gRPC endpoint; must use HTTPS (e.g. "https://relay.noxy.network") */
    val relayUrl: String,
    val maxRetries: Int = 5,
    val retryTimeoutMs: Long = 15_000,
    val requireAck: Boolean = false,
    /** FCM token for wake-up when offline. Set via options or setFcmToken() */
    val fcmToken: String? = null
)
