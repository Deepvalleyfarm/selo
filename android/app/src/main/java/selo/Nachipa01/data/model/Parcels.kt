package selo.Nachipa01.data.model

/* ------------------------------------------------------------------ shared address system */

data class AddressSuggestion(val id: String, val label: String, val secondary: String, val source: String,
    /** Straight-line distance from the user's current position, when known. */
    val distanceM: Int? = null)

/** Lipila withdrawal states: PENDING / SUBMITTED wait for the provider; COMPLETED, FAILED or MANUAL_REVIEW. */
data class WithdrawOutcome(val id: String, val status: String, val amountNgwee: Long, val message: String)
data class ActiveWithdrawal(val id: String, val status: String, val amountNgwee: Long, val message: String?)
data class WalletStatus(val availableNgwee: Long, val lockedNgwee: Long, val minNgwee: Long, val block: String?, val active: ActiveWithdrawal?)

/** Beem OTP challenge: Beem's pinId for this number and how long the PIN is valid. */
data class OtpChallenge(val pinId: String, val expiresInSec: Int)

/** A receipt issued by SeloNaChipa on behalf of a seller, agent or rider (see functions/src/payments/receipts.ts). */
data class ReceiptLine(val label: String, val amountNgwee: Long, val onBehalfOf: String?)
data class Receipt(val id: String, val number: String, val kind: String, val refId: String, val issuerNote: String, val payerName: String, val onBehalfOf: String,
                   val lines: List<ReceiptLine>, val totalNgwee: Long, val method: String, val wallet: String, val paidAt: Long, val paymentReference: String, val token: String)

/** Public shop page: registered business name, logo, banner and the shop's live products. */
data class ShopProfile(val sellerUid: String, val name: String, val verified: Boolean, val photoUrl: String?, val bannerUrl: String?,
                       val address: String, val listings: List<Listing>)

/** stores/{sellerUid}: public store marker. */
data class StoreLocation(val sellerUid: String, val name: String, val address: String, val lat: Double, val lng: Double)

/** A confirmed point: from a suggestion, the map pin, or the user's current location. */
data class Place(
    val id: String, val label: String, val address: String, val lat: Double, val lng: Double,
    val cityId: String? = null, val cityName: String? = null, val neighbourhood: String? = null,
    val inServiceArea: Boolean = true, val approximate: Boolean = false, val addressId: String? = null,
    /** Google place id when the point came from Places autocomplete (lets the server skip re-geocoding). */
    val placeId: String? = null,
    /** A building or named place within 10 m of the point — shown to riders. */
    val landmark: String? = null,
) {
    val short get() = label.ifBlank { address }
}

data class RouteDistance(val distanceKm: Double, val durationMin: Int, val approximate: Boolean, val sameCity: Boolean = true, val fromCity: String? = null)

data class SavedAddress(val id: String, val nickname: String, val place: Place, val isDefault: Boolean = false)

/** Checkout preview from the server: the delivery fee comes from the road distance to the chosen address. */
data class OrderQuoteResult(
    val goodsNgwee: Long, val deliveryNgwee: Long, val serviceFeeNgwee: Long, val processingFeeNgwee: Long, val totalNgwee: Long,
    val distanceKm: Double, val approximate: Boolean, val etaMin: Int, val unavailable: List<String> = emptyList(),
    /** Marketing Centre deals and listing discounts applied by the server (pricing.ts). */
    val fullGoodsNgwee: Long = goodsNgwee, val savingsNgwee: Long = 0, val deals: List<Pair<String, Long>> = emptyList(), val freeItems: List<String> = emptyList(),
)

/* ------------------------------------------------------------------ parcels v2 */

enum class ParcelStatus(val label: String) {
    PENDING_PAYMENT("Waiting for payment"), AWAITING_RIDER("Finding a rider"), RIDER_ASSIGNED("Rider on the way to pickup"),
    IN_TRANSIT("On the way"), AWAITING_DELIVERY_CODE("Rider has arrived"), DELIVERED("Delivered"), COMPLETED("Completed"),
    CANCELLED("Cancelled"), DELIVERY_FAILED("Delivery failed"), DISPUTED("Under review");
    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: PENDING_PAYMENT }
    val active get() = this in listOf(PENDING_PAYMENT, AWAITING_RIDER, RIDER_ASSIGNED, IN_TRANSIT, AWAITING_DELIVERY_CODE)
}

val WEIGHT_BANDS = listOf("Under 1 kg", "1–2 kg", "2–3 kg", "3–4 kg", "4–5 kg")
val PARCEL_CATEGORIES = listOf("Document", "Letter", "Clothing", "Food", "Sample", "Other")

data class ParcelQuoteResult(
    val distanceKm: Double, val approximate: Boolean, val deliveryNgwee: Long, val serviceNgwee: Long, val insuranceNgwee: Long,
    val totalNgwee: Long, val etaMin: Int, val cityName: String,
)

data class NewParcel(
    val pickup: Place, val dropoff: Place, val weightBand: String, val description: String, val category: String, val handlingNotes: String,
    val receiverName: String, val receiverMsisdn: String, val deliveryInstructions: String, val insured: Boolean, val declaredValueNgwee: Long,
    val walletMsisdn: String, val scheduledFor: Long? = null,
)

data class TimelineEvent(val event: String, val at: Long)

data class Parcel(
    val id: String, val status: ParcelStatus, val iAmSender: Boolean, val senderName: String,
    val pickup: Place, val dropoff: Place, val weightBand: String, val description: String, val category: String,
    val receiverName: String, val receiverMsisdn: String, val deliveryInstructions: String,
    val distanceKm: Double, val approximate: Boolean, val etaMin: Int,
    val deliveryNgwee: Long, val serviceNgwee: Long, val insuranceNgwee: Long, val totalNgwee: Long,
    val riderName: String? = null, val riderPlate: String? = null, val riderLat: Double? = null, val riderLng: Double? = null,
    val paymentStatus: String = "PENDING", val paymentFailure: String? = null, val walletMsisdn: String? = null,
    val codeState: String? = null, val codeChannel: String? = null,
    val unavailableDeadline: Long? = null, val unavailableResponse: String? = null,
    val trackingUrl: String? = null, val createdAt: Long = 0, val deliveredAt: Long? = null, val scheduledFor: Long? = null,
    val timeline: List<TimelineEvent> = emptyList(), val returnStatus: String? = null,
    val trackingPolyline: String? = null, val navEtaMinutes: Int? = null,
)

/* ------------------------------------------------------------------ delivery confirmation */

data class CodeRequestResult(val state: String, val channel: String, val expiresAt: Long, val attemptsLeft: Int, val codesLeft: Int)
/** result: verified | wrong | locked | expired | no_code */
data class CodeVerifyResult(val result: String, val attemptsLeft: Int? = null, val earningsNgwee: Long? = null)
/** A code received by this phone (push or the demo stand-in). Shown on the receiver's Delivery Code screen. */
data class ReceivedCode(val kind: String, val orderId: String, val code: String, val expiresAt: Long, val receivedAt: Long)

val ISSUE_TYPES = listOf("Parcel not delivered", "Parcel damaged", "Wrong item", "Late delivery", "Rider misconduct")

/* ------------------------------------------------------------------ support */

val SUPPORT_CATEGORIES = listOf("Order or delivery", "Payment or refund", "Account and PIN", "Parcel", "Rider or seller conduct", "Other")
data class SupportTicket(val id: String, val reference: String, val subject: String, val category: String, val status: String,
                         val lastMessage: String, val lastFromSupport: Boolean, val unread: Int, val updatedAt: Long)
data class TicketMessage(val id: String, val fromSupport: Boolean, val text: String, val at: Long, val agent: String? = null)
