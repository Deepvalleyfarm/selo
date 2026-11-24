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
