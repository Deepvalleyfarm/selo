package selo.Nachipa01.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.domain.Cities
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.ParcelFees
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.push.DeliveryCodeStore
import kotlin.math.roundToInt

/**
 * Demo stand-ins for the v6.4 features: address search (landmarks + gazetteer + a shared store), parcels with a
 * simulated rider, 5-digit delivery codes, account settings and support. No network; same rules as the server.
 */
class DemoV64(private val scope: CoroutineScope, private val session: () -> SessionUser?) {
    private val now get() = System.currentTimeMillis()

    /* ---------------------------------------------------------------- addresses */
    private val landmarks = listOf(
        Triple("Soweto Market", -15.4235, 28.2733), Triple("Manda Hill Mall", -15.3980, 28.3060), Triple("Arcades Shopping Mall", -15.3990, 28.3220),
        Triple("Levy Junction", -15.4180, 28.2880), Triple("East Park Mall", -15.3940, 28.3150), Triple("Comesa Market", -15.4190, 28.2820),
        Triple("Woodlands Shoprite", -15.4300, 28.3280), Triple("UTH Main Gate", -15.4300, 28.3070), Triple("Kabulonga Shoprite", -15.4180, 28.3400),
        Triple("Munali Mall", -15.3890, 28.3770), Triple("Cosmopolitan Mall", -15.4560, 28.2780), Triple("Kitwe Freedom Park", -12.8150, 28.2120),
        Triple("Ndola Jacaranda Mall", -12.9700, 28.6330), Triple("Livingstone Mosi-oa-Tunya Rd", -17.8520, 25.8560),
    )
    private val store = MutableStateFlow(landmarks.mapIndexed { i, (n, la, lo) -> place("addr:lm$i", n, "$n, ${Cities.cityOf(la, lo)?.name ?: ""}", la, lo, "addr:lm$i") })
    private fun place(id: String, label: String, address: String, lat: Double, lng: Double, addressId: String? = null, approximate: Boolean = false): Place {
        val c = Cities.cityOf(lat, lng)
        return Place(id, label, address, lat, lng, c?.id, c?.name, Cities.nearestNeighbourhood(lat, lng), c != null, approximate, addressId)
    }

    suspend fun suggestions(input: String, cityId: String?): List<AddressSuggestion> {
        delay(180)
        val q = input.trim().lowercase(); if (q.length < 2) return emptyList()
        val fromStore = store.value.filter { (cityId == null || it.cityId == cityId) && (it.label.lowercase().contains(q) || it.address.lowercase().contains(q)) }
            .take(6).map { AddressSuggestion(it.id, it.label, it.address, "store") }
        val gaz = Cities.ALL.filter { cityId == null || it.id == cityId }.flatMap { c -> c.neighbourhoods.filter { it.lowercase().contains(q) }.map { AddressSuggestion("nb:${c.id}:$it", it, "${c.name} · neighbourhood", "gazetteer") } }
        return (fromStore + gaz).take(10)
    }
    suspend fun details(id: String): Place {
        delay(120)
        store.value.firstOrNull { it.id == id }?.let { return it }
        if (id.startsWith("nb:")) {
            val (_, cid, nb) = id.split(":", limit = 3); val c = Cities.byId(cid) ?: throw BackendException("Unknown city.")
            val (la, lo) = if (nb.isBlank()) c.lat to c.lng else Cities.neighbourhoodPoint(c, nb)
            return place(id, nb.ifBlank { c.name }, "${nb.ifBlank { "City centre" }}, ${c.name}", la, lo, approximate = true)
        }
        throw BackendException("That address is no longer available.")
    }
    suspend fun reverse(lat: Double, lng: Double): Place {
        delay(220)
        val near = store.value.minByOrNull { Cities.km(lat, lng, it.lat, it.lng) }?.takeIf { Cities.km(lat, lng, it.lat, it.lng) < 0.25 }
        val nb = Cities.nearestNeighbourhood(lat, lng)
        val label = near?.let { "Near ${it.label}" } ?: if (nb != null) "Pinned location near $nb" else "Pinned location"
        return place("pin:%.6f,%.6f".format(lat, lng), label, listOfNotNull(nb, Cities.cityOf(lat, lng)?.name).joinToString(", ").ifBlank { "%.5f, %.5f".format(lat, lng) }, lat, lng)
    }
    suspend fun distance(a: Place, b: Place): RouteDistance {
        delay(150)
        val km = (Cities.km(a.lat, a.lng, b.lat, b.lng) * 1.3 * 10).roundToInt() / 10.0
        return RouteDistance(km, (km / 25 * 60).roundToInt().coerceAtLeast(3), approximate = true,
            sameCity = Cities.cityOf(a.lat, a.lng)?.id == Cities.cityOf(b.lat, b.lng)?.id && a.inServiceArea, fromCity = a.cityName)
    }
    /** Same dedupe rule as the server: within 8 m, or same name within 30 m, is the same place. */
    fun save(p: Place): String {
        if (!p.inServiceArea) throw BackendException("That location is outside our service area.")
        store.value.firstOrNull { val m = Cities.km(p.lat, p.lng, it.lat, it.lng) * 1000; m <= 8 || (m <= 30 && it.label.equals(p.label, true)) }?.let { return it.addressId ?: it.id }
        val id = "addr:u${store.value.size}"
        store.value = store.value + p.copy(id = id, addressId = id)
        return id
    }
    private val mine = MutableStateFlow(listOf(SavedAddress("a1", "Home", place("addr:home", "Plot 214, Munali", "Plot 214, Great East Rd, Munali, Lusaka", -15.3890, 28.3700), true)))
    fun saved(): Flow<List<SavedAddress>> = mine
    fun saveMine(nick: String, p: Place, def: Boolean): String {
        val id = "a${now}"
        mine.value = mine.value.map { if (def) it.copy(isDefault = false) else it } + SavedAddress(id, nick.ifBlank { p.short }, p, def || mine.value.isEmpty())
        return id
    }
    fun deleteMine(id: String) { mine.value = mine.value.filterNot { it.id == id } }

    /** Demo sellers' shop locations, for the distance-based delivery fee. */
    fun sellerPlace(sellerUid: String) = when (sellerUid) { "s2" -> landmarks[3]; "s10" -> landmarks[12]; "s11" -> landmarks[7]; else -> landmarks[0] }
        .let { (n, la, lo) -> place("seller:$sellerUid", n, n, la, lo) }

    /* ---------------------------------------------------------------- parcels */
    private val parcels = MutableStateFlow<List<Parcel>>(emptyList())
    private val pins = mutableMapOf<String, String>()
    private val codes = mutableMapOf<String, String>()
    /** What the demo "receiver's phone" would show — only rendered in demo builds, labelled as such. */
    val demoReceiverInbox = MutableStateFlow<Map<String, String>>(emptyMap())
    private var seq = 1

    fun seedParcels() {
        if (parcels.value.isNotEmpty()) return
        val u = session() ?: return
        val a = place("p1", "Soweto Market", "Soweto Market, stall 14, Lusaka", -15.4235, 28.2733)
        val b = place("p2", "Woodlands Shoprite", "Woodlands Shoprite, Lusaka", -15.4300, 28.3280)
        val q = quote(a, b, false, 0)
        parcels.value = listOf(
            Parcel("PCL-7Q2M4X1", ParcelStatus.AWAITING_DELIVERY_CODE, iAmSender = false, senderName = "Bashi Tembo Electronics", pickup = a, dropoff = b,
                weightBand = "1–2 kg", description = "Phone charger and earphones", category = "Other", receiverName = u.fullName, receiverMsisdn = u.msisdn,
                deliveryInstructions = "Blue gate", distanceKm = q.distanceKm, approximate = true, etaMin = q.etaMin, deliveryNgwee = q.deliveryNgwee,
                serviceNgwee = q.serviceNgwee, insuranceNgwee = 0, totalNgwee = q.totalNgwee, riderName = "Chanda Mulenga", riderPlate = "BAK 4821",
                riderLat = -15.4301, riderLng = 28.3281, paymentStatus = "PAID", codeState = "sent", codeChannel = "push", createdAt = now - 50 * 60_000,
                timeline = tl(ParcelStatus.AWAITING_DELIVERY_CODE, now - 50 * 60_000)),
            Parcel("PCL-3K8D2Z9", ParcelStatus.COMPLETED, iAmSender = true, senderName = u.fullName, pickup = b, dropoff = a, weightBand = "Under 1 kg",
                description = "Signed contract", category = "Document", receiverName = "Joseph Banda", receiverMsisdn = "+260966451200", deliveryInstructions = "",
                distanceKm = q.distanceKm, approximate = true, etaMin = q.etaMin, deliveryNgwee = q.deliveryNgwee, serviceNgwee = q.serviceNgwee, insuranceNgwee = 0,
                totalNgwee = q.totalNgwee, riderName = "Grace Phiri", riderPlate = "BAZ 1122", paymentStatus = "PAID", codeState = "verified", codeChannel = "sms",
                createdAt = now - 3 * 86_400_000L, deliveredAt = now - 3 * 86_400_000L + 40 * 60_000, timeline = tl(ParcelStatus.COMPLETED, now - 3 * 86_400_000L)),
        )
        val code = "48213"
        DeliveryCodeStore.put(ReceivedCode("parcel", "PCL-7Q2M4X1", code, now + 14 * 60_000, now - 60_000))
    }
    private fun tl(upTo: ParcelStatus, start: Long): List<TimelineEvent> {
        val order = listOf(ParcelStatus.PENDING_PAYMENT, ParcelStatus.AWAITING_RIDER, ParcelStatus.RIDER_ASSIGNED, ParcelStatus.IN_TRANSIT, ParcelStatus.AWAITING_DELIVERY_CODE, ParcelStatus.DELIVERED, ParcelStatus.COMPLETED)
        return order.take(order.indexOf(upTo) + 1).mapIndexed { i, s -> TimelineEvent(s.name, start + i * 7 * 60_000L) }
    }

    fun quote(a: Place, b: Place, insured: Boolean, declared: Long): ParcelQuoteResult {
        Cities.sameCityProblem(a.lat, a.lng, b.lat, b.lng)?.let { throw BackendException(it) }
        val km = (Cities.km(a.lat, a.lng, b.lat, b.lng) * 1.3 * 10).roundToInt() / 10.0
        val d = ParcelFees.delivery(km); val s = ParcelFees.sending(d); val ins = if (insured) ParcelFees.insurance(declared) else 0
        return ParcelQuoteResult(km, true, d, s, ins, d + s + ins, (km / 25 * 60).roundToInt() + 5, a.cityName ?: "")
    }

    fun create(r: NewParcel): String {
        val u = session() ?: throw BackendException("Please log in again.")
        if (parcels.value.count { it.iAmSender && it.status.active } >= 10) throw BackendException("You can have up to 10 active parcels at a time.")
        if (Validation.normaliseMsisdn(r.receiverMsisdn) == null) throw BackendException("Enter the receiver's Airtel, MTN or Zamtel number — the delivery code is sent to it.")
        val q = quote(r.pickup, r.dropoff, r.insured, r.declaredValueNgwee)
        val id = "PCL-" + (1_000_000 + seq++ * 7919).toString(36).uppercase().padStart(7, '0').takeLast(7)
        pins[id] = "%04d".format((1000..9998).random())
        parcels.value = listOf(Parcel(id, ParcelStatus.PENDING_PAYMENT, true, u.fullName, r.pickup, r.dropoff, r.weightBand, r.description, r.category,
            r.receiverName, Validation.normaliseMsisdn(r.receiverMsisdn)!!, r.deliveryInstructions, q.distanceKm, q.approximate, q.etaMin, q.deliveryNgwee,
            q.serviceNgwee, q.insuranceNgwee, q.totalNgwee, walletMsisdn = r.walletMsisdn, createdAt = now, scheduledFor = r.scheduledFor,
            trackingUrl = "https://mabala-f2d65.web.app/t/demo${id.takeLast(5)}", timeline = listOf(TimelineEvent("PENDING_PAYMENT", now)))) + parcels.value
        simulate(id)
        return id
    }
    private fun upd(id: String, f: (Parcel) -> Parcel) { parcels.value = parcels.value.map { if (it.id == id) f(it) else it } }
    private fun step(p: Parcel, s: ParcelStatus, extra: (Parcel) -> Parcel = { it }) = extra(p.copy(status = s, timeline = p.timeline + TimelineEvent(s.name, now)))

    private fun simulate(id: String) = scope.launch {
        delay(1_500)
        val p = parcels.value.first { it.id == id }
        if ((p.walletMsisdn ?: "").endsWith("00")) { upd(id) { it.copy(paymentStatus = "FAILED", paymentFailure = "Insufficient funds on ${Validation.network(it.walletMsisdn!!)} ${Validation.maskMsisdn(it.walletMsisdn!!)}") }; return@launch }
        upd(id) { step(it, ParcelStatus.AWAITING_RIDER) { x -> x.copy(paymentStatus = "PAID", paymentFailure = null) } }
        if (p.scheduledFor != null) return@launch
        delay(4_000); if (parcels.value.first { it.id == id }.status != ParcelStatus.AWAITING_RIDER) return@launch
        upd(id) { step(it, ParcelStatus.RIDER_ASSIGNED) { x -> x.copy(riderName = "Chanda Mulenga", riderPlate = "BAK 4821", riderLat = x.pickup.lat + .01, riderLng = x.pickup.lng) } }
        delay(8_000)
        upd(id) { step(it, ParcelStatus.IN_TRANSIT) { x -> x.copy(riderLat = x.pickup.lat, riderLng = x.pickup.lng) } }
        delay(8_000)
        val code = "%05d".format((10_000..99_998).random()); codes[id] = code
        val cur = parcels.value.first { it.id == id }
        val toMe = cur.receiverMsisdn == session()?.msisdn
        if (toMe) DeliveryCodeStore.put(ReceivedCode("parcel", id, code, now + 15 * 60_000, now))
        upd(id) { step(it, ParcelStatus.AWAITING_DELIVERY_CODE) { x -> x.copy(riderLat = x.dropoff.lat, riderLng = x.dropoff.lng, codeState = "sent", codeChannel = if (toMe) "push" else "sms") } }
        delay(15_000) // the receiver reads the code out; the rider enters it
        if (parcels.value.first { it.id == id }.status != ParcelStatus.AWAITING_DELIVERY_CODE) return@launch
        upd(id) { step(it, ParcelStatus.DELIVERED) { x -> x.copy(codeState = "verified", deliveredAt = now) } }
        DeliveryCodeStore.clear("parcel", id)
    }

    fun list(): Flow<List<Parcel>> { seedParcels(); return parcels }
    fun one(id: String): Flow<Parcel?> { seedParcels(); return parcels.map { l -> l.firstOrNull { it.id == id } } }
    fun pin(id: String): Flow<String?> = parcels.map { l -> l.firstOrNull { it.id == id }?.takeIf { it.iAmSender }?.let { pins[id] ?: "4821" } }
    suspend fun retry(id: String, wallet: String) { delay(300); upd(id) { it.copy(walletMsisdn = wallet, paymentStatus = "PENDING", paymentFailure = null) }; simulate(id) }
    suspend fun cancel(id: String): Long {
        delay(300)
        val p = parcels.value.firstOrNull { it.id == id } ?: throw BackendException("Parcel not found.")
        if (p.status !in listOf(ParcelStatus.PENDING_PAYMENT, ParcelStatus.AWAITING_RIDER)) throw BackendException("A rider is already assigned. Contact support to cancel.")
        upd(id) { step(it, ParcelStatus.CANCELLED) }
        return if (p.status == ParcelStatus.AWAITING_RIDER) p.totalNgwee else 0
    }
    suspend fun editReceiver(id: String, name: String, msisdn: String) {
        delay(250)
        val m = Validation.normaliseMsisdn(msisdn) ?: throw BackendException("Enter a valid Airtel, MTN or Zamtel number.")
        val p = parcels.value.first { it.id == id }
        if (p.status !in listOf(ParcelStatus.PENDING_PAYMENT, ParcelStatus.AWAITING_RIDER, ParcelStatus.RIDER_ASSIGNED)) throw BackendException("The parcel is already on its way. Contact support to change the receiver.")
        upd(id) { it.copy(receiverName = name, receiverMsisdn = m) }
    }
    suspend fun respond(id: String, action: String) {
        delay(250)
        when (action) {
            "cancel" -> upd(id) { step(it, ParcelStatus.DELIVERY_FAILED) { x -> x.copy(returnStatus = "RETURN_TO_SENDER", unavailableResponse = "cancel") } }
            "wait" -> upd(id) { it.copy(unavailableResponse = "wait", unavailableDeadline = (it.unavailableDeadline ?: now) + 20 * 60_000) }
            else -> upd(id) { it.copy(unavailableResponse = "alt_contact") }
        }
    }
    suspend fun confirmReceipt(id: String) { delay(200); upd(id) { step(it, ParcelStatus.COMPLETED) } }
    suspend fun reportIssue(id: String): String { delay(300); upd(id) { step(it, ParcelStatus.DISPUTED) }; return "D-${(1000..9999).random()}" }

    /* ---------------------------------------------------------------- rider-side codes (orders and parcels) */
    private val attempts = mutableMapOf<String, Int>()
    private val issued = mutableMapOf<String, Int>()
    fun issueCode(id: String, resend: Boolean): CodeRequestResult {
        val n = issued[id] ?: 0
        if (codes[id] != null && !resend && (attempts[id] ?: 0) < 5) return CodeRequestResult("active", "sms", now + 15 * 60_000, 5 - (attempts[id] ?: 0), 3 - n)
        if (n >= 3) throw BackendException("No more codes can be sent for this order. Tap Receiver Unavailable or contact support.")
        val code = "%05d".format((10_000..99_998).random())
        codes[id] = code; attempts[id] = 0; issued[id] = n + 1
        demoReceiverInbox.value = demoReceiverInbox.value + (id to code)
        return CodeRequestResult("sent", "sms", now + 15 * 60_000, 5, 3 - n - 1)
    }
    fun check(id: String, code: String): CodeVerifyResult {
        val c = codes[id] ?: return CodeVerifyResult("no_code")
        val a = (attempts[id] ?: 0)
        if (a >= 5) return CodeVerifyResult("locked")
        if (c == code) { attempts[id] = a + 1; demoReceiverInbox.value = demoReceiverInbox.value - id; codes.remove(id); return CodeVerifyResult("verified") }
        attempts[id] = a + 1
        return if (a + 1 >= 5) CodeVerifyResult("locked") else CodeVerifyResult("wrong", 5 - a - 1)
    }
    /** The pickup PIN the sender would read to the rider (demo only shows it on the rider screen). */
    val riderJobPins = mutableMapOf<String, String>()

    /* ---------------------------------------------------------------- account + support */
    var lastOtp: String? = null; private set
    fun sendOtp(msisdn: String): Int { if (Validation.normaliseMsisdn(msisdn) == null) throw BackendException("Enter a valid Airtel, MTN or Zamtel number."); lastOtp = "%06d".format((0..999_999).random()); return 600 }
    fun verifyOtp(code: String): String { if (code != lastOtp) throw BackendException("That code isn't right."); return "demo-otp-token-0000000000000000" }
    val prefs = MutableStateFlow(mapOf("orders" to true, "promotions" to false, "chat" to true, "deliveryCodeSms" to true, "weeklySummary" to true))
    val wallets = MutableStateFlow<List<String>>(emptyList())
    private val tickets = MutableStateFlow(listOf(SupportTicket("t1", "SUP-8F2K1A", "Refund for cancelled order", "Payment or refund", "RESOLVED", "Your refund of K 24.00 was sent to Airtel Money.", true, 0, now - 5 * 86_400_000L)))
    private val messages = MutableStateFlow(mapOf("t1" to listOf(
        TicketMessage("m1", false, "My order SNC-48012 was cancelled but I haven't received my money.", now - 6 * 86_400_000L),
        TicketMessage("m2", true, "Your refund of K 24.00 was sent to Airtel Money. It can take up to 2 hours to show.", now - 5 * 86_400_000L, "Natasha (Support)"))))
    fun ticketList(): Flow<List<SupportTicket>> = tickets
    fun ticketMsgs(id: String): Flow<List<TicketMessage>> = messages.map { it[id].orEmpty() }
    fun newTicket(subject: String, category: String, message: String): String {
        if (message.trim().length < 10) throw BackendException("Tell us a bit more (at least 10 characters).")
        val id = "t${now}"; val ref = "SUP-" + id.takeLast(6)
        tickets.value = listOf(SupportTicket(id, ref, subject, category, "OPEN", message.take(140), false, 0, now)) + tickets.value
        messages.value = messages.value + (id to listOf(TicketMessage("m$now", false, message, now)))
        scope.launch { delay(3_000); reply(id, "Thanks for getting in touch. We've received $ref and an agent will reply within 24 hours.", true) }
        return id
    }
    fun reply(id: String, text: String, fromSupport: Boolean = false) {
        messages.value = messages.value + (id to (messages.value[id].orEmpty() + TicketMessage("m$now", fromSupport, text, now, if (fromSupport) "SeloNaChipa Support" else null)))
        tickets.value = tickets.value.map { if (it.id == id) it.copy(lastMessage = text.take(140), lastFromSupport = fromSupport, status = if (fromSupport) "AWAITING_USER" else "OPEN",
            unread = if (fromSupport) it.unread + 1 else 0, updatedAt = now) else it }
    }
    @Suppress("unused") fun fmt(n: Long) = Fees.formatK(n)
}
