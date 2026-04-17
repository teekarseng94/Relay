package com.gastropos.relay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {
    private lateinit var grabKeywordsInput: EditText
    private lateinit var shopeeKeywordsInput: EditText
    private lateinit var lastRouteValue: TextView
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
        grabKeywordsInput = findViewById(R.id.grabKeywordsInput)
        shopeeKeywordsInput = findViewById(R.id.shopeeKeywordsInput)
        lastRouteValue = findViewById(R.id.lastRouteValue)
        strictModeSwitch = findViewById(R.id.strictModeSwitch)
        findViewById<Button>(R.id.saveKeywordsButton).setOnClickListener {
            saveKeywordRoutingSettings()
        }
        findViewById<Button>(R.id.resetKeywordsButton).setOnClickListener {
            resetKeywordRoutingSettings()
        }
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

        loadKeywordRoutingSettings()
        loadLastRouteStatus()
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

    private fun loadKeywordRoutingSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val grabKeywords = prefs.getString(KEY_GRAB_KEYWORDS, DEFAULT_GRAB_KEYWORDS).orEmpty()
        val shopeeKeywords = prefs.getString(KEY_SHOPEE_KEYWORDS, DEFAULT_SHOPEE_KEYWORDS).orEmpty()
        if (grabKeywordsInput.text.toString() != grabKeywords) {
            grabKeywordsInput.setText(grabKeywords)
        }
        if (shopeeKeywordsInput.text.toString() != shopeeKeywords) {
            shopeeKeywordsInput.setText(shopeeKeywords)
        }
    }

    private fun loadLastRouteStatus() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lastType = prefs.getString(KEY_LAST_ROUTE_TYPE, "").orEmpty()
        val lastKeyword = prefs.getString(KEY_LAST_ROUTE_KEYWORD, "").orEmpty()
        lastRouteValue.text = when (lastType) {
            "grab" -> getString(R.string.last_route_grab, lastKeyword)
            "shopee" -> getString(R.string.last_route_shopee, lastKeyword)
            "none" -> getString(R.string.last_route_none, lastKeyword)
            else -> getString(R.string.last_route_default)
        }
    }

    private fun saveKeywordRoutingSettings() {
        val grabKeywords = grabKeywordsInput.text.toString().trim()
        val shopeeKeywords = shopeeKeywordsInput.text.toString().trim()
        if (grabKeywords.isBlank() || shopeeKeywords.isBlank()) {
            Toast.makeText(this, R.string.keyword_input_required, Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_GRAB_KEYWORDS, grabKeywords)
            .putString(KEY_SHOPEE_KEYWORDS, shopeeKeywords)
            .apply()
        Toast.makeText(this, R.string.keywords_saved, Toast.LENGTH_SHORT).show()
        Log.i("MainActivity", "Keyword routing settings saved")
    }

    private fun resetKeywordRoutingSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_GRAB_KEYWORDS, DEFAULT_GRAB_KEYWORDS)
            .putString(KEY_SHOPEE_KEYWORDS, DEFAULT_SHOPEE_KEYWORDS)
            .apply()
        loadKeywordRoutingSettings()
        Toast.makeText(this, R.string.keywords_reset, Toast.LENGTH_SHORT).show()
        Log.i("MainActivity", "Keyword routing settings reset to defaults")
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
        private const val KEY_GRAB_KEYWORDS = "grab_keywords_csv"
        private const val KEY_SHOPEE_KEYWORDS = "shopee_keywords_csv"
        private const val KEY_LAST_ROUTE_TYPE = "last_route_type"
        private const val KEY_LAST_ROUTE_KEYWORD = "last_route_keyword"
        private const val KEY_STRICT_MODE_ENABLED = "strict_mode_enabled"
        private const val DEFAULT_GRAB_KEYWORDS = "GF, Grab"
        private const val DEFAULT_SHOPEE_KEYWORDS = "#, New order, Order ID"
        private const val GRAB_PACKAGE = "com.grab.merchant"
        private const val SHOPEE_PACKAGE = "com.shopeepay.merchant.my"
    }
}
