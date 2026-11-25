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

    val listings = listOf(
        Listing("l1", "s1", "Mama Bupe's Veg", "Fresh tomatoes 5 kg", "Firm, deep-red tomatoes from Chongwe.", 8_000, "Fresh produce", "Lusaka Central", stock = 14, views = 820, saves = 64),
        Listing("l2", "s1", "Mama Bupe's Veg", "Rape bundle", "Freshly cut this morning.", 1_000, "Fresh produce", "Lusaka Central", stock = 40, views = 310, saves = 12),
        Listing("l3", "s2", "Bashi Tembo Electronics", "Smartphone 128 GB", "Dual SIM, 1-year warranty.", 245_000, "Electronics", "Lusaka CBD", stock = 6, views = 2_100, saves = 230),
        Listing("l4", "s3", "Comesa Fashions", "Chitenge fabric 6 yd", "Wax print, 3 colours.", 12_000, "Fashion", "Comesa", stock = 30, views = 540, saves = 41),
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

    override suspend fun runAiPipeline(mediaPaths: List<String>, prompt: String?, onStep: (Int) -> Unit): AiDraft {
        for (s in 0..4) { onStep(s); delay(900) }
        onStep(5)
        return AiDraft("Fresh Chongwe Tomatoes – 5 kg Bag",
            "Firm, deep-red tomatoes picked this morning in Chongwe. Ideal for relish and stews. Sold in 5 kg bags, packed at Soweto Market.",
            8_500, 7_800, 9_200, "Fresh produce", listOf("tomatoes", "chongwe", "relish"), mediaPaths, 14, 31)
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
