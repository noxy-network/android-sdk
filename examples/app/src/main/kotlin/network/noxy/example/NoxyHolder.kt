package network.noxy.example

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import network.noxy.sdk.NoxyClient
import network.noxy.sdk.createNoxyClient
import network.noxy.sdk.identity.NoxyEoaWalletIdentity
import network.noxy.sdk.identity.NoxyIdentity
import network.noxy.sdk.identity.Signature
import network.noxy.sdk.network.NoxyNetworkOptions

/**
 * Holds the Noxy client instance and provides access.
 * Initializes at app startup.
 */
object NoxyHolder {

    private val mutex = Mutex()
    private var _client: NoxyClient? = null
    private var _initialized = false
    private var _initError: String? = null

    val client: NoxyClient?
        get() = _client

    val isInitialized: Boolean
        get() = _initialized

    /** Error message if initialization failed */
    val initError: String?
        get() = _initError

    /** Reset state to allow retry after failure */
    fun resetForRetry() {
        _client = null
        _initialized = false
        _initError = null
    }

    suspend fun initialize(context: Context) = mutex.withLock {
        if (_initialized) return@withLock
        _initError = null

        val identity = createDemoIdentity(context)
        val network = NoxyNetworkOptions(
            appId = BuildConfig.NOXY_APP_ID,
            relayUrl = BuildConfig.NOXY_RELAY_URL
        )

        _client = createNoxyClient(
            context = context,
            identity = identity,
            network = network
        )

        try {
            val ok = withTimeoutOrNull(10_000L) {
                _client?.initialize()
                true
            }
            if (ok == true) {
                _initialized = true
            } else {
                _initError = "Connection timed out. Check relay URL and network."
            }
        } catch (e: Exception) {
            _initError = "${e.javaClass.simpleName}: ${e.message}"
            throw e
        }
    }

    private fun createDemoIdentity(context: Context): NoxyIdentity {
        val demoWallet = DemoWallet(context)
        return NoxyIdentity.Eoa(
            NoxyEoaWalletIdentity(
                address = demoWallet.address,
                signer = { data ->
                    Signature(bytes = demoWallet.sign(data))
                }
            )
        )
    }
}
