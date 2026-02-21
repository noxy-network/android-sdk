# Noxy Example App

A reference Android app demonstrating Noxy SDK integration.

## Features

- Initialize Noxy at app startup
- Subscribe to real-time notifications
- Display decrypted notifications as Android push notifications
- Show full wallet address with copy-to-clipboard

## Configuration

Relay URL and app ID are configured in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    buildConfigField("String", "NOXY_APP_ID", "\"your-app-id\"")
    buildConfigField("String", "NOXY_RELAY_URL", "\"https://relay.noxy.network\"")
}
```

## Running

```bash
./gradlew :examples:app:installDebug
```

Or run the `examples:app` configuration in Android Studio.

## Structure

- **NoxyExampleApp** — Application class; initializes Noxy and creates notification channel
- **NoxyHolder** — Singleton holding the Noxy client
- **MainActivity** — UI with status, address display, copy button, and subscribe
- **DemoWallet** — Ed25519 demo signer (replace with real wallet for production)

## Demo Wallet

The example uses a `DemoWallet` with a locally stored Ed25519 keypair. For production, integrate your wallet (e.g. Web3, WalletConnect) to sign device registration.
