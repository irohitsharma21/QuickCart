package com.rohit.quickcart.data.remote.fake

import com.rohit.quickcart.data.remote.CatalogResponse
import com.rohit.quickcart.data.remote.CategoryDto
import com.rohit.quickcart.data.remote.ProductDto

/** Catalog served by the in-app fake backend. The FastAPI server in /server serves the same data. */
object FakeCatalog {

    private val categories = listOf(
        CategoryDto("fruits-veg", "Fruits & Vegetables", "🥦"),
        CategoryDto("dairy", "Dairy, Bread & Eggs", "🥛"),
        CategoryDto("snacks", "Snacks & Munchies", "🍿"),
        CategoryDto("drinks", "Cold Drinks & Juices", "🧃"),
        CategoryDto("staples", "Atta, Rice & Dal", "🌾"),
        CategoryDto("home", "Cleaning & Home", "🧼"),
    )

    private fun p(id: String, name: String, cat: String, unit: String, price: Int, mrp: Int, emoji: String, description: String, inStock: Boolean = true) =
        ProductDto(id, name, cat, unit, price * 100L, mrp * 100L, emoji, inStock, description)

    private val products = listOf(
        p("banana", "Robusta Banana", "fruits-veg", "6 pcs", 42, 50, "🍌", "Naturally ripened, sweet and soft. Great for breakfast and shakes."),
        p("tomato", "Hybrid Tomato", "fruits-veg", "500 g", 24, 30, "🍅", "Firm, juicy tomatoes for curries, salads and chutneys."),
        p("onion", "Onion", "fruits-veg", "1 kg", 38, 45, "🧅", "Everyday red onions. Store in a cool, dry place."),
        p("potato", "Potato", "fruits-veg", "1 kg", 32, 40, "🥔", "All-purpose potatoes for sabzi, fries and parathas."),
        p("apple", "Shimla Apple", "fruits-veg", "4 pcs", 129, 160, "🍎", "Crisp and sweet apples from Himachal orchards."),
        p("carrot", "Orange Carrot", "fruits-veg", "500 g", 35, 42, "🥕", "Crunchy carrots for halwa, salads and juices."),
        p("grapes", "Green Grapes", "fruits-veg", "500 g", 79, 99, "🍇", "Seedless, sweet and washed. Keep refrigerated."),
        p("mango", "Alphonso Mango", "fruits-veg", "2 pcs", 199, 240, "🥭", "The king of mangoes. Back in season soon.", inStock = false),

        p("milk", "Toned Milk", "dairy", "500 ml", 28, 28, "🥛", "Pasteurised toned milk, 3% fat. Boil before use."),
        p("bread", "Whole Wheat Bread", "dairy", "400 g", 45, 50, "🍞", "Soft whole wheat bread, no maida. Best within 3 days."),
        p("eggs", "Farm Eggs", "dairy", "6 pcs", 54, 60, "🥚", "Fresh brown eggs from cage-free farms."),
        p("butter", "Salted Butter", "dairy", "100 g", 58, 60, "🧈", "Creamy salted butter for toast and parathas."),
        p("paneer", "Fresh Paneer", "dairy", "200 g", 89, 99, "🧀", "Soft, fresh malai paneer. Use within 2 days."),
        p("curd", "Fresh Curd", "dairy", "400 g", 35, 40, "🥣", "Thick set curd made from toned milk."),

        p("chips", "Classic Salted Chips", "snacks", "52 g", 20, 20, "🥔", "Thin, crispy potato chips with a pinch of salt."),
        p("popcorn", "Butter Popcorn", "snacks", "90 g", 45, 55, "🍿", "Ready-to-eat buttery popcorn for movie night."),
        p("cookies", "Choco Chip Cookies", "snacks", "150 g", 55, 65, "🍪", "Crunchy cookies loaded with chocolate chips."),
        p("chocolate", "Dark Chocolate", "snacks", "80 g", 99, 120, "🍫", "55% cocoa dark chocolate bar."),
        p("peanuts", "Roasted Peanuts", "snacks", "200 g", 60, 75, "🥜", "Salted roasted peanuts, a protein-rich snack."),
        p("noodles", "Instant Noodles", "snacks", "4 x 70 g", 56, 60, "🍜", "Ready in 2 minutes. Masala tastemaker inside."),

        p("cola", "Cola", "drinks", "750 ml", 40, 45, "🥤", "Chilled fizzy cola. Best served cold."),
        p("juice", "Mixed Fruit Juice", "drinks", "1 L", 99, 120, "🧃", "No added preservatives. Refrigerate after opening."),
        p("coconut", "Tender Coconut Water", "drinks", "200 ml", 45, 50, "🥥", "Natural electrolytes, no added sugar."),
        p("coffee", "Cold Coffee", "drinks", "180 ml", 35, 40, "☕", "Ready-to-drink cold coffee with real milk."),
        p("greentea", "Green Tea Bags", "drinks", "25 pcs", 145, 175, "🍵", "Light, refreshing green tea. Zero calories."),
        p("lemonade", "Lemon Soda", "drinks", "300 ml", 25, 30, "🍋", "Tangy lemon soda with a hint of mint."),

        p("rice", "Basmati Rice", "staples", "1 kg", 115, 140, "🍚", "Long-grain aged basmati. Fluffy and fragrant."),
        p("atta", "Whole Wheat Atta", "staples", "5 kg", 249, 290, "🌾", "Stone-ground chakki atta for soft rotis."),
        p("dal", "Toor Dal", "staples", "1 kg", 165, 180, "🫘", "Unpolished arhar dal, high in protein."),
        p("salt", "Iodised Salt", "staples", "1 kg", 25, 28, "🧂", "Free-flowing iodised salt."),
        p("oil", "Sunflower Oil", "staples", "1 L", 155, 175, "🫗", "Refined sunflower oil, light for everyday cooking."),
        p("sugar", "Sugar", "staples", "1 kg", 48, 55, "🍬", "Fine crystal sugar."),

        p("soap", "Bathing Soap", "home", "3 x 100 g", 99, 120, "🧼", "Moisturising soap with glycerin."),
        p("tissue", "Tissue Roll", "home", "4 rolls", 120, 150, "🧻", "Soft 2-ply kitchen and toilet rolls."),
        p("sponge", "Scrub Sponge", "home", "3 pcs", 49, 60, "🧽", "Dual-sided scrub for utensils."),
        p("detergent", "Liquid Detergent", "home", "1 L", 189, 230, "🧴", "For top and front load machines."),
        p("dishwash", "Dishwash Gel", "home", "500 ml", 105, 120, "🫧", "Cuts grease fast, gentle on hands."),
        p("garbagebag", "Garbage Bags", "home", "30 pcs", 89, 110, "🗑️", "Medium size, leak-proof bags."),
    )

    fun response() = CatalogResponse(categories, products)

    fun productIds(): Set<String> = products.mapTo(HashSet()) { it.id }
}
