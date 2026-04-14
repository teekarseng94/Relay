package com.gastropos.relay

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private val orderIdPattern = Pattern.compile("(?:order|pesanan|id)\\s*[:#]?\\s*([A-Za-z0-9-]{4,})")
    private val noiseTokens = setOf(
        "accept", "reject", "chat", "call", "delivery", "pickup", "driver",
        "back", "home", "help", "support", "view all", "history"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val packageName = event.packageName?.toString() ?: return
        if (!supportedPackages.contains(packageName)) return

        val pending = OrderRepository.getPending(packageName) ?: return
        val root = rootInActiveWindow ?: return

        val allTexts = mutableListOf<String>()
        collectTextsRecursive(root, allTexts)
        if (allTexts.isEmpty()) return
        Log.d("OrderScraper", "Collected ${allTexts.size} text nodes for $packageName")

        val scrapedOrderId = findOrderId(allTexts) ?: pending.orderId
        if (OrderRepository.isAlreadyProcessed(this, scrapedOrderId)) return

        val result = extractItemsAndTotal(allTexts, packageName)
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

        // Mark processed before dispatching network request to avoid duplicate uploads.
        OrderRepository.markProcessed(this, scrapedOrderId)
        OrderRelayClient.send(payload)
        Log.i("OrderScraper", "Scraped and uploaded order $scrapedOrderId")
    }

    override fun onInterrupt() {
        Log.w("OrderScraper", "Accessibility service interrupted")
    }

    private fun collectTextsRecursive(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        normalizeNodeText(node.text?.toString())?.let { out.add(it) }
        normalizeNodeText(node.contentDescription?.toString())?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectTextsRecursive(node.getChild(i), out)
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
}
