package network.noxy.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NoxyExampleApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeNoxy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Noxy Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Decrypted push notifications from Noxy"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun initializeNoxy() {
        appScope.launch {
            try {
                NoxyHolder.initialize(this@NoxyExampleApp)
            } catch (_: Exception) {
                // Error surfaced via NoxyHolder.initError
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "noxy_notifications"
    }
}
