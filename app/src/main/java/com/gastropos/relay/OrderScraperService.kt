package com.gastropos.relay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.util.ArrayMap
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast
import java.util.Locale
import java.util.regex.Pattern

class OrderScraperService : AccessibilityService() {
    private val supportedPackages = setOf(
        "com.grab.merchant",
        "com.shopeepay.merchant.my"
    )
    private val rmPattern = Pattern.compile("RM\\s?\\d+(?:\\.\\d{1,2})?", Pattern.CASE_INSENSITIVE)
    private val qtyPattern = Pattern.compile("(?:qty|x)\\s*[:x]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
    private val compactQtyPattern = Pattern.compile("^(\\d+)x$", Pattern.CASE_INSENSITIVE)
    private val orderIdPattern = Pattern.compile(
        "(?:order\\s*id|order|pesanan\\s*id|pesanan|id)\\s*[:#-]?\\s*([A-Za-z0-9-]{4,})",
        Pattern.CASE_INSENSITIVE
    )
    private val compactOrderIdPattern = Pattern.compile("#([A-Za-z0-9-]{4,})")
    private val noiseTokens = setOf(
        "accept", "reject", "chat", "call", "delivery", "pickup", "driver",
        "back", "home", "help", "support", "view all", "history"
    )

    private val scrapeDebounceLock = Any()
    private val lastScrapeElapsedByWindow = ArrayMap<String, Long>()
    private var windowManager: WindowManager? = null
    private var floatingSyncButton: View? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("OrderScraper", "Service Created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("OrderScraper", "Service successfully connected and visible")
        setupFloatingSyncButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val packageName = event.packageName?.toString() ?: return
        if (!supportedPackages.contains(packageName)) return

        val pending = OrderRepository.getPending(packageName) ?: return
        if (OrderRepository.isAlreadyProcessed(this, pending.orderId)) {
            Log.d("OrderScraper", "Skipping recursive scrape for already processed ${pending.orderId}")
            return
        }

        val windowId = event.windowId
        if (shouldDebounceScrape(packageName, windowId)) {
            Log.d("OrderScraper", "Debounced scrape (${SCRAPE_DEBOUNCE_MS}ms) for $packageName window=$windowId")
            return
        }

        val root = rootInActiveWindow ?: return
        scrapeAndSend(root, packageName, pending, isManualTrigger = false)
    }

    override fun onInterrupt() {
        Log.w("OrderScraper", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        removeFloatingSyncButton()
        super.onDestroy()
    }

    private fun setupFloatingSyncButton() {
        if (floatingSyncButton != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val overlayView = LayoutInflater.from(this).inflate(R.layout.floating_sync_button, null)
        overlayView.findViewById<ImageButton>(R.id.syncButton)?.setOnClickListener {
            triggerManualSync()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32
            y = 220
        }

        try {
            wm.addView(overlayView, params)
            floatingSyncButton = overlayView
        } catch (e: Exception) {
            Log.e("OrderScraper", "Failed to attach floating sync button", e)
        }
    }

    private fun removeFloatingSyncButton() {
        val wm = windowManager ?: return
        val view = floatingSyncButton ?: return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w("OrderScraper", "Failed to remove floating sync button", e)
        } finally {
            floatingSyncButton = null
        }
    }

    private fun triggerManualSync() {
        val root = rootInActiveWindow
        if (root == null) {
            Toast.makeText(this, "No active merchant screen to sync.", Toast.LENGTH_SHORT).show()
            return
        }

        val packageName = root.packageName?.toString()
        if (packageName.isNullOrBlank() || !supportedPackages.contains(packageName)) {
            root.recycle()
            Toast.makeText(this, "Sync is only available in Grab/Shopee merchant apps.", Toast.LENGTH_SHORT).show()
            return
        }

        val pending = OrderRepository.getPending(packageName)
        if (pending == null) {
            root.recycle()
            Toast.makeText(this, "No pending order to sync.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Sync triggered. Sending latest order data...", Toast.LENGTH_SHORT).show()
        scrapeAndSend(root, packageName, pending, isManualTrigger = true)
    }

    private fun scrapeAndSend(
        root: AccessibilityNodeInfo,
        packageName: String,
        pending: OrderRepository.PendingOrder,
        isManualTrigger: Boolean
    ) {
        try {
            val allTexts = mutableListOf<String>()
            collectTextsRecursive(root, allTexts)
            if (allTexts.isEmpty()) return
            Log.d("OrderScraper", "Collected ${allTexts.size} text nodes for $packageName")

            val scrapedOrderId = findOrderId(allTexts) ?: pending.orderId
            if (OrderRepository.isAlreadyProcessed(this, scrapedOrderId)) return

            val result = extractItemsAndTotal(allTexts, packageName)
            logExtractionSummary(scrapedOrderId, packageName, result, allTexts)
            if (shouldSkipDueToStrictMode(scrapedOrderId, result)) {
                Log.w(
                    "OrderScraper",
                    "Strict mode skipped upload: orderId=$scrapedOrderId items=${result.items.size} total=${result.total ?: "N/A"}"
                )
                return
            }
            if (result.items.isEmpty()) {
                Log.d("OrderScraper", "No item rows found yet, waiting for more UI updates.")
                return
            }

            val payload = RelayOrderPayload(
                source = pending.source,
                sourcePackage = packageName,
                orderId = scrapedOrderId,
                total = result.total,
                items = result.items,
                rawTexts = allTexts,
                scrapedAtEpochMs = System.currentTimeMillis()
            )

            OrderRepository.markProcessed(this, scrapedOrderId)
            OrderRelayClient.send(payload)
            Log.i(
                "OrderScraper",
                if (isManualTrigger) {
                    "Manually scraped and uploaded order $scrapedOrderId"
                } else {
                    "Scraped and uploaded order $scrapedOrderId"
                }
            )
        } finally {
            root.recycle()
        }
    }

    private fun shouldDebounceScrape(packageName: String, windowId: Int): Boolean {
        val key = "$packageName|$windowId"
        val now = SystemClock.elapsedRealtime()
        synchronized(scrapeDebounceLock) {
            val last = lastScrapeElapsedByWindow[key] ?: 0L
            if (now - last < SCRAPE_DEBOUNCE_MS) return true
            lastScrapeElapsedByWindow[key] = now
            return false
        }
    }

    private fun collectTextsRecursive(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size >= MAX_TEXT_NODES) return
        if (!node.isVisibleToUser) return

        val text = normalizeNodeText(node.text?.toString())
        val desc = normalizeNodeText(node.contentDescription?.toString())
        val childCount = node.childCount
        if (childCount == 0 && text == null && desc == null) return

        if (text != null) out.add(text)
        if (desc != null) out.add(desc)
        if (out.size >= MAX_TEXT_NODES) return

        for (i in 0 until childCount) {
            if (out.size >= MAX_TEXT_NODES) break
            val child = node.getChild(i) ?: continue
            try {
                collectTextsRecursive(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun normalizeNodeText(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value
            .replace("\\s+".toRegex(), " ")
            .trim()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun findOrderId(lines: List<String>): String? {
        lines.forEach { line ->
            val matcher = orderIdPattern.matcher(line)
            if (matcher.find()) return matcher.group(1)
            val compactMatcher = compactOrderIdPattern.matcher(line)
            if (compactMatcher.find()) return compactMatcher.group(1)
        }
        return null
    }

    private data class ExtractionResult(
        val items: List<OrderItem>,
        val total: String?
    )

    private fun extractItemsAndTotal(lines: List<String>, packageName: String): ExtractionResult {
        val filtered = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { isNoiseLine(it) }
            .distinct()

        val total = filtered.firstOrNull {
            it.lowercase(Locale.ROOT).contains("total") && rmPattern.matcher(it).find()
        } ?: filtered.lastOrNull { rmPattern.matcher(it).find() }

        val items = if (packageName.contains("grab")) {
            extractGrabItems(filtered)
        } else {
            extractShopeeItems(filtered)
        }.toMutableList()

        if (items.isEmpty()) {
            items.addAll(extractGenericItems(filtered))
        }
        if (items.isEmpty()) {
            items.addAll(extractNamePricePairs(filtered))
        }

        return ExtractionResult(items = items.distinctBy { "${it.name}|${it.quantity}|${it.price}" }, total = total)
    }

    private fun extractGrabItems(filtered: List<String>): List<OrderItem> {
        val items = mutableListOf<OrderItem>()
        for (index in filtered.indices) {
            val line = filtered[index]
            val qty = parseQuantity(line) ?: continue

            val name = nearestItemName(filtered, index) ?: continue
            val price = nearestPrice(filtered, index)
            items.add(OrderItem(name = name, quantity = qty, price = price))
        }
        return items
    }

    private fun extractShopeeItems(filtered: List<String>): List<OrderItem> {
        val items = mutableListOf<OrderItem>()
        for (index in filtered.indices) {
            val line = filtered[index]
            if (!rmPattern.matcher(line).find()) continue

            val name = filtered.getOrNull(index - 1)
                ?.takeIf { looksLikeItemName(it) }
                ?: continue
            val qty = parseQuantity(filtered.getOrNull(index + 1))
                ?: parseQuantity(filtered.getOrNull(index - 2))
            items.add(OrderItem(name = name, quantity = qty, price = line))
        }
        return items
    }

    private fun extractGenericItems(filtered: List<String>): List<OrderItem> {
        val items = mutableListOf<OrderItem>()
        for (index in filtered.indices) {
            val line = filtered[index]
            val qty = parseQuantity(line)
            if (qty == null && !line.contains("qty", true)) continue

            val maybePrice = filtered.getOrNull(index + 1)?.takeIf { rmPattern.matcher(it).find() }
            val maybeName = filtered.getOrNull(index - 1)?.takeIf {
                looksLikeItemName(it)
            } ?: "Unknown Item"

            items.add(
                OrderItem(
                    name = maybeName,
                    quantity = qty,
                    price = maybePrice
                )
            )
        }
        return items
    }

    private fun extractNamePricePairs(filtered: List<String>): List<OrderItem> {
        val items = mutableListOf<OrderItem>()
        for (index in 1 until filtered.size) {
            val current = filtered[index]
            if (!rmPattern.matcher(current).find()) continue
            if (current.contains("total", true) || current.contains("subtotal", true)) continue

            val nameCandidate = filtered[index - 1]
            if (!looksLikeItemName(nameCandidate)) continue
            val qty = parseQuantity(filtered.getOrNull(index + 1))
                ?: parseQuantity(filtered.getOrNull(index - 2))
                ?: 1
            items.add(OrderItem(name = nameCandidate, quantity = qty, price = current))
        }
        return items
    }

    private fun parseQuantity(line: String?): Int? {
        if (line.isNullOrBlank()) return null
        qtyPattern.matcher(line).let { if (it.find()) return it.group(1)?.toIntOrNull() }
        compactQtyPattern.matcher(line.trim()).let { if (it.find()) return it.group(1)?.toIntOrNull() }
        return null
    }

    private fun nearestItemName(lines: List<String>, pivot: Int): String? {
        val candidates = listOf(pivot - 1, pivot - 2, pivot + 1)
        for (index in candidates) {
            val line = lines.getOrNull(index) ?: continue
            if (looksLikeItemName(line)) return line
        }
        return null
    }

    private fun nearestPrice(lines: List<String>, pivot: Int): String? {
        val candidates = listOf(pivot + 1, pivot + 2, pivot - 1)
        for (index in candidates) {
            val line = lines.getOrNull(index) ?: continue
            if (rmPattern.matcher(line).find() && !line.contains("total", true)) return line
        }
        return null
    }

    private fun looksLikeItemName(line: String): Boolean {
        if (line.length < 2) return false
        if (rmPattern.matcher(line).find()) return false
        if (line.contains("total", true) || line.contains("subtotal", true)) return false
        if (line.contains("order", true) || line.contains("pesanan", true)) return false
        return !isNoiseLine(line)
    }

    private fun isNoiseLine(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if (lower.length <= 1) return true
        return noiseTokens.any { lower == it || lower.startsWith("$it ") }
    }

    private fun logExtractionSummary(
        orderId: String,
        packageName: String,
        result: ExtractionResult,
        allTexts: List<String>
    ) {
        val sampleItems = result.items.take(3).joinToString(" | ") {
            "${it.name} x${it.quantity ?: "?"} @ ${it.price ?: "N/A"}"
        }.ifBlank { "none" }
        val textPreview = allTexts.take(5).joinToString(" || ").take(220)
        Log.i(
            "OrderScraper",
            "Extraction summary: pkg=$packageName orderId=$orderId items=${result.items.size} total=${result.total ?: "N/A"} sample=$sampleItems preview=$textPreview"
        )
    }

    private fun shouldSkipDueToStrictMode(orderId: String, result: ExtractionResult): Boolean {
        val strictModeEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_STRICT_MODE_ENABLED, true)
        if (!strictModeEnabled) return false
        if (orderId.isBlank() || orderId.startsWith("UNKNOWN-")) return true
        if (result.items.isEmpty()) return true
        if (result.total.isNullOrBlank()) return true
        return false
    }

    private companion object {
        private const val SCRAPE_DEBOUNCE_MS = 2000L
        private const val MAX_TEXT_NODES = 3500
        private const val PREFS = "relay_prefs"
        private const val KEY_STRICT_MODE_ENABLED = "strict_mode_enabled"
    }
}
