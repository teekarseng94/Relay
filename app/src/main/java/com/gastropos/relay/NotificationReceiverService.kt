package com.gastropos.relay

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.ArrayMap
import android.util.Log
import android.app.PendingIntent
import java.util.Locale
import java.util.regex.Pattern

class NotificationReceiverService : NotificationListenerService() {
    private val supportedPackages = setOf(
        "com.grab.merchant",
        "com.shopeepay.merchant.my"
    )
    private val orderIdPattern = Pattern.compile("(?:order|pesanan|id)\\s*[:#]?\\s*([A-Za-z0-9-]{4,})")
    private val notificationDebounceLock = Any()
    private val lastNotificationElapsedByKey = ArrayMap<String, Long>()
    private var isTestReceiverRegistered = false
    private val testNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TEST_NOTIFICATION -> {
                    Log.i("NotificationReceiver", "Received test broadcast")
                    launchMerchantApp(GRAB_PACKAGE)
                }
                ACTION_TEST_KEYWORD -> {
                    val payload = intent.getStringExtra(EXTRA_TEST_TEXT).orEmpty()
                    val routingResult = resolveTargetPackageFromKeywords(payload)
                    Log.i("NotificationReceiver", "Received keyword test broadcast: \"$payload\"")
                    if (routingResult == null) {
                        persistLastRoute(type = "none", keyword = payload)
                        Log.w("NotificationReceiver", "No keyword route matched for test payload")
                        return
                    }
                    persistLastRoute(
                        type = if (routingResult.packageName == GRAB_PACKAGE) "grab" else "shopee",
                        keyword = routingResult.matchedKeyword
                    )
                    launchMerchantApp(routingResult.packageName)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("NotificationReceiver", "NotificationListenerService created")
        val filter = IntentFilter().apply {
            addAction(ACTION_TEST_NOTIFICATION)
            addAction(ACTION_TEST_KEYWORD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Allow adb shell broadcasts to reach this runtime receiver for testing.
            registerReceiver(testNotificationReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(testNotificationReceiver, filter)
        }
        isTestReceiverRegistered = true
        Log.i(
            "NotificationReceiver",
            "Test broadcast receiver registered for $ACTION_TEST_NOTIFICATION and $ACTION_TEST_KEYWORD"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("NotificationReceiver", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w("NotificationReceiver", "Notification listener disconnected, requesting rebind")
        requestRebind(ComponentName(this, NotificationReceiverService::class.java))
    }

    override fun onDestroy() {
        if (isTestReceiverRegistered) {
            unregisterReceiver(testNotificationReceiver)
            isTestReceiverRegistered = false
            Log.i("NotificationReceiver", "Test broadcast receiver unregistered")
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val sourcePackage = resolveCanonicalSourcePackage(sbn.packageName)
        if (sourcePackage == null) {
            Log.d("NotificationReceiver", "Ignoring unsupported package: ${sbn.packageName}")
            return
        }

        val notification = sbn.notification ?: return
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val fullText = listOf(title, text, bigText).joinToString(" ").trim()
        Log.i("NotificationReceiver", "Notification captured: pkg=${sbn.packageName} text=\"$fullText\"")

        // Live-safe behavior:
        // 1) default route by source package (Grab/Shopee app that sent the notification)
        // 2) optionally override by keyword routing if a keyword match exists.
        val routingResult = resolveTargetPackageFromKeywords(fullText)
        val targetPackage = routingResult?.packageName ?: sourcePackage
        val matchedKeyword = routingResult?.matchedKeyword

        if (routingResult == null) {
            Log.d(
                "NotificationReceiver",
                "No keyword match, using source package route: source=$sourcePackage"
            )
        }

        persistLastRoute(
            type = if (targetPackage == GRAB_PACKAGE) "grab" else "shopee",
            keyword = matchedKeyword ?: "source-package"
        )

        val orderId = extractOrderId(fullText)
            ?: "NOID-${targetPackage.hashCode()}-${fullText.lowercase(Locale.ROOT).hashCode()}"
        if (shouldDebounceNotification(targetPackage, orderId)) {
            Log.d("NotificationReceiver", "Debounced notification (${NOTIFICATION_DEBOUNCE_MS}ms): $orderId")
            return
        }
        if (OrderRepository.isAlreadyProcessed(this, orderId)) {
            Log.d("NotificationReceiver", "Order already processed: $orderId")
            return
        }

        val source = if (targetPackage.contains("grab")) "grab" else "shopee"
        val addedToPending = OrderRepository.addPending(
            OrderRepository.PendingOrder(
                source = source,
                sourcePackage = targetPackage,
                orderId = orderId
            )
        )

        if (addedToPending) {
            launchMerchantOrderDetails(targetPackage, notification)
        } else {
            Log.w("NotificationReceiver", "Failed to enqueue pending order: $orderId")
        }
    }

    private fun isNewOrderNotification(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val isOrder = normalized.contains("new order") ||
            normalized.contains("pesanan baru") ||
            normalized.contains("order masuk")
        val isMarketing = normalized.contains("promo") ||
            normalized.contains("voucher") ||
            normalized.contains("sale")
        return isOrder && !isMarketing
    }

    private fun extractOrderId(text: String): String? {
        if (TextUtils.isEmpty(text)) return null
        val matcher = orderIdPattern.matcher(text)
        if (matcher.find()) return matcher.group(1)
        return null
    }

    private data class RoutingResult(
        val packageName: String,
        val matchedKeyword: String
    )

    private fun resolveTargetPackageFromKeywords(text: String): RoutingResult? {
        val normalized = text.lowercase(Locale.ROOT)
        val grabKeywords = loadKeywords(KEY_GRAB_KEYWORDS, DEFAULT_GRAB_KEYWORDS)
        val shopeeKeywords = loadKeywords(KEY_SHOPEE_KEYWORDS, DEFAULT_SHOPEE_KEYWORDS)
        val grabMatch = grabKeywords.firstOrNull { normalized.contains(it) }
        if (grabMatch != null) return RoutingResult(packageName = GRAB_PACKAGE, matchedKeyword = grabMatch)
        val shopeeMatch = shopeeKeywords.firstOrNull { normalized.contains(it) }
        if (shopeeMatch != null) return RoutingResult(packageName = SHOPEE_PACKAGE, matchedKeyword = shopeeMatch)
        return null
    }

    private fun loadKeywords(prefKey: String, defaultCsv: String): List<String> {
        val csv = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(prefKey, defaultCsv)
            .orEmpty()
        return csv.split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    private fun resolveCanonicalSourcePackage(packageName: String): String? {
        if (supportedPackages.contains(packageName)) return packageName
        val lower = packageName.lowercase(Locale.ROOT)
        return when {
            lower.contains("grab") -> GRAB_PACKAGE
            lower.contains("shopee") -> SHOPEE_PACKAGE
            else -> null
        }
    }

    private fun persistLastRoute(type: String, keyword: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ROUTE_TYPE, type)
            .putString(KEY_LAST_ROUTE_KEYWORD, keyword.take(80))
            .apply()
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
            Log.e("NotificationReceiver", "Cannot resolve launch activity for package=$packageName")
            return
        }

        try {
            startActivity(launchIntent)
            Log.i("NotificationReceiver", "Launched $packageName for scraping via $resolved")
        } catch (e: ActivityNotFoundException) {
            Log.e("NotificationReceiver", "Failed to launch package=$packageName", e)
        } catch (e: SecurityException) {
            Log.e("NotificationReceiver", "SecurityException launching package=$packageName", e)
        }
    }

    private fun launchMerchantOrderDetails(packageName: String, notification: Notification) {
        val contentIntent = notification.contentIntent
        if (contentIntent != null) {
            try {
                contentIntent.send()
                Log.i("NotificationReceiver", "Opened order details via notification contentIntent for $packageName")
                return
            } catch (e: PendingIntent.CanceledException) {
                Log.w(
                    "NotificationReceiver",
                    "contentIntent canceled for $packageName, falling back to launcher",
                    e
                )
            } catch (e: Exception) {
                Log.w(
                    "NotificationReceiver",
                    "Failed to open contentIntent for $packageName, falling back to launcher",
                    e
                )
            }
        } else {
            Log.w("NotificationReceiver", "No contentIntent found for $packageName notification")
        }
        launchMerchantApp(packageName)
    }

    private fun shouldDebounceNotification(packageName: String, orderId: String): Boolean {
        val key = "$packageName|$orderId"
        val now = SystemClock.elapsedRealtime()
        synchronized(notificationDebounceLock) {
            val last = lastNotificationElapsedByKey[key] ?: 0L
            if (now - last < NOTIFICATION_DEBOUNCE_MS) return true
            lastNotificationElapsedByKey[key] = now
            return false
        }
    }

    private companion object {
        private const val NOTIFICATION_DEBOUNCE_MS = 2000L
        private const val ACTION_TEST_NOTIFICATION = "com.gastropos.relay.TEST_NOTIFICATION"
        private const val ACTION_TEST_KEYWORD = "com.gastropos.relay.TEST_KEYWORD"
        private const val EXTRA_TEST_TEXT = "text"
        private const val PREFS = "relay_prefs"
        private const val KEY_GRAB_KEYWORDS = "grab_keywords_csv"
        private const val KEY_SHOPEE_KEYWORDS = "shopee_keywords_csv"
        private const val KEY_LAST_ROUTE_TYPE = "last_route_type"
        private const val KEY_LAST_ROUTE_KEYWORD = "last_route_keyword"
        private const val DEFAULT_GRAB_KEYWORDS = "GF, Grab"
        private const val DEFAULT_SHOPEE_KEYWORDS = "#, New order, Order ID"
        private const val GRAB_PACKAGE = "com.grab.merchant"
        private const val SHOPEE_PACKAGE = "com.shopeepay.merchant.my"
    }
}
