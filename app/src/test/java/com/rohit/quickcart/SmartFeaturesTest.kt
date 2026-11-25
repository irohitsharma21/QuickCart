package com.rohit.quickcart

import com.rohit.quickcart.data.remote.fake.FakeCatalog
import com.rohit.quickcart.data.remote.toProductEntities
import com.rohit.quickcart.data.local.toDomain
import com.rohit.quickcart.domain.Bill
import com.rohit.quickcart.domain.Order
import com.rohit.quickcart.domain.OrderLine
import com.rohit.quickcart.domain.OrderStatus
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.ReorderPredictor
import com.rohit.quickcart.domain.smartlist.GroceryListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two "smart" features: the Hinglish list parser and the reorder predictor. Pure Kotlin, no Android. */
class SmartFeaturesTest {

    private val products = FakeCatalog.response().toProductEntities().map { it.toDomain() }
    private val parser = GroceryListParser(products)

    private fun parse(text: String) = parser.parse(text).map { (it.product?.id ?: "?") to it.quantity }

    // ---------- GroceryListParser ----------

    @Test
    fun `hinglish sentence with aur and number words`() {
        assertEquals(
            listOf("milk" to 2, "bread" to 1, "onion" to 1),
            parse("do doodh, ek bread aur aadha kilo pyaaz"),
        )
    }

    @Test
    fun `hindi in devanagari, with dozen converted to packs of 6`() {
        assertEquals(listOf("milk" to 2, "eggs" to 2), parse("दो दूध और एक दर्जन अंडे"))
    }

    @Test
    fun `pasted whatsapp list with numbering, digits and units`() {
        val text = "1. Milk 2 packet\n2. tamatar 1kg\n3. Atta"
        // 1 kg of tomatoes in 500 g packs = 2 packs
        assertEquals(listOf("milk" to 2, "tomato" to 2, "atta" to 1), parse(text))
    }

    @Test
    fun `filler words are ignored, and 'de do' is not read as the number two`() {
        assertEquals(listOf("sugar" to 1), parse("mujhe cheeni chahiye please de do"))
    }

    @Test
    fun `typos still match but are flagged for review`() {
        val item = parser.parse("tomoto").single()
        assertEquals("tomato", item.product?.id)
        assertTrue(item.needsReview)
    }

    @Test
    fun `unknown items are not guessed`() {
        val item = parser.parse("2 lightbulbs").single()
        assertNull(item.product)
        assertTrue(item.needsReview)
    }

    @Test
    fun `same product twice is merged`() {
        assertEquals(listOf("milk" to 3), parse("2 doodh aur ek milk"))
    }

    @Test
    fun `multi-word names and aliases`() {
        assertEquals(listOf("cola" to 2, "coffee" to 1), parse("2 cold drink, cold coffee"))
    }

    @Test
    fun `quantity is capped`() {
        assertEquals(listOf("banana" to GroceryListParser.MAX_QTY), parse("50 kela"))
    }

    @Test
    fun `kilo of a liquid is read as litres`() {
        assertEquals(listOf("milk" to 2), parse("1 kilo doodh"))   // 500 ml packs
    }

    @Test
    fun `edit distance`() {
        assertEquals(3, GroceryListParser.levenshtein("kitten", "sitting"))
        assertEquals(0, GroceryListParser.levenshtein("doodh", "doodh"))
    }

    // ---------- ReorderPredictor ----------

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun order(daysAgo: Int, vararg productIds: String, status: OrderStatus = OrderStatus.DELIVERED) = Order(
        localId = "o$daysAgo", serverId = null, status = status, bill = Bill(0, 0, 0), itemCount = productIds.size,
        address = "", paymentMethod = PaymentMethod.UPI, etaMinutes = null, riderProgress = 1f,
        createdAt = now - daysAgo * day, lines = productIds.map { OrderLine(it, it, "", "", 0, 1) },
    )

    @Test
    fun `item bought every 2 days is due 2 days after the last purchase`() {
        val orders = listOf(order(6, "milk"), order(4, "milk"), order(2, "milk"))
        val suggestion = ReorderPredictor.suggest(orders, now).single()
        assertEquals("milk", suggestion.productId)
        assertTrue(suggestion.isPrediction)
        assertEquals("You buy this every ~2 days", suggestion.reason)
    }

    @Test
    fun `item not due yet is not suggested`() {
        // Bought every 10 days, last one 2 days ago -> not running low.
        val orders = listOf(order(22, "atta"), order(12, "atta"), order(2, "atta"))
        assertTrue(ReorderPredictor.suggest(orders, now).none { it.isPrediction })
    }

    @Test
    fun `items bought once come back as buy-again, after predictions`() {
        val orders = listOf(order(6, "milk"), order(4, "milk", "chips"), order(2, "milk"))
        val result = ReorderPredictor.suggest(orders, now)
        assertEquals(listOf("milk", "chips"), result.map { it.productId })
        assertEquals(false, result[1].isPrediction)
    }

    @Test
    fun `cancelled orders don't count`() {
        val orders = listOf(order(6, "milk"), order(4, "milk", status = OrderStatus.CANCELLED), order(2, "milk"))
        // Gaps are then 4 days (6 -> 2), so milk isn't due yet (only 2 of 4 days passed).
        assertTrue(ReorderPredictor.suggest(orders, now).none { it.isPrediction })
    }
}
