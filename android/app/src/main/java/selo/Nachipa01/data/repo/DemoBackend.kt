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
import selo.Nachipa01.data.model.FeedGuard
import selo.Nachipa01.data.model.FeedPage
import selo.Nachipa01.data.model.Rail
import selo.Nachipa01.data.model.SearchFilters
import selo.Nachipa01.data.model.SearchResult
import selo.Nachipa01.data.model.SellerDashboard
import selo.Nachipa01.data.model.SellerListingCard
import selo.Nachipa01.data.model.Wishlist
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
        l("l1", "s1", "Mama Bupe's Veg", "Fresh Chongwe tomatoes – 5 kg bag", "Firm, deep-red tomatoes picked this morning in Chongwe. Ideal for relish and stews. Packed at Soweto Market, stall 14.", 8_000, "Fresh produce", "Soweto", true, 0xFFB0281A, 0xFFF2B33D, 820, 4100, tags = listOf("tomatoes", "chongwe")),
        l("l2", "s1", "Mama Bupe's Veg", "Rape bundle, freshly cut", "Tender rape leaves cut at dawn. Bundle feeds a family of four.", 1_000, "Fresh produce", "Soweto", false, 0xFF1B5E20, 0xFF7CB342, 140, 900),
        l("l3", "s2", "Bashi Tembo Electronics", "Smartphone 128 GB, dual SIM", "Dual SIM, 6.5\" screen, 5000 mAh battery. One-year shop warranty.", 245_000, "Phones & accessories", "Lusaka CBD", true, 0xFF1A1A2E, 0xFF4B3B8F, 2300, 11000, disc = 8.0, tags = listOf("phone", "smartphone")),
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
    private var session: SessionUser? = null
    private val orders = MutableStateFlow<Map<String, Order>>(emptyMap())
    private val online = MutableStateFlow(true)

    override fun currentUser() = session
    override suspend fun restoreSession(): SessionUser? = null
    override fun config(): Flow<PlatformConfig> = flowOf(PlatformConfig())
    override fun adverts(): Flow<List<Advert>> = flowOf(DemoData.adverts)
    override suspend fun questionBank() = DemoData.questions

    override suspend fun login(msisdn: String, pin: String): SessionUser {
        delay(600)
        Validation.pinProblem(pin)?.let { throw BackendException("Incorrect PIN. 4 attempts left before a 30-minute lock.") }
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

    private fun simulate(id: String) = scope.launch {
        delay(2500); advance(id, OrderState.ESCROW_LOCKED)
        delay(3000); advance(id, OrderState.RIDER_ASSIGNED, "Chanda M.")
        delay(4000); advance(id, OrderState.PICKED_UP)
        delay(3000); advance(id, OrderState.IN_TRANSIT)
    }

    override suspend fun checkout(req: CheckoutRequest): String {
        val goods = req.items.sumOf { it.unitNgwee * it.qty }
        val delivery = Fees.deliveryFee(req.distanceKm, PlatformConfig())
        val id = "SNC-${(48_200..48_999).random()}"
        put(Order(id, "demo-buyer", req.sellerUid, "Mama Bupe's Veg", null, null, req.items, goods, delivery, goods + delivery,
            OrderState.INITIATED, "Lusaka Central", req.dropoff, "Soweto Market, stall 14",
            deliveryCode = "%06d".format((100_000..999_999).random()), distanceKm = req.distanceKm, lipilaRef = "SELO-COL-$id"))
        simulate(id); return id
    }

    override suspend fun sendParcel(req: ParcelRequest): String {
        val q = Fees.quote(0, req.distanceKm, PlatformConfig(), if (req.insured) req.declaredValueNgwee else 0)
        val id = "PCL-${(20_000..29_999).random()}"
        val ins = if (req.insured) "INS-" + List(4) { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("") +
            "-" + List(4) { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("") else null
        put(Order(id, "demo-buyer", "", req.sender, null, null, listOf(OrderItem("", req.contents, 1, 0)), 0, q.deliveryNgwee,
            q.totalNgwee, OrderState.INITIATED, "Lusaka", req.dropoff, req.pickup, "%06d".format((100_000..999_999).random()),
            req.distanceKm, true, ins, if (req.insured) req.declaredValueNgwee else 0, "SELO-COL-$id"))
        simulate(id); return id
    }

    override suspend fun confirmReceipt(orderId: String) { advance(orderId, OrderState.DELIVERED) }

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

    override fun riderStats() = online.map { DemoData.riderStats }
    override fun availableJobs(): Flow<List<Order>> = online.map { on ->
        if (!on) emptyList() else listOf(Order("SNC-48240", "b3", "s1", "Mama Bupe's Veg",
            items = listOf(OrderItem("l1", "Tomatoes 5 kg", 2, 8_000)), goodsNgwee = 16_000, deliveryNgwee = 4_000, totalNgwee = 20_000,
            state = OrderState.BROADCASTING, zone = "Lusaka Central", dropoff = "Woodlands Ext., near Shoprite",
            pickup = "Soweto Market, stall 14", distanceKm = 4.6))
    }
    override suspend fun setOnline(online: Boolean) { this.online.value = online }
    private val riderJobs = MutableStateFlow<List<Order>>(emptyList())
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
        return FeedPage(sessionId ?: "ses_demo_${now}", out.map { it.copy(rankSource = if (g.capReached) "unranked_cap" else "demo") }, "control", 0, g)
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
            SellerListingCard(it.id, it.title, it.status, it.thumbUrl, it.mediaType, it.priceNgwee, it.views, it.likes, it.shares, it.enquiries, System.currentTimeMillis() - 86_400_000L * (it.id.length))
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

    override suspend fun acceptJob(orderId: String) {
        delay(400)
        riderJobs.value = listOf(Order(orderId, "b3", "s1", "Mama Bupe's Veg", "demo-rider", "Chanda M.",
            listOf(OrderItem("l1", "Tomatoes 5 kg", 2, 8_000)), 16_000, 4_000, 20_000, OrderState.RIDER_ASSIGNED, "Lusaka Central",
            "Woodlands Ext., near Shoprite", "Soweto Market, stall 14", distanceKm = 4.6))
    }
    override fun riderActiveJobs(): Flow<List<Order>> = riderJobs
    override suspend fun markCollected(orderId: String) {
        delay(400); riderJobs.value = riderJobs.value.map { if (it.id == orderId) it.copy(state = OrderState.IN_TRANSIT) else it }
    }
    override suspend fun confirmDeliveryCode(orderId: String, code: String) {
        delay(400)
        if (code.length != 6) throw BackendException("That code doesn't match. Ask the buyer to check their app.")
        riderJobs.value = riderJobs.value.filterNot { it.id == orderId }
    }
}
