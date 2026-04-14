package com.gastropos.relay

data class OrderItem(
    val name: String,
    val quantity: Int?,
    val price: String?
)

data class RelayOrderPayload(
    val source: String,
    val sourcePackage: String,
    val orderId: String,
    val total: String?,
    val items: List<OrderItem>,
    val rawTexts: List<String>,
    val scrapedAtEpochMs: Long
)
