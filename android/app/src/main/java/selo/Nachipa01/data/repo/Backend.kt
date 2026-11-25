package selo.Nachipa01.data.repo

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import selo.Nachipa01.data.model.*

data class QuestionAnswer(val questionId: String, val answer: String)

data class RegistrationRequest(
    val role: Role,
    val msisdn: String,
    val pin: String,
    val questions: List<QuestionAnswer>,
    /** Role-specific profile fields (names, store, hub, vehicle, schedule, categories...). */
    val profile: Map<String, Any?>,
    val payoutWallet: String?,
    val referralCode: String?,
    val kycDocs: Map<String, Uri>,
    val acceptedTermsVersion: String = "2026-11",
)

data class AiDraft(
    val title: String, val description: String, val priceNgwee: Long, val priceLowNgwee: Long,
    val priceHighNgwee: Long, val category: String, val tags: List<String>, val mediaPaths: List<String>,
    val stock: Int = 1, val comparables: Int = 0,
    val condition: String = "Good", val priceScope: String = "", val aiUsed: Boolean = true,
)

data class CheckoutRequest(val items: List<OrderItem>, val sellerUid: String, val dropoff: String, val walletMsisdn: String, val distanceKm: Double, val sellerName: String = "")

data class ParcelRequest(
    val pickup: String, val dropoff: String, val sender: String, val recipientName: String, val recipientMsisdn: String,
    val contents: String, val size: String, val insured: Boolean, val declaredValueNgwee: Long, val distanceKm: Double,
    val walletMsisdn: String,
)

/** Everything the screens need. FirebaseBackend talks to project Mabala; DemoBackend serves sample data. */
interface Backend {
    val isDemo: Boolean
    fun currentUser(): SessionUser?
    suspend fun restoreSession(): SessionUser?
    fun config(): Flow<PlatformConfig>
    fun adverts(): Flow<List<Advert>>
    suspend fun questionBank(): List<SecurityQuestion>

    suspend fun login(msisdn: String, pin: String): SessionUser
    suspend fun register(req: RegistrationRequest, onProgress: (String) -> Unit = {}): SessionUser
    suspend fun recoveryQuestions(msisdn: String): List<SecurityQuestion>?
    suspend fun verifyAnswers(msisdn: String, answers: List<QuestionAnswer>): String
    suspend fun resetPin(resetToken: String, newPin: String)
    suspend fun logout()
    /** Lipila account-holder lookup used before linking a payout wallet. */
    suspend fun lookupWallet(msisdn: String): String

    fun listings(category: String? = null): Flow<List<Listing>>
    fun buyerOrders(): Flow<List<Order>>
    fun order(id: String): Flow<Order?>
    suspend fun checkout(req: CheckoutRequest): String
    suspend fun sendParcel(req: ParcelRequest): String
    suspend fun confirmReceipt(orderId: String)

    fun sellerStats(): Flow<SellerStats>
    fun sellerOrders(): Flow<List<Order>>
    suspend fun confirmPack(orderId: String)
    suspend fun uploadMedia(uris: List<Uri>, onProgress: (Float) -> Unit): List<String>
    /** Frames (already extracted on-device) + on-device labels → cloud LLM draft. */
    suspend fun runAiPipeline(mediaPaths: List<String>, prompt: String?, onStep: (Int) -> Unit, labels: List<Pair<String, Float>> = emptyList(), mediaType: String = "photo"): AiDraft
    suspend fun publishListing(draft: AiDraft, asDraft: Boolean): String

    fun riderStats(): Flow<RiderStats>
    fun availableJobs(): Flow<List<Order>>
    suspend fun setOnline(online: Boolean)
    suspend fun acceptJob(orderId: String)
    fun riderActiveJobs(): Flow<List<Order>>
    suspend fun markCollected(orderId: String)
    /** Rider enters the buyer's 6-digit in-app delivery code (no SMS). */
    suspend fun confirmDeliveryCode(orderId: String, code: String)

    // ------------------------------------------------ v6.1 discovery (feed, rails, search, social)
    /** Writes a batch of behaviour events to the append-only log. */
    suspend fun logEvents(events: List<selo.Nachipa01.data.events.BehaviourEvent>, variant: String?)
    suspend fun feed(sessionId: String?, count: Int = 8): FeedPage
    suspend fun homeRails(): List<Rail>
    suspend fun search(query: String, filters: SearchFilters): SearchResult
    suspend fun listing(id: String): Listing?
    fun likedIds(): Flow<Set<String>>
    fun likedListings(): Flow<List<Listing>>
    fun wishlists(): Flow<List<Wishlist>>
    suspend fun saveToWishlist(listingId: String, wishlistName: String)
    suspend fun createWishlist(name: String)
    fun following(): Flow<Set<String>>
    suspend fun setFollow(sellerUid: String, follow: Boolean)
    suspend fun shareToUser(listingId: String, phone: String)
    suspend fun setLeadConsent(consent: Boolean, ageBand: String?)

    // seller v6.1
    suspend fun sellerDashboard(): SellerDashboard
    suspend fun bulkListings(action: String, ids: List<String>?): Int
    suspend fun updateListing(id: String, changes: Map<String, Any?>)
    suspend fun saveSellerSettings(settings: Map<String, Any?>)
    suspend fun publishListingV2(req: PublishRequest): String

    // ------------------------------------------------ v6.2 payments + paid promotions
    /** Starts a new mobile-money prompt for an unpaid order on a different (or the same) number. */
    suspend fun retryPayment(orderId: String, walletMsisdn: String)
    suspend fun estimateAudience(t: Targeting, reach: BoostReach): Int
    /** Creates a Boost or Feed Story and sends the payment prompt; the promotion goes ACTIVE when the payment is confirmed. */
    suspend fun createPromotion(type: String, listingId: String, reach: BoostReach, days: Int, t: Targeting, walletMsisdn: String): String
    suspend fun retryPromotionPayment(promoId: String, walletMsisdn: String)
    fun promotion(id: String): Flow<Promotion?>
    fun myPromotions(): Flow<List<Promotion>>
    suspend fun stories(): List<Story>
}

/** Everything the AI pre-fill + review screen sends when posting (PRD v6.1 §4.1). */
data class PublishRequest(
    val mediaType: String, val videoPath: String?, val photoPaths: List<String>, val thumbPath: String?, val durationMs: Long,
    val title: String, val description: String, val priceNgwee: Long, val category: String, val condition: String, val availability: String,
    val province: String, val district: String, val location: String, val tags: List<String>, val discountPct: Double, val aiUsed: Boolean, val asDraft: Boolean,
)

class BackendException(message: String) : Exception(message)
