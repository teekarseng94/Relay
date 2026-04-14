package com.gastropos.relay

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import java.util.Locale
import java.util.regex.Pattern

class NotificationReceiverService : NotificationListenerService() {
    private val supportedPackages = setOf(
        "com.grab.merchant",
        "com.shopeepay.merchant.my"
    )
    private val orderIdPattern = Pattern.compile("(?:order|pesanan|id)\\s*[:#]?\\s*([A-Za-z0-9-]{4,})")

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!supportedPackages.contains(sbn.packageName)) return

        val notification = sbn.notification ?: return
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val fullText = listOf(title, text, bigText).joinToString(" ").trim()

        if (!isNewOrderNotification(fullText)) {
            Log.d("NotificationReceiver", "Filtered non-order notification: $fullText")
            return
        }

        val orderId = extractOrderId(fullText) ?: "UNKNOWN-${System.currentTimeMillis()}"
        if (OrderRepository.isAlreadyProcessed(this, orderId)) {
            Log.d("NotificationReceiver", "Order already processed: $orderId")
            return
        }

        val source = if (sbn.packageName.contains("grab")) "grab" else "shopee"
        OrderRepository.addPending(
            OrderRepository.PendingOrder(
                source = source,
                sourcePackage = sbn.packageName,
                orderId = orderId
            )
        )

        launchMerchantApp(sbn.packageName)
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

    private fun launchMerchantApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launchIntent)
        Log.i("NotificationReceiver", "Launched $packageName for scraping")
    }
}
