package com.gastropos.relay

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object OrderRelayClient {
    private const val FIRESTORE_COLLECTION = "orders"
    private const val TAG = "OrderRelayClient"
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    // SupervisorJob so a single failed upload doesn't cancel the scope and
    // kill subsequent uploads. IO dispatcher because Firebase Tasks → coroutine
    // bridge ultimately blocks until the network round-trip resolves.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(context: Context, payload: RelayOrderPayload) {
        val appContext = context.applicationContext
        val destination = getRelayUrl(appContext)
        val buildResult = try {
            buildPosOrderJson(payload)
        } catch (jsonError: Exception) {
            Log.e(
                TAG,
                "Failed to build JSON for order ${payload.orderId}: ${jsonError.message}",
                jsonError
            )
            persistUploadStatus(
                appContext,
                relayUrl = destination,
                status = "JSON build failed: ${jsonError.message ?: "unknown error"}",
                success = false,
                firestoreOrderId = null
            )
            return
        }
        val parsedOrderJson = buildResult.json

        val rawTextCombined = payload.rawTexts.joinToString("\n").trim()
        persistParsedOrderJson(appContext, parsedOrderJson.toString(2), rawTextCombined)

        val scrapeValidationFailure = detectScrapeValidationFailure(buildResult, rawTextCombined, payload)
        if (!payload.forceUpload && scrapeValidationFailure != null) {
            val statusMessage = "Scrape validation failed (upload skipped): $scrapeValidationFailure"
            Log.w(TAG, "Skipping Firestore upload for ${payload.orderId}: $statusMessage")
            persistUploadStatus(
                appContext,
                relayUrl = destination,
                status = statusMessage,
                success = false,
                firestoreOrderId = null
            )
            return
        }

        val orderId = parsedOrderJson.optString("order_id", payload.orderId)
        val documentId = orderId.toFirestoreDocumentId()
            ?: firestore.collection(FIRESTORE_COLLECTION).document().id
        val orderData = buildFirestoreOrderData(payload, parsedOrderJson, documentId)

        scope.launch {
            try {
                // Make sure we're signed in as the relay user with the
                // gastropos-relay claim BEFORE attempting the write.
                // The Firestore Security Rules in rdp-pos reject writes
                // from unauthenticated callers.
                RelayAuth.ensureSignedIn()

                firestore.collection(FIRESTORE_COLLECTION)
                    .document(documentId)
                    .set(orderData)
                    .await()

                OrderRepository.markProcessed(appContext, orderId)
                Log.i(TAG, "Firestore upload succeeded: $FIRESTORE_COLLECTION/$documentId")
                persistUploadStatus(
                    appContext,
                    relayUrl = destination,
                    status = "Firestore upload successful: $FIRESTORE_COLLECTION/$documentId",
                    success = true,
                    firestoreOrderId = documentId
                )
            } catch (error: Exception) {
                Log.e(TAG, "Firestore upload failed for order $orderId", error)
                persistUploadStatus(
                    appContext,
                    relayUrl = destination,
                    status = "Firestore upload failed: ${error.message ?: "unknown error"}",
                    success = false,
                    firestoreOrderId = null
                )
            }
        }
    }

    fun recordScrapeFailure(context: Context, reason: String) {
        val appContext = context.applicationContext
        persistUploadStatus(
            context = appContext,
            relayUrl = getRelayUrl(appContext),
            status = "Scrape failed before upload: $reason",
            success = false,
            firestoreOrderId = null
        )
    }

    fun getRelayUrl(context: Context): String {
        return "Firestore collection: $FIRESTORE_COLLECTION"
    }

    fun getLastParsedOrderJson(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PARSED_ORDER_JSON, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun getLastRawScrapeText(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_RAW_SCRAPE_TEXT, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun forceUploadLastCaptured(context: Context): ForceUploadResult {
        val raw = getLastRawScrapeText(context.applicationContext)
            ?: return ForceUploadResult(false, "No cached raw scrape text available yet.")
        val parsed = OrderTextParser.parse_order_text(raw)
        val orderId = parsed.orderId.takeUnless { it.equals("Unknown", ignoreCase = true) }
            ?: Regex("\\bGF-[A-Z0-9\\-]+\\b", RegexOption.IGNORE_CASE).find(raw)?.value
            ?: "MANUAL-${System.currentTimeMillis()}"
        val source = if (orderId.startsWith("GF-", ignoreCase = true)) "grab" else "shopee"
        val items = parsed.items.map { parsedItem ->
            OrderItem(
                name = parsedItem.name,
                quantity = parsedItem.quantity,
                price = parsedItem.price?.let { String.format(Locale.ROOT, "RM%.2f", it) }
            )
        }
        val payload = RelayOrderPayload(
            source = source,
            sourcePackage = if (source == "grab") "com.grab.merchant" else "com.shopeepay.merchant.my",
            orderId = orderId,
            total = parsed.grandTotal?.let { String.format(Locale.ROOT, "RM%.2f", it) },
            items = items,
            rawTexts = raw.lines().filter { it.isNotBlank() },
            scrapedAtEpochMs = System.currentTimeMillis(),
            forceUpload = true
        )
        send(context.applicationContext, payload)
        return ForceUploadResult(true, "Force upload queued for order $orderId")
    }

    fun recordParsedOrderPreview(context: Context, payload: RelayOrderPayload) {
        val parsedOrderJson = try {
            buildPosOrderJson(payload).json.toString(2)
        } catch (error: Exception) {
            JSONObject().apply {
                put("order_id", payload.orderId)
                put("grand_total", extractMoney(payload.total) ?: JSONObject.NULL)
                put("items", JSONArray().apply {
                    payload.items.forEach { item ->
                        put(JSONObject().apply {
                            put("quantity", item.quantity ?: 1)
                            put("name", item.name)
                            put("price", extractMoney(item.price) ?: JSONObject.NULL)
                        })
                    }
                })
                put("parser_status", "Preview fallback: ${error.message ?: "parser unavailable"}")
            }.toString(2)
        }
        val rawTextCombined = payload.rawTexts.joinToString("\n").trim()
        persistParsedOrderJson(context.applicationContext, parsedOrderJson, rawTextCombined)
    }

    private fun buildPosOrderJson(payload: RelayOrderPayload): PosBuildResult {
        val rawTextCombined = payload.rawTexts.joinToString("\n").trim()
        val parsedOrder = OrderTextParser.parse_order_text(rawTextCombined)
        val orderId = parsedOrder.orderId
            .takeUnless { it.equals("Unknown", ignoreCase = true) }
            ?: payload.orderId
        val items = parsedOrder.items.takeIf { it.isNotEmpty() }
            ?: payload.items.map {
                ParsedOrderItem(
                    quantity = it.quantity ?: 1,
                    name = it.name,
                    price = extractMoney(it.price)
                )
            }
        val grandTotal = parsedOrder.grandTotal ?: extractMoney(payload.total)

        val json = JSONObject().apply {
            put("order_id", orderId)
            put("grand_total", grandTotal ?: JSONObject.NULL)
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("quantity", item.quantity)
                        put("name", item.name)
                        put("price", item.price ?: JSONObject.NULL)
                        if (item.note.isNotBlank()) {
                            put("note", item.note)
                        }
                    })
                }
            })
        }
        return PosBuildResult(
            json = json,
            parsedOrder = parsedOrder
        )
    }

    private fun detectScrapeValidationFailure(
        buildResult: PosBuildResult,
        rawTextCombined: String,
        payload: RelayOrderPayload
    ): String? {
        val parsed = buildResult.parsedOrder
        val parsedItems = parsed.items
        if (parsedItems.isEmpty()) {
            return "no parsed items"
        }
        if (parsed.status == "INCOMPLETE_SCRAPE") {
            return "parser marked incomplete scrape"
        }

        val sourceIsGrab = payload.source.equals("grab", ignoreCase = true) ||
            payload.sourcePackage.contains("grab", ignoreCase = true)
        if (!sourceIsGrab) return null

        val expectedItemCount = ITEM_LINE_PATTERN.findAll(rawTextCombined).count()
        if (expectedItemCount >= 2 && parsedItems.size < expectedItemCount) {
            return "parsed ${parsedItems.size} items but detected about $expectedItemCount item lines"
        }

        if (parsedItems.size == 1) {
            val single = parsedItems.first()
            val singlePrice = single.price ?: 0.0
            val grandTotal = parsed.grandTotal
            val nameLooksLikeOrderId = single.name.contains(parsed.orderId, ignoreCase = true) ||
                single.name.contains(payload.orderId, ignoreCase = true)
            if (nameLooksLikeOrderId && grandTotal != null && grandTotal > (singlePrice + 1.0)) {
                return "single parsed item looks like order-id placeholder (${single.name})"
            }
        }

        return null
    }

    private fun buildFirestoreOrderData(
        payload: RelayOrderPayload,
        parsedOrderJson: JSONObject,
        documentId: String
    ): Map<String, Any?> {
        val rawTextCombined = payload.rawTexts.joinToString("\n").trim()
        return mapOf(
            "document_id" to documentId,
            "order_id" to parsedOrderJson.optString("order_id", payload.orderId),
            "items" to parsedOrderJson.optJSONArray("items").toItemMaps(),
            "grand_total" to parsedOrderJson.nullableDouble("grand_total"),
            "source" to payload.source,
            "source_package" to payload.sourcePackage,
            "raw_text" to rawTextCombined,
            "raw_texts" to payload.rawTexts,
            "scraped_at_epoch_ms" to payload.scrapedAtEpochMs,
            "created_at" to FieldValue.serverTimestamp()
        )
    }

    private fun extractMoney(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val match = MONEY_PATTERN.find(value) ?: return null
        return match.groups[1]?.value
            ?.replace(",", "")
            ?.toDoubleOrNull()
    }

    private fun persistParsedOrderJson(context: Context, parsedOrderJson: String, rawScrapeText: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PARSED_ORDER_JSON, parsedOrderJson)
            .putString(KEY_LAST_RAW_SCRAPE_TEXT, rawScrapeText)
            .apply()

        context.sendBroadcast(
            Intent(ACTION_UPLOAD_STATUS_UPDATED).apply {
                `package` = context.packageName
                putExtra(EXTRA_PARSED_ORDER_JSON, parsedOrderJson)
                putExtra(EXTRA_RAW_SCRAPE_TEXT, rawScrapeText)
            }
        )
    }

    private fun persistUploadStatus(
        context: Context,
        relayUrl: String,
        status: String,
        success: Boolean,
        firestoreOrderId: String?
    ) {
        val timestamp = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPLOAD_URL, relayUrl)
            .putLong(KEY_LAST_UPLOAD_TIME_MS, timestamp)
            .putString(KEY_LAST_UPLOAD_STATUS, status)
            .putBoolean(KEY_LAST_UPLOAD_SUCCESS, success)
            .putString(KEY_LAST_FIRESTORE_ORDER_ID, firestoreOrderId)
            .apply()

        context.sendBroadcast(
            Intent(ACTION_UPLOAD_STATUS_UPDATED).apply {
                `package` = context.packageName
                putExtra(EXTRA_UPLOAD_URL, relayUrl)
                putExtra(EXTRA_UPLOAD_TIME_MS, timestamp)
                putExtra(EXTRA_UPLOAD_STATUS, status)
                putExtra(EXTRA_UPLOAD_SUCCESS, success)
                putExtra(EXTRA_FIRESTORE_ORDER_ID, firestoreOrderId)
                getLastParsedOrderJson(context)?.let {
                    putExtra(EXTRA_PARSED_ORDER_JSON, it)
                }
                getLastRawScrapeText(context)?.let {
                    putExtra(EXTRA_RAW_SCRAPE_TEXT, it)
                }
            }
        )
    }

    private const val PREFS = "relay_prefs"
    private const val KEY_LAST_UPLOAD_URL = "last_upload_url"
    private const val KEY_LAST_UPLOAD_TIME_MS = "last_upload_time_ms"
    private const val KEY_LAST_UPLOAD_STATUS = "last_upload_status"
    private const val KEY_LAST_UPLOAD_SUCCESS = "last_upload_success"
    private const val KEY_LAST_FIRESTORE_ORDER_ID = "last_firestore_order_id"
    private const val KEY_LAST_PARSED_ORDER_JSON = "last_parsed_order_json"
    private const val KEY_LAST_RAW_SCRAPE_TEXT = "last_raw_scrape_text"
    private val ITEM_LINE_PATTERN = Regex("(?i)\\b\\d+\\s*x\\s+")
    private val MONEY_PATTERN = Regex("(?i)(?:RM|MYR)?\\s*(-?\\d+(?:[.,]\\d{2})?)")

    const val ACTION_UPLOAD_STATUS_UPDATED = "com.gastropos.relay.UPLOAD_STATUS_UPDATED"
    const val EXTRA_UPLOAD_URL = "extra_upload_url"
    const val EXTRA_UPLOAD_TIME_MS = "extra_upload_time_ms"
    const val EXTRA_UPLOAD_STATUS = "extra_upload_status"
    const val EXTRA_UPLOAD_SUCCESS = "extra_upload_success"
    const val EXTRA_FIRESTORE_ORDER_ID = "extra_firestore_order_id"
    const val EXTRA_PARSED_ORDER_JSON = "extra_parsed_order_json"
    const val EXTRA_RAW_SCRAPE_TEXT = "extra_raw_scrape_text"

    private fun String.toFirestoreDocumentId(): String? {
        val cleaned = trim()
            .takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
            ?.replace("/", "-")
            ?.take(120)
            ?: return null
        if (cleaned == "." || cleaned == "..") return null
        return cleaned
    }

    private fun JSONArray?.toItemMaps(): List<Map<String, Any?>> {
        if (this == null) return emptyList()
        val items = mutableListOf<Map<String, Any?>>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            items.add(
                mapOf(
                    "quantity" to item.optInt("quantity", 1),
                    "name" to item.optString("name", ""),
                    "price" to item.nullableDouble("price"),
                    "note" to item.optString("note", "").takeIf { it.isNotBlank() }
                )
            )
        }
        return items
    }

    private fun JSONObject.nullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeUnless { it.isNaN() }
    }

    private data class PosBuildResult(
        val json: JSONObject,
        val parsedOrder: ParsedOrderText
    )

    data class ForceUploadResult(
        val success: Boolean,
        val message: String
    )

}
