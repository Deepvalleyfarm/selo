package selo.Nachipa01.data.repo

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.data.model.FeedGuard
import selo.Nachipa01.data.model.FeedPage
import selo.Nachipa01.data.model.Rail
import selo.Nachipa01.data.model.SearchFilters
import selo.Nachipa01.data.model.SearchResult
import selo.Nachipa01.data.model.SellerDashboard
import selo.Nachipa01.data.model.SellerListingCard
import selo.Nachipa01.data.model.Wishlist
import selo.Nachipa01.data.model.BoostReach
import selo.Nachipa01.data.model.Promotion
import selo.Nachipa01.data.model.Story
import selo.Nachipa01.data.model.Targeting
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Validation

object DemoData {
    val questions = selo.Nachipa01.domain.SecurityQuestions.BANK

    val adverts = listOf(
        Advert("ad1", "Fresh tomatoes, 20 kg crate", "Mama Bupe's Veg · Soweto Market", 18_000, "Shop now", "produce", 0xFF7A0016, 0xFFE2334D),
        Advert("ad2", "Smartphone 128 GB, 1-yr warranty", "Bashi Tembo Electronics · Lusaka CBD", 245_000, "Shop now", "electronics", 0xFF1A1A2E, 0xFF4B3B8F),
        Advert("ad3", "Nshima & chicken combo", "Kwacha Grill · delivered in 30 min", 7_500, "Order now", "food", 0xFF9A3412, 0xFFF59E0B),
        Advert("ad4", "Solar lantern + phone charger kit", "SunPower Zambia · Kitwe & Ndola", 65_000, "Shop now", "solar", 0xFF065F46, 0xFF0D9488),
        Advert("ad5", "Chitenge fabric, 6 yards", "Comesa Fashions · 3 colours", 12_000, "Shop now", "fashion", 0xFF86198F, 0xFFDB2777),
    )

    private fun l(id: String, seller: String, sellerName: String, title: String, desc: String, price: Long, cat: String, district: String,
                  video: Boolean, a: Long, b: Long, likes: Int, views: Int, verified: Boolean = true, cond: String = "New", disc: Double = 0.0,
                  avail: String = "In Stock", tags: List<String> = emptyList(), province: String = "Lusaka") =
        Listing(id, seller, sellerName, title, desc, price, cat, district, stock = 10, views = views, saves = likes / 3, mediaType = if (video) "video" else "photo",
            sellerVerified = verified, condition = cond, availability = avail, province = province, district = district, tags = tags, likes = likes,
            shares = likes / 5, enquiries = likes / 4, discountPct = disc, colorA = a, colorB = b, durationMs = if (video) 32_000 else 0,
            photoUrls = if (video) emptyList() else listOf("1", "2", "3"))

    val listings = listOf(
        l("l1", "s1", "Mama Bupe's Veg", "Fresh Chongwe tomatoes – 5 kg bag", "Firm, deep-red tomatoes picked this morning in Chongwe. Ideal for relish and stews. Packed at Soweto Market, stall 14.", 8_000, "Fresh produce", "Soweto", true, 0xFFB0281A, 0xFFF2B33D, 820, 4100, tags = listOf("tomatoes", "chongwe")).copy(aiEnhanced = true),
        l("l2", "s1", "Mama Bupe's Veg", "Rape bundle, freshly cut", "Tender rape leaves cut at dawn. Bundle feeds a family of four.", 1_000, "Fresh produce", "Soweto", false, 0xFF1B5E20, 0xFF7CB342, 140, 900),
        l("l3", "s2", "Bashi Tembo Electronics", "Smartphone 128 GB, dual SIM", "Dual SIM, 6.5\" screen, 5000 mAh battery. One-year shop warranty.", 245_000, "Phones & accessories", "Lusaka CBD", true, 0xFF1A1A2E, 0xFF4B3B8F, 2300, 11000, disc = 8.0, tags = listOf("phone", "smartphone")).copy(aiEnhanced = true, aiGeneratedMotion = true),
        l("l4", "s3", "Comesa Fashions", "Chitenge fabric, 6 yards", "Wax print chitenge in three colourways. Ideal for outfits and headwraps.", 12_000, "Fashion", "Comesa", false, 0xFF86198F, 0xFFDB2777, 610, 3200, tags = listOf("chitenge", "fabric")),
        l("l5", "s4", "Kwacha Grill", "Nshima & grilled chicken combo", "Quarter chicken, nshima and village chicken relish. Delivered hot in 30 minutes.", 7_500, "Fast food", "Kabwata", true, 0xFF9A3412, 0xFFF59E0B, 1450, 6400, disc = 10.0),
        l("l6", "s5", "SunPower Zambia", "Solar lantern + phone charger kit", "10 W panel, two LED lanterns and USB charging. Ready for load-shedding.", 65_000, "Electronics", "Kitwe", true, 0xFF065F46, 0xFF0D9488, 980, 5200, province = "Copperbelt"),
        l("l7", "s6", "Chilenje Furniture Works", "Solid mukwa coffee table", "Hand-made mukwa hardwood table, 120 × 60 cm, oil finish.", 180_000, "Furniture", "Chilenje", false, 0xFF5D4037, 0xFFBCAAA4, 220, 1500, avail = "Made to Order"),
        l("l8", "s7", "Mwila Hardware", "Cement 50 kg (Dangote)", "Grade 42.5 cement. Free delivery within Matero for 20+ bags.", 13_500, "Building materials", "Matero", false, 0xFF37474F, 0xFF90A4AE, 90, 800, avail = "Last Units"),
        l("l9", "s3", "Comesa Fashions", "Men's linen shirt", "Breathable linen shirt, sizes M–XXL, four colours.", 25_000, "Fashion", "Comesa", true, 0xFF0D47A1, 0xFF64B5F6, 410, 2100, disc = 15.0),
        l("l10", "s8", "Baby Joy Store", "Baby carrier, ergonomic", "Hip-healthy carrier for 3–36 months. Breathable mesh.", 45_000, "Baby", "Woodlands", false, 0xFFAD1457, 0xFFF8BBD0, 260, 1300),
        l("l11", "s9", "Mazabuka Sugar Depot", "Sugar 10 kg", "Zambia Sugar white sugar, 10 kg bag.", 22_000, "Groceries", "Kabulonga", false, 0xFF4E342E, 0xFFD7CCC8, 170, 1100),
        l("l12", "s2", "Bashi Tembo Electronics", "Bluetooth speaker, 20 W", "Waterproof speaker, 12-hour battery, FM radio.", 38_000, "Electronics", "Lusaka CBD", true, 0xFF263238, 0xFF00BCD4, 720, 3300, cond = "Like new"),
        l("l13", "s10", "Ndola Auto Spares", "Toyota Corolla brake pads", "Front brake pads for Corolla 2008–2018. Fitting available.", 42_000, "Vehicles & parts", "Ndola", false, 0xFF212121, 0xFFFF7043, 120, 700, province = "Copperbelt"),
        l("l14", "s11", "Glow Beauty Lusaka", "Shea butter body cream", "Natural shea and cocoa butter, 500 ml. Made in Zambia.", 9_500, "Beauty", "Kalingalinga", true, 0xFF6A1B9A, 0xFFFFB74D, 530, 2600),
        l("l15", "s12", "Chongwe Farm Fresh", "Village chickens (live)", "Free-range village chickens, 1.5–2 kg each.", 15_000, "Agriculture", "Chongwe", true, 0xFF33691E, 0xFFFFCA28, 380, 1900),
        l("l16", "s1", "Mama Bupe's Veg", "Fresh onions 10 kg", "Red onions from Mkushi. Long-keeping.", 9_000, "Fresh produce", "Soweto", false, 0xFF880E4F, 0xFFFF8A80, 200, 1200, disc = 5.0),
    )

    val sellerStats = SellerStats(341_800, 61_500, 3, 24, 2, 988_000, 61)
    val riderStats = RiderStats(18_620, 7, 2, 92, 2_360, 13, 18_600, "Rising", listOf(40, 62, 30, 80, 55, 70, 48))

    val zones = listOf(Zone("z1", "Kabulonga", "Lusaka"), Zone("z2", "Woodlands", "Lusaka"), Zone("z3", "Munali", "Lusaka"))

    fun demoUser(role: Role, msisdn: String = "+260971234512", status: AccountStatus = AccountStatus.ACTIVE) = when (role) {
        Role.BUYER -> SessionUser("demo-buyer", role, "Mwila", "Mwila Chanda", msisdn, status, "Munali, Lusaka", memberSince = "Nov 2026")
        Role.SELLER -> SessionUser("demo-seller", role, "Bupe", "Bupe Mwanza", msisdn, status, "Soweto Market", storeName = "Mama Bupe's Veg", memberSince = "Nov 2026")
        Role.AGENT -> SessionUser("demo-agent", role, "Chileshe", "Chileshe Mulenga", msisdn, status, "Soweto Market", storeName = "Chileshe Market Agency", memberSince = "Nov 2026")
        Role.RIDER -> SessionUser("demo-rider", role, "Chanda", "Chanda Mulenga", msisdn, status, "Kabulonga · Woodlands", vehicle = "Motorbike", memberSince = "Nov 2026")
    }
}

/**
 * Runs the whole app offline with sample data when google-services.json is missing.
 * Demo logins: any valid 5-digit PIN. Numbers ending 1 = Buyer, 2 = Seller, 3 = Agent, 4 = Rider.
 */
class DemoBackend : Backend {
    override val isDemo = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var session: SessionUser? = null
    private val orders = MutableStateFlow<Map<String, Order>>(emptyMap())
    private val online = MutableStateFlow(true)

    override fun currentUser() = session
    override suspend fun restoreSession(): SessionUser? = null
    /** Demo shows a 1% transaction fee so the fee line is visible; production starts at 0% and is set in the back office. */
    override fun config(): Flow<PlatformConfig> = flowOf(PlatformConfig(paymentProcessingPct = 1.0))
    override fun adverts(): Flow<List<Advert>> = flowOf(DemoData.adverts)
    override suspend fun questionBank() = DemoData.questions

    private val wrongPins = mutableMapOf<String, Int>()
    override suspend fun login(msisdn: String, pin: String): SessionUser {
        delay(600)
        if (Validation.pinProblem(pin) != null) {
            val n = (wrongPins[msisdn] ?: 0) + 1; wrongPins[msisdn] = if (n >= 5) 0 else n
            throw BackendException(if (n >= 5) "Too many attempts. Locked for 30 minutes." else "Incorrect PIN. ${5 - n} attempt${if (5 - n == 1) "" else "s"} left before a 30-minute lock.")
        }
        wrongPins.remove(msisdn)
        val role = when (msisdn.last()) { '2' -> Role.SELLER; '3' -> Role.AGENT; '4' -> Role.RIDER; else -> Role.BUYER }
        return DemoData.demoUser(role, msisdn).also { session = it }
    }

    /** Remembers each number's chosen questions and normalised answers so Forgot PIN asks the same three. */
    private val securityByMsisdn = mutableMapOf<String, List<QuestionAnswer>>()

    override suspend fun register(req: RegistrationRequest, onProgress: (String) -> Unit): SessionUser {
        onProgress("Creating your account"); delay(700)
        securityByMsisdn[req.msisdn] = req.questions.map { it.copy(answer = Validation.normaliseAnswer(it.answer)) }
        if (req.kycDocs.isNotEmpty()) { onProgress("Uploading documents"); delay(700) }
        val status = if (req.role == Role.BUYER) AccountStatus.ACTIVE
            else if (req.role == Role.RIDER) AccountStatus.PENDING_VERIFICATION else AccountStatus.PENDING_KYC
        val first = (req.profile["firstName"] as? String)?.ifBlank { null }
            ?: (req.profile["fullName"] as? String)?.substringBefore(' ') ?: req.role.label
        return DemoData.demoUser(req.role, req.msisdn, status).copy(
            firstName = first,
            fullName = (req.profile["fullName"] as? String) ?: first,
            storeName = (req.profile["storeName"] as? String) ?: "",
        ).also { session = it }
    }

    override suspend fun recoveryQuestions(msisdn: String): List<SecurityQuestion>? {
        delay(400)
        val set = securityByMsisdn[msisdn] ?: return listOf(DemoData.questions[1], DemoData.questions[2], DemoData.questions[4])
        return set.map { SecurityQuestion(it.questionId, selo.Nachipa01.domain.SecurityQuestions.textFor(it.questionId)) }
    }
    override suspend fun verifyAnswers(msisdn: String, answers: List<QuestionAnswer>): String {
        delay(600)
        val set = securityByMsisdn[msisdn]
        val ok = if (set != null) set.all { s -> answers.any { it.questionId == s.questionId && Validation.normaliseAnswer(it.answer) == s.answer } }
            else answers.none { Validation.answerProblem(it.answer) != null }
        if (!ok) throw BackendException("One or more answers did not match. Try again.")
        return "demo-reset-token"
    }
    override suspend fun resetPin(resetToken: String, newPin: String) { delay(400) }
    override suspend fun logout() { session = null }
    override suspend fun lookupWallet(msisdn: String): String { delay(700); return "BUPE MWANZA" }

    override fun listings(category: String?) = flowOf(DemoData.listings.filter { category == null || it.category == category })
    override fun buyerOrders(): Flow<List<Order>> = orders.map { it.values.filter { o -> !o.isParcel }.toList() }
    override fun order(id: String): Flow<Order?> = orders.map { it[id] }

    private fun put(o: Order) { orders.value = orders.value + (o.id to o) }
    private fun advance(id: String, state: OrderState, riderName: String? = null) {
        orders.value[id]?.let { put(it.copy(state = state, riderName = riderName ?: it.riderName, timeline = it.timeline + (state.name to "now"))) }
    }

    /** Demo balances: the registered number holds K1,240.50 (as on the wallet card); other numbers have plenty; numbers ending 00 are empty. */
    private fun balance(msisdn: String) = when { msisdn.endsWith("00") -> 0L; msisdn == session?.msisdn -> 124_050L; else -> 5_000_000L }

    private fun simulate(id: String) = scope.launch {
        delay(2500)
        val o = orders.value[id] ?: return@launch
        val w = o.walletMsisdn ?: session?.msisdn ?: ""
        if (o.totalNgwee > balance(w)) {
            put(o.copy(paymentStatus = "FAILED", paymentFailure = "Insufficient funds on ${Validation.network(w)} ${Validation.maskMsisdn(w)}")); return@launch
        }
        put(o.copy(paymentStatus = "PAID")); advance(id, OrderState.ESCROW_LOCKED)
        delay(3000); advance(id, OrderState.RIDER_ASSIGNED, "Chanda M.")
        delay(4000); advance(id, OrderState.PICKED_UP)
        delay(3000); advance(id, OrderState.IN_TRANSIT)
        // rider arrives: the server sends the buyer (receiver) a 5-digit code; the rider enters it a little later
        delay(6000); advance(id, OrderState.AWAITING_DELIVERY_CODE)
        selo.Nachipa01.push.DeliveryCodeStore.put(ReceivedCode("order", id, "%05d".format((10_000..99_998).random()), System.currentTimeMillis() + 15 * 60_000, System.currentTimeMillis()))
        delay(15_000)
        if (orders.value[id]?.state == OrderState.AWAITING_DELIVERY_CODE) { advance(id, OrderState.DELIVERED); selo.Nachipa01.push.DeliveryCodeStore.clear("order", id) }
    }

    override suspend fun checkout(req: CheckoutRequest): String {
        val q = Fees.quote(req.items.sumOf { it.unitNgwee * it.qty }, req.distanceKm, PlatformConfig(paymentProcessingPct = 1.0))
        val id = "SNC-${(48_200..48_999).random()}"
        put(Order(id, "demo-buyer", req.sellerUid, req.sellerName.ifBlank { "Mama Bupe's Veg" }, null, null, req.items, q.goodsNgwee, q.deliveryNgwee, q.totalNgwee,
            OrderState.INITIATED, "Lusaka Central", req.dropoff, v64.sellerPlace(req.sellerUid).label,
            deliveryCode = "%06d".format((100_000..999_999).random()), distanceKm = req.distanceKm, lipilaRef = "SELO-COL-$id",
            walletMsisdn = req.walletMsisdn, serviceFeeNgwee = q.serviceFeeNgwee, processingFeeNgwee = q.processingFeeNgwee))
        simulate(id); return id
    }

    private var attempt = 1
    override suspend fun retryPayment(orderId: String, walletMsisdn: String) {
        delay(300)
        val o = orders.value[orderId] ?: throw BackendException("Order not found.")
        if (o.state != OrderState.INITIATED) throw BackendException("This order is already paid.")
        attempt++
        put(o.copy(walletMsisdn = walletMsisdn, paymentStatus = "PENDING", paymentFailure = null, lipilaRef = "SELO-COL-$orderId-R$attempt"))
        simulate(orderId)
    }

    // ---------------- paid promotions (demo)
    private val promos = MutableStateFlow<List<Promotion>>(emptyList())
    private fun promoPrice(type: String, reach: BoostReach, days: Int) = Fees.promoPriceNgwee(type, reach, days, PlatformConfig())

    override suspend fun estimateAudience(t: Targeting, reach: BoostReach): Int {
        delay(150)
        val base = when (reach) { BoostReach.LOCAL -> 1_800; BoostReach.REGIONAL -> 9_000 * maxOf(1, t.provinces.size); BoostReach.NATIONAL -> 42_000 }
        val age = if (t.ageBands.isEmpty()) 1.0 else t.ageBands.size / 5.0 + 0.1
        val interest = if (t.interests.isEmpty()) 1.0 else minOf(1.0, 0.25 * t.interests.size + if (t.searchIntent) 0.1 else 0.0)
        return (base * age * interest).toInt()
    }

    override suspend fun createPromotion(type: String, listingId: String, reach: BoostReach, days: Int, t: Targeting, walletMsisdn: String): String {
        delay(300)
        val l = all().firstOrNull { it.id == listingId }
        val id = "prm_${selo.Nachipa01.data.events.Keys.uuidv7()}_s00"
        val p = Promotion(id, type, listingId, l?.title ?: "", "PENDING_PAYMENT", promoPrice(type, reach, days), if (type == "story") 1 else days, reach.name, t,
            walletMsisdn = walletMsisdn, estimatedReach = estimateAudience(t, reach))
        promos.value = listOf(p) + promos.value
        settlePromo(id); return id
    }

    private fun settlePromo(id: String) = scope.launch {
        delay(2000)
        promos.value = promos.value.map { p -> if (p.id != id) p else if (p.priceNgwee > balance(p.walletMsisdn ?: ""))
            p.copy(status = "PAYMENT_FAILED", failureReason = "Insufficient funds on ${Validation.network(p.walletMsisdn ?: "")} ${Validation.maskMsisdn(p.walletMsisdn ?: "")}")
        else { val now = System.currentTimeMillis(); p.copy(status = "ACTIVE", startsAt = now, endsAt = now + p.days * 86_400_000L, failureReason = null) } }
    }

    override suspend fun retryPromotionPayment(promoId: String, walletMsisdn: String) {
        delay(300)
        promos.value = promos.value.map { if (it.id == promoId) it.copy(status = "PENDING_PAYMENT", walletMsisdn = walletMsisdn, failureReason = null) else it }
        settlePromo(promoId)
    }
    override fun promotion(id: String): Flow<Promotion?> = promos.map { l -> l.firstOrNull { it.id == id } }
    override fun myPromotions(): Flow<List<Promotion>> = promos
    override suspend fun stories(): List<Story> {
        val mine = promos.value.filter { it.type == "story" && it.status == "ACTIVE" }.mapNotNull { p -> all().firstOrNull { it.id == p.listingId }?.let { Story(p.id, it, it.sellerName, false) } }
        val seeded = listOf("l5", "l3", "l9", "l14", "l6").mapNotNull { id -> all().firstOrNull { it.id == id } }.map { Story("story_${it.id}", it, it.sellerName, it.sellerUid in follows.value) }
        return (mine + seeded).sortedByDescending { it.followed }
    }


    override suspend fun confirmReceipt(orderId: String) { advance(orderId, OrderState.COMPLETED) }

    override fun sellerStats() = flowOf(DemoData.sellerStats)
    override fun sellerOrders(): Flow<List<Order>> = flowOf(listOf(
        Order("SNC-48230", "b1", "s1", "Mama Bupe's Veg", items = listOf(OrderItem("l1", "Tomatoes 5 kg", 2, 8_000)), goodsNgwee = 16_000,
            deliveryNgwee = 3_500, totalNgwee = 19_500, state = OrderState.ESCROW_LOCKED, zone = "Chilenje", dropoff = "Chilenje", pickup = "Soweto"),
        Order("SNC-48227", "b2", "s1", "Mama Bupe's Veg", items = listOf(OrderItem("l2", "Rape bundle", 6, 1_000)), goodsNgwee = 6_000,
            deliveryNgwee = 3_000, totalNgwee = 9_000, state = OrderState.ESCROW_LOCKED, zone = "Kabwata", dropoff = "Kabwata", pickup = "Soweto"),
    ))
    override suspend fun confirmPack(orderId: String) { delay(300) }

    override suspend fun uploadMedia(uris: List<Uri>, onProgress: (Float) -> Unit): List<String> {
        for (i in 1..10) { delay(120); onProgress(i / 10f) }
        return uris.mapIndexed { i, _ -> "listing-uploads/demo/$i" }
    }

    /** Set to make the demo AI pipeline fail or hang, to exercise the manual-entry fallback. */
    var aiDelayMs = 900L
    override suspend fun runAiPipeline(mediaPaths: List<String>, prompt: String?, onStep: (Int) -> Unit, labels: List<Pair<String, Float>>, mediaType: String): AiDraft {
        for (s in 3..4) { onStep(s); delay(aiDelayMs) }
        onStep(5)
        val cat = labels.firstOrNull()?.first?.let { selo.Nachipa01.domain.Taxonomy.fromLabel(it) } ?: "Fresh produce"
        return AiDraft("Fresh Chongwe Tomatoes – 5 kg Bag",
            "Firm, deep-red tomatoes picked this morning in Chongwe. They are ideal for relish, stews and salads. Each 5 kg bag is hand-sorted, so you only get whole fruit. Collect from Soweto Market or have a rider bring it to you.",
            8_500, 7_800, 9_200, cat, listOf("tomatoes", "chongwe", "relish", "fresh"), mediaPaths, 14, 31, "New", "Lusaka")
    }
    override suspend fun publishListing(draft: AiDraft, asDraft: Boolean): String { delay(500); return "l-new" }

    /** Rider side lives in DemoRider; tests can reach it to control dispatch timing. */
    val rider = DemoRider(scope, PlatformConfig())
    override fun riderStats() = rider.stats
    override fun availableJobs(): Flow<List<Order>> = rider.availableJobs()
    override suspend fun setOnline(online: Boolean) { this.online.value = online; rider.setOnline(online) }

    /* ============================================ v6.1 discovery (in-memory, same rules as the server, simplified) */

    val eventLog = mutableListOf<selo.Nachipa01.data.events.BehaviourEvent>()
    private val shown = mutableMapOf<String, Long>()
    private val blocked = mutableSetOf<String>()
    private val liked = MutableStateFlow<Set<String>>(emptySet())
    private val lists = MutableStateFlow(listOf(Wishlist("favourites", "Favourites", emptyList()), Wishlist("for-the-house", "For the house", emptyList())))
    private val follows = MutableStateFlow<Set<String>>(emptySet())
    private val affinity = mutableMapOf<String, Double>()
    var minutesTodayOverride: Int? = null
    private var minutesToday = 0.0
    private val myListings = MutableStateFlow<List<Listing>>(emptyList())

    override suspend fun logEvents(events: List<selo.Nachipa01.data.events.BehaviourEvent>, variant: String?) {
        events.forEach { e ->
            eventLog += e
            val cat = e.category ?: DemoData.listings.firstOrNull { it.id == e.listingId }?.category ?: return@forEach
            val w = when (e.type) { "like" -> if (e.undone == true) -1.0 else 1.0; "share" -> 1.2; "enquiry_open" -> 1.1; "save" -> 0.8; "details_open" -> 0.5
                "video_watch" -> ((e.watchMs ?: 0).toDouble() / (e.totalMs ?: 1).coerceAtLeast(1)) - 0.3; "skip" -> -0.15; "not_interested" -> -1.5; else -> 0.05 }
            affinity[cat] = (affinity[cat] ?: 0.0) + w
            if (e.type == "not_interested") e.listingId?.let(blocked::add)
            if (e.type == "like") e.listingId?.let { id -> liked.value = if (e.undone == true) liked.value - id else liked.value + id }
            if (e.type == "session_end") minutesToday += (e.durationMs ?: 0) / 60_000.0
        }
    }

    private fun all() = myListings.value + DemoData.listings
    private fun demoScore(l: Listing) = (l.likes + 2.0 * l.shares + 2.0 * l.enquiries) / (l.views + 10) * 3 + (affinity[l.category] ?: 0.0) * 0.6 +
        (if (l.isVideo) 0.1 else 0.0)

    override suspend fun feed(sessionId: String?, count: Int): FeedPage {
        delay(250)
        val now = System.currentTimeMillis()
        val mins = minutesTodayOverride ?: minutesToday.toInt()
        val g = FeedGuard(mins, warn = mins >= 130, capReached = mins >= 145, budgetCheck = eventLog.count { it.type == "enquiry_open" } > 13)
        val pool = all().filter { it.id !in blocked && (shown[it.id]?.let { t -> now - t > 72 * 3_600_000 } ?: true) }
            .ifEmpty { all().filter { it.id !in blocked } }
        val ranked = if (g.capReached) pool.sortedByDescending { it.publishedAt } else pool.sortedByDescending(::demoScore)
        val out = mutableListOf<Listing>()
        val rest = ranked.toMutableList()
        while (out.size < count && rest.isNotEmpty()) {
            val i = rest.indexOfFirst { it.sellerUid != out.lastOrNull()?.sellerUid }.let { if (it < 0) 0 else it }
            out += rest.removeAt(i)
        }
        out.forEach { shown[it.id] = now }
        // paid Boosts take slots 2 and 6 (PRD v6.2), labelled Sponsored
        promos.value.filter { it.type == "boost" && it.status == "ACTIVE" && it.endsAt > now }.take(2).forEachIndexed { i, p ->
            all().firstOrNull { it.id == p.listingId }?.let { l -> out.removeAll { it.id == l.id }; out.add(minOf(out.size, if (i == 0) 1 else 5), l.copy(promoId = p.id, rankSource = "boost")) }
        }
        return FeedPage(sessionId ?: "ses_demo_${now}", out.map { it.copy(rankSource = it.rankSource ?: if (g.capReached) "unranked_cap" else "demo") }, "control", 0, g)
    }

    override suspend fun homeRails(): List<Rail> {
        val rails = mutableListOf<Rail>()
        liked.value.lastOrNull()?.let { id -> all().firstOrNull { it.id == id } }?.let { ref ->
            rails += Rail("because_liked", "Because you liked ${ref.title}", all().filter { it.id != ref.id }.sortedByDescending { (if (it.category == ref.category) 2.0 else 0.0) + (affinity[it.category] ?: 0.0) }.take(8))
        }
        rails += Rail("popular_area", "Popular in Lusaka", all().filter { it.province == "Lusaka" }.sortedByDescending { it.likes }.take(8))
        eventLog.lastOrNull { it.type == "search" && it.firstClickListingId == null }?.query?.let { q ->
            val hits = all().filter { it.title.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
            if (hits.isNotEmpty()) rails += Rail("searched_nearby", "You searched for \"$q\" — here are sellers nearby", hits)
        }
        rails += Rail("deals", "Deals for you", all().filter { it.hasOffer }.sortedByDescending(::demoScore))
        return rails
    }

    override suspend fun search(query: String, filters: SearchFilters): SearchResult {
        delay(200)
        val toks = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        val res = all().filter { l -> toks.all { t -> "${l.title} ${l.description} ${l.tags.joinToString(" ")} ${l.sellerName}".lowercase().contains(t) } }
            .filter { l -> (filters.minNgwee == null || l.priceNgwee >= filters.minNgwee) && (filters.maxNgwee == null || l.priceNgwee <= filters.maxNgwee) &&
                (filters.province == null || l.province == filters.province) && (filters.condition == null || l.condition == filters.condition) &&
                (filters.mediaType == null || l.mediaType == filters.mediaType) && (filters.availability == null || l.availability == filters.availability) &&
                (filters.category == null || l.category == filters.category) }
        val also = mapOf("tom" to listOf("onions", "rape", "potatoes"), "pho" to listOf("speaker", "charger", "earphones"), "chi" to listOf("fabric", "shirt"))
            .entries.firstOrNull { (k, _) -> toks.firstOrNull()?.startsWith(k) == true }?.value ?: listOf("tomatoes", "phone", "chitenge")
        return SearchResult(res, also)
    }

    override suspend fun listing(id: String): Listing? = all().firstOrNull { it.id == id }
    override fun likedIds(): Flow<Set<String>> = liked
    override fun likedListings(): Flow<List<Listing>> = liked.map { ids -> all().filter { it.id in ids } }
    override fun wishlists(): Flow<List<Wishlist>> = lists
    override suspend fun createWishlist(name: String) { if (lists.value.none { it.name.equals(name, true) }) lists.value = lists.value + Wishlist(name.lowercase(), name, emptyList()) }
    override suspend fun saveToWishlist(listingId: String, wishlistName: String) {
        createWishlist(wishlistName)
        lists.value = lists.value.map { if (it.name.equals(wishlistName, true)) it.copy(itemIds = (it.itemIds + listingId).distinct()) else it }
    }
    override fun following(): Flow<Set<String>> = follows
    override suspend fun setFollow(sellerUid: String, follow: Boolean) { follows.value = if (follow) follows.value + sellerUid else follows.value - sellerUid }
    override suspend fun shareToUser(listingId: String, phone: String) {
        delay(300); if (Validation.normaliseMsisdn(phone) == null) throw BackendException("Enter your friend's mobile number.")
    }
    override suspend fun setLeadConsent(consent: Boolean, ageBand: String?) { delay(150) }

    override suspend fun sellerDashboard(): SellerDashboard {
        delay(200)
        val mine = (myListings.value + DemoData.listings.filter { it.sellerUid == "s1" }).map {
            SellerListingCard(it.id, it.title, it.status, it.thumbUrl, it.mediaType, it.priceNgwee, it.views, it.likes, it.shares, it.enquiries, System.currentTimeMillis() - 86_400_000L * (it.id.length),
                studioStatus = listOf("ready", "processing", null)[it.id.hashCode().mod(3)], studioApplied = it.id.hashCode().mod(3) == 0)
        }
        return SellerDashboard(mine, 5_420, 4_310, 1_160, 232, 291,
            listOf("Views" to 6_200, "Taps" to 1_480, "Enquiries" to 291, "Sales" to 61),
            mapOf("views" to mine.maxByOrNull { it.views }, "enquiries" to mine.maxByOrNull { it.enquiries }, "likes" to mine.maxByOrNull { it.likes }),
            mapOf("18–24" to 120, "25–34" to 310, "35–44" to 140, "45+" to 60, "Not given" to 90),
            mapOf("Lusaka" to 520, "Copperbelt" to 140, "Central" to 60), mapOf(6 to 40, 7 to 95, 8 to 70, 12 to 60, 17 to 110, 18 to 150, 19 to 130, 20 to 80))
    }
    override suspend fun bulkListings(action: String, ids: List<String>?): Int { delay(200); return ids?.size ?: 4 }
    override suspend fun updateListing(id: String, changes: Map<String, Any?>) {
        delay(150); myListings.value = myListings.value.map { if (it.id == id && changes["status"] is String) it.copy(status = changes["status"] as String) else it }
    }
    override suspend fun saveSellerSettings(settings: Map<String, Any?>) { delay(200) }
    override suspend fun publishListingV2(req: PublishRequest): String {
        delay(500)
        val id = "lst_${selo.Nachipa01.data.events.Keys.uuidv7()}_s00"
        myListings.value = listOf(Listing(id, "s1", "Mama Bupe's Veg", req.title, req.description, req.priceNgwee, req.category, req.district,
            mediaType = req.mediaType, condition = req.condition, availability = req.availability, province = req.province, district = req.district,
            tags = req.tags, discountPct = req.discountPct, status = if (req.asDraft) "draft" else "live", publishedAt = System.currentTimeMillis(),
            photoUrls = req.photoPaths)) + myListings.value
        return id
    }

    override suspend fun acceptJob(orderId: String) = rider.accept(orderId)
    override fun riderActiveJobs(): Flow<List<Order>> = rider.activeJobs()
    override suspend fun markCollected(orderId: String) = rider.collected(orderId)
    override suspend fun declineJob(orderId: String) = rider.decline(orderId)
    override suspend fun rateBuyer(orderId: String, stars: Int) { delay(200) }
    override fun riderProfile(): Flow<RiderProfile?> = rider.profile()
    override suspend fun updateRiderProfile(changes: Map<String, Any?>, photo: Uri?) { delay(300); rider.update(changes + (if (photo != null) mapOf("photoUrl" to photo.toString()) else emptyMap())) }
    override fun riderHistory(): Flow<List<RiderDelivery>> = rider.history()
    override fun riderZones(): Flow<List<DeliveryZone>> = rider.zones()
    override suspend fun addZone(zoneId: String) = rider.addZone(zoneId)
    override fun workingHours(): Flow<List<WorkingDay>> = rider.hours()
    override suspend fun setWorkingHours(days: List<WorkingDay>) { delay(200); rider.setHours(days) }
    override suspend fun fileClaim(category: ClaimCategory, amountNgwee: Long, description: String, photo: Uri?) = rider.fileClaim(category, amountNgwee, description, photo)
    override fun myClaims(): Flow<List<SocialFundClaim>> = rider.claims()
    override fun chatThreads(): Flow<List<ChatThread>> = rider.threads()
    override fun chatMessages(threadId: String): Flow<List<ChatMessage>> { rider.markRead(threadId); return rider.messages(threadId) }
    override suspend fun sendChat(threadId: String, text: String) = rider.send(threadId, text)
    override fun notifications(): Flow<List<AppNotification>> = rider.notifications()
    override suspend fun withdrawFull(pin: String) = rider.withdrawFull(pin)
    override suspend fun walletStatus() = rider.walletStatus()
    override suspend fun studioStart(listingId: String): String? { delay(300); return null }
    override suspend fun studioApply(listingId: String, apply: Boolean) { delay(300) }
    override suspend fun setPayoutWallet(msisdn: String) {
        delay(300); val m = Validation.normaliseMsisdn(msisdn) ?: throw BackendException("Enter an Airtel Money, MTN Money or Zamtel Money number.")
        rider.setWallet(m)
    }
    override suspend fun changePin(currentPin: String, newPin: String) {
        delay(300); if (currentPin.length != 5) throw BackendException("Current PIN is incorrect.")
        Validation.pinProblem(newPin)?.let { throw BackendException(it) }
    }
    override suspend fun updateSecurityQuestions(pin: String, answers: List<QuestionAnswer>) {
        delay(300); if (pin.length != 5) throw BackendException("Current PIN is incorrect.")
        if (answers.size != 3 || answers.any { it.answer.trim().length < 2 }) throw BackendException("Answer all three questions.")
    }
    override suspend fun reportIncident(r: IncidentReport) = rider.report(r)
    override suspend fun supportStatus() = true to 4
    override suspend fun locateStops(orderIds: List<String>) = rider.locate(orderIds)
    override suspend fun directions(origin: selo.Nachipa01.domain.route.LatLng, stops: List<selo.Nachipa01.domain.route.Stop>, vehicle: String) =
        rider.directions(origin, stops)
    override suspend fun mediaUrl(path: String): String? = path

    /* ======================================================== v6.4 (demo) */
    val v64 = DemoV64(scope) { session }
    override suspend fun addressSuggestions(input: String, cityId: String?, nearLat: Double?, nearLng: Double?) = v64.suggestions(input, cityId)
    override suspend fun placeDetails(id: String) = v64.details(id)
    override suspend fun reverseGeocode(lat: Double, lng: Double) = v64.reverse(lat, lng)
    override suspend fun routeDistance(from: Place, to: Place) = v64.distance(from, to)
    override suspend fun savePlace(p: Place, source: String) = v64.save(p)
    override fun savedAddresses() = v64.saved()
    override suspend fun saveMyAddress(nickname: String, p: Place, makeDefault: Boolean): String { delay(200); return v64.saveMine(nickname, p, makeDefault) }
    override suspend fun deleteMyAddress(id: String) { delay(150); v64.deleteMine(id) }
    override suspend fun orderQuote(items: List<OrderItem>, dropoff: Place): OrderQuoteResult {
        delay(250)
        if (!dropoff.inServiceArea) throw BackendException("That address is outside our delivery area.")
        val listings = all()
        val sellerUid = listings.firstOrNull { it.id == items.firstOrNull()?.listingId }?.sellerUid ?: "s1"
        val d = v64.distance(v64.sellerPlace(sellerUid), dropoff)
        val q = Fees.quote(items.sumOf { it.unitNgwee * it.qty }, d.distanceKm, PlatformConfig(paymentProcessingPct = 1.0))
        val unavailable = items.filter { i -> listings.firstOrNull { it.id == i.listingId }?.let { it.stock <= 0 || it.availability.equals("Sold out", true) } ?: false }.map { it.listingId }
        return OrderQuoteResult(q.goodsNgwee, q.deliveryNgwee, q.serviceFeeNgwee, q.processingFeeNgwee, q.totalNgwee, d.distanceKm, d.approximate, d.durationMin + 10, unavailable)
    }

    override suspend fun parcelQuote(pickup: Place, dropoff: Place, insured: Boolean, declaredNgwee: Long): ParcelQuoteResult { delay(300); return v64.quote(pickup, dropoff, insured, declaredNgwee) }
    override suspend fun createParcel(r: NewParcel): String { delay(400); return v64.create(r) }
    override fun myParcels() = v64.list()
    override fun parcel(id: String) = v64.one(id)
    override fun parcelPickupPin(id: String) = v64.pin(id)
    override suspend fun retryParcelPayment(id: String, walletMsisdn: String) = v64.retry(id, walletMsisdn)
    override suspend fun cancelParcel(id: String) = v64.cancel(id)
    override suspend fun editParcelReceiver(id: String, name: String, msisdn: String) = v64.editReceiver(id, name, msisdn)
    override suspend fun confirmParcelPickup(id: String, pin: String) = rider.collectParcel(id, pin)
    override suspend fun setRiderAreas(cityId: String, neighbourhoods: List<String>) {
        delay(200); if (neighbourhoods.size !in 1..10) throw BackendException("Choose between 1 and 10 neighbourhoods.")
    }
    override suspend fun pingLocation(lat: Double, lng: Double, accuracy: Float?) { riderPos = lat to lng }
    private var riderPos: Pair<Double, Double>? = null

    override suspend fun requestDeliveryCode(kind: String, id: String, lat: Double?, lng: Double?, resend: Boolean, smsOnly: Boolean): CodeRequestResult {
        delay(500)
        val o = rider.activeJobs().first().firstOrNull { it.id == id } ?: throw BackendException("This delivery isn't assigned to you.")
        val drop = rider.placeOf(o.dropoff)
        val at = if (lat != null && lng != null) lat to lng else riderPos
        if (drop != null && at != null && selo.Nachipa01.domain.route.Geo.m(selo.Nachipa01.domain.route.LatLng(at.first, at.second), drop) > 150)
            throw BackendException("You are not at the delivery location yet.")
        val r = v64.issueCode(id, resend || smsOnly)
        rider.arrived(id)
        return r
    }
    override suspend fun verifyDeliveryCode(kind: String, id: String, code: String, lat: Double?, lng: Double?): CodeVerifyResult {
        delay(400)
        val r = v64.check(id, code)
        if (r.result != "verified") return r
        val o = rider.activeJobs().first().firstOrNull { it.id == id }
        val earnings = o?.let { if (it.kind == "parcel") selo.Nachipa01.domain.ParcelFees.split(it.deliveryNgwee).first else Fees.split(0, it.deliveryNgwee, PlatformConfig()).riderNetNgwee }
        rider.delivered(id, code, null)
        return r.copy(earningsNgwee = earnings)
    }
    override suspend fun receiverUnavailable(kind: String, id: String, lat: Double?, lng: Double?): Long {
        delay(300)
        val o = rider.activeJobs().first().firstOrNull { it.id == id } ?: throw BackendException("Not found.")
        val waited = System.currentTimeMillis() - (o.arrivedAt ?: System.currentTimeMillis())
        // the real rule is 10 minutes at the door; the demo shortens it to 10 seconds
        if (waited < 10_000) throw BackendException("Please wait at the drop-off a little longer before reporting the receiver unavailable (10 min; 10 s in the demo).")
        scope.launch { delay(6_000); rider.failed(id) }
        return System.currentTimeMillis() + 20 * 60_000
    }
    override suspend fun respondUnavailable(kind: String, id: String, action: String, name: String?, msisdn: String?) = v64.respond(id, action)
    override suspend fun confirmReceipt(kind: String, id: String) { if (kind == "parcel") v64.confirmReceipt(id) else confirmReceipt(id) }
    override suspend fun rateDelivery(kind: String, id: String, stars: Int, comment: String) { delay(200); if (stars !in 1..5) throw BackendException("Choose 1 to 5 stars.") }
    override suspend fun reportIssue(kind: String, id: String, type: String, description: String) = if (kind == "parcel") v64.reportIssue(id) else "D-${(1000..9999).random()}"
    override suspend fun ackDeliveryCode(kind: String, id: String) {}
    override suspend fun attachProofOfDelivery(kind: String, id: String, photo: Uri) { delay(300) }

    override suspend fun sendSignupOtp(msisdn: String, appContext: String): OtpChallenge { delay(400); return v64.sendOtp(msisdn) }
    override suspend fun verifySignupOtp(msisdn: String, pinId: String, pin: String): String { delay(300); return v64.verifyOtp(pinId, pin) }
    override suspend fun saveMyLocation(p: Place) { delay(200); v64.saveLocation(p) }
    override suspend fun nearbyStores(lat: Double, lng: Double) = v64.stores
    override suspend fun shopProfile(sellerUid: String): ShopProfile {
        delay(300)
        val items = DemoData.listings.filter { it.sellerUid == sellerUid && it.status == "live" }
        val name = items.firstOrNull()?.sellerName ?: v64.stores.firstOrNull { it.sellerUid == sellerUid }?.name ?: "SeloNaChipa shop"
        return ShopProfile(sellerUid, name, true, null, null, v64.stores.firstOrNull { it.sellerUid == sellerUid }?.address ?: "Lusaka", items)
    }
    override suspend fun setProfilePhoto(uri: android.net.Uri): String { delay(300); demoPhoto = uri.toString(); return uri.toString() }
    var demoPhoto: String? = null
    override suspend fun receipts(refId: String?): List<Receipt> {
        delay(200)
        val r = Receipt("rcpt_demo", "SNC-R-261126-4F2A9C", "order", refId ?: "SNC-4823390", "Issued by SeloNaChipa on behalf of Mama Bupe's Veg", "Mwila Chanda",
            "Mama Bupe's Veg", listOf(ReceiptLine("Fresh Chongwe tomatoes – 5 kg bag × 2", 16_000, "Mama Bupe's Veg"), ReceiptLine("Delivery (6.1 km)", 7_855, "the delivery rider"),
                ReceiptLine("Transaction fee", 239, "SeloNaChipa")), 24_094, "Airtel Money", "0977 ••• 512", System.currentTimeMillis() - 3_600_000, "SELO-COL-SNC-4823390", "demo")
        return listOf(r)
    }
    override suspend fun myQuestions(): List<SecurityQuestion> {
        delay(200)
        val chosen = session?.msisdn?.let { securityByMsisdn[it] }?.map { it.questionId } ?: listOf("q_mother_maiden_name", "q_first_school", "q_football_team")
        return chosen.map { id -> SecurityQuestion(id, selo.Nachipa01.domain.SecurityQuestions.textFor(id)) }
    }
    override suspend fun updateProfile(changes: Map<String, Any?>) {
        delay(250)
        val name = (changes["fullName"] as? String)?.trim()
        if (name != null && name.length < 3) throw BackendException("Enter your full name.")
        session = session?.let { s -> s.copy(fullName = name ?: s.fullName, firstName = name?.substringBefore(" ") ?: s.firstName, area = (changes["area"] as? String) ?: s.area) }
    }
    override fun notificationPrefs() = v64.prefs
    override suspend fun saveNotificationPrefs(prefs: Map<String, Boolean>) { delay(200); v64.prefs.value = prefs }
    override fun paymentWallets() = v64.wallets
    override suspend fun savePaymentWallets(wallets: List<String>) {
        delay(200); v64.wallets.value = wallets.map { Validation.normaliseMsisdn(it) ?: throw BackendException("$it isn't an Airtel, MTN or Zamtel number.") }.distinct()
    }
    override fun supportTickets() = v64.ticketList()
    override fun ticketMessages(id: String) = v64.ticketMsgs(id)
    override suspend fun createTicket(subject: String, category: String, message: String, orderRef: String): String { delay(400); return v64.newTicket(subject, category, message) }
    override suspend fun replyTicket(id: String, text: String) { delay(200); v64.reply(id, text) }
}
