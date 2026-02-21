package network.noxy.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.noxy.example.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isSubscribed = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission needed for push notifications", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        setupUi()
        observeNoxyStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED -> { /* already granted */ }
                else -> requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupUi() {
        binding.subscribeButton.setOnClickListener {
            lifecycleScope.launch {
                if (NoxyHolder.initError != null) {
                    retryInit()
                } else {
                    subscribeToNotifications()
                }
            }
        }
        binding.copyButton.setOnClickListener {
            val address = binding.addressText.text?.toString()
            if (!address.isNullOrEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Wallet address", address))
                Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun retryInit() {
        NoxyHolder.resetForRetry()
        try {
            NoxyHolder.initialize(this)
        } catch (_: Exception) { /* error shown in status */ }
    }

    private fun observeNoxyStatus() {
        lifecycleScope.launch {
            while (true) {
                delay(500)
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val client = NoxyHolder.client
        val error = NoxyHolder.initError
        binding.subscribeButton.text = if (error != null) "Retry" else "Subscribe for Notifications"
        binding.statusText.text = buildString {
            append("Noxy: ")
            when {
                NoxyHolder.isInitialized && client != null -> {
                    append("Ready\n")
                    append("Relay: ${if (client.isRelayConnected) "Connected" else "Disconnected"}\n")
                    append("Subscribed: ${if (isSubscribed) "Yes" else "No"}")
                }
                error != null -> append("Failed: $error\n\nTap Subscribe to retry.")
                else -> append("Initializing...")
            }
        }
        // Show full address and copy button when ready
        if (NoxyHolder.isInitialized && client != null) {
            binding.addressText.text = client.address
            binding.addressLabel.visibility = android.view.View.VISIBLE
            binding.addressContainer.visibility = android.view.View.VISIBLE
            binding.copyButton.visibility = android.view.View.VISIBLE
        } else {
            binding.addressLabel.visibility = android.view.View.GONE
            binding.addressContainer.visibility = android.view.View.GONE
            binding.copyButton.visibility = android.view.View.GONE
        }
    }

    private suspend fun subscribeToNotifications() {
        val client = NoxyHolder.client
        if (client == null) {
            val err = NoxyHolder.initError
            Toast.makeText(
                this,
                if (err != null) "Tap to retry" else "Noxy not ready yet, please wait",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isSubscribed) {
            Toast.makeText(this, "Already subscribed", Toast.LENGTH_SHORT).show()
            return
        }

        binding.subscribeButton.isEnabled = false
        binding.subscribeButton.text = "Subscribing..."

        try {
            client.on { payload ->
                showNotification(payload)
            }
            isSubscribed = true
            runOnUiThread {
                Toast.makeText(this, "Subscribed to notifications", Toast.LENGTH_SHORT).show()
                binding.subscribeButton.text = "Subscribed"
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Subscribe failed: ${e.message}", Toast.LENGTH_LONG).show()
                binding.subscribeButton.isEnabled = true
                binding.subscribeButton.text = "Subscribe for Notifications"
            }
        }
    }

    private fun showNotification(payload: Map<String, Any?>) {
        val title = payload["title"] as? String ?: "Noxy Notification"
        val body = payload["body"] as? String ?: "New notification received"

        val notification = android.app.Notification.Builder(this, NoxyExampleApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService<android.app.NotificationManager>()
        manager?.notify(System.currentTimeMillis().toInt(), notification)
    }
}
