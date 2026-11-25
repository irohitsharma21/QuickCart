package com.rohit.quickcart.domain

/**
 * Domain models: what the UI and ViewModels work with.
 * They are plain Kotlin and don't depend on Room, Retrofit or Android.
 *
 * Money is always stored in **paise as Long** (₹1 = 100 paise). Double can't represent
 * 0.1 exactly, so adding prices with Double eventually gives answers like ₹45.000000001.
 */

data class Category(
    val id: String,
    val name: String,
    val emoji: String,
)

data class Product(
    val id: String,
    val name: String,
    val categoryId: String,
    val unit: String,          // "500 g", "1 L", "6 pcs"
    val pricePaise: Long,
    val mrpPaise: Long,
    val emoji: String,
    val inStock: Boolean,
    val description: String = "",
) {
    val discountPercent: Int
        get() = if (mrpPaise > pricePaise && mrpPaise > 0) (((mrpPaise - pricePaise) * 100) / mrpPaise).toInt() else 0
}

data class CartLine(
    val product: Product,
    val quantity: Int,
) {
    val lineTotalPaise: Long get() = product.pricePaise * quantity
    val lineSavingsPaise: Long get() = (product.mrpPaise - product.pricePaise).coerceAtLeast(0) * quantity
}

enum class OrderStatus {
    /** Saved on the phone, not yet accepted by the server (e.g. placed while offline). */
    PENDING_SYNC,
    PLACED,
    PACKED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED;

    val isTerminal: Boolean get() = this == DELIVERED || this == CANCELLED

    val label: String
        get() = when (this) {
            PENDING_SYNC -> "Waiting for network"
            PLACED -> "Order placed"
            PACKED -> "Packed"
            OUT_FOR_DELIVERY -> "On the way"
            DELIVERED -> "Delivered"
            CANCELLED -> "Cancelled"
        }
}

enum class PaymentMethod(val label: String) {
    UPI("UPI (simulated)"),
    CASH_ON_DELIVERY("Cash on delivery"),
}

data class OrderLine(
    val productId: String,
    val name: String,
    val emoji: String,
    val unit: String,
    val pricePaise: Long,
    val quantity: Int,
)

data class Order(
    val localId: String,
    val serverId: String?,
    val status: OrderStatus,
    val bill: Bill,
    val itemCount: Int,
    val address: String,
    val paymentMethod: PaymentMethod,
    val etaMinutes: Int?,
    val riderProgress: Float,
    val createdAt: Long,
    val lines: List<OrderLine>,
)

data class Bill(
    val itemTotalPaise: Long,
    val deliveryFeePaise: Long,
    val handlingFeePaise: Long,
    val tipPaise: Long = 0,
) {
    val totalPaise: Long get() = itemTotalPaise + deliveryFeePaise + handlingFeePaise + tipPaise
}
