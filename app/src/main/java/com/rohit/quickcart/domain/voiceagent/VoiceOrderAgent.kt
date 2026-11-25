package com.rohit.quickcart.domain.voiceagent

import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.domain.smartlist.GroceryListParser
import com.rohit.quickcart.domain.smartlist.ParsedItem
import com.rohit.quickcart.util.formatRupees

enum class AgentLanguage(val speechTag: String) {
    ENGLISH("en-IN"), // understands English + Hinglish, replies in English
    HINDI("hi-IN"),   // understands Hindi (Devanagari) + Hinglish, replies in Hindi
}

/** Something the agent wants done to the cart. The ViewModel applies it. */
sealed interface CartAction {
    data class Add(val productId: String, val quantity: Int) : CartAction
    data class Remove(val productId: String, val quantity: Int) : CartAction
}

/** The agent asked "Did you mean X?" and is waiting for yes / no. */
data class PendingConfirmation(val product: Product, val quantity: Int)

/** One agent turn: what to say, what to do, and what happens next. */
data class AgentTurn(
    val reply: String,
    val actions: List<CartAction> = emptyList(),
    val pending: PendingConfirmation? = null,
    /** true = conversation finished, go to the cart. */
    val done: Boolean = false,
)

/**
 * The "brain" of the voice ordering agent: understands one user utterance and decides the reply.
 *
 * Pure Kotlin, no Android, so every conversation path is unit-tested. Speech-to-text and
 * text-to-speech are separate adapters (see AndroidSpeechIo), so they can be swapped (e.g. for a
 * streaming cloud STT, or an LLM for the language understanding) without touching this class.
 *
 * Intents, checked in this order:
 *   1. answer to a pending "Did you mean …?"  (haan / yes / nahi / no)
 *   2. DONE      "bas", "that's all", "checkout", "ho gaya"
 *   3. TOTAL     "kitna hua", "total", "how much"
 *   4. REMOVE    "doodh hata do", "remove chips"
 *   5. ADD       everything else goes through GroceryListParser
 */
class VoiceOrderAgent(
    products: List<Product>,
    private val language: AgentLanguage,
) {
    private val parser = GroceryListParser(products)
    private val hindi = language == AgentLanguage.HINDI

    fun greeting(): String =
        if (hindi) "नमस्ते! क्या मँगवाना है? जैसे, दो दूध और एक ब्रेड।"
        else "Hi! What would you like to order? For example, two milk and one bread."

    fun didNotHear(): String =
        if (hindi) "माफ़ कीजिए, सुनाई नहीं दिया। फिर से बोलिए?" else "Sorry, I didn't catch that. Could you say it again?"

    fun handle(utterance: String, cart: List<CartLine>, pending: PendingConfirmation?): AgentTurn {
        val text = " ${utterance.lowercase().trim()} "
        if (text.isBlank()) return AgentTurn(didNotHear(), pending = pending)

        // 1. Yes / no for "Did you mean …?"
        if (pending != null) {
            when {
                YES.any { text.containsWord(it) } -> {
                    val added = listOf(pending.product to pending.quantity)
                    return AgentTurn(addedReply(added), actions = listOf(CartAction.Add(pending.product.id, pending.quantity)))
                }
                NO.any { text.containsWord(it) } && text.trim().split(" ").size <= 3 ->
                    return AgentTurn(if (hindi) "ठीक है, छोड़ दिया। और कुछ?" else "Okay, skipped. Anything else?")
                // Anything else: treat it as a new request and drop the question.
            }
        }

        // 2. Finished
        if (DONE.any { text.containsWord(it) }) {
            if (cart.isEmpty()) {
                return AgentTurn(if (hindi) "आपकी कार्ट अभी खाली है। क्या मँगवाना है?" else "Your cart is still empty. What would you like?")
            }
            val summary = summary(cart)
            return AgentTurn(
                if (hindi) "बढ़िया! $summary। कार्ट खोल रहे हैं।" else "Done! $summary. Opening your cart.",
                done = true,
            )
        }

        // 3. Total so far
        if (TOTAL.any { text.containsWord(it) }) {
            return AgentTurn(
                if (cart.isEmpty()) (if (hindi) "कार्ट अभी खाली है।" else "Your cart is empty so far.")
                else (if (hindi) "अभी तक ${summary(cart)}। और कुछ?" else "So far, ${summary(cart)}. Anything else?"),
            )
        }

        // 4. Remove
        if (REMOVE.any { text.containsWord(it) }) {
            var rest = text
            (REMOVE + FILLER_AFTER_REMOVE).forEach { rest = rest.replace(Regex("""\s$it\s"""), " ") }
            val inCart = cart.associateBy { it.product.id }
            val removed = parser.parse(rest).mapNotNull { item -> item.product?.let { inCart[it.id] } }
            if (removed.isEmpty()) {
                return AgentTurn(if (hindi) "वो आपकी कार्ट में नहीं है। और कुछ?" else "That's not in your cart. Anything else?")
            }
            val names = removed.joinToString(if (hindi) " और " else " and ") { it.product.name }
            return AgentTurn(
                if (hindi) "$names हटा दिया। और कुछ?" else "Removed $names. Anything else?",
                actions = removed.map { CartAction.Remove(it.product.id, it.quantity) },
            )
        }

        // 5. Add
        val items = parser.parse(utterance)
        if (items.isEmpty()) return AgentTurn(didNotHear())
        return addTurn(items)
    }

    private fun addTurn(items: List<ParsedItem>): AgentTurn {
        val sure = items.filter { it.product != null && !it.needsReview && it.product.inStock }
        val outOfStock = items.mapNotNull { it.product }.filter { !it.inStock }
        val unsure = items.firstOrNull { it.product != null && it.needsReview && it.product.inStock }
        val unknown = items.filter { it.product == null }

        val parts = mutableListOf<String>()
        if (sure.isNotEmpty()) parts += addedReply(sure.map { it.product!! to it.quantity }, withQuestion = false)
        outOfStock.forEach { parts += if (hindi) "${it.name} अभी स्टॉक में नहीं है" else "Sorry, ${it.name} is out of stock" }
        unknown.forEach { item ->
            val guess = item.suggestions.firstOrNull()
            parts += when {
                guess != null && hindi -> "\"${item.raw}\" नहीं मिला"
                guess != null -> "I couldn't find \"${item.raw}\""
                hindi -> "\"${item.raw}\" नहीं मिला"
                else -> "I couldn't find \"${item.raw}\""
            }
        }

        val actions = sure.map { CartAction.Add(it.product!!.id, it.quantity) }
        if (unsure != null) {
            val p = unsure.product!!
            parts += if (hindi) "क्या आपका मतलब ${p.name} था?" else "Did you mean ${p.name}?"
            return AgentTurn(parts.joinToString(". "), actions, pending = PendingConfirmation(p, unsure.quantity))
        }
        parts += if (hindi) "और कुछ?" else "Anything else?"
        return AgentTurn(parts.joinToString(". ").replace(". और कुछ?", "। और कुछ?"), actions)
    }

    private fun addedReply(added: List<Pair<Product, Int>>, withQuestion: Boolean = true): String {
        val list = added.joinToString(if (hindi) " और " else " and ") { (p, q) ->
            if (added.size == 1) "$q ${p.name}, ${p.unit}${if (q > 1 && !hindi) " each" else ""}" else "$q ${p.name}"
        }
        val core = if (hindi) "ठीक है, $list जोड़ दिया" else "Added $list"
        return if (!withQuestion) core else core + if (hindi) "। और कुछ?" else ". Anything else?"
    }

    private fun summary(cart: List<CartLine>): String {
        val count = cart.sumOf { it.quantity }
        val total = PricingRules.bill(cart).itemTotalPaise.formatRupees()
        return if (hindi) "$count आइटम, कुल $total" else "$count items for $total"
    }

    private fun String.containsWord(word: String) = contains(" $word ")

    private companion object {
        val YES = listOf("yes", "yeah", "yup", "haan", "han", "ha", "ji", "haan ji", "correct", "right", "sahi", "हाँ", "हां", "जी", "सही")
        val NO = listOf("no", "nope", "nahi", "nahin", "na", "galat", "wrong", "नहीं", "ना", "गलत")
        val DONE = listOf(
            "bas", "that's all", "thats all", "that is all", "done", "checkout", "check out", "ho gaya", "hogaya",
            "nothing else", "kuch nahi", "kuch nahin", "finish", "बस", "हो गया", "कुछ नहीं",
        )
        val TOTAL = listOf("kitna", "kitne", "total", "how much", "bill", "कितना", "कितने", "टोटल")
        val REMOVE = listOf("hata", "hatao", "hata do", "remove", "nikal", "nikalo", "delete", "cancel", "हटा", "हटाओ", "हटा दो", "निकाल", "निकालो")
        val FILLER_AFTER_REMOVE = listOf("do", "dena", "दो", "please")
    }
}
