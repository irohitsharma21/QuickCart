package com.rohit.quickcart

import com.rohit.quickcart.data.local.toDomain
import com.rohit.quickcart.data.remote.fake.FakeCatalog
import com.rohit.quickcart.data.remote.toProductEntities
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.voiceagent.AgentLanguage
import com.rohit.quickcart.domain.voiceagent.AgentNext
import com.rohit.quickcart.domain.voiceagent.CartAction
import com.rohit.quickcart.domain.voiceagent.Pending
import com.rohit.quickcart.domain.voiceagent.PlaceOrder
import com.rohit.quickcart.domain.voiceagent.VoiceOrderAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every conversation path of the voice agent's "brain". Pure Kotlin, no microphone needed. */
class VoiceOrderAgentTest {

    private val products = FakeCatalog.response().toProductEntities().map { it.toDomain() }
    private val byId = products.associateBy { it.id }
    private val agent = VoiceOrderAgent(products, AgentLanguage.ENGLISH)
    private val hindiAgent = VoiceOrderAgent(products, AgentLanguage.HINDI)

    private fun cart(vararg items: Pair<String, Int>) = items.map { (id, q) -> CartLine(byId.getValue(id), q) }

    @Test
    fun `one kilo doodh means one litre, so two 500 ml packs`() {
        val turn = agent.handle("ek kilo doodh", cart(), null)
        assertEquals(listOf(CartAction.Add("milk", 2)), turn.actions)
        assertEquals("Added 2 Toned Milk, 500 ml each. Anything else?", turn.reply)
        assertEquals(AgentNext.CONTINUE, turn.next)
    }

    @Test
    fun `several items in one sentence`() {
        val turn = agent.handle("tamatar aur do chips", cart(), null)
        assertEquals(listOf(CartAction.Add("tomato", 1), CartAction.Add("chips", 2)), turn.actions)
        assertTrue(turn.reply.startsWith("Added 1 Hybrid Tomato and 2 Classic Salted Chips"))
    }

    @Test
    fun `remove an item that is in the cart`() {
        val turn = agent.handle("doodh hata do", cart("milk" to 2, "bread" to 1), null)
        assertEquals(listOf(CartAction.Remove("milk", 2)), turn.actions)
        assertEquals("Removed Toned Milk. Anything else?", turn.reply)
    }

    @Test
    fun `remove something not in the cart`() {
        val turn = agent.handle("remove chips", cart("milk" to 1), null)
        assertTrue(turn.actions.isEmpty())
        assertEquals("That's not in your cart. Anything else?", turn.reply)
    }

    @Test
    fun `asking the total`() {
        val turn = agent.handle("kitna hua?", cart("milk" to 2), null)
        assertTrue(turn.actions.isEmpty())
        assertEquals("So far, 2 items for ₹56. Anything else?", turn.reply)
    }

    private val address = "Flat 12, Sector 62"

    @Test
    fun `that's it - in every spelling speech recognition produces - asks to place the order`() {
        val cart = cart("milk" to 2, "bread" to 1) // ₹101 + ₹25 delivery + ₹4 handling = ₹130
        for (said in listOf("that's it", "That's it.", "that’s it", "thats it", "bas", "that's all", "ho gaya", "nothing else", "thatz it")) {
            val turn = agent.handle(said, cart, null, address)
            assertEquals(said, Pending.ConfirmOrder(address), turn.pending)
            assertTrue(said, turn.actions.isEmpty())
        }
        assertEquals(
            "That's 3 items, ₹130 with delivery. Shall I place the order, cash on delivery, to Flat 12, Sector 62?",
            agent.handle("that's it", cart, null, address).reply,
        )
    }

    @Test
    fun `order all four for me - then yes - places the order`() {
        val cart = cart("milk" to 2, "chips" to 2)
        val ask = agent.handle("order all four for me", cart, null, address)
        assertEquals(Pending.ConfirmOrder(address), ask.pending)

        val yes = agent.handle("haan", cart, ask.pending, address)
        assertEquals(PlaceOrder(address, PaymentMethod.CASH_ON_DELIVERY), yes.placeOrder)
        assertEquals(agent.orderPlaced(), yes.reply)
    }

    @Test
    fun `other ways to say place the order`() {
        val cart = cart("milk" to 1)
        for (said in listOf("order kar do", "place the order", "checkout", "buy it", "go ahead and order everything", "ऑर्डर कर दो")) {
            assertEquals(said, Pending.ConfirmOrder(address), agent.handle(said, cart, null, address).pending)
        }
    }

    @Test
    fun `many ways of saying yes place the order`() {
        val cart = cart("milk" to 1)
        val ask = Pending.ConfirmOrder(address)
        for (said in listOf(
            "yes", "Yes please.", "yeah", "ya", "yess", "ok", "okay", "sure", "of course", "go ahead", "sounds good",
            "haan", "haanji", "ji haan", "theek hai", "bilkul", "chalega", "haan kar do", "kar do na", "हाँ", "ठीक है", "बिल्कुल",
        )) {
            assertEquals(said, PlaceOrder(address, PaymentMethod.CASH_ON_DELIVERY), agent.handle(said, cart, ask, address).placeOrder)
        }
    }

    @Test
    fun `many ways of saying no never place the order`() {
        val cart = cart("milk" to 1)
        val ask = Pending.ConfirmOrder(address)
        for (said in listOf(
            "no", "nope", "no thanks", "not now", "wait", "hold on", "don't place it", "cancel", "nahi", "abhi nahi",
            "rehne do", "mat karo", "theek hai rehne do", "नहीं", "रुको",
        )) {
            val turn = agent.handle(said, cart, ask, address)
            assertNull(said, turn.placeOrder)
            assertEquals(said, AgentNext.OPEN_CART, turn.next)
        }
    }

    @Test
    fun `yes plus a new item confirms and adds both`() {
        val ask = agent.handle("tomoto", cart(), null)
        val turn = agent.handle("haan, aur ek doodh bhi", cart(), ask.pending)
        assertEquals(listOf(CartAction.Add("tomato", 1), CartAction.Add("milk", 1)), turn.actions)
    }

    @Test
    fun `adding an item while answering the order question asks again instead of ordering`() {
        val turn = agent.handle("haan aur ek bread bhi", cart("milk" to 1), Pending.ConfirmOrder(address), address)
        assertNull(turn.placeOrder)
        assertEquals(listOf(CartAction.Add("bread", 1)), turn.actions)
    }

    @Test
    fun `no to the order question opens the cart instead`() {
        val no = agent.handle("no", cart("milk" to 1), Pending.ConfirmOrder(address), address)
        assertEquals(AgentNext.OPEN_CART, no.next)
        assertNull(no.placeOrder)
    }

    @Test
    fun `without a saved address it opens checkout`() {
        val turn = agent.handle("place order", cart("milk" to 1), null, lastAddress = null)
        assertEquals(AgentNext.OPEN_CHECKOUT, turn.next)
        assertNull(turn.pending)
    }

    @Test
    fun `finishing with an empty cart keeps going`() {
        val turn = agent.handle("that's it", cart(), null, address)
        assertEquals(AgentNext.CONTINUE, turn.next)
        assertNull(turn.pending)
    }

    @Test
    fun `naming a product wins over the word order`() {
        // "order" here is part of an add request, not "place the order".
        val turn = agent.handle("I want to order two milk", cart(), null, address)
        assertEquals(listOf(CartAction.Add("milk", 2)), turn.actions)
        assertNull(turn.pending)
    }

    @Test
    fun `gibberish gets help, not a fake not-found`() {
        val turn = agent.handle("hmm what can you do", cart(), null, address)
        assertTrue(turn.actions.isEmpty())
        assertTrue(turn.reply, turn.reply.startsWith("Sorry, I didn't get that"))
    }

    @Test
    fun `unclear word asks did-you-mean, and yes adds it`() {
        val ask = agent.handle("tomoto", cart(), null)
        assertTrue(ask.actions.isEmpty())
        assertEquals("Did you mean Hybrid Tomato?", ask.reply)
        assertNotNull(ask.pending)

        val yes = agent.handle("haan", cart(), ask.pending)
        assertEquals(listOf(CartAction.Add("tomato", 1)), yes.actions)
        assertNull(yes.pending)
        assertTrue(ask.pending is Pending.ConfirmItem)
    }

    @Test
    fun `no to did-you-mean skips it`() {
        val ask = agent.handle("tomoto", cart(), null)
        val no = agent.handle("nahi", cart(), ask.pending)
        assertTrue(no.actions.isEmpty())
        assertEquals("Okay, skipped. Anything else?", no.reply)
    }

    @Test
    fun `a new request instead of yes-no is handled normally`() {
        val ask = agent.handle("tomoto", cart(), null)
        val turn = agent.handle("do bread", cart(), ask.pending)
        assertEquals(listOf(CartAction.Add("bread", 2)), turn.actions)
    }

    @Test
    fun `out of stock and unknown items are explained`() {
        val turn = agent.handle("aam aur lightbulb", cart(), null)
        assertTrue(turn.actions.isEmpty())
        assertTrue(turn.reply, turn.reply.contains("Alphonso Mango is out of stock"))
        assertTrue(turn.reply, turn.reply.contains("couldn't find \"lightbulb\""))
    }

    @Test
    fun `hindi conversation in devanagari`() {
        val turn = hindiAgent.handle("दो दूध", cart(), null)
        assertEquals(listOf(CartAction.Add("milk", 2)), turn.actions)
        assertEquals("ठीक है, 2 Toned Milk, 500 ml जोड़ दिया। और कुछ?", turn.reply)

        val done = hindiAgent.handle("बस", cart("milk" to 2), null, address)
        assertEquals(Pending.ConfirmOrder(address), done.pending)
    }

    @Test
    fun `silence or noise asks again`() {
        assertEquals(agent.didNotHear(), agent.handle("  ", cart(), null).reply)
    }
}
