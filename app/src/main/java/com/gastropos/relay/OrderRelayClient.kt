package com.gastropos.relay

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
    private const val RELAY_URL = "https://external-order-receiver-npmjzo5oca-as.a.run.app"
    private const val TAG = "OrderRelayClient"
    private const val USER_AGENT = "GastroPos-Relay-v1"
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Enqueues JSON serialization and OkHttp on a dedicated worker thread.
     * Safe to call from the main thread; no network or heavy work runs on the caller.
     */
    fun send(payload: RelayOrderPayload) {
        executor.execute {
            check(Looper.getMainLooper().thread !== Thread.currentThread()) {
                "Relay upload must not run on the main thread"
            }
            try {
                val json = try {
                    JSONObject().apply {
                        put("source", payload.source)
                        put("source_package", payload.sourcePackage)
                        put("order_id", payload.orderId)
                        put("total", payload.total ?: JSONObject.NULL)
                        put("scraped_at_epoch_ms", payload.scrapedAtEpochMs)
                        put("raw_texts", JSONArray(payload.rawTexts))
                        put("items", JSONArray().apply {
                            payload.items.forEach { item ->
                                put(JSONObject().apply {
                                    put("name", item.name)
                                    put("quantity", item.quantity ?: JSONObject.NULL)
                                    put("price", item.price ?: JSONObject.NULL)
                                })
                            }
                        })
                    }
                } catch (jsonError: Exception) {
                    Log.e(
                        TAG,
                        "Failed to build JSON for order ${payload.orderId}: ${jsonError.message}",
                        jsonError
                    )
                    return@execute
                }

                val request = Request.Builder()
                    .url(RELAY_URL)
                    .addHeader("User-Agent", USER_AGENT)
                    .post(
                        json.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()

                var lastError: Throwable? = null
                for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
                    try {
                        client.newCall(request).execute().use { response: Response ->
                            if (response.isSuccessful) {
                                if (response.code == 200) {
                                    Log.i(TAG, "200 OK received for order ${payload.orderId} (attempt=$attempt)")
                                } else {
                                    Log.i(TAG, "Upload success for order ${payload.orderId} (attempt=$attempt)")
                                }
                                return@execute
                            }

                            val responseBody = response.body?.string().orEmpty()
                            Log.e(
                                TAG,
                                "Upload failed for ${payload.orderId}: HTTP ${response.code}, attempt=$attempt, body=$responseBody"
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
            } catch (error: Exception) {
                Log.e(TAG, "Failed to upload order payload", error)
            }
        }
    }

    private const val MAX_UPLOAD_ATTEMPTS = 3
    private val RETRY_DELAYS_MS = longArrayOf(1500L, 3000L)
}
