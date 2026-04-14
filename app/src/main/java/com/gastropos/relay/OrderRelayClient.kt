package com.gastropos.relay

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
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

    fun send(payload: RelayOrderPayload) {
        executor.execute {
            try {
                val json = JSONObject().apply {
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

                val request = Request.Builder()
                    .url(RELAY_URL)
                    .header("User-Agent", USER_AGENT)
                    .post(
                        json.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        Log.e(
                            TAG,
                            "Network failure uploading order ${payload.orderId} to $RELAY_URL: ${e.message}",
                            e
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!it.isSuccessful) {
                                val responseBody = it.body?.string().orEmpty()
                                Log.e(
                                    TAG,
                                    "Upload failed for ${payload.orderId}: HTTP ${it.code}, body=$responseBody"
                                )
                            } else {
                                Log.i(TAG, "Upload success for order ${payload.orderId}")
                            }
                        }
                    }
                })
            } catch (error: Exception) {
                Log.e(TAG, "Failed to upload order payload", error)
            }
        }
    }
}
