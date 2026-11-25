package selo.Nachipa01.domain

/** Selonachipa taxonomy (matches functions/src/listings.ts CATEGORIES) and on-device label → category mapping. */
object Taxonomy {
    val CATEGORIES = listOf("Fresh produce", "Groceries", "Fast food", "Electronics", "Phones & accessories", "Fashion", "Beauty", "Home",
        "Furniture", "Hardware", "Building materials", "Agriculture", "Baby", "Vehicles & parts", "Services", "Other")
    val CONDITIONS = listOf("New", "Like new", "Good", "Fair", "For parts")
    val AVAILABILITY = listOf("In Stock", "Made to Order", "Last Units")
    val PROVINCES = listOf("Lusaka", "Copperbelt", "Central", "Eastern", "Luapula", "Muchinga", "Northern", "North-Western", "Southern", "Western")
    val AGE_BANDS = listOf("18–24", "25–34", "35–44", "45–54", "55+")
    /** Main districts per province, used for Local boost targeting. */
    val DISTRICTS = mapOf(
        "Lusaka" to listOf("Lusaka", "Chilanga", "Chongwe", "Kafue", "Rufunsa", "Luangwa"),
        "Copperbelt" to listOf("Ndola", "Kitwe", "Chingola", "Mufulira", "Luanshya", "Kalulushi", "Chililabombwe"),
        "Central" to listOf("Kabwe", "Kapiri Mposhi", "Mkushi", "Serenje", "Mumbwa", "Chibombo"),
        "Eastern" to listOf("Chipata", "Petauke", "Katete", "Lundazi", "Chadiza"),
        "Luapula" to listOf("Mansa", "Kawambwa", "Nchelenge", "Samfya"),
        "Muchinga" to listOf("Chinsali", "Mpika", "Nakonde", "Isoka"),
        "Northern" to listOf("Kasama", "Mbala", "Mpulungu", "Luwingu"),
        "North-Western" to listOf("Solwezi", "Kasempa", "Mwinilunga", "Zambezi"),
        "Southern" to listOf("Livingstone", "Choma", "Mazabuka", "Monze", "Kalomo"),
        "Western" to listOf("Mongu", "Senanga", "Kaoma", "Sesheke"),
    )

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
