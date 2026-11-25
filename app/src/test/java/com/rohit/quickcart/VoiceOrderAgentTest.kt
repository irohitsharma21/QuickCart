package com.rohit.quickcart

import com.rohit.quickcart.data.local.toDomain
import com.rohit.quickcart.data.remote.fake.FakeCatalog
import com.rohit.quickcart.data.remote.toProductEntities
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.voiceagent.AgentLanguage
import com.rohit.quickcart.domain.voiceagent.CartAction
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
        assertFalse(turn.done)
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

    @Test
    fun `bas finishes the conversation and opens the cart`() {
        val turn = agent.handle("bas", cart("milk" to 2, "bread" to 1), null)
        assertTrue(turn.done)
        assertEquals("Done! 3 items for ₹101. Opening your cart.", turn.reply)
    }

    @Test
    fun `bas with an empty cart keeps going`() {
        val turn = agent.handle("that's all", cart(), null)
        assertFalse(turn.done)
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

        val done = hindiAgent.handle("बस", cart("milk" to 2), null)
        assertTrue(done.done)
    }

    @Test
    fun `silence or noise asks again`() {
        assertEquals(agent.didNotHear(), agent.handle("  ", cart(), null).reply)
    }
}
