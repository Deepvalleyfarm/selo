package selo.Nachipa01.domain

/** Selonachipa taxonomy (matches functions/src/listings.ts CATEGORIES) and on-device label → category mapping. */
object Taxonomy {
    val CATEGORIES = listOf("Fresh produce", "Groceries", "Fast food", "Electronics", "Phones & accessories", "Fashion", "Beauty", "Home",
        "Furniture", "Hardware", "Building materials", "Agriculture", "Baby", "Vehicles & parts", "Services", "Other")
    val CONDITIONS = listOf("New", "Like new", "Good", "Fair", "For parts")
    val AVAILABILITY = listOf("In Stock", "Made to Order", "Last Units")
    val PROVINCES = listOf("Lusaka", "Copperbelt", "Central", "Eastern", "Luapula", "Muchinga", "Northern", "North-Western", "Southern", "Western")
    val AGE_BANDS = listOf("18–24", "25–34", "35–44", "45–54", "55+")

    private val labelMap = listOf(
        listOf("vegetable", "fruit", "tomato", "food", "produce", "plant", "leaf", "flower") to "Fresh produce",
        listOf("mobile phone", "cell phone", "phone", "smartphone") to "Phones & accessories",
        listOf("computer", "laptop", "television", "speaker", "electronic", "gadget", "camera", "headphone") to "Electronics",
        listOf("shirt", "dress", "clothing", "jeans", "shoe", "textile", "fabric", "bag", "hat") to "Fashion",
        listOf("cosmetic", "lipstick", "perfume", "cream") to "Beauty",
        listOf("table", "chair", "couch", "sofa", "bed", "furniture", "desk", "cabinet") to "Furniture",
        listOf("tool", "hammer", "drill", "wrench") to "Hardware",
        listOf("brick", "cement", "tile", "wood", "roof") to "Building materials",
        listOf("chicken", "goat", "cattle", "bird", "animal", "seed") to "Agriculture",
        listOf("baby", "toy", "stroller") to "Baby",
        listOf("car", "tire", "wheel", "vehicle", "motorcycle", "bicycle") to "Vehicles & parts",
        listOf("bread", "pizza", "fast food", "burger", "meal", "dish", "cuisine") to "Fast food",
        listOf("bottle", "packaged", "sugar", "rice", "flour", "drink") to "Groceries",
    )

    /** On-device category suggestion from ML Kit image labels (instant, before the cloud LLM answers). */
    fun fromLabel(label: String): String? = labelMap.firstOrNull { (keys, _) -> keys.any { label.contains(it, ignoreCase = true) } }?.second
    fun fromLabels(labels: List<Pair<String, Float>>): String? = labels.sortedByDescending { it.second }.firstNotNullOfOrNull { fromLabel(it.first) }
}
