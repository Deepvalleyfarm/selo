package selo.Nachipa01.data.model

enum class Role(val label: String) { BUYER("Buyer"), SELLER("Seller"), AGENT("Agent"), RIDER("Rider");
    companion object { fun from(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: BUYER }
}

enum class AccountStatus { ACTIVE, PENDING_KYC, PENDING_VERIFICATION, SUSPENDED;
    companion object { fun from(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: ACTIVE }
}

enum class OrderState {
    INITIATED, ESCROW_LOCKED, BROADCASTING, RIDER_ASSIGNED, PICKED_UP, IN_TRANSIT,
    DELIVERED, REFUNDED, CANCELLED, PAYOUT_PENDING_RETRY;
    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: INITIATED }
}

data class SessionUser(
    val uid: String,
    val role: Role,
    val firstName: String,
    val fullName: String,
    val msisdn: String,
    val status: AccountStatus,
    val area: String = "",
    val storeName: String = "",
    val vehicle: String = "",
    val memberSince: String = "",
)

/** Mirrors platformConfig/default in Firestore. Defaults match PRD v6.0 section 8.4. */
data class PlatformConfig(
    val flatPlatformFeeKwacha: Double = 5.0,
    val buyerServiceFeePct: Double = 0.0,
    val riderPlatformFeePct: Double = 0.0,
    val socialFundPct: Double = 2.0,
    val socialFundMilestoneDeliveries: Int = 20,
    val deliveryFeePerKm: Double = 5.0,
    val deliveryBaseFee: Double = 5.0,
    val paymentProcessingPct: Double = 0.0,
    val parcelListingFee: Double = 5.0,
    val insurancePremiumPct: Double = 2.0,
    val insuranceMaxDeclaredValue: Double = 20_000.0,
    val agentPlatformCutPct: Double = 5.0,
    val sellerRegistrationFee: Double = 10.0,
    val riderWithdrawalFee: Double = 15.0,
    val minWithdrawal: Double = 50.0,
    val broadcastTimeoutMin: Int = 5,
    val autoConfirmHours: Int = 48,
    val maxConcurrentDeliveries: Int = 4,
    val dispatchCountdownSec: Int = 15,
    val version: Long = 0,
    /** v6.1 listing creation */
    val aiTimeoutSec: Int = 8, val videoMinSec: Int = 15, val videoMaxSec: Int = 60, val maxPhotos: Int = 4,
    /** v6.2 paid promotions (platform revenue) */
    val boostLocalPerDay: Double = 20.0, val boostRegionalPerDay: Double = 45.0, val boostNationalPerDay: Double = 90.0, val storyPrice24h: Double = 10.0,
    val boostFrequencyCap: Int = 3, val boostSlotsPerPage: Int = 2,
) {
    companion object {
        fun fromMap(m: Map<String, Any?>): PlatformConfig {
            fun d(k: String, def: Double) = (m[k] as? Number)?.toDouble() ?: def
            fun i(k: String, def: Int) = (m[k] as? Number)?.toInt() ?: def
            val x = PlatformConfig()
            return PlatformConfig(
                d("flatPlatformFeeKwacha", x.flatPlatformFeeKwacha), d("buyerServiceFeePct", x.buyerServiceFeePct),
                d("riderPlatformFeePct", x.riderPlatformFeePct), d("socialFundPct", x.socialFundPct),
                i("socialFundMilestoneDeliveries", x.socialFundMilestoneDeliveries), d("deliveryFeePerKm", x.deliveryFeePerKm),
                d("deliveryBaseFee", x.deliveryBaseFee), d("paymentProcessingPct", x.paymentProcessingPct),
                d("parcelListingFee", x.parcelListingFee), d("insurancePremiumPct", x.insurancePremiumPct),
                d("insuranceMaxDeclaredValue", x.insuranceMaxDeclaredValue), d("agentPlatformCutPct", x.agentPlatformCutPct),
                d("sellerRegistrationFee", x.sellerRegistrationFee), d("riderWithdrawalFee", x.riderWithdrawalFee),
                d("minWithdrawal", x.minWithdrawal), i("broadcastTimeoutMin", x.broadcastTimeoutMin),
                i("autoConfirmHours", x.autoConfirmHours), i("maxConcurrentDeliveries", x.maxConcurrentDeliveries),
                i("dispatchCountdownSec", x.dispatchCountdownSec), (m["version"] as? Number)?.toLong() ?: 0,
                i("aiTimeoutSec", x.aiTimeoutSec), i("videoMinSec", x.videoMinSec), i("videoMaxSec", x.videoMaxSec), i("maxPhotos", x.maxPhotos),
                d("boostLocalPerDay", x.boostLocalPerDay), d("boostRegionalPerDay", x.boostRegionalPerDay), d("boostNationalPerDay", x.boostNationalPerDay),
                d("storyPrice24h", x.storyPrice24h), i("boostFrequencyCap", x.boostFrequencyCap), i("boostSlotsPerPage", x.boostSlotsPerPage),
            )
        }
    }
}

/** Sponsored advert on the welcome screen, managed in back office Content > Adverts. */
data class Advert(
    val id: String, val title: String, val seller: String, val priceNgwee: Long,
    val cta: String = "Shop now", val category: String = "produce", val colorA: Long = 0xFF7A0016, val colorB: Long = 0xFFE2334D,
    val listingId: String? = null,
)

data class Listing(
    val id: String, val sellerUid: String, val sellerName: String, val title: String,
    val description: String, val priceNgwee: Long, val category: String, val zone: String,
    val thumbUrl: String? = null, val videoUrl: String? = null, val stock: Int = 0, val status: String = "live",
    val views: Int = 0, val saves: Int = 0,
    // v6.1 discovery fields
    val mediaType: String = if (videoUrl != null) "video" else "photo", val photoUrls: List<String> = emptyList(),
    val sellerVerified: Boolean = false, val condition: String = "", val availability: String = "In Stock",
    val province: String = "", val district: String = "", val tags: List<String> = emptyList(),
    val likes: Int = 0, val shares: Int = 0, val enquiries: Int = 0, val discountPct: Double = 0.0, val discountNgwee: Long = 0,
    val rankSource: String? = null, val publishedAt: Long = 0, val durationMs: Long = 0,
    /** Set when this card is a paid Boost placement (shown with a "Sponsored" label). */
    val promoId: String? = null,
    /** Demo only: gradient colours used when a listing has no media yet. */
    val colorA: Long = 0xFFB0281A, val colorB: Long = 0xFFF2B33D,
) {
    val isVideo get() = mediaType == "video"
    val hasOffer get() = discountPct > 0 || discountNgwee > 0
    val offerPriceNgwee: Long get() = when {
        discountNgwee > 0 -> (priceNgwee - discountNgwee).coerceAtLeast(0)
        discountPct > 0 -> (priceNgwee * (1 - discountPct / 100)).toLong()
        else -> priceNgwee
    }
}

/** One page of the personalised feed plus the guardrail state (PRD v6.1 §6.4). */
data class FeedGuard(val minutesToday: Int = 0, val warn: Boolean = false, val capReached: Boolean = false, val budgetCheck: Boolean = false,
                     val warnAtMinutes: Int = 130, val capMinutes: Int = 145)
data class FeedPage(val sessionId: String, val items: List<Listing>, val variant: String = "control", val cohort: Int = 0, val guard: FeedGuard = FeedGuard())
data class Rail(val id: String, val title: String, val items: List<Listing>)
data class SearchFilters(val minNgwee: Long? = null, val maxNgwee: Long? = null, val province: String? = null, val district: String? = null,
                         val condition: String? = null, val mediaType: String? = null, val availability: String? = null, val category: String? = null) {
    val active get() = listOfNotNull(minNgwee, maxNgwee, province, district, condition, mediaType, availability, category).size
}
data class SearchResult(val results: List<Listing>, val alsoSearched: List<String>)
data class Wishlist(val id: String, val name: String, val itemIds: List<String>)

data class SellerListingCard(val id: String, val title: String, val status: String, val thumbUrl: String?, val mediaType: String, val priceNgwee: Long,
                             val views: Int, val likes: Int, val shares: Int, val enquiries: Int, val postedAt: Long)
data class SellerDashboard(
    val listings: List<SellerListingCard>, val weekViews: Int, val lastWeekViews: Int, val likes: Int, val shares: Int, val enquiries: Int,
    val funnel: List<Pair<String, Int>>, val top: Map<String, SellerListingCard?>, val ageBand: Map<String, Int>, val province: Map<String, Int>,
    val peakHours: Map<Int, Int>,
)

data class OrderItem(val listingId: String, val title: String, val qty: Int, val unitNgwee: Long)

data class Order(
    val id: String, val buyerUid: String, val sellerUid: String, val sellerName: String,
    val riderUid: String? = null, val riderName: String? = null,
    val items: List<OrderItem>, val goodsNgwee: Long, val deliveryNgwee: Long, val totalNgwee: Long,
    val state: OrderState, val zone: String, val dropoff: String, val pickup: String,
    val deliveryCode: String? = null, val distanceKm: Double = 0.0, val isParcel: Boolean = false,
    val insuranceId: String? = null, val declaredValueNgwee: Long = 0, val lipilaRef: String? = null,
    val timeline: Map<String, String> = emptyMap(),
    /** Latest mobile-money collection attempt: PENDING, PAID or FAILED (with a reason the buyer can act on). */
    val paymentStatus: String = "PENDING", val paymentFailure: String? = null, val walletMsisdn: String? = null,
    val serviceFeeNgwee: Long = 0, val processingFeeNgwee: Long = 0,
)

data class RiderStats(
    val todayNetNgwee: Long, val todayDeliveries: Int, val active: Int, val acceptanceRate: Int,
    val socialFundNgwee: Long, val completedDeliveries: Int, val availableNgwee: Long, val tier: String,
    val weekFund: List<Long> = emptyList(),
)

data class SellerStats(
    val earningsNgwee: Long, val todayNgwee: Long, val toPack: Int, val liveListings: Int, val lowStock: Int,
    val monthNgwee: Long, val monthOrders: Int,
)

data class SecurityQuestion(val id: String, val text: String)

data class Zone(val id: String, val name: String, val city: String)

/** Cart line; one seller per cart (one rider pickup). */
data class CartItem(val listing: Listing, val qty: Int)

/** Who a Boost or Feed Story is shown to. Empty lists mean "anyone". */
data class Targeting(
    val ageBands: List<String> = emptyList(), val provinces: List<String> = emptyList(), val districts: List<String> = emptyList(),
    val interests: List<String> = emptyList(), val searchIntent: Boolean = true, val followers: Boolean = true,
)

enum class BoostReach(val label: String, val blurb: String) {
    LOCAL("Local", "Your district and nearby"), REGIONAL("Regional", "Whole provinces you choose"), NATIONAL("National", "All of Zambia")
}

/** A paid Boost or Feed Story (PRD v6.2). Status: PENDING_PAYMENT → ACTIVE → ENDED, or PAYMENT_FAILED (retry with another number). */
data class Promotion(
    val id: String, val type: String, val listingId: String, val listingTitle: String, val status: String, val priceNgwee: Long,
    val days: Int, val reach: String, val targeting: Targeting, val startsAt: Long = 0, val endsAt: Long = 0,
    val impressions: Int = 0, val clicks: Int = 0, val likes: Int = 0, val enquiries: Int = 0,
    val failureReason: String? = null, val walletMsisdn: String? = null, val estimatedReach: Int = 0,
)

/** A 24-hour Feed Story shown in the stories row. */
data class Story(val promoId: String, val listing: Listing, val sellerName: String, val followed: Boolean)
