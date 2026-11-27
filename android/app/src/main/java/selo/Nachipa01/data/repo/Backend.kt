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
    /** Proof that the number received the sign-up SMS code (auth_verifyOtp). */
    val otpToken: String? = null,
)

data class AiDraft(
    val title: String, val description: String, val priceNgwee: Long, val priceLowNgwee: Long,
    val priceHighNgwee: Long, val category: String, val tags: List<String>, val mediaPaths: List<String>,
    val stock: Int = 1, val comparables: Int = 0,
    val condition: String = "Good", val priceScope: String = "", val aiUsed: Boolean = true,
)

data class CheckoutRequest(val items: List<OrderItem>, val sellerUid: String, val dropoff: String, val walletMsisdn: String, val distanceKm: Double, val sellerName: String = "",
    /** v6.4: the confirmed delivery point; the server recomputes the road distance and fee from it. */
    val dropoffPlace: Place? = null)


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

    // ---------------- v6.3 rider post-login
    suspend fun declineJob(orderId: String)
    suspend fun rateBuyer(orderId: String, stars: Int)
    fun riderProfile(): Flow<RiderProfile?>
    /** Allowed keys: emergencyName, emergencyPhone, vehicle, plate, weeklyTargetNgwee, autoSettle. */
    suspend fun updateRiderProfile(changes: Map<String, Any?>, photo: Uri? = null)
    fun riderHistory(): Flow<List<RiderDelivery>>
    fun riderZones(): Flow<List<DeliveryZone>>
    suspend fun addZone(zoneId: String)
    fun workingHours(): Flow<List<WorkingDay>>
    suspend fun setWorkingHours(days: List<WorkingDay>)
    suspend fun fileClaim(category: ClaimCategory, amountNgwee: Long, description: String, photo: Uri?): String
    fun myClaims(): Flow<List<SocialFundClaim>>
    fun chatThreads(): Flow<List<ChatThread>>
    fun chatMessages(threadId: String): Flow<List<ChatMessage>>
    suspend fun sendChat(threadId: String, text: String)
    fun notifications(): Flow<List<AppNotification>>
    /** Full-balance withdrawal (riders, sellers, agents). The server reads the balance; the PIN is checked server-side. */
    suspend fun withdrawFull(pin: String): WithdrawOutcome
    /** Balance, locked amount, minimum and any in-flight withdrawal (drives the Withdraw button). */
    suspend fun walletStatus(): WalletStatus
    /** Listing Studio: (re)produce the professional version, or switch between studio and original media. */
    suspend fun studioStart(listingId: String): String?
    suspend fun studioApply(listingId: String, apply: Boolean)
    suspend fun setPayoutWallet(msisdn: String)
    suspend fun changePin(currentPin: String, newPin: String)
    suspend fun updateSecurityQuestions(pin: String, answers: List<QuestionAnswer>)
    suspend fun reportIncident(r: IncidentReport): String
    /** Live chat availability: online flag and average reply time in minutes. */
    suspend fun supportStatus(): Pair<Boolean, Int>

    /** Viewable URL for a stored photo path (proof of delivery, claims). */
    suspend fun mediaUrl(path: String): String?

    // ---------------- multi-stop routing and turn-by-turn
    /** Coordinates of each order's pickup and drop-off (geocoded and cached server-side when the order has none). */
    suspend fun locateStops(orderIds: List<String>): Map<String, Pair<selo.Nachipa01.domain.route.LatLng?, selo.Nachipa01.domain.route.LatLng?>>
    /** Road directions through the stops in the given order, with turn-by-turn steps. */
    suspend fun directions(origin: selo.Nachipa01.domain.route.LatLng, stops: List<selo.Nachipa01.domain.route.Stop>, vehicle: String): selo.Nachipa01.domain.route.Directions

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

    // ------------------------------------------------ v6.4 shared address system
    suspend fun addressSuggestions(input: String, cityId: String? = null, nearLat: Double? = null, nearLng: Double? = null): List<AddressSuggestion>
    suspend fun placeDetails(id: String): Place
    suspend fun reverseGeocode(lat: Double, lng: Double): Place
    suspend fun routeDistance(from: Place, to: Place): RouteDistance
    /** Saves a confirmed point to the shared platform address store (deduplicated server-side). Returns its id. */
    suspend fun savePlace(p: Place, source: String = "user"): String
    fun savedAddresses(): Flow<List<SavedAddress>>
    suspend fun saveMyAddress(nickname: String, p: Place, makeDefault: Boolean = false): String
    suspend fun deleteMyAddress(id: String)
    suspend fun orderQuote(items: List<OrderItem>, dropoff: Place): OrderQuoteResult

    // ------------------------------------------------ v6.4 parcels
    suspend fun parcelQuote(pickup: Place, dropoff: Place, insured: Boolean, declaredNgwee: Long): ParcelQuoteResult
    suspend fun createParcel(r: NewParcel): String
    fun myParcels(): Flow<List<Parcel>>
    fun parcel(id: String): Flow<Parcel?>
    /** The 4-digit Pickup PIN — visible to the sender only. */
    fun parcelPickupPin(id: String): Flow<String?>
    suspend fun retryParcelPayment(id: String, walletMsisdn: String)
    suspend fun cancelParcel(id: String): Long
    suspend fun editParcelReceiver(id: String, name: String, msisdn: String)
    suspend fun confirmParcelPickup(id: String, pin: String)
    suspend fun setRiderAreas(cityId: String, neighbourhoods: List<String>)
    suspend fun pingLocation(lat: Double, lng: Double, accuracy: Float?)

    // ------------------------------------------------ v6.4 delivery confirmation (parcels + orders). kind = "parcel" | "order"
    suspend fun requestDeliveryCode(kind: String, id: String, lat: Double?, lng: Double?, resend: Boolean = false, smsOnly: Boolean = false): CodeRequestResult
    suspend fun verifyDeliveryCode(kind: String, id: String, code: String, lat: Double?, lng: Double?): CodeVerifyResult
    suspend fun receiverUnavailable(kind: String, id: String, lat: Double?, lng: Double?): Long
    suspend fun respondUnavailable(kind: String, id: String, action: String, name: String? = null, msisdn: String? = null)
    suspend fun confirmReceipt(kind: String, id: String)
    suspend fun rateDelivery(kind: String, id: String, stars: Int, comment: String = "")
    suspend fun reportIssue(kind: String, id: String, type: String, description: String): String
    suspend fun ackDeliveryCode(kind: String, id: String)
    suspend fun attachProofOfDelivery(kind: String, id: String, photo: Uri)

    // ------------------------------------------------ v6.4 account + support
    /** Beem OTP: sends the SMS PIN; returns Beem's pinId and the expiry (the PIN itself never reaches the app). */
    suspend fun sendSignupOtp(msisdn: String, appContext: String = "signup"): OtpChallenge
    /** Verifies the PIN against the pinId issued to that number; returns the one-time registration token. */
    suspend fun verifySignupOtp(msisdn: String, pinId: String, pin: String): String
    /** During registration (before an account exists) the verified OTP token authorises address search. */
    fun setPreAuth(msisdn: String?, otpToken: String?) {}
    /** Saves the user's permanent location (buyer delivery / seller store / agent base), geocoded once server-side. */
    suspend fun saveMyLocation(p: Place)
    /** Public store markers near a point ("shops near you" map). */
    suspend fun nearbyStores(lat: Double, lng: Double): List<StoreLocation>
    /** A shop's public page with its live products. */
    suspend fun shopProfile(sellerUid: String): ShopProfile
    /** Profile picture for any user (camera or gallery); returns the new photo URL. */
    suspend fun setProfilePhoto(uri: android.net.Uri): String
    /** Payment receipts issued to me (or on my behalf); [refId] = an order, parcel or promotion id. */
    suspend fun receipts(refId: String? = null): List<Receipt>
    suspend fun myQuestions(): List<SecurityQuestion>
    suspend fun updateProfile(changes: Map<String, Any?>)
    fun notificationPrefs(): Flow<Map<String, Boolean>>
    suspend fun saveNotificationPrefs(prefs: Map<String, Boolean>)
    fun paymentWallets(): Flow<List<String>>
    suspend fun savePaymentWallets(wallets: List<String>)
    fun supportTickets(): Flow<List<SupportTicket>>
    fun ticketMessages(id: String): Flow<List<TicketMessage>>
    suspend fun createTicket(subject: String, category: String, message: String, orderRef: String): String
    suspend fun replyTicket(id: String, text: String)

    // ------------------------------------------------ v6.9 growth & safety
    suspend fun campaigns(): List<Campaign>
    suspend fun saveCampaign(c: CampaignDraft): String
    suspend fun setCampaignStatus(id: String, status: String)
    /** Uploads the product photo and returns the AI promotional image, description and social copy. */
    suspend fun generateAd(photo: android.net.Uri, productName: String, priceNgwee: Long, offer: String): AdPack
    suspend fun performanceReport(month: String?): PerformanceReport
    suspend fun safetyStatus(): SafetyStatus
    suspend fun setRiderInsurance(on: Boolean, acceptedTerms: Boolean = false): SafetyStatus
    suspend fun completeLesson(id: String): SafetyStatus
    /** kind = "SOS" (texts the emergency contact) or "SHARE" (live link only). */
    suspend fun sendSos(lat: Double, lng: Double, accuracy: Float?, kind: String, note: String = ""): SosResult
    suspend fun closeSos(alertId: String, safe: Boolean)
    suspend fun riderIdCard(): RiderIdCard
    suspend fun addDeliveryEvidence(kind: String, id: String, photo: android.net.Uri, stage: String, lat: Double?, lng: Double?): Int
}

/** Everything the AI pre-fill + review screen sends when posting (PRD v6.1 §4.1). */
data class PublishRequest(
    val mediaType: String, val videoPath: String?, val photoPaths: List<String>, val thumbPath: String?, val durationMs: Long,
    val title: String, val description: String, val priceNgwee: Long, val category: String, val condition: String, val availability: String,
    val province: String, val district: String, val location: String, val tags: List<String>, val discountPct: Double, val aiUsed: Boolean, val asDraft: Boolean,
)

class BackendException(message: String) : Exception(message)
/** Shown whenever a request fails for lack of connectivity (screens offer a retry). */
const val NETWORK_ERROR = "No connection. Check your mobile data or Wi-Fi and try again."
