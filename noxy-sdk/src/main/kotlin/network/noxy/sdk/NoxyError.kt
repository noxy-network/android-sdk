package network.noxy.sdk

sealed class NoxyError(message: String) : Exception(message) {
    class InitializationFailed(message: String) : NoxyError(message)
    class General(message: String) : NoxyError(message)
}
