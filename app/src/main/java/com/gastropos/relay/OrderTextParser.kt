package com.gastropos.relay

import java.util.Locale

data class ParsedOrderItem(
    val quantity: Int,
    val name: String,
    val price: Double?,
    val note: String = ""
)

data class ParsedOrderText(
    val orderId: String,
    val items: List<ParsedOrderItem>,
    val calculatedTotal: Double,
    val grandTotal: Double?,
    val status: String
)

object OrderTextParser {
    private val totalRmPattern = Regex(
        "(?i)(?:^|[^\\d])(?:total|grand\\s*total|amount\\s*due|subtotal|pay(?:able)?)\\s*[:：]?\\s*(?:RM|MYR)?\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)"
    )
    private val rmStandalonePattern = Regex("(?i)\\bRM\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)\\b")
    private val grandTotalPattern = Regex(
        "(?i)(?:^|[^\\d])(?:grand\\s*total|amount\\s*due|pay(?:able)?)\\s*[:：]?\\s*(?:RM|MYR)?\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)"
    )
    private val orderAmountPattern = Regex(
        "(?i)\\border\\s*amount\\b[^\\n\\r]{0,40}?(-?)\\s*(?:RM|MYR)?\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)"
    )
    private val promotionSubsidyPattern = Regex(
        "(?i)\\bpromotion\\s*subsidy\\b[^\\n\\r]{0,40}?(-?)\\s*(?:RM|MYR)?\\s*([\\d]{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?)"
    )
    private val orderRefPattern = Regex("#([A-Z0-9\\-]+)|\\b(GF-[A-Z0-9\\-]+)\\b", RegexOption.IGNORE_CASE)
    private val grabOrderIdPattern = Regex("\\bGF-[A-Z0-9\\-]+\\b", RegexOption.IGNORE_CASE)
    private val shopeeQueueIdPattern = Regex("#(\\d{1,3})(?!\\d)")
    private val shopeeIdTokenPattern = Regex("#([A-Za-z0-9][A-Za-z0-9\\-]*)")
    private val shopeeFallbackNumericPattern = Regex("#([0-9][A-Za-z0-9\\-]{1,})")
    private val shopeeSystemRefPattern = Regex("^[A-Z][A-Z0-9]*-\\d{4,}$", RegexOption.IGNORE_CASE)
    private val grabStartPattern = Regex("(?i)\\bitems\\s*for\\s*\\*+\\b")
    private val grabItemsCountStartPattern = Regex("(?i)\\b(\\d+)\\s+items?\\s+for\\s*\\*+\\b")
    private val grabItemSplitPattern = Regex("(?i)(\\d+)\\s*x\\s*")
    private val grabAnyPricePattern = Regex("(?i)(?:RM\\s*)?(-?\\d+(?:[.,]\\d{2}))")
    private val grabInlineItemPattern = Regex(
        "(?i)(\\d+)\\s*x\\s+(.+?)(?=(?:\\s+\\d+\\s*x\\s+)|(?:\\s+subtotal\\b)|(?:\\s+total\\b)|$)"
    )
    private val grabStandalonePricePattern = Regex("^\\s*(?:RM\\s*)?(-?\\d+(?:[.,]\\d{2}))\\s*$", RegexOption.IGNORE_CASE)
    private val grabItemLinePattern = Regex(
        "^\\s*(\\d+)\\s*x\\s+(.+?)(?:\\s+(?:RM\\s*)?(-?\\d+(?:[.,]\\d{2})))?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val grabSectionEndPattern = Regex(
        "(?i)\\b(subtotal|total|grand\\s*total|order\\s*amount|promotion\\s*subsidy|includes\\s*tax)\\b"
    )
    private val grabPromoHealthyFruitPattern = Regex(
        "(?i)dapatkan\\s+healthy\\s+fruit.*?pesanan\\s+minimum\\s*rm\\s*\\d+(?:[.,]\\d{2})?"
    )
    private val grabMinimumRmPattern = Regex("(?i)minimum\\s*rm\\s*\\d+(?:[.,]\\d{2})?")
    private val itemStartPattern = Regex("^\\s*(\\d+)\\s*x\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val priceOnlyPattern = Regex("^\\s*-?\\s*(?:RM\\s*)?(\\d+(?:[.,]\\d{2})?)\\s*$", RegexOption.IGNORE_CASE)
    private val noteLinePattern = Regex("^\\s*([-*\\u2022])\\s*(.+?)\\s*$")
    private val sectionStartPattern = Regex(
        "(?i)\\b(order\\s*summary|items?|item\\s*list|your\\s*items?|order\\s*items?)\\b"
    )
    private val sectionEndPattern = Regex(
        "(?i)\\b(order\\s*amount|promotion\\s*subsidy|subtotal|total|grand\\s*total|discount|delivery\\s*fee|service\\s*fee|paid\\s*with)\\b"
    )
    private val metaLinePattern = Regex(
        "(?i)^\\s*(?:add-?on|addon|modifier|option|note|special\\s+instruction|remarks?)\\b[:：-]?\\s*$"
    )
    private val uiItemBlocklist = setOf("back", "confirm", "cancel", "home")
    private val shopeeIgnoredIds = setOf("details", "order", "info")
    private val shopeeBlockedPrefixes = listOf("manual-", "ref-", "spx-", "shp-", "spm-", "auto-")

    fun parse_order_text(rawText: String): ParsedOrderText = parseOrderText(rawText)

    fun parseOrderText(rawText: String): ParsedOrderText {
        val text = cleanRawText(rawText)
        if (text.isBlank()) {
            return ParsedOrderText(
                orderId = "Unknown",
                items = emptyList(),
                calculatedTotal = 0.0,
                grandTotal = null,
                status = "INCOMPLETE_SCRAPE"
            )
        }

        var orderId = "Unknown"
        for (match in orderRefPattern.findAll(text)) {
            val candidate = match.groups[1]?.value ?: match.groups[2]?.value ?: continue
            if (isBlockedShopeeToken(candidate) && !candidate.uppercase(Locale.ROOT).startsWith("GF-")) {
                continue
            }
            orderId = candidate
            break
        }

        val grabIdMatch = grabOrderIdPattern.find(text)
        if (grabIdMatch != null) {
            orderId = grabIdMatch.value.uppercase(Locale.ROOT)
            val items = parseGrabStream(text)
            val grandTotal = extractGrandTotal(text)
            val calculatedTotal = calculateItemsTotal(items)
            return ParsedOrderText(
                orderId = orderId,
                items = items,
                calculatedTotal = calculatedTotal,
                grandTotal = grandTotal,
                status = if (grandTotal != null && calculatedTotal + 0.01 < grandTotal) {
                    "INCOMPLETE_SCRAPE"
                } else {
                    "COMPLETE_SCRAPE"
                }
            )
        }

        extractShopeeOrderId(text)?.let { orderId = it }
        val items = parseShopeeItems(text)
        val orderAmount = extractOrderAmount(text)
        val promotionSubsidy = extractPromotionSubsidy(text)
        val grandTotal = when {
            orderAmount != null && promotionSubsidy != null -> roundMoney(orderAmount + promotionSubsidy)
            else -> extractGrandTotal(text) ?: orderAmount
        }
        val calculatedTotal = calculateItemsTotal(items)

        return ParsedOrderText(
            orderId = orderId,
            items = items,
            calculatedTotal = calculatedTotal,
            grandTotal = grandTotal,
            status = if (grandTotal != null && calculatedTotal + 0.01 < grandTotal) {
                "INCOMPLETE_SCRAPE"
            } else {
                "COMPLETE_SCRAPE"
            }
        )
    }

    private fun parseGrabStream(rawText: String): List<ParsedOrderItem> {
        val text = stripGrabNoise(rawText)
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val anchorIndex = lines.indexOfFirst {
            grabItemsCountStartPattern.containsMatchIn(it) || grabStartPattern.containsMatchIn(it)
        }
        val startIndex = when {
            anchorIndex < 0 -> 0
            // Some Grab UIs put "7 items for *" and all "1 x ..." tokens in the same line.
            grabItemSplitPattern.containsMatchIn(lines[anchorIndex]) -> anchorIndex
            else -> anchorIndex + 1
        }
        val endIndex = lines.withIndex().firstOrNull { (index, line) ->
            index > startIndex && grabSectionEndPattern.containsMatchIn(line)
        }?.index ?: lines.size
        val orderSection = lines.subList(startIndex, endIndex)
        val hasMergedMultiItemLine = orderSection.any { line ->
            grabItemSplitPattern.findAll(line).count() > 1
        }
        if (hasMergedMultiItemLine) {
            // Some Grab screens expose all items in one huge accessibility line.
            // In that case line-by-line parsing collapses into a single fake item.
            val inlineParsed = parseGrabFromInlineMergedText(orderSection.joinToString(" "))
            if (inlineParsed.isNotEmpty()) return dedupeItems(inlineParsed)
            return parseGrabBySegmentFallback(text)
        }

        val entries = mutableListOf<ParsedOrderItem>()
        var current: MutableParsedItem? = null
        val pendingNotes = mutableListOf<String>()

        for (line in orderSection) {
            val itemMatch = grabItemLinePattern.matchEntire(line)
            if (itemMatch != null) {
                current?.let {
                    it.note = mergeNotes(it.note, pendingNotes)
                    entries.add(it.toParsedOrderItem())
                }
                pendingNotes.clear()

                val qty = itemMatch.groups[1]?.value?.toIntOrNull() ?: continue
                val name = itemMatch.groups[2]?.value.orEmpty().trim()
                val inlinePrice = normalizePrice(itemMatch.groups[3]?.value.orEmpty())
                if (name.isBlank()) {
                    current = null
                    continue
                }
                current = MutableParsedItem(quantity = qty, name = name, price = inlinePrice)
                continue
            }

            if (current != null) {
                val standalonePriceMatch = grabStandalonePricePattern.matchEntire(line)
                if (standalonePriceMatch != null && current!!.price == null) {
                    current!!.price = normalizePrice(standalonePriceMatch.groups[1]?.value.orEmpty())
                    current!!.note = mergeNotes(current!!.note, pendingNotes)
                    entries.add(current!!.toParsedOrderItem())
                    current = null
                    pendingNotes.clear()
                    continue
                }

                if (!isNoiseBetweenNameAndPrice(line) && !grabSectionEndPattern.containsMatchIn(line)) {
                    pendingNotes.add(line)
                }
            }
        }

        current?.let {
            it.note = mergeNotes(it.note, pendingNotes)
            entries.add(it.toParsedOrderItem())
        }

        if (entries.isNotEmpty()) return dedupeItems(entries)

        // Fallback: keep legacy segment parsing for unusual merged accessibility blobs.
        val inlineParsed = parseGrabFromInlineMergedText(text)
        if (inlineParsed.isNotEmpty()) return dedupeItems(inlineParsed)
        return parseGrabBySegmentFallback(text)
    }

    private fun parseGrabFromInlineMergedText(text: String): List<ParsedOrderItem> {
        if (text.isBlank()) return emptyList()
        val compact = text.replace(Regex("\\s+"), " ").trim()
        val entries = mutableListOf<ParsedOrderItem>()
        for (match in grabInlineItemPattern.findAll(compact)) {
            val qty = match.groups[1]?.value?.toIntOrNull() ?: continue
            val segment = match.groups[2]?.value.orEmpty().trim()
            if (segment.isBlank()) continue
            val firstPriceMatch = grabAnyPricePattern.find(segment)
            val name: String
            val price: Double?
            val note: String
            if (firstPriceMatch != null) {
                name = segment.substring(0, firstPriceMatch.range.first).trim(' ', '-', ':', '\t')
                val pricing = extractGrabPriceAndNote(segment, firstPriceMatch)
                price = pricing.first
                note = pricing.second
            } else {
                name = segment.trim(' ', '-', ':', '\t')
                price = null
                note = ""
            }
            if (name.isNotBlank() && name.length > 1) {
                entries.add(
                    ParsedOrderItem(
                        quantity = qty,
                        name = name,
                        price = price,
                        note = note
                    )
                )
            }
        }
        return entries
    }

    private fun parseGrabBySegmentFallback(text: String): List<ParsedOrderItem> {
        var itemBlob = grabStartPattern.find(text)?.let { text.substring(it.range.last + 1).trim() } ?: text
        val cutleryMatch = Regex("(?i)^\\s*(cutlery needed|no cutlery)\\b").find(itemBlob)
        if (cutleryMatch != null) {
            itemBlob = itemBlob.substring(cutleryMatch.range.last + 1).trim()
        }
        val starts = grabItemSplitPattern.findAll(itemBlob).toList()
        val entries = mutableListOf<ParsedOrderItem>()
        starts.forEachIndexed { index, itemStart ->
            val qty = itemStart.groups[1]?.value?.toIntOrNull() ?: return@forEachIndexed
            val segmentStart = itemStart.range.last + 1
            val segmentEnd = starts.getOrNull(index + 1)?.range?.first ?: itemBlob.length
            var segment = itemBlob.substring(segmentStart, segmentEnd)
            Regex("(?i)\\bsubtotal\\b").find(segment)?.let {
                segment = segment.substring(0, it.range.first)
            }
            segment = segment.trim()
            if (segment.isBlank()) return@forEachIndexed

            val priceMatch = grabAnyPricePattern.find(segment)
            val name: String
            val price: Double?
            val note: String
            if (priceMatch != null) {
                name = segment.substring(0, priceMatch.range.first).trim(' ', '-', ':', '\t')
                val pricing = extractGrabPriceAndNote(segment, priceMatch)
                price = pricing.first
                note = pricing.second
            } else {
                name = segment.trim(' ', '-', ':', '\t')
                price = null
                note = ""
            }

            if (name.isNotBlank()) {
                entries.add(ParsedOrderItem(quantity = qty, name = name, price = price, note = note))
            }
        }
        return dedupeItems(entries)
    }

    private fun mergeNotes(existing: String, pendingNotes: MutableList<String>): String {
        val merged = (listOf(existing) + pendingNotes)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        pendingNotes.clear()
        return merged
    }

    private fun extractGrabPriceAndNote(segment: String, firstPriceMatch: MatchResult): Pair<Double?, String> {
        val basePrice = normalizePrice(firstPriceMatch.groups[1]?.value.orEmpty())
        val trailing = segment.substring(firstPriceMatch.range.last + 1).trim()
        if (basePrice == null) return Pair(null, trailing)

        var discount = 0.0
        grabAnyPricePattern.findAll(trailing).forEach { match ->
            val valueText = match.groups[1]?.value.orEmpty()
            val value = normalizePrice(valueText) ?: return@forEach
            val contextStart = (match.range.first - 16).coerceAtLeast(0)
            val contextEnd = (match.range.last + 1 + 2).coerceAtMost(trailing.length)
            val context = trailing.substring(contextStart, contextEnd).lowercase(Locale.ROOT)
            if (grabMinimumRmPattern.containsMatchIn(context)) return@forEach
            if (value < 0) discount += value
        }
        val finalPrice = (basePrice + discount).coerceAtLeast(0.0)
        val cleanNote = trailing
            .replace(grabPromoHealthyFruitPattern, " ")
            .replace(grabMinimumRmPattern, " ")
            .replace(Regex("[❌⭕⛔️🔻🔺🔸🔹'\"`]+"), " ")
            .replace(Regex("(?i)\\bRM\\s*-?\\d+(?:[.,]\\d{2})?\\b"), " ")
            .replace(Regex("(?<!\\w)-?\\d+(?:[.,]\\d{2})(?!\\w)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Pair(roundMoney(finalPrice), cleanNote)
    }

    private fun parseShopeeItems(text: String): List<ParsedOrderItem> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        var startIndex = 0
        var hasStartAnchor = false
        for ((index, line) in lines.withIndex()) {
            if (sectionStartPattern.containsMatchIn(line) || line.equals("nicole", ignoreCase = true)) {
                startIndex = index + 1
                hasStartAnchor = true
                break
            }
        }

        var endIndex = lines.size
        for (index in startIndex until lines.size) {
            if (sectionEndPattern.containsMatchIn(lines[index])) {
                endIndex = index
                break
            }
        }

        if (!hasStartAnchor) {
            lines.indexOfFirst { itemStartPattern.matches(it) }
                .takeIf { it >= 0 }
                ?.let { startIndex = it }
        }

        val orderList = mutableListOf<ParsedOrderItem>()
        var currentItem: MutableParsedItem? = null
        val pendingNameLines = mutableListOf<String>()

        for (line in lines.subList(startIndex, endIndex)) {
            val itemMatch = itemStartPattern.matchEntire(line)
            if (itemMatch != null) {
                currentItem?.let {
                    appendPendingName(it, pendingNameLines)
                    orderList.add(it.toParsedOrderItem())
                }
                val qty = itemMatch.groups[1]?.value?.toIntOrNull()
                val name = itemMatch.groups[2]?.value?.trim().orEmpty()
                if (qty == null || name.lowercase(Locale.ROOT) in uiItemBlocklist) {
                    currentItem = null
                    pendingNameLines.clear()
                    continue
                }
                currentItem = MutableParsedItem(quantity = qty, name = name)
                pendingNameLines.clear()
                continue
            }

            val priceMatch = priceOnlyPattern.matchEntire(line)
            if (priceMatch != null && currentItem != null) {
                val price = normalizePrice(priceMatch.groups[1]?.value.orEmpty())
                if (price != null) {
                    appendPendingName(currentItem!!, pendingNameLines)
                    currentItem!!.price = price
                    orderList.add(currentItem!!.toParsedOrderItem())
                    currentItem = null
                }
                continue
            }

            val noteMatch = noteLinePattern.matchEntire(line)
            if (noteMatch != null && currentItem != null) {
                val symbol = noteMatch.groups[1]?.value.orEmpty()
                val body = noteMatch.groups[2]?.value.orEmpty().trim()
                val noteText = "$symbol $body".trim()
                if (noteText.isNotBlank()) {
                    currentItem!!.note = listOf(currentItem!!.note, noteText)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
                continue
            }

            if (currentItem != null && currentItem!!.price == null && !isNoiseBetweenNameAndPrice(line)) {
                pendingNameLines.add(line)
            }
        }

        currentItem?.let {
            appendPendingName(it, pendingNameLines)
            orderList.add(it.toParsedOrderItem())
        }

        return dedupeItems(orderList)
    }

    private fun appendPendingName(item: MutableParsedItem, pendingNameLines: MutableList<String>) {
        if (pendingNameLines.isEmpty() || item.price != null) return
        val stitched = pendingNameLines.joinToString(" ") { it.trim() }.trim()
        if (stitched.isNotBlank()) {
            item.name = "${item.name} $stitched".trim()
        }
        pendingNameLines.clear()
    }

    private fun normalizePrice(value: String): Double? {
        var normalized = value.trim().replace(",", "")
        return try {
            if (normalized.count { it == '.' } > 1 && normalized.endsWith(",")) {
                normalized = normalized.replace(".", "").replace(",", ".")
            }
            normalized.toDouble()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun extractTotal(text: String): Double? {
        text.lines().forEach { line ->
            totalRmPattern.find(line)?.groups?.get(1)?.value?.let {
                normalizePrice(it)?.let { price -> return price }
            }
        }
        return rmStandalonePattern.findAll(text)
            .mapNotNull { normalizePrice(it.groups[1]?.value.orEmpty()) }
            .maxOrNull()
    }

    private fun extractGrandTotal(text: String): Double? {
        text.lines().forEach { line ->
            grandTotalPattern.find(line)?.groups?.get(1)?.value?.let {
                normalizePrice(it)?.let { price -> return price }
            }
        }
        return extractTotal(text)
    }

    private fun extractOrderAmount(text: String): Double? {
        text.lines().forEach { line ->
            val match = orderAmountPattern.find(line) ?: return@forEach
            val amount = normalizePrice(match.groups[2]?.value.orEmpty()) ?: return@forEach
            return if (match.groups[1]?.value == "-") -kotlin.math.abs(amount) else amount
        }
        return null
    }

    private fun extractPromotionSubsidy(text: String): Double? {
        text.lines().forEach { line ->
            val match = promotionSubsidyPattern.find(line) ?: return@forEach
            val amount = normalizePrice(match.groups[2]?.value.orEmpty()) ?: return@forEach
            return -kotlin.math.abs(amount)
        }
        return null
    }

    private fun cleanRawText(rawText: String): String {
        return rawText
            .replace("\ufeff", "")
            .replace("\u200b", "")
            .replace("\u200c", "")
            .replace("\u200d", "")
            .replace("\u00a0", " ")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("#Details", "")
            .replace("#Order", "")
            .replace("#Info", "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun stripGrabNoise(text: String): String {
        return text.lines()
            .map { line ->
                line.trim()
                    .replace(grabPromoHealthyFruitPattern, " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun extractShopeeOrderId(text: String): String? {
        shopeeQueueIdPattern.find(text)?.groups?.get(1)?.value?.let { return it }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        for ((index, line) in lines.withIndex()) {
            for (token in shopeeIdTokenPattern.findAll(line)) {
                val cleaned = sanitizeShopeeId(token.groups[1]?.value)
                if (isBlockedShopeeToken(cleaned)) {
                    if (cleaned.lowercase(Locale.ROOT) in shopeeIgnoredIds) {
                        listOfNotNull(lines.getOrNull(index + 1), lines.getOrNull(index - 1)).forEach { nearby ->
                            val near = shopeeIdTokenPattern.find(nearby)
                            val nearClean = sanitizeShopeeId(near?.groups?.get(1)?.value)
                            if (isValidShopeeId(nearClean)) return nearClean
                        }
                    }
                    continue
                }
                if (isValidShopeeId(cleaned)) return cleaned
            }
        }
        val fallback = shopeeFallbackNumericPattern.find(text)
        val cleaned = sanitizeShopeeId(fallback?.groups?.get(1)?.value)
        return cleaned.takeIf { it.isNotBlank() && it.first().isDigit() && isValidShopeeId(it) }
    }

    private fun sanitizeShopeeId(candidate: String?): String {
        return candidate.orEmpty()
            .trim()
            .replace("#Details", "")
            .replace("#Order", "")
            .replace("#Info", "")
            .trimStart('#')
            .replace(Regex("[^A-Za-z0-9\\-]"), "")
            .trim()
    }

    private fun isBlockedShopeeToken(candidate: String): Boolean {
        if (candidate.isBlank()) return true
        val lower = candidate.lowercase(Locale.ROOT)
        if (lower in shopeeIgnoredIds) return true
        if (shopeeBlockedPrefixes.any { lower.startsWith(it) }) return true
        if (shopeeSystemRefPattern.matches(candidate)) return true
        if (candidate.all { it.isDigit() } && candidate.length >= 8) return true
        return false
    }

    private fun isValidShopeeId(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (isBlockedShopeeToken(candidate)) return false
        return Regex("[A-Za-z0-9][A-Za-z0-9\\-]*").matches(candidate)
    }

    private fun isNoiseBetweenNameAndPrice(line: String): Boolean {
        val token = line.trim()
        if (token.isBlank()) return true
        if (metaLinePattern.matches(token)) return true
        if (noteLinePattern.matches(token)) return true
        if (token.lowercase(Locale.ROOT) in uiItemBlocklist) return true
        return false
    }

    private fun dedupeItems(items: List<ParsedOrderItem>): List<ParsedOrderItem> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            val signature = "${item.name.lowercase(Locale.ROOT)}|${item.quantity}|${item.price}"
            item.name.isNotBlank() && item.quantity > 0 && seen.add(signature)
        }
    }

    private fun calculateItemsTotal(items: List<ParsedOrderItem>): Double {
        return roundMoney(items.sumOf { it.price ?: 0.0 })
    }

    private fun roundMoney(value: Double): Double {
        return kotlin.math.round(value * 100.0) / 100.0
    }

    private data class MutableParsedItem(
        val quantity: Int,
        var name: String,
        var price: Double? = null,
        var note: String = ""
    ) {
        fun toParsedOrderItem(): ParsedOrderItem {
            return ParsedOrderItem(
                quantity = quantity,
                name = name,
                price = price,
                note = note
            )
        }
    }
}
