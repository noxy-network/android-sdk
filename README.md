# 📦 @noxy-network/android-sdk

**Noxy** is a decentralized push notification network for Web3 apps. This SDK lets your Android app receive secure, end-to-end encrypted notifications using **wallet-based identity** — no emails or phone numbers.

Users register a device once with a wallet signature. After that, they receive real-time or store-and-forward notifications — **without centralized user accounts**.

---

## Features

- **Wallet-based identity** — EOA and Smart Contract Wallets; no email or phone
- **End-to-end encrypted notifications** — Kyber (post-quantum) + AES-GCM
- **One-time device registration** — Sign with wallet; device keys and post-quantum keys generated and stored securely
- **Relay-based delivery** — gRPC connection to relay; real-time or store-and-forward
- **Secure storage** — EncryptedSharedPreferences backed by Android Keystore for device data and private keys

---

## Requirements

- **Java 21** — Gradle and tooling require Java 21. If you have a newer JDK (e.g. 25), set `JAVA_HOME` to Java 21 before building.
- Android 7.0+ (API 24+)
- Kotlin 1.9+
- Coroutines

---

## Installation

Add to your app's `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("network.noxy:android-sdk:1.0.0")
}
```

For local development from source:

```kotlin
// settings.gradle.kts
include(":noxy-sdk")
```

```kotlin
dependencies {
    implementation(project(":noxy-sdk"))
}
```

---

## Quick Start

```kotlin
import network.noxy.sdk.*
import network.noxy.sdk.identity.*

// 1. Create identity with wallet signer
val identity = NoxyIdentity.Eoa(NoxyEoaWalletIdentity(
    address = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb1",
    signer = { data ->
        val sig = wallet.signMessage(data)
        Signature(bytes = sig)
    }
))

// 2. Create client (optionally with fcmToken for offline wake-up)
val client = createNoxyClient(
    context = context,
    identity = identity,
    network = NoxyNetworkOptions(
        appId = "your-app-id",
        relayUrl = "https://relay.noxy.network",
        fcmToken = fcmToken  // optional: enables wake-up when app is backgrounded
    )
)

// Or set FCM token later when Firebase returns it
client.setFcmToken(firebaseToken)

// 3. Initialize (loads or registers device, connects to relay)
lifecycleScope.launch {
    client.initialize()
}

// 4. Subscribe to notifications
lifecycleScope.launch {
    client.on { notification ->
        // notification is the decrypted payload (e.g. { "title": "...", "body": "...", "data": {...} })
        handleNotification(notification)
    }
}

// 5. Disconnect when done
lifecycleScope.launch {
    client.close()
}
```

---

## Usage Examples

### EOA Identity (Externally Owned Account)

```kotlin
val identity = NoxyIdentity.Eoa(NoxyEoaWalletIdentity(
    address = walletAddress,
    signer = { data ->
        val sig = yourWallet.signMessage(data)
        Signature(bytes = sig)
    }
))
```

### Smart Contract Wallet Identity

```kotlin
val identity = NoxyIdentity.Scw(NoxyScwWalletIdentity(
    address = scwAddress,
    signer = { data ->
        val sig = yourWallet.signMessage(data)
        Signature(bytes = sig)
    }
))
```

### Custom Storage

```kotlin
val storage = NoxyStorage(
    context = context,
    storageName = "com.yourapp.noxy"
)

val client = createNoxyClient(
    context = context,
    identity = identity,
    network = networkOptions,
    storage = storage
)
```

### Displaying Notifications

Request notification permission and show decrypted notifications as local alerts:

```kotlin
// Request permission before initialize
val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
}

lifecycleScope.launch {
    client.initialize()
    client.on { payload ->
        val title = payload["title"] as? String ?: "Notification"
        val body = payload["body"] as? String ?: "New notification"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
```

### Revoke or Rotate Device

```kotlin
lifecycleScope.launch {
    // Revoke device (removes from relay and local storage)
    client.revokeDevice()

    // Rotate device keys (new keys, same identity)
    client.rotateKeys()
}
```

---

## Security Model

- **Device registration** — Device signs once with the wallet; the signature binds the device to the identity.
- **Notification encryption** — Kyber KEM for key agreement, HKDF for key derivation, AES-GCM for payload encryption.
- **Relay** — Sees only encrypted payloads; no plaintext and no need for centralized user accounts.

---

## FCM & Offline Wake-Up

With `fcmToken` set (via `NoxyNetworkOptions` or `setFcmToken()`), the relay can send wake-up data messages when the app is backgrounded. Your app can then reconnect and fetch new notifications.

**Without FCM token:** Online-only — notifications arrive while the app has an active gRPC connection.

**With FCM token:** Online + offline — relay sends FCM data messages with `data["noxy"] == "wake"` to wake the app; call `handleWakeUpNotification()` to reconnect and fetch.

```kotlin
// 1. Get FCM token (FirebaseMessaging.getInstance().token)
// 2. Set it on the client
client.setFcmToken(token)

// 3. In FirebaseMessagingService.onMessageReceived, for data messages:
override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data ?: return
    if (NoxyClient.isNoxyWakeUp(data)) {
        CoroutineScope(Dispatchers.Default).launch {
            val result = client.handleWakeUpNotification(data)
            // Use result (NewData, NoData, Failed) for logging or WorkManager
        }
    }
}
```

---

## API Overview

| Method | Description |
|--------|-------------|
| `initialize()` | Load or create device, connect to relay, authenticate |
| `on(handler)` | Subscribe to notifications; handler receives decrypted payload |
| `setFcmToken(token)` | Register FCM token for wake-up when backgrounded |
| `handleWakeUpNotification(data?)` | Handle FCM wake-up: reconnect and fetch notifications |
| `revokeDevice()` | Revoke device locally and on relay |
| `rotateKeys()` | Rotate device keys locally and on relay |
| `close()` | Disconnect from relay |

---

## Proto & gRPC

The network layer uses gRPC with generated client from `noxy.device.proto`. Proto files are in `noxy-sdk/src/main/proto/`. Code is generated automatically by the protobuf Gradle plugin during build.

---

## Building

```bash
./gradlew :noxy-sdk:assemble
```

---

## Publishing

The SDK is published to Maven Central as `network.noxy:android-sdk`.

**Version:** Set in `gradle.properties` as `NOXY_SDK_VERSION` (default: 1.0.0).

**Publish to local Maven** (to verify before publishing to Maven Central):

```bash
./gradlew :noxy-sdk:publishToMavenLocal
```

Then in a consumer project, add `mavenLocal()` to repositories and `implementation("network.noxy:android-sdk:1.0.0")` to verify the artifact resolves.

**Publish to Maven Central** (requires Sonatype credentials and GPG signing):

```bash
./gradlew :noxy-sdk:publishReleasePublicationToMavenCentral \
  -PSONATYPE_USERNAME=your-username \
  -PSONATYPE_PASSWORD=your-token \
  -PSIGNING_KEY_ID=your-key-id \
  -PSIGNING_KEY=base64-private-key \
  -PSIGNING_PASSWORD=key-passphrase
```

**CI/CD:** The `.github/workflows/publish.yml` workflow publishes on release. Configure these secrets: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_KEY`, `SIGNING_PASSWORD`.

---

## License

MIT © Noxy Network
