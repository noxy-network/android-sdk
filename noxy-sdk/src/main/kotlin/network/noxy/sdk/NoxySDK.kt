package network.noxy.sdk

import android.content.Context
import network.noxy.sdk.identity.NoxyIdentity
import network.noxy.sdk.network.NoxyNetworkOptions
import network.noxy.sdk.storage.NoxyStorage

/**
 * Create a [NoxyClient] for the Noxy Decision Layer (encrypted decision requests + outcomes).
 *
 * @param context Android context (for storage)
 * @param identity EOA or SCW wallet identity with signer
 * @param network Relay gRPC URL and app ID
 * @param storage Optional custom storage (default: EncryptedSharedPreferences)
 *
 * Example:
 * ```kotlin
 * val client = createNoxyClient(
 *     context = context,
 *     identity = identity,
 *     network = NoxyNetworkOptions(
 *         appId = "your-app",
 *         relayUrl = "https://relay.noxy.network",
 *         appSigningSecret = "your-app-signing-secret",
 *     )
 * )
 * client.initialize()
 * client.on { relayMessageId, decision -> ... }
 * ```
 */
fun createNoxyClient(
    context: Context,
    identity: NoxyIdentity,
    network: NoxyNetworkOptions,
    storage: NoxyStorage = NoxyStorage(context)
): NoxyClient {
    return NoxyClient(identity = identity, networkOptions = network, storage = storage)
}
