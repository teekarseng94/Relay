package com.gastropos.relay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bring users directly to required permission screens.
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onResume() {
        super.onResume()
        val notificationGranted = isNotificationAccessGranted()
        val accessibilityGranted = isAccessibilityAccessGranted()
        Log.d(
            "MainActivity",
            "Permission state => notification=$notificationGranted, accessibility=$accessibilityGranted"
        )
        if (notificationGranted && accessibilityGranted) {
            Log.d("MainActivity", "Required permissions granted for GastroPos Relay")
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val myComponent = ComponentName(this, NotificationReceiverService::class.java).flattenToString()
        return enabledListeners.contains(myComponent)
    }

    private fun isAccessibilityAccessGranted(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val myComponent = ComponentName(this, OrderScraperService::class.java).flattenToString()
        return enabledServices
            .split(':')
            .any { it.equals(myComponent, ignoreCase = true) }
    }
}
