package com.rohit.quickcart.domain.smartlist

import com.rohit.quickcart.domain.Product
import kotlin.math.ceil
import kotlin.math.max

/** One line of the user's list, understood. */
data class ParsedItem(
    /** The words the user said for this item, e.g. "aadha kilo pyaaz". */
    val raw: String,
    val quantity: Int,
    /** null = we couldn't confidently tell which product they meant. */
    val product: Product?,
    /** 0..1: how sure the match is. Under [GroceryListParser.SURE] the UI asks "did you mean?". */
    val confidence: Float,
    /** Close guesses for unclear items. */
    val suggestions: List<Product> = emptyList(),
) {
    val needsReview: Boolean get() = product == null || confidence < GroceryListParser.SURE
}

/**
 * Turns a spoken or pasted grocery list into products and quantities, on the device.
 *
 *   "do doodh, ek bread aur aadha kilo pyaaz"   -> 2 × Toned Milk, 1 × Bread, 1 × Onion (1 kg)
 *   "दो दूध और एक दर्जन अंडे"                     -> 2 × Toned Milk, 2 × Eggs (6 pcs)
 *   "1. Milk 2 packet\n2. tamatar 1kg"          -> 2 × Toned Milk, 2 × Tomato (500 g)
 *
 * Steps for each item:
 *   1. split the text into items (commas, new lines, "aur" / "and" / "और")
 *   2. pull out the number and unit (digits, English / Hinglish / Hindi number words, kg, packet…)
 *   3. drop filler words ("chahiye", "de do", "please"…)
 *   4. match what's left against product aliases: exact, then contains, then fuzzy
 *      (edit distance, so "tomoto" or "panir" still match)
 *   5. convert the amount to packs using the product's pack size (2 kg of a 1 kg pack = 2)
 *
 * It's rule-based on purpose: instant, offline, free, predictable and unit-tested. Messy input it
 * can't handle is shown to the user for review instead of silently guessed. An LLM fallback for
 * those items would be the next step.
 */
class GroceryListParser(
    private val products: List<Product>,
    aliases: Map<String, List<String>> = ProductAliases.ALIASES,
) {

    private val termsByProduct: Map<Product, List<String>> = products.associateWith { product ->
        val nameWords = product.name.lowercase().split(" ").filter { it.length >= 3 }
        (aliases[product.id].orEmpty() + product.name.lowercase() + nameWords).distinct()
    }

    fun parse(text: String): List<ParsedItem> {
        val items = splitItems(text).mapNotNull(::parseItem)
        // "doodh ... aur ek doodh" -> one line with the quantities added up
        return items
            .groupBy { it.product?.id ?: "?${it.raw}" }
            .map { (_, same) -> same.first().copy(quantity = same.sumOf { it.quantity }.coerceAtMost(MAX_QTY)) }
    }

    // ---- 1. split ----

    private fun splitItems(text: String): List<String> =
        text.lowercase()
            .replace(LIST_NUMBERING, "\n")                 // "1. milk" / "- milk" / "• milk"
            .replace(Regex("""[,;\n।|+&]"""), "\n")
            .replace(Regex("""\s+(aur|and|or|then|phir|और|फिर|plus)\s+"""), "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---- 2-5. one item ----

    private fun parseItem(raw: String): ParsedItem? {
        var text = " $raw "
        FILLER_PHRASES.forEach { text = text.replace(it, " ") }

        val tokens = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        var count: Double? = null
        var unitGrams: Double? = null   // amount converted to g / ml / pcs, when the user gave a unit
        var unitKind: UnitKind? = null

        val rest = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val glued = GLUED_NUMBER_UNIT.matchEntire(token)   // "1kg", "500ml", "2x"
            when {
                glued != null -> {
                    count = glued.groupValues[1].toDouble()
                    UNITS[glued.groupValues[2]]?.let { (kind, factor) -> unitKind = kind; unitGrams = count!! * factor }
                }
                token.toDoubleOrNull() != null -> count = token.toDouble()
                token in NUMBER_WORDS -> count = NUMBER_WORDS.getValue(token)
                token in UNITS -> {
                    val (kind, factor) = UNITS.getValue(token)
                    unitKind = kind
                    unitGrams = (count ?: 1.0) * factor
                }
                token in FILLER_WORDS -> Unit
                else -> rest += token
            }
            i++
        }

        val name = rest.joinToString(" ").trim()
        if (name.isEmpty()) return null

        val ranked = rank(name)
        val best = ranked.firstOrNull()
        val matched = best?.takeIf { it.second >= MATCH }?.first

        val quantity = when {
            matched == null -> (count ?: 1.0).toIntQuantity()
            unitGrams != null && unitKind != UnitKind.PACK -> packsFor(matched, unitGrams!!, unitKind!!) ?: (count ?: 1.0).toIntQuantity()
            else -> (count ?: 1.0).toIntQuantity()
        }

        return ParsedItem(
            raw = raw.trim(),
            quantity = quantity,
            product = matched,
            confidence = best?.second ?: 0f,
            suggestions = if (matched == null || best!!.second < SURE) {
                ranked.filter { it.second >= SUGGEST }.take(3).map { it.first }
            } else {
                emptyList()
            },
        )
    }

    /** Products sorted by how well they match [name], best first. */
    private fun rank(name: String): List<Pair<Product, Float>> =
        termsByProduct.map { (product, terms) -> product to terms.maxOf { score(name, it) } }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }

    private fun score(input: String, term: String): Float {
        if (input == term) return 1f
        val inputWords = input.split(" ")
        val termWords = term.split(" ")
        // "brown bread" contains "bread"; "cold coffee" contains "coffee"
        if (term.length >= 3 && (inputWords.containsAll(termWords) || termWords.containsAll(inputWords))) return 0.92f
        var best = 0f
        val meaningful = inputWords.filter { it.length >= 3 }
        var covered = 0
        for (a in meaningful) {
            var bestForWord = 0f
            for (b in termWords) {
                if (b.length < 3) continue
                bestForWord = max(bestForWord, similarity(a, b) * 0.95f)
            }
            if (bestForWord >= 0.7f) covered++
            best = max(best, bestForWord)
        }
        // Coverage penalty: one fuzzy word out of four ("hmm WHAT can you" ~ "wheat") is weak evidence.
        // A single typo'd word ("tomoto") keeps its full score.
        val coverage = if (meaningful.isEmpty()) 0f else covered.toFloat() / meaningful.size
        return best * (0.7f + 0.3f * coverage)
    }

    /** 2 kg of onions in a "1 kg" pack -> 2. Half a kilo -> still 1 pack. */
    private fun packsFor(product: Product, amount: Double, kind: UnitKind): Int? {
        val pack = PACK_SIZE.find(product.unit.lowercase()) ?: return null
        if (product.unit.contains("x")) return null // multipacks like "4 x 70 g"
        val (packKind, factor) = UNITS[pack.groupValues[2]] ?: return null
        // People say "1 kilo doodh" and mean a litre: treat weight and volume as the same for groceries (≈ water).
        val liquidOrSolid = setOf(UnitKind.WEIGHT, UnitKind.VOLUME)
        if (packKind != kind && !(packKind in liquidOrSolid && kind in liquidOrSolid)) return null
        val packAmount = pack.groupValues[1].toDouble() * factor
        return ceil(amount / packAmount).toInt().coerceIn(1, MAX_QTY)
    }

    private fun Double.toIntQuantity(): Int = ceil(this).toInt().coerceIn(1, MAX_QTY)

    private enum class UnitKind { WEIGHT, VOLUME, PIECES, PACK }

    companion object {
        const val SURE = 0.88f      // at or above: show as matched
        const val MATCH = 0.72f     // at or above: pick it, but ask the user to check
        const val SUGGEST = 0.45f   // at or above: offer as a "did you mean"
        const val MAX_QTY = 10

        /** Normalised edit-distance similarity: 1.0 = identical. */
        fun similarity(a: String, b: String): Float {
            if (a == b) return 1f
            val longest = max(a.length, b.length)
            return if (longest == 0) 1f else 1f - levenshtein(a, b).toFloat() / longest
        }

        /** Classic dynamic-programming edit distance, two rows. O(n·m) time, O(m) memory. */
        fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = curr; curr = t
            }
            return prev[b.length]
        }

        private val LIST_NUMBERING = Regex("""(^|\n)\s*(\d+[.)]|[-•*])\s+""")
        private val GLUED_NUMBER_UNIT = Regex("""(\d+(?:\.\d+)?)(kg|kgs|g|gm|gms|ml|l|ltr|x)""")
        private val PACK_SIZE = Regex("""(\d+(?:\.\d+)?)\s*(kg|g|l|ml|pcs)""")

        private val NUMBER_WORDS: Map<String, Double> = mapOf(
            // English
            "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
            "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "half" to 0.5,
            // Hinglish
            "ek" to 1.0, "do" to 2.0, "teen" to 3.0, "char" to 4.0, "chaar" to 4.0, "paanch" to 5.0, "panch" to 5.0,
            "chhe" to 6.0, "che" to 6.0, "saat" to 7.0, "aath" to 8.0, "nau" to 9.0, "das" to 10.0,
            "aadha" to 0.5, "adha" to 0.5, "aadhi" to 0.5, "dedh" to 1.5, "dhai" to 2.5,
            // Devanagari
            "एक" to 1.0, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0, "पांच" to 5.0, "पाँच" to 5.0, "छह" to 6.0,
            "सात" to 7.0, "आठ" to 8.0, "नौ" to 9.0, "दस" to 10.0, "आधा" to 0.5, "आधी" to 0.5, "डेढ़" to 1.5,
        )

        /** unit word -> (kind, factor to grams / ml / pieces) */
        private val UNITS: Map<String, Pair<UnitKind, Double>> = mapOf(
            "kg" to (UnitKind.WEIGHT to 1000.0), "kgs" to (UnitKind.WEIGHT to 1000.0), "kilo" to (UnitKind.WEIGHT to 1000.0),
            "किलो" to (UnitKind.WEIGHT to 1000.0), "g" to (UnitKind.WEIGHT to 1.0), "gm" to (UnitKind.WEIGHT to 1.0),
            "gms" to (UnitKind.WEIGHT to 1.0), "gram" to (UnitKind.WEIGHT to 1.0), "grams" to (UnitKind.WEIGHT to 1.0), "ग्राम" to (UnitKind.WEIGHT to 1.0),
            "l" to (UnitKind.VOLUME to 1000.0), "ltr" to (UnitKind.VOLUME to 1000.0), "litre" to (UnitKind.VOLUME to 1000.0),
            "liter" to (UnitKind.VOLUME to 1000.0), "लीटर" to (UnitKind.VOLUME to 1000.0), "ml" to (UnitKind.VOLUME to 1.0),
            "dozen" to (UnitKind.PIECES to 12.0), "darjan" to (UnitKind.PIECES to 12.0), "दर्जन" to (UnitKind.PIECES to 12.0),
            "pcs" to (UnitKind.PIECES to 1.0), "pc" to (UnitKind.PIECES to 1.0), "piece" to (UnitKind.PIECES to 1.0), "pieces" to (UnitKind.PIECES to 1.0),
            "packet" to (UnitKind.PACK to 1.0), "packets" to (UnitKind.PACK to 1.0), "pkt" to (UnitKind.PACK to 1.0),
            "pack" to (UnitKind.PACK to 1.0), "packs" to (UnitKind.PACK to 1.0), "bottle" to (UnitKind.PACK to 1.0),
            "bottles" to (UnitKind.PACK to 1.0), "dabba" to (UnitKind.PACK to 1.0), "पैकेट" to (UnitKind.PACK to 1.0), "x" to (UnitKind.PACK to 1.0),
        )

        private val FILLER_WORDS = setOf(
            "wala", "wali", "wale", "ka", "ki", "ke", "chahiye", "chaiye", "please", "pls", "bhi", "mujhe", "hume", "humko",
            "some", "of", "get", "buy", "need", "want", "i", "the", "also", "add", "order", "lao", "lana", "dena",
            // common English words in spoken requests that must never fuzzy-match a product ("what" ~ "wheat")
            "what", "can", "you", "would", "like", "give", "have", "show", "me", "my", "is", "are", "a", "for", "and",
            "with", "it", "this", "that", "to", "hmm", "umm", "uh", "please", "kya", "hai", "aur", "bhi", "toh",
            "चाहिए", "भी", "मुझे", "का", "की", "के", "वाला", "वाली",
        )

        /** Multi-word fillers removed before tokenising ("de do" must go before "do" is read as 2). */
        private val FILLER_PHRASES = listOf(
            Regex("""\s(de|la|le)\s?do\s"""), Regex("""\sdedo\s"""), Regex("""\sले\s?आओ\s"""),
            Regex("""\s(दे|ला)\s?दो\s"""), Regex("""\sमंगा\s?दो\s"""),
        )
    }
}
