# 📦 @noxy-network/android-sdk

Android SDK for [Noxy](https://noxy.network).

## What is Noxy?

[Noxy](https://noxy.network) adds **human-in-the-loop** guardrails: encrypted prompts reach your app, the **user makes a decision**, and your app **sends the outcome** to the relay—plaintext prompts stay off the relay.

Users register a device once using **`appSigningSecret`** (registration HMAC). This SDK decrypts payloads locally and sends **`DecisionOutcome`** over **gRPC**.

## Before you integrate

Create your app at [noxy.network](https://noxy.network). On the dashboard, copy **APP_ID** into **`appId`** and **APP_SIGNING_SECRET** into **`appSigningSecret`** in `NoxyNetworkOptions`. Device registration sends an HMAC derived from **APP_SIGNING_SECRET**. Agent backends use a separate **app token**, not these values.

---

## Features

- **Human-in-the-loop payloads** — Kyber (post-quantum) + AES-GCM for encrypted prompts from the relay.
- **Relay identities** — **`wallet`**, **`email`**, **`phone`**, **`user_id`** — see [Relay identity types](#relay-identity-types).
- **Subscribe / outcomes** — `SubscribeDecisions` on the relay; `sendDecisionOutcome` to publish the user’s outcome.
- **Optional FCM wake-up** — Reconnect when the app is backgrounded (`setFcmToken`, `handleWakeUpNotification`).
- **Secure storage** — EncryptedSharedPreferences backed by Android Keystore for device data and private keys.

---

## Relay identity types

The relay **`identity_type`** values are **`wallet`**, **`email`**, **`phone`**, and **`user_id`**. In Kotlin use **`NoxyRelayIdentityType`** (`USER_ID` ↔ **`user_id`** on the wire). **`logicalIdentityIdOf(identity)`** returns the stable string key for storage and APIs.

### Kotlin examples

Non-wallet cases use **`NoxyIdentity.Email`** / **`Phone`** / **`UserId`** (no separate registration signer). **`logicalIdentityIdOf(identity)`** is the stable key; **`NoxyNetworkOptions.appSigningSecret`** supplies the registration HMAC for all identity kinds.

```kotlin
import network.noxy.sdk.identity.NoxyIdentity

// Email (relay identity_type "email")
val emailIdentity = NoxyIdentity.Email(email = "you@example.com")

// Phone (relay identity_type "phone")
val phoneIdentity = NoxyIdentity.Phone(phone = "+15551234567")

// Opaque id (relay identity_type "user_id")
val userIdIdentity = NoxyIdentity.UserId(userId = "internal-user-abc123")

val client = createNoxyClient(
    context,
    userIdIdentity,
    NoxyNetworkOptions(
        appId = "…",
        relayUrl = "https://relay.noxy.network",
        appSigningSecret = "your-app-signing-secret",
    ),
)
```

Use **`client.logicalIdentityId`** for logging or UI; **`address`** / **`walletAddress`** are only valid for **`Eoa`** / **`Scw`**.

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
    implementation("network.noxy:android-sdk:2.1.0")
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

## Quick start (wallet identity)

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
        appSigningSecret = "your-app-signing-secret",
        fcmToken = fcmToken  // optional: enables wake-up when app is backgrounded
    )
)

client.setFcmToken(firebaseToken)

lifecycleScope.launch {
    client.initialize()
}

lifecycleScope.launch {
    client.on { relayMessageId, decision ->
        val decisionId = NoxyClient.resolveDecisionId(decision, relayMessageId)
            ?: return@on
        // Show UI — user decides; send outcome via sendDecisionOutcome
    }
}

// Wire UI — pass the `NoxyDecisionChoice` that reflects the user’s selection:
fun submitDecisionOutcome(choice: NoxyDecisionChoice) {
    lifecycleScope.launch {
        val id = pendingDecisionId ?: return@launch
        client.sendDecisionOutcome(id, choice)
    }
}

lifecycleScope.launch {
    client.close()
}
```
---

Delivery acknowledgements (`DecisionAck`) are sent automatically after each successfully decrypted decision when a decision id is available (`decision_id` / `decisionId` / `message_id` in the JSON, or the relay stream `message_id`).

---

## API Overview

| Method | Description |
|--------|-------------|
| `initialize()` | Load or create device, connect to relay, authenticate |
| `on(handler)` | Subscribe to encrypted decisions; handler receives relay `message_id`, then decrypted `Map` (`decision`) |
| `NoxyClient.resolveDecisionId(decision, relayMessageId?)` | Pick id for outcomes: `decision_id` / `decisionId` / `message_id` in JSON, else relay `message_id` |
| `sendDecisionOutcome(decisionId, outcome, receivedAt?)` | Send a **`DecisionOutcome`** (proto) for the user’s choice |
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

The network layer uses gRPC with generated clients from `noxy/device/noxy.device.proto`. Code is generated by the protobuf Gradle plugin during build.

---

## Building

```bash
./gradlew :noxy-sdk:assemble
```

---

## License

MIT © Noxy Network
