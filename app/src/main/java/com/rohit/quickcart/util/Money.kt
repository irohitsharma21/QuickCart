package com.rohit.quickcart.util

/** 4550 paise -> "₹45.50", 4500 paise -> "₹45". */
fun Long.formatRupees(): String {
    val rupees = this / 100
    val paise = this % 100
    return if (paise == 0L) "₹$rupees" else "₹$rupees.${paise.toString().padStart(2, '0')}"
}
