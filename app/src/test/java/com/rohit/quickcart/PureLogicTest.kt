package com.rohit.quickcart

import com.rohit.quickcart.data.realtime.Backoff
import com.rohit.quickcart.data.remote.fake.OrderTimeline
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.util.formatRupees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PureLogicTest {

    private fun product(pricePaise: Long, mrpPaise: Long = pricePaise) =
        Product("p", "P", "c", "1 pc", pricePaise, mrpPaise, "🍎", inStock = true)

    @Test
    fun `below threshold pays delivery and handling`() {
        val bill = PricingRules.bill(listOf(CartLine(product(50_00), 2)))
        assertEquals(100_00, bill.itemTotalPaise)
        assertEquals(PricingRules.DELIVERY_FEE_PAISE, bill.deliveryFeePaise)
        assertEquals(100_00 + 25_00 + 4_00, bill.totalPaise)
        assertEquals(99_00, PricingRules.amountForFreeDelivery(bill.itemTotalPaise))
    }

    @Test
    fun `at threshold delivery is free`() {
        val bill = PricingRules.bill(listOf(CartLine(product(199_00), 1)))
        assertEquals(0, bill.deliveryFeePaise)
        assertEquals(0, PricingRules.amountForFreeDelivery(bill.itemTotalPaise))
    }

    @Test
    fun `empty cart costs nothing`() {
        assertEquals(0, PricingRules.bill(emptyList()).totalPaise)
    }

    @Test
    fun `discount percent rounds down`() {
        assertEquals(16, product(42_00, 50_00).discountPercent)
        assertEquals(0, product(28_00, 28_00).discountPercent)
    }

    @Test
    fun `paise format as rupees`() {
        assertEquals("₹45", 4500L.formatRupees())
        assertEquals("₹45.50", 4550L.formatRupees())
        assertEquals("₹0.05", 5L.formatRupees())
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val noJitter = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.5 // jitter factor (0.5*2-1) = 0
        }
        assertEquals(1_000, Backoff.delayMillis(0, noJitter))
        assertEquals(2_000, Backoff.delayMillis(1, noJitter))
        assertEquals(8_000, Backoff.delayMillis(3, noJitter))
        assertEquals(Backoff.MAX_MS, Backoff.delayMillis(50, noJitter))
        repeat(100) {
            val d = Backoff.delayMillis(2)
            assertTrue("jitter keeps 4s within ±20%: $d", d in 3_200..4_800)
        }
    }

    @Test
    fun `order timeline moves forward and ends delivered`() {
        assertEquals("PLACED", OrderTimeline.at("x", 0).status)
        assertEquals(10, OrderTimeline.at("x", 0).etaMinutes)
        assertEquals("PACKED", OrderTimeline.at("x", 12_000).status)
        val midway = OrderTimeline.at("x", 45_000)
        assertEquals("OUT_FOR_DELIVERY", midway.status)
        assertEquals(0.5f, midway.riderProgress, 0.01f)
        assertEquals("DELIVERED", OrderTimeline.at("x", 70_000).status)
    }
}
