package com.gastropos.relay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {
    private lateinit var strictModeSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.openNotificationSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.openAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.testGrabButton).setOnClickListener {
            launchMerchantApp(GRAB_PACKAGE)
        }
        findViewById<Button>(R.id.testShopeeButton).setOnClickListener {
            launchMerchantApp(SHOPEE_PACKAGE)
        }
        findViewById<Button>(R.id.openManualScraperPageButton).setOnClickListener {
            startActivity(Intent(this, ManualScraperActivity::class.java))
        }
        strictModeSwitch = findViewById(R.id.strictModeSwitch)
        strictModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STRICT_MODE_ENABLED, isChecked)
                .apply()
            Log.i("MainActivity", "Strict mode changed: $isChecked")
        }
    }

    override fun onResume() {
        super.onResume()
        val notificationGranted = isNotificationAccessGranted()
        val accessibilityGranted = isAccessibilityAccessGranted()
        Log.d(
            "MainActivity",
            "Permission state => notification=$notificationGranted, accessibility=$accessibilityGranted"
        )
        val status = findViewById<TextView>(R.id.statusText)
        status.text = when {
            notificationGranted && accessibilityGranted ->
                getString(R.string.main_status_ready)
            notificationGranted ->
                getString(R.string.main_status_need_accessibility)
            accessibilityGranted ->
                getString(R.string.main_status_need_notification)
            else ->
                getString(R.string.main_instructions)
        }
        if (notificationGranted && accessibilityGranted) {
            Log.d("MainActivity", "Required permissions granted for GastroPos Relay")
        }

        loadStrictModeSetting()
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
        val exactServiceName = "com.gastropos.relay/.OrderScraperService"
        val fullServiceName = ComponentName(this, OrderScraperService::class.java).flattenToString()
        return enabledServices
            .split(':')
            .any {
                it.equals(exactServiceName, ignoreCase = true) ||
                    it.equals(fullServiceName, ignoreCase = true)
            }
    }

    private fun launchMerchantApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val resolved = launchIntent.resolveActivity(packageManager)
        if (resolved == null) {
            Toast.makeText(this, getString(R.string.test_launch_failed, packageName), Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Cannot resolve launch activity for package=$packageName")
            return
        }

        startActivity(launchIntent)
        Toast.makeText(this, getString(R.string.test_launch_success, packageName), Toast.LENGTH_SHORT).show()
        Log.i("MainActivity", "Manual test launch sent for package=$packageName via $resolved")
    }

    private fun loadStrictModeSetting() {
        val enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_STRICT_MODE_ENABLED, true)
        if (strictModeSwitch.isChecked != enabled) {
            strictModeSwitch.isChecked = enabled
        }
    }

    private companion object {
        private const val PREFS = "relay_prefs"
        private const val KEY_STRICT_MODE_ENABLED = "strict_mode_enabled"
        private const val GRAB_PACKAGE = "com.grab.merchant"
        private const val SHOPEE_PACKAGE = "com.shopeepay.merchant.my"
    }
}
