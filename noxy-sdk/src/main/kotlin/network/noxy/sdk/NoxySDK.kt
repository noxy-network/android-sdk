package network.noxy.sdk

import android.content.Context
import network.noxy.sdk.identity.NoxyIdentity
import network.noxy.sdk.network.NoxyNetworkOptions
import network.noxy.sdk.storage.NoxyStorage

/**
 * Create and initialize a Noxy client
 *
 * @param context Android context (for storage)
 * @param identity EOA or SCW wallet identity with signer
 * @param network Relay gRPC URL and app ID
 * @param storage Optional custom storage (default: EncryptedSharedPreferences)
 * @return NoxyClient instance
 *
 * Example:
 * ```kotlin
 * val identity = NoxyIdentity.Eoa(NoxyEoaWalletIdentity(
 *     address = "0x...",
 *     signer = { data -> Signature(bytes = wallet.signMessage(data)) }
 * ))
 * val client = createNoxyClient(
 *     context = context,
 *     identity = identity,
 *     network = NoxyNetworkOptions(appId = "your-app", relayUrl = "https://relay.noxy.network")
 * )
 * client.initialize()
 * client.on { notification -> println(notification) }
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
