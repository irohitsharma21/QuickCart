package com.rohit.quickcart.domain.smartlist

/**
 * What people actually call products: English, Hindi written in English letters (Hinglish),
 * and Hindi in Devanagari, because speech recognition returns either depending on the language.
 *
 * In production this list would come from the server and grow from real search logs
 * (searches that ended in "add to cart" are free training data).
 */
object ProductAliases {
    val ALIASES: Map<String, List<String>> = mapOf(
        "banana" to listOf("banana", "bananas", "kela", "kele", "केला", "केले"),
        "tomato" to listOf("tomato", "tomatoes", "tamatar", "टमाटर"),
        "onion" to listOf("onion", "onions", "pyaaz", "pyaz", "pyaj", "kanda", "प्याज", "प्याज़"),
        "potato" to listOf("potato", "potatoes", "aloo", "alu", "आलू"),
        "apple" to listOf("apple", "apples", "seb", "सेब"),
        "carrot" to listOf("carrot", "carrots", "gajar", "गाजर"),
        "grapes" to listOf("grapes", "grape", "angoor", "angur", "अंगूर"),
        "mango" to listOf("mango", "mangoes", "aam", "आम"),
        "milk" to listOf("milk", "doodh", "dudh", "दूध"),
        "bread" to listOf("bread", "double roti", "pav", "ब्रेड", "डबल रोटी"),
        "eggs" to listOf("egg", "eggs", "anda", "ande", "अंडा", "अंडे"),
        "butter" to listOf("butter", "makhan", "makkhan", "मक्खन", "बटर"),
        "paneer" to listOf("paneer", "panir", "cottage cheese", "पनीर"),
        "curd" to listOf("curd", "dahi", "yogurt", "दही"),
        "chips" to listOf("chips", "wafers", "चिप्स"),
        "popcorn" to listOf("popcorn", "पॉपकॉर्न"),
        "cookies" to listOf("cookies", "cookie", "biscuit", "biscuits", "बिस्कुट"),
        "chocolate" to listOf("chocolate", "chocolates", "चॉकलेट"),
        "peanuts" to listOf("peanuts", "peanut", "moongfali", "mungfali", "मूंगफली"),
        "noodles" to listOf("noodles", "noodle", "नूडल्स"),
        "cola" to listOf("cola", "cold drink", "soft drink", "कोल्ड ड्रिंक"),
        "juice" to listOf("juice", "जूस"),
        "coconut" to listOf("coconut water", "nariyal pani", "nariyal", "नारियल पानी", "नारियल"),
        "coffee" to listOf("coffee", "cold coffee", "कॉफी"),
        "greentea" to listOf("green tea", "tea", "chai", "चाय"),
        "lemonade" to listOf("lemon soda", "nimbu soda", "lemonade", "nimbu", "नींबू सोडा"),
        "rice" to listOf("rice", "chawal", "चावल"),
        "atta" to listOf("atta", "aata", "flour", "wheat flour", "आटा"),
        "dal" to listOf("dal", "daal", "toor dal", "arhar", "दाल"),
        "salt" to listOf("salt", "namak", "नमक"),
        "oil" to listOf("oil", "tel", "cooking oil", "तेल"),
        "sugar" to listOf("sugar", "cheeni", "chini", "shakkar", "चीनी"),
        "soap" to listOf("soap", "sabun", "साबुन"),
        "tissue" to listOf("tissue", "tissues", "toilet paper", "टिश्यू"),
        "sponge" to listOf("sponge", "scrubber", "स्पंज"),
        "detergent" to listOf("detergent", "washing liquid", "डिटर्जेंट"),
        "dishwash" to listOf("dishwash", "dish wash", "bartan soap", "बर्तन साबुन"),
        "garbagebag" to listOf("garbage bag", "garbage bags", "dustbin bag", "kachra bag", "कचरा बैग"),
    )
}
