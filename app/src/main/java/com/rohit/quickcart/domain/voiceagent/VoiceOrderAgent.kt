package com.rohit.quickcart.domain.voiceagent

import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.PaymentMethod
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

/** A question the agent asked and is waiting for a yes / no to. */
sealed interface Pending {
    /** "Did you mean Hybrid Tomato?" */
    data class ConfirmItem(val product: Product, val quantity: Int) : Pending
    /** "Shall I place it, cash on delivery, to <address>?" */
    data class ConfirmOrder(val address: String) : Pending
}

/** What the screen should do after the agent finishes speaking. */
enum class AgentNext { CONTINUE, OPEN_CART, OPEN_CHECKOUT }

/** Place a real order (only ever sent after the user said yes to [Pending.ConfirmOrder]). */
data class PlaceOrder(val address: String, val paymentMethod: PaymentMethod)

/** One agent turn: what to say, what to do, and what happens next. */
data class AgentTurn(
    val reply: String,
    val actions: List<CartAction> = emptyList(),
    val pending: Pending? = null,
    val next: AgentNext = AgentNext.CONTINUE,
    val placeOrder: PlaceOrder? = null,
)

/**
 * The "brain" of the voice ordering agent: understands one user utterance and decides the reply.
 *
 * Pure Kotlin, no Android, so every conversation path is unit-tested. Speech-to-text and
 * text-to-speech are separate adapters (see AndroidSpeechIo), so they can be swapped (e.g. for a
 * streaming cloud STT, or an LLM for the language understanding) without touching this class.
 *
 * How one utterance is understood:
 *   0. normalise: lowercase, remove apostrophes and punctuation ("That's it." -> "thats it")
 *   1. a pending yes/no question is answered first ("haan", "no")
 *   2. REMOVE if a remove word is present ("doodh hata do")
 *   3. if the sentence names products -> ADD them (products win over commands, so
 *      "I want to order milk" adds milk instead of placing an order)
 *   4. otherwise it's a command, matched against example phrases (exact, then fuzzy):
 *      PLACE_ORDER ("order all four", "order kar do"), DONE ("that's it", "bas"), TOTAL ("kitna hua")
 *   5. nothing matched -> say so and suggest what to say (never guess)
 *
 * Placing an order costs money, so it always needs one spoken "yes" to a question that repeats the
 * total, the payment method and the address.
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

    fun orderPlaced(): String =
        if (hindi) "ऑर्डर हो गया! लगभग 10 मिनट में पहुँचेगा।" else "Order placed! It'll reach you in about 10 minutes."

    fun handle(utterance: String, cart: List<CartLine>, pending: Pending?, lastAddress: String? = null): AgentTurn {
        val text = normalize(utterance)
        if (text.isBlank()) return AgentTurn(didNotHear(), pending = pending)

        // 1. Answer to the question we asked last turn.
        if (pending != null) {
            val answer = yesOrNo(text)
            // Products mentioned in the same breath: "haan, aur ek doodh bhi" / "nahi, potato chahiye".
            val pendingId = (pending as? Pending.ConfirmItem)?.product?.id
            val extra = parser.parse(text).filter { it.product != null && it.product.id != pendingId }
            when (pending) {
                is Pending.ConfirmItem -> when (answer) {
                    Answer.YES -> {
                        val confirmed = CartAction.Add(pending.product.id, pending.quantity)
                        if (extra.isEmpty()) {
                            return AgentTurn(addedReply(listOf(pending.product to pending.quantity)), actions = listOf(confirmed))
                        }
                        val more = addTurn(extra)
                        return more.copy(
                            reply = addedReply(listOf(pending.product to pending.quantity), withQuestion = false) + ". " + more.reply,
                            actions = listOf(confirmed) + more.actions,
                        )
                    }
                    Answer.NO -> {
                        if (extra.isNotEmpty()) return addTurn(extra)
                        return AgentTurn(if (hindi) "ठीक है, छोड़ दिया। और कुछ?" else "Okay, skipped. Anything else?")
                    }
                    Answer.NONE -> Unit
                }
                is Pending.ConfirmOrder -> when (answer) {
                    // Money: if they add something while answering, add it and ask again with the new total.
                    Answer.YES -> if (extra.isEmpty()) {
                        return AgentTurn(
                            reply = orderPlaced(),
                            placeOrder = PlaceOrder(pending.address, PaymentMethod.CASH_ON_DELIVERY),
                        )
                    }
                    Answer.NO -> if (extra.isEmpty()) {
                        return AgentTurn(
                            if (hindi) "ठीक है, कार्ट खोल रहे हैं। वहाँ से बदल सकते हैं।" else "No problem. Opening your cart so you can review it.",
                            next = AgentNext.OPEN_CART,
                        )
                    }
                    Answer.NONE -> Unit
                }
            }
            // Neither yes nor no (or a new item): treat it as a new request and drop the question.
        }

        // 2. Remove
        if (matches(text, REMOVE)) return removeTurn(text, cart)

        // 3. Products named -> add them. Commands are only considered when no product is mentioned.
        val items = parser.parse(text)
        val mentionsProducts = items.any { it.product != null }
        if (mentionsProducts) return addTurn(items)

        // 4. Commands
        when {
            matches(text, PLACE_ORDER) -> return orderTurn(cart, lastAddress)
            matches(text, DONE) -> return orderTurn(cart, lastAddress)
            matches(text, TOTAL) -> return AgentTurn(
                if (cart.isEmpty()) (if (hindi) "कार्ट अभी खाली है।" else "Your cart is empty so far.")
                else (if (hindi) "अभी तक ${summary(cart)}। और कुछ?" else "So far, ${summary(cart)}. Anything else?"),
            )
        }

        // 5. Not understood. If a short phrase came genuinely close to a product, ask about it;
        //    otherwise give examples. ("what can you do" must not become "Did you mean Whole Wheat Bread?")
        val nearMiss = items.firstOrNull { it.suggestions.isNotEmpty() && it.confidence >= 0.6f && it.raw.split(" ").size <= 2 }
        if (nearMiss != null) {
            val guess = nearMiss.suggestions.first()
            return AgentTurn(
                if (hindi) "क्या आपका मतलब ${guess.name} था?" else "Did you mean ${guess.name}?",
                pending = Pending.ConfirmItem(guess, nearMiss.quantity),
            )
        }
        return AgentTurn(
            if (hindi) "माफ़ कीजिए, समझ नहीं आया। आप कह सकते हैं: दो दूध, कितना हुआ, या ऑर्डर कर दो।"
            else "Sorry, I didn't get that. You can say an item like \"two milk\", ask \"how much\", or say \"place the order\".",
        )
    }

    /** "That's it" / "order all four": confirm total, payment and address, then wait for yes. */
    private fun orderTurn(cart: List<CartLine>, lastAddress: String?): AgentTurn {
        if (cart.isEmpty()) {
            return AgentTurn(if (hindi) "आपकी कार्ट अभी खाली है। क्या मँगवाना है?" else "Your cart is still empty. What would you like?")
        }
        val total = PricingRules.bill(cart).totalPaise.formatRupees()
        val count = cart.sumOf { it.quantity }
        if (lastAddress.isNullOrBlank()) {
            return AgentTurn(
                if (hindi) "$count आइटम, कुल $total। पता डालने के लिए चेकआउट खोल रहे हैं।"
                else "That's $count items, $total with delivery. Opening checkout so you can add your address.",
                next = AgentNext.OPEN_CHECKOUT,
            )
        }
        return AgentTurn(
            if (hindi) "$count आइटम, कुल $total। $lastAddress पर, कैश ऑन डिलीवरी से ऑर्डर कर दूँ?"
            else "That's $count items, $total with delivery. Shall I place the order, cash on delivery, to $lastAddress?",
            pending = Pending.ConfirmOrder(lastAddress),
        )
    }

    private fun removeTurn(text: String, cart: List<CartLine>): AgentTurn {
        var rest = " $text "
        (REMOVE + FILLER_AFTER_REMOVE).forEach { rest = rest.replace(" $it ", " ") }
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

    private fun addTurn(items: List<ParsedItem>): AgentTurn {
        val sure = items.filter { it.product != null && !it.needsReview && it.product.inStock }
        val outOfStock = items.mapNotNull { it.product }.filter { !it.inStock }
        val unsure = items.firstOrNull { it.product != null && it.needsReview && it.product.inStock }
        val unknown = items.filter { it.product == null }

        val parts = mutableListOf<String>()
        if (sure.isNotEmpty()) parts += addedReply(sure.map { it.product!! to it.quantity }, withQuestion = false)
        outOfStock.forEach { parts += if (hindi) "${it.name} अभी स्टॉक में नहीं है" else "Sorry, ${it.name} is out of stock" }
        unknown.forEach { parts += if (hindi) "\"${it.raw}\" नहीं मिला" else "I couldn't find \"${it.raw}\"" }

        val actions = sure.map { CartAction.Add(it.product!!.id, it.quantity) }
        if (unsure != null) {
            val p = unsure.product!!
            parts += if (hindi) "क्या आपका मतलब ${p.name} था?" else "Did you mean ${p.name}?"
            return AgentTurn(parts.joinToString(". "), actions, pending = Pending.ConfirmItem(p, unsure.quantity))
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

    private enum class Answer { YES, NO, NONE }

    /**
     * NO is checked first: "don't place it" contains "place it", and with money involved a wrong
     * "no" (we just open the cart) is far cheaper than a wrong "yes" (an order nobody wanted).
     */
    private fun yesOrNo(text: String): Answer = when {
        matches(text, NO) -> Answer.NO
        matches(text, YES) -> Answer.YES
        else -> Answer.NONE
    }

    companion object {
        /** "That's it." / "that’s it" / "thats it" -> "thats it" */
        fun normalize(text: String): String =
            text.lowercase()
                .replace(Regex("""['’`]"""), "")
                .replace(Regex("""[.,!?;:"()।]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

        /**
         * True if the utterance contains one of the phrases as whole words, or is a near-miss of one
         * (edit-distance similarity ≥ 0.8 over the whole short utterance, e.g. "thatz it", "order kardo").
         */
        fun matches(text: String, phrases: List<String>): Boolean {
            val padded = " $text "
            if (phrases.any { padded.contains(" $it ") }) return true
            if (text.split(" ").size > 6) return false // fuzzy only for short commands
            return phrases.any { GroceryListParser.similarity(text, it) >= 0.8f }
        }

        private fun List<String>.norm() = map(::normalize).distinct()

        val YES = listOf(
            // English, incl. how Indian-English speech often comes out of the recognizer
            "yes", "yeah", "yea", "yah", "ya", "yep", "yup", "yess", "ok", "okay", "okie", "k", "sure", "of course",
            "absolutely", "definitely", "correct", "right", "thats right", "exactly", "alright", "all right", "fine",
            "sounds good", "perfect", "great", "go ahead", "go for it", "why not", "please do", "yes please",
            "do it", "place it", "confirm", "confirmed", "proceed", "uh huh", "mm hmm",
            // Hinglish
            "haan", "haa", "han", "ha", "haanji", "haan ji", "ji", "ji haan", "ji ha", "sahi", "sahi hai", "theek hai",
            "thik hai", "theek", "bilkul", "zaroor", "jarur", "chalega", "chalo", "kar do", "kardo", "haan kar do",
            "haan bhai", "ha bhai", "ok kar do", "done",
            // Devanagari
            "हाँ", "हां", "हाँ जी", "जी", "जी हाँ", "सही", "सही है", "ठीक है", "बिल्कुल", "ज़रूर", "जरूर", "चलेगा", "कर दो", "ओके",
        ).norm()
        val NO = listOf(
            // English
            "no", "nope", "nah", "no thanks", "no thank you", "not now", "not yet", "dont", "do not", "dont place",
            "wait", "hold on", "stop", "cancel", "never mind", "nevermind", "wrong", "not that", "later",
            // Hinglish ("na" alone is left out: "kar do na" means "please do it")
            "nahi", "nahin", "nai", "galat", "mat karo", "mat", "ruko", "rehne do", "rehnedo", "abhi nahi",
            "nahi chahiye", "na bhai", "mat mangao", "cancel karo",
            // Devanagari
            "नहीं", "गलत", "मत करो", "मत", "रुको", "रहने दो", "अभी नहीं", "नहीं चाहिए",
        ).norm()
        val DONE = listOf(
            "that's it", "that is it", "that's all", "that is all", "bas", "bas itna", "itna hi", "bas itna hi", "ho gaya",
            "hogaya", "done", "im done", "i am done", "nothing else", "nothing more", "no more", "thats everything",
            "kuch nahi", "kuch aur nahi", "aur kuch nahi", "finish", "finished",
            "बस", "बस इतना", "इतना ही", "हो गया", "कुछ नहीं", "और कुछ नहीं",
        ).norm()
        val PLACE_ORDER = listOf(
            "place order", "place the order", "place my order", "order it", "order them", "order all", "order everything",
            "order all of them", "order these", "order now", "order kar do", "order kardo", "order karo", "order de do",
            "confirm order", "confirm the order", "checkout", "check out", "buy it", "buy now", "buy them", "proceed",
            "mangwa do", "mangva do", "mangao", "bhej do",
            "ऑर्डर कर दो", "ऑर्डर करो", "ऑर्डर कर दीजिए", "मंगवा दो", "भेज दो",
        ).norm()
        val TOTAL = listOf(
            "kitna", "kitna hua", "kitne", "kitne ka", "total", "how much", "bill", "whats the total", "what is the total",
            "कितना", "कितना हुआ", "कितने", "टोटल",
        ).norm()
        val REMOVE = listOf(
            "hata", "hatao", "hata do", "remove", "nikal", "nikalo", "delete", "cancel", "हटा", "हटाओ", "हटा दो", "निकाल", "निकालो",
        ).norm()
        private val FILLER_AFTER_REMOVE = listOf("do", "dena", "दो", "please")
    }
}
