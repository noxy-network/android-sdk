package network.noxy.sdk.network

/**
 * Network configuration for relay connection
 */
data class NoxyNetworkOptions(
    val appId: String,
    /** gRPC endpoint (e.g. "https://relay.noxy.network") */
    val relayUrl: String,
    val maxRetries: Int = 5,
    val retryTimeoutMs: Long = 15_000,
    val requireAck: Boolean = false
)
