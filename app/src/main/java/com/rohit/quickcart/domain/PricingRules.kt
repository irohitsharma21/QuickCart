package com.rohit.quickcart.domain

/**
 * Bill calculation. It's a pure function (no Android, no I/O), so it's trivial to unit test.
 * A real app would get these numbers from the server. Here they're constants.
 */
object PricingRules {
    const val FREE_DELIVERY_THRESHOLD_PAISE = 199_00L   // ₹199
    const val DELIVERY_FEE_PAISE = 25_00L               // ₹25
    const val HANDLING_FEE_PAISE = 4_00L                // ₹4
    const val MAX_QUANTITY_PER_ITEM = 10

    val TIP_OPTIONS_PAISE = listOf(0L, 10_00L, 20_00L, 30_00L)

    fun bill(lines: List<CartLine>, tipPaise: Long = 0): Bill {
        val itemTotal = lines.sumOf { it.lineTotalPaise }
        if (itemTotal == 0L) return Bill(0, 0, 0)
        val delivery = if (itemTotal >= FREE_DELIVERY_THRESHOLD_PAISE) 0L else DELIVERY_FEE_PAISE
        return Bill(itemTotal, delivery, HANDLING_FEE_PAISE, tipPaise.coerceAtLeast(0))
    }

    /** MRP minus selling price, over the whole cart: the "You saved ₹X" number. */
    fun savings(lines: List<CartLine>): Long = lines.sumOf { it.lineSavingsPaise }

    /** 0.0 .. 1.0, for the "free delivery" progress bar. */
    fun freeDeliveryProgress(itemTotalPaise: Long): Float =
        (itemTotalPaise.toFloat() / FREE_DELIVERY_THRESHOLD_PAISE).coerceIn(0f, 1f)

    /** How much more the user must add to get free delivery (0 if already free). */
    fun amountForFreeDelivery(itemTotalPaise: Long): Long =
        (FREE_DELIVERY_THRESHOLD_PAISE - itemTotalPaise).coerceAtLeast(0)
}
