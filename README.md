# 📦 @noxy-network/android-sdk

Android SDK to integrate with the [Noxy](https://noxy.network) **Decision Layer**: subscribe to encrypted decision requests, present them to the user, and respond with decision — all with wallet-based identity.

Users register a device once with a wallet signature. The relay streams encrypted decision payloads; the SDK decrypts them locally and can send `DecisionOutcome` back over the same gRPC session.

**Before you integrate:** Create your app at [noxy.network](https://noxy.network). When the app is created, you receive an **app id** and an **app token** (auth token). This Android SDK uses the **app id** (`appId` in `NoxyNetworkOptions`). The **app token** is for agent/orchestrator SDKs (Go, Rust, Python, Node, etc.), not for this package.

---

## Features

- **Wallet-based identity** — EOA and Smart Contract Wallets
- **Encrypted decision events** — Kyber (post-quantum) + AES-GCM for payloads from the Decision Layer
- **Subscribe / outcomes** — `SubscribeDecisions` on the relay; `sendDecisionOutcome` for approve/reject
- **Optional FCM wake-up** — Reconnect when the app is backgrounded (`setFcmToken`, `handleWakeUpNotification`)
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
    implementation("network.noxy:android-sdk:2.0.1")
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
import network.noxy.sdk.network.NoxyDecisionChoice

val identity = NoxyIdentity.Eoa(NoxyEoaWalletIdentity(
    address = "0x...",
    signer = { data ->
        val sig = wallet.signMessage(data)
        Signature(bytes = sig)
    }
))

val client = createNoxyClient(
    context = context,
    identity = identity,
    network = NoxyNetworkOptions(
        appId = "your-app-id",
        relayUrl = "https://relay.noxy.network",
        fcmToken = fcmToken  // optional: enables wake-up when app is backgrounded
    )
)

client.setFcmToken(firebaseToken)

var pendingDecisionId: String? = null

lifecycleScope.launch {
    client.initialize()
}

lifecycleScope.launch {
    client.on { relayMessageId, decision ->
        val title = decision["title"] as? String
        val summary = decision["summary"] as? String
        val decisionId = NoxyClient.resolveDecisionId(decision, relayMessageId)
            ?: return@on
        // Show UI (e.g. dialog or full-screen Activity)
    }
}

// In your Activity, Fragment, or notification receiver — wire this to Approve / Reject taps:
fun onApproveOrRejectClick(approve: Boolean) {
    lifecycleScope.launch {
        val id = pendingDecisionId ?: return@launch
        client.sendDecisionOutcome(
            id,
            if (approve) NoxyDecisionChoice.Approve else NoxyDecisionChoice.Reject
        )
    }
}

lifecycleScope.launch {
    client.close()
}
```

Delivery acknowledgements (`DecisionAck`) are sent automatically after each successfully decrypted decision when a decision id is available (`decision_id` / `decisionId` / `message_id` in the JSON, or the relay stream `message_id`).

---

## API Overview

| Method | Description |
|--------|-------------|
| `initialize()` | Load or create device, connect to relay, authenticate |
| `on(handler)` | Subscribe to encrypted decisions; handler receives relay `message_id`, then decrypted `Map` (`decision`) |
| `NoxyClient.resolveDecisionId(decision, relayMessageId?)` | Pick id for outcomes: `decision_id` / `decisionId` / `message_id` in JSON, else relay `message_id` |
| `sendDecisionOutcome(decisionId, outcome, receivedAt?)` | Send approve/reject (`DecisionOutcome` in proto) |
| `sendDecisionAck(decisionId, receivedAt?)` | Optional extra delivery ack (normally automatic after decrypt) |
| `setFcmToken(token)` | Register FCM token for wake-up when backgrounded |
| `handleWakeUpNotification(data?)` | Reconnect decision stream after FCM wake |
| `revokeDevice()` | Revoke device locally and on relay |
| `rotateKeys()` | Rotate device keys locally and on relay |
| `close()` | Disconnect from relay |

---

## FCM & offline wake-up

With `fcmToken` set (via `NoxyNetworkOptions` or `setFcmToken()`), the relay can send wake-up data messages when the app is backgrounded.

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data ?: return
    if (NoxyClient.isNoxyWakeUp(data)) {
        lifecycleScope.launch {
            client.handleWakeUpNotification(data)
        }
    }
}
```

---

## Proto & gRPC

The network layer uses gRPC with generated clients from `noxy/device/noxy.device.proto` (aligned with the iOS SDK). Code is generated by the protobuf Gradle plugin during build.

---

## Building

```bash
./gradlew :noxy-sdk:assemble
```

---

## License

MIT © Noxy Network
