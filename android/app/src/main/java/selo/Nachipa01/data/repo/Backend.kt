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
)

data class CheckoutRequest(val items: List<OrderItem>, val sellerUid: String, val dropoff: String, val walletMsisdn: String, val distanceKm: Double)

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
    suspend fun runAiPipeline(mediaPaths: List<String>, prompt: String?, onStep: (Int) -> Unit): AiDraft
    suspend fun publishListing(draft: AiDraft, asDraft: Boolean): String

    fun riderStats(): Flow<RiderStats>
    fun availableJobs(): Flow<List<Order>>
    suspend fun setOnline(online: Boolean)
    suspend fun acceptJob(orderId: String)
    fun riderActiveJobs(): Flow<List<Order>>
    suspend fun markCollected(orderId: String)
    /** Rider enters the buyer's 6-digit in-app delivery code (no SMS). */
    suspend fun confirmDeliveryCode(orderId: String, code: String)
}

class BackendException(message: String) : Exception(message)
