package com.gastropos.relay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object OrderRelayClient {
    private const val RELAY_URL = "https://desapetaling-53381.web.app/"
    private const val TAG = "OrderRelayClient"
    private const val USER_AGENT = "GastroPos-Relay-v1"
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Enqueues JSON serialization and OkHttp on a dedicated worker thread.
     * Safe to call from the main thread; no network or heavy work runs on the caller.
     */
    fun send(context: Context, payload: RelayOrderPayload) {
        val appContext = context.applicationContext
        executor.execute {
            check(Looper.getMainLooper().thread !== Thread.currentThread()) {
                "Relay upload must not run on the main thread"
            }
            try {
                val json = try {
                    buildPosOrderJson(payload)
                } catch (jsonError: Exception) {
                    Log.e(
                        TAG,
                        "Failed to build JSON for order ${payload.orderId}: ${jsonError.message}",
                        jsonError
                    )
                    persistUploadStatus(
                        appContext,
                        status = "JSON build failed: ${jsonError.message ?: "unknown error"}",
                        success = false,
                        firestoreOrderId = null
                    )
                    return@execute
                }
                persistParsedOrderJson(appContext, json.toString(2))

                val request = Request.Builder()
                    .url(RELAY_URL)
                    .addHeader("User-Agent", USER_AGENT)
                    .post(
                        json.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()

                var lastError: Throwable? = null
                var lastFailureStatus: String? = null
                for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
                    try {
                        client.newCall(request).execute().use { response: Response ->
                            val responseBody = response.body?.string().orEmpty()
                            if (response.isSuccessful) {
                                val parsedMeta = parseResponseMeta(responseBody)
                                val statusText = if (response.code == 200) "HTTP 200 OK" else "HTTP ${response.code}"
                                if (response.code == 200) {
                                    Log.i(TAG, "200 OK received for order ${payload.orderId} (attempt=$attempt)")
                                } else {
                                    Log.i(TAG, "Upload success for order ${payload.orderId} (attempt=$attempt)")
                                }
                                val confirmedSaved = parsedMeta.firestoreOrderId != null ||
                                    parsedMeta.ok == true
                                val composedStatus = buildString {
                                    append("$statusText (order ${payload.orderId})")
                                    parsedMeta.firestoreOrderId?.let {
                                        append(" | firestore_order_id: $it")
                                    }
                                    parsedMeta.ok?.let {
                                        append(" | ok: $it")
                                    }
                                    if (!confirmedSaved) {
                                        append(" | warning: save not confirmed by response")
                                    }
                                    if (responseBody.isNotBlank()) {
                                        append(" | response: ")
                                        append(responseBody.compactForStatus(260))
                                    }
                                }
                                persistUploadStatus(
                                    appContext,
                                    status = composedStatus,
                                    success = confirmedSaved,
                                    firestoreOrderId = parsedMeta.firestoreOrderId
                                )
                                if (confirmedSaved) return@execute
                                lastFailureStatus = composedStatus
                                lastError = IllegalStateException("Save not confirmed by response")
                            }

                            Log.e(
                                TAG,
                                "Upload failed for ${payload.orderId}: HTTP ${response.code}, attempt=$attempt, body=$responseBody"
                            )
                            lastFailureStatus =
                                buildString {
                                    append("HTTP ${response.code} (attempt $attempt, order ${payload.orderId})")
                                    if (responseBody.isNotBlank()) {
                                        append(" | response: ")
                                        append(responseBody.compactForStatus(220))
                                    }
                                }
                            persistUploadStatus(
                                appContext,
                                status = lastFailureStatus!!,
                                success = false,
                                firestoreOrderId = null
                            )
                            lastError = IllegalStateException("HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(
                            TAG,
                            "Network failure uploading order ${payload.orderId} on attempt=$attempt: ${e.message}",
                            e
                        )
                        lastFailureStatus = "Network error (attempt $attempt): ${e.message ?: "unknown error"}"
                        persistUploadStatus(
                            appContext,
                            status = lastFailureStatus!!,
                            success = false,
                            firestoreOrderId = null
                        )
                    }

                    if (attempt < MAX_UPLOAD_ATTEMPTS) {
                        try {
                            Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                Log.e(TAG, "All upload attempts failed for order ${payload.orderId}", lastError)
                persistUploadStatus(
                    appContext,
                    status = buildString {
                        append("All upload attempts failed for order ${payload.orderId}")
                        lastFailureStatus?.let { append(" | last failure: $it") }
                        lastError?.message?.let { append(" | error: $it") }
                    },
                    success = false,
                    firestoreOrderId = null
                )
            } catch (error: Exception) {
                Log.e(TAG, "Failed to upload order payload", error)
                persistUploadStatus(
                    appContext,
                    status = "Unexpected error: ${error.message ?: "unknown error"}",
                    success = false,
                    firestoreOrderId = null
                )
            }
        }
    }

    fun recordScrapeFailure(context: Context, reason: String) {
        persistUploadStatus(
            context = context.applicationContext,
            status = "Scrape failed before upload: $reason",
            success = false,
            firestoreOrderId = null
        )
    }

    fun getRelayUrl(): String = RELAY_URL

    fun getLastParsedOrderJson(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PARSED_ORDER_JSON, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun recordParsedOrderPreview(context: Context, payload: RelayOrderPayload) {
        val parsedOrderJson = try {
            buildPosOrderJson(payload).toString(2)
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
        persistParsedOrderJson(context.applicationContext, parsedOrderJson)
    }

    private fun buildPosOrderJson(payload: RelayOrderPayload): JSONObject {
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

        return JSONObject().apply {
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
    }

    private fun extractMoney(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val match = MONEY_PATTERN.find(value) ?: return null
        return match.groups[1]?.value
            ?.replace(",", "")
            ?.toDoubleOrNull()
    }

    private fun persistParsedOrderJson(context: Context, parsedOrderJson: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PARSED_ORDER_JSON, parsedOrderJson)
            .apply()

        context.sendBroadcast(
            Intent(ACTION_UPLOAD_STATUS_UPDATED).apply {
                `package` = context.packageName
                putExtra(EXTRA_PARSED_ORDER_JSON, parsedOrderJson)
            }
        )
    }

    private fun persistUploadStatus(
        context: Context,
        status: String,
        success: Boolean,
        firestoreOrderId: String?
    ) {
        val timestamp = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPLOAD_URL, RELAY_URL)
            .putLong(KEY_LAST_UPLOAD_TIME_MS, timestamp)
            .putString(KEY_LAST_UPLOAD_STATUS, status)
            .putBoolean(KEY_LAST_UPLOAD_SUCCESS, success)
            .putString(KEY_LAST_FIRESTORE_ORDER_ID, firestoreOrderId)
            .apply()

        context.sendBroadcast(
            Intent(ACTION_UPLOAD_STATUS_UPDATED).apply {
                `package` = context.packageName
                putExtra(EXTRA_UPLOAD_URL, RELAY_URL)
                putExtra(EXTRA_UPLOAD_TIME_MS, timestamp)
                putExtra(EXTRA_UPLOAD_STATUS, status)
                putExtra(EXTRA_UPLOAD_SUCCESS, success)
                putExtra(EXTRA_FIRESTORE_ORDER_ID, firestoreOrderId)
                getLastParsedOrderJson(context)?.let {
                    putExtra(EXTRA_PARSED_ORDER_JSON, it)
                }
            }
        )
    }

    private const val MAX_UPLOAD_ATTEMPTS = 4
    private val RETRY_DELAYS_MS = longArrayOf(2000L, 4000L, 7000L)
    private const val PREFS = "relay_prefs"
    private const val KEY_LAST_UPLOAD_URL = "last_upload_url"
    private const val KEY_LAST_UPLOAD_TIME_MS = "last_upload_time_ms"
    private const val KEY_LAST_UPLOAD_STATUS = "last_upload_status"
    private const val KEY_LAST_UPLOAD_SUCCESS = "last_upload_success"
    private const val KEY_LAST_FIRESTORE_ORDER_ID = "last_firestore_order_id"
    private const val KEY_LAST_PARSED_ORDER_JSON = "last_parsed_order_json"
    private val MONEY_PATTERN = Regex("(?i)(?:RM|MYR)?\\s*(-?\\d+(?:[.,]\\d{2})?)")

    const val ACTION_UPLOAD_STATUS_UPDATED = "com.gastropos.relay.UPLOAD_STATUS_UPDATED"
    const val EXTRA_UPLOAD_URL = "extra_upload_url"
    const val EXTRA_UPLOAD_TIME_MS = "extra_upload_time_ms"
    const val EXTRA_UPLOAD_STATUS = "extra_upload_status"
    const val EXTRA_UPLOAD_SUCCESS = "extra_upload_success"
    const val EXTRA_FIRESTORE_ORDER_ID = "extra_firestore_order_id"
    const val EXTRA_PARSED_ORDER_JSON = "extra_parsed_order_json"

    private data class ResponseMeta(
        val ok: Boolean?,
        val firestoreOrderId: String?
    )

    private fun parseResponseMeta(responseBody: String): ResponseMeta {
        if (responseBody.isBlank()) return ResponseMeta(ok = null, firestoreOrderId = null)
        return try {
            val json = JSONObject(responseBody)
            ResponseMeta(
                ok = if (json.has("ok")) json.optBoolean("ok") else null,
                firestoreOrderId = json.optString("firestore_order_id", null)
            )
        } catch (_: Exception) {
            ResponseMeta(ok = null, firestoreOrderId = null)
        }
    }

    private fun String.compactForStatus(maxLength: Int): String {
        val singleLine = replace("\\s+".toRegex(), " ").trim()
        return if (singleLine.length <= maxLength) {
            singleLine
        } else {
            singleLine.take(maxLength - 3) + "..."
        }
    }
}
