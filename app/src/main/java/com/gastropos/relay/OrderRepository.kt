package com.gastropos.relay

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

object OrderRepository {
    private const val PREFS = "relay_prefs"
    private const val KEY_LAST_ORDER_ID = "last_order_id"
    private val pendingOrders = ConcurrentHashMap<String, PendingOrder>()
    private val processedOrderIds = ConcurrentHashMap.newKeySet<String>()

    data class PendingOrder(
        val source: String,
        val sourcePackage: String,
        val orderId: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun addPending(order: PendingOrder): Boolean {
        pendingOrders[order.sourcePackage] = order
        return true
    }

    fun getPending(sourcePackage: String): PendingOrder? = pendingOrders[sourcePackage]

    fun isAlreadyProcessed(context: Context, orderId: String): Boolean {
        if (processedOrderIds.contains(orderId)) return true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ORDER_ID, null)
        return saved == orderId
    }

    fun markProcessed(context: Context, orderId: String) {
        processedOrderIds.add(orderId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ORDER_ID, orderId)
            .apply()
    }
}
