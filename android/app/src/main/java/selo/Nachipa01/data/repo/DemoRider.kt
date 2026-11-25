package selo.Nachipa01.data.repo

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Validation
import kotlin.math.roundToLong

/**
 * Rider side of the demo backend (PRD 6.x). Same money rules as the server: 8% platform fee and 5% Social Fund
 * per delivery fee, +10% Hero bonus on the net. Jobs arrive a few seconds after going online.
 */
class DemoRider(private val scope: CoroutineScope, private val cfg: PlatformConfig = PlatformConfig()) {
    private val now get() = System.currentTimeMillis()
    private val day = 86_400_000L
    private val areas = listOf("Soweto Market" to "Woodlands", "Kabulonga" to "Rhodes Park", "Manda Hill" to "Olympia", "Arcades" to "Kalingalinga",
        "Levy Mall" to "Longacres", "East Park" to "Chelston", "Comesa Market" to "Matero", "Woodlands" to "Ibex Hill", "Northmead" to "Roma")
    private val items = listOf("Tomatoes 5 kg × 2", "Bluetooth speaker", "Chitenge fabric × 3", "Rape bundle × 4", "Phone charger", "Shea butter × 2",
        "Village chicken", "Solar lamp", "Rice 10 kg", "Kapenta 2 kg")

    private fun delivery(i: Int, at: Long): RiderDelivery {
        val (p, d) = areas[i % areas.size]
        val gross = 2_500L + (i * 733 % 3_000)
        val s = Fees.split(0, gross, cfg)
        val bonus = 0L
        return RiderDelivery("SNC-${48_100 + i}", p, d, "$p, stall ${10 + i % 30}", "$d, house ${100 + i * 7 % 300}", items[i % items.size],
            gross, s.riderPlatformFeeNgwee, s.socialFundNgwee, bonus, s.riderNetNgwee + bonus, 2.0 + (i * 37 % 60) / 10.0, at, podUrl = null)
    }

    private val history = MutableStateFlow(buildList {
        var i = 0
        // 7 today, then a few a day for the last 30 days
        repeat(7) { add(delivery(i++, now - (it + 1) * 55 * 60_000L)) }
        for (d in 1..29) repeat(1 + (d * 7) % 4) { add(delivery(i++, now - d * day - it * 3_600_000L - 9 * 3_600_000L)) }
    })

    private val profile = MutableStateFlow(RiderProfile("demo-rider", "Chanda Mulenga", "+260971234514", "Kabulonga", "Lusaka", "Motorbike", "BAK 4821",
        tier = "Rising", rating = 4.8, ratingCount = 212, totalDeliveries = 236, completionRate = 98, acceptanceRate = 92,
        emergencyName = "Mutale Mulenga", emergencyPhone = "+260966451200", payoutWallet = "+260977347823", memberSince = "Mar 2026",
        weeklyTargetNgwee = 100_000, autoSettle = true))

    private val fund = MutableStateFlow(41_250L)
    private val available = MutableStateFlow(18_600L)
    private var accepted = 46; private var offered = 50

    val stats: Flow<RiderStats> get() = combine(history, profile, fund, available, activeJobs()) { h, p, f, a, act ->
        val today = h.filter { it.at > startOfDay() }
        val week = h.filter { it.at > now - 7 * day }
        val weekFund = (6 downTo 0).map { d -> h.filter { it.at in (startOfDay() - d * day) until (startOfDay() - d * day + day) }.sumOf { it.fundNgwee } }
        RiderStats(today.sumOf { it.netNgwee }, today.size, act.size, p.acceptanceRate, f, p.totalDeliveries, a, p.tier, weekFund,
            weekNetNgwee = week.sumOf { it.netNgwee }, todayKm = today.sumOf { it.km }, fundTodayNgwee = today.sumOf { it.fundNgwee },
            bonusWeekNgwee = week.sumOf { it.bonusNgwee })
    }

    private fun startOfDay(): Long { val c = java.util.Calendar.getInstance(); c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); return c.timeInMillis }

    // ---------------- dispatch
    val online = MutableStateFlow(false)
    private val incoming = MutableStateFlow<Order?>(null)
    private val active = MutableStateFlow<List<Order>>(emptyList())
    private var nextJob: Job? = null
    private var jobNo = 0

    /** Pickup PINs the senders of demo parcel jobs would read out (shown to the tester in demo builds only). */
    val parcelPins = mutableMapOf<String, String>()

    private fun newJob(): Order {
        jobNo++
        val (p, d) = areas[(jobNo + 2) % areas.size]
        if (jobNo % 3 == 0) { // every third job is a parcel
            val fee = selo.Nachipa01.domain.ParcelFees.delivery(4.6)
            val id = "PCL-D${(1000 + jobNo * 37)}"
            parcelPins[id] = "%04d".format(1000 + (jobNo * 7919) % 8999)
            return Order(id, "sender-d", "sender-d", "Grace Phiri", items = listOf(OrderItem("", "Signed contract documents", 1, 0)), goodsNgwee = 0,
                deliveryNgwee = fee, totalNgwee = fee, state = OrderState.BROADCASTING, zone = "Kabulonga", dropoff = "$d, House ${100 + jobNo * 7}",
                pickup = "$p, Office 3", distanceKm = 4.6, buyerName = "Joseph Banda", buyerMsisdn = "+260966451200", itemsSummary = "Signed contract documents",
                kind = "parcel", weightBand = "Under 1 kg", riderEarningsNgwee = selo.Nachipa01.domain.ParcelFees.split(fee).first,
                pickupLat = -15.42, pickupLng = 28.29, dropLat = -15.40, dropLng = 28.33)
        }
        val fee = 3_500L + jobNo * 250
        return Order("SNC-${48_240 + jobNo}", "b3", "s1", if (jobNo % 2 == 1) "Mama Bupe's Veg" else "Bashi Tembo Electronics",
            items = listOf(OrderItem("l1", if (jobNo % 2 == 1) "Fresh tomatoes 5 kg" else "Bluetooth speaker, 20 W", 2, 8_000)), goodsNgwee = 16_000,
            deliveryNgwee = fee, totalNgwee = 16_000 + fee, state = OrderState.BROADCASTING, zone = "Kabulonga",
            dropoff = "$d, Plot ${200 + jobNo * 13}, near Shoprite", pickup = "$p, stall 14", distanceKm = 4.6,
            buyerName = "Mwila Chanda", buyerMsisdn = "+260971234512", itemsSummary = "2 × ${if (jobNo % 2 == 1) "Fresh tomatoes 5 kg" else "Bluetooth speaker"}",
            pickupLat = -15.42, pickupLng = 28.29, dropLat = -15.40, dropLng = 28.33)
    }

    fun setOnline(on: Boolean, firstJobAfterMs: Long = 3_000) {
        online.value = on
        nextJob?.cancel()
        if (!on) { incoming.value = null; return }
        schedule(firstJobAfterMs)
    }
    private fun schedule(ms: Long) { nextJob?.cancel(); nextJob = scope.launch { delay(ms); if (online.value && active.value.size < cfg.maxConcurrentDeliveries) incoming.value = newJob() } }

    fun availableJobs(): Flow<List<Order>> = combine(online, incoming) { on, j -> if (on && j != null) listOf(j) else emptyList() }
    fun activeJobs(): Flow<List<Order>> = active

    suspend fun accept(id: String) {
        delay(300)
        val j = incoming.value?.takeIf { it.id == id } ?: throw BackendException("Order already taken.")
        incoming.value = null; accepted++; offered++
        active.value = active.value + j.copy(state = OrderState.RIDER_ASSIGNED, riderUid = "demo-rider", riderName = "Chanda M.")
        profile.value = profile.value.copy(acceptanceRate = accepted * 100 / offered)
    }
    suspend fun decline(id: String) {
        delay(150)
        if (incoming.value?.id == id) incoming.value = null
        offered++; profile.value = profile.value.copy(acceptanceRate = accepted * 100 / offered)
        schedule(20_000)
    }
    suspend fun collected(id: String) { delay(300); active.value = active.value.map { if (it.id == id) it.copy(state = OrderState.IN_TRANSIT) else it } }
    suspend fun collectParcel(id: String, pin: String) {
        delay(300)
        if (parcelPins[id] != null && parcelPins[id] != pin) throw BackendException("That Pickup PIN doesn't match. Ask the sender to read it from their parcel screen.")
        collected(id)
    }
    fun arrived(id: String) { active.value = active.value.map { if (it.id == id && it.state == OrderState.IN_TRANSIT) it.copy(state = OrderState.AWAITING_DELIVERY_CODE, arrivedAt = System.currentTimeMillis()) else it } }
    fun failed(id: String) { active.value = active.value.filterNot { it.id == id } }
    suspend fun delivered(id: String, code: String, pod: Uri?) {
        delay(400)
        val o = active.value.firstOrNull { it.id == id } ?: throw BackendException("Order not found.")
        val s = if (o.kind == "parcel") selo.Nachipa01.domain.ParcelFees.split(o.deliveryNgwee).let { (r, pl, f) -> selo.Nachipa01.domain.PayoutSplit(0, 0, r, pl, f) } else Fees.split(0, o.deliveryNgwee, cfg)
        val bonus = if (RiderTiers.isHero(profile.value.tier)) (s.riderNetNgwee * cfg.heroBonusPct / 100).roundToLong() else 0
        history.value = listOf(RiderDelivery(o.id, o.pickup.substringBefore(","), o.dropoff.substringBefore(","), o.pickup, o.dropoff,
            o.itemsSummary.ifBlank { o.items.joinToString { "${it.title} × ${it.qty}" } }, o.deliveryNgwee, s.riderPlatformFeeNgwee, s.socialFundNgwee,
            bonus, s.riderNetNgwee + bonus, o.distanceKm, now, pod?.toString())) + history.value
        fund.value += s.socialFundNgwee
        if (!profile.value.autoSettle) available.value += s.riderNetNgwee + bonus
        val total = profile.value.totalDeliveries + 1
        profile.value = profile.value.copy(totalDeliveries = total, tier = if (profile.value.tier == "Ambassador") "Ambassador" else RiderTiers.forDeliveries(total))
        active.value = active.value.filterNot { it.id == id }
        notes.value = listOf(AppNotification("n${now}", "PAYOUT", "Delivery confirmed", "${Fees.formatK(s.riderNetNgwee + bonus)} added for ${o.id}. " +
            "${Fees.formatK(s.socialFundNgwee)} went to your Social Fund.", now, false)) + notes.value
        if (online.value) schedule(8_000)
    }

    // ---------------- profile, history, zones, hours
    fun profile(): Flow<RiderProfile?> = profile
    fun update(changes: Map<String, Any?>) {
        var p = profile.value
        changes.forEach { (k, v) -> p = when (k) {
            "emergencyName" -> p.copy(emergencyName = v as String); "emergencyPhone" -> p.copy(emergencyPhone = v as String)
            "vehicle" -> p.copy(vehicle = v as String); "plate" -> p.copy(plate = (v as String).uppercase())
            "weeklyTargetNgwee" -> p.copy(weeklyTargetNgwee = (v as Number).toLong()); "autoSettle" -> p.copy(autoSettle = v as Boolean)
            "photoUrl" -> p.copy(photoUrl = v as String?); else -> p } }
        profile.value = p
    }
    fun setWallet(m: String) { profile.value = profile.value.copy(payoutWallet = m) }
    fun history(): Flow<List<RiderDelivery>> = history

    private val zones = MutableStateFlow(listOf(
        DeliveryZone("kabulonga", "Kabulonga", "Lusaka", listOf("Kabulonga", "Woodlands", "Ibex Hill", "Leopards Hill Rd"), 5.0, true, primary = true),
        DeliveryZone("cbd", "Lusaka CBD", "Lusaka", listOf("Cairo Rd", "Kamwala", "Soweto Market", "Lusaka Central"), 4.0, false),
        DeliveryZone("munali", "Munali", "Lusaka", listOf("Munali", "Chelston", "Kalingalinga", "Mtendere"), 6.0, false),
        DeliveryZone("longacres", "Longacres", "Lusaka", listOf("Longacres", "Rhodes Park", "Olympia", "Fairview"), 4.5, false),
    ))
    fun zones(): Flow<List<DeliveryZone>> = zones
    suspend fun addZone(id: String) { delay(250); zones.value = zones.value.map { if (it.id == id) it.copy(active = true) else it } }
    private val hours = MutableStateFlow(WorkingDay.DEFAULT.map { if (it.day == 5) it.copy(start = "08:00", end = "14:00") else it })
    fun hours(): Flow<List<WorkingDay>> = hours
    fun setHours(d: List<WorkingDay>) { hours.value = d }

    // ---------------- money
    suspend fun withdraw(amount: Long): String {
        delay(400)
        val fee = Fees.kwachaToNgwee(cfg.riderWithdrawalFee)
        if (amount - fee < Fees.kwachaToNgwee(cfg.minWithdrawal)) throw BackendException("Minimum withdrawal is K${(cfg.minWithdrawal + cfg.riderWithdrawalFee).toInt()} (K${cfg.minWithdrawal.toInt()} payout + K${cfg.riderWithdrawalFee.toInt()} fee).")
        if (amount > available.value) throw BackendException("That's more than your available balance.")
        available.value -= amount
        return "WD-${(10_000..99_999).random()}"
    }

    // ---------------- Social Fund claims
    private val claims = MutableStateFlow(listOf(SocialFundClaim("C-288", ClaimCategory.BIKE.label, 32_000, "Chain and sprocket after a pothole on Leopards Hill Rd", "PAID", now - 21 * day)))
    fun claims(): Flow<List<SocialFundClaim>> = claims
    suspend fun fileClaim(c: ClaimCategory, amount: Long, desc: String, photo: Uri?): String {
        delay(500)
        if (c.heroOnly && !RiderTiers.isHero(profile.value.tier)) throw BackendException("School fees claims unlock at Hero tier (300 deliveries).")
        if (c.photoRequired && photo == null) throw BackendException("Add a photo of the damage for a bike repair claim.")
        if (amount <= 0 || amount > fund.value) throw BackendException("Claims can be up to your fund balance of ${Fees.formatK(fund.value)}.")
        val id = "C-${(300..999).random()}"
        claims.value = listOf(SocialFundClaim(id, c.label, amount, desc, "PENDING", now)) + claims.value
        return id
    }

    // ---------------- messages and notifications
    private val threads = MutableStateFlow(listOf(
        ChatThread("t1", "Buyer — SNC-48107", "buyer", "SNC-48107", "Please call when you reach the blue gate.", now - 12 * 60_000, 1),
        ChatThread("t2", "Seller — Mama Bupe's Veg — SNC-48107", "seller", "SNC-48107", "Packed and ready at stall 14.", now - 25 * 60_000, 0),
        ChatThread("t3", "Buyer — SNC-48102", "buyer", "SNC-48102", "Thank you, all received!", now - 3 * 3_600_000, 0),
    ))
    private val messages = MutableStateFlow(mapOf(
        "t1" to listOf(ChatMessage("m1", "Hello, I'm Chanda, your rider. I've collected your tomatoes.", true, now - 20 * 60_000),
            ChatMessage("m2", "Great! Plot 214, Woodlands Ext.", false, now - 15 * 60_000),
            ChatMessage("m3", "Please call when you reach the blue gate.", false, now - 12 * 60_000)),
        "t2" to listOf(ChatMessage("m4", "I'm 5 minutes away.", true, now - 30 * 60_000), ChatMessage("m5", "Packed and ready at stall 14.", false, now - 25 * 60_000)),
        "t3" to listOf(ChatMessage("m6", "Delivered at the gate.", true, now - 3 * 3_600_000 - 60_000), ChatMessage("m7", "Thank you, all received!", false, now - 3 * 3_600_000)),
    ))
    fun threads(): Flow<List<ChatThread>> = threads
    fun messages(id: String): Flow<List<ChatMessage>> = messages.map { it[id].orEmpty() }
    fun send(id: String, text: String) {
        messages.value = messages.value + (id to (messages.value[id].orEmpty() + ChatMessage("m$now", text, true, now)))
        threads.value = threads.value.map { if (it.id == id) it.copy(lastMessage = text, lastAt = now, unread = 0) else it }
    }
    fun markRead(id: String) { threads.value = threads.value.map { if (it.id == id) it.copy(unread = 0) else it } }

    private val notes = MutableStateFlow(listOf(
        AppNotification("n1", "NEW_ORDER_AVAILABLE", "New job in Kabulonga", "K 38.00 · Soweto Market → Woodlands · 4.6 km", now - 20 * 60_000, false),
        AppNotification("n2", "PAYOUT", "Payout settled", "K 214.60 sent to Airtel Money ●●●●7823.", now - 6 * 3_600_000, false),
        AppNotification("n3", "SOCIAL_FUND_DAILY", "Social Fund today", "+K 13.25 from 7 deliveries. Balance K 412.50.", now - 9 * 3_600_000, true),
        AppNotification("n4", "RATING", "New 5★ rating", "Mwila C.: \"Fast and friendly, thank you!\"", now - day - 2 * 3_600_000, true),
        AppNotification("n5", "TIER_MILESTONE", "Rising tier unlocked", "50 deliveries done. 250 more to Hero tier.", now - 2 * day, true),
        AppNotification("n6", "ACCEPTANCE_WARNING", "Acceptance rate dipped", "You declined 3 jobs in a row. Stay above 80% to keep priority dispatch.", now - 3 * day, true),
        AppNotification("n7", "INCIDENT_ACK", "Incident received", "We got your report INC-2041. Operations will call you within 24 hours.", now - 6 * day, true),
    ))
    fun notifications(): Flow<List<AppNotification>> = notes

    suspend fun report(r: IncidentReport): String {
        delay(400)
        if (r.description.length < 10) throw BackendException("Describe what happened in a few words.")
        val id = "INC-${(2_100..2_999).random()}"
        notes.value = listOf(AppNotification("n$now", "INCIDENT_ACK", "Incident received", "We got your report $id. Operations will review it within 24 hours.", now, false)) + notes.value
        return id
    }
    @Suppress("UNUSED_PARAMETER") fun msisdnOk(m: String) = Validation.normaliseMsisdn(m) != null

    // ---------------- routing (demo): fixed Lusaka coordinates and synthetic street-by-street directions
    private val places = mapOf(
        "Soweto Market" to selo.Nachipa01.domain.route.LatLng(-15.4235, 28.2733),
        "Kabulonga" to selo.Nachipa01.domain.route.LatLng(-15.4180, 28.3400),
        "Manda Hill" to selo.Nachipa01.domain.route.LatLng(-15.3980, 28.3060),
        "Arcades" to selo.Nachipa01.domain.route.LatLng(-15.3990, 28.3220),
        "Levy Mall" to selo.Nachipa01.domain.route.LatLng(-15.4180, 28.2880),
        "East Park" to selo.Nachipa01.domain.route.LatLng(-15.3940, 28.3150),
        "Comesa Market" to selo.Nachipa01.domain.route.LatLng(-15.4190, 28.2820),
        "Woodlands" to selo.Nachipa01.domain.route.LatLng(-15.4300, 28.3280),
        "Northmead" to selo.Nachipa01.domain.route.LatLng(-15.3970, 28.3000),
        "Rhodes Park" to selo.Nachipa01.domain.route.LatLng(-15.4110, 28.3080),
        "Olympia" to selo.Nachipa01.domain.route.LatLng(-15.3920, 28.3110),
        "Kalingalinga" to selo.Nachipa01.domain.route.LatLng(-15.4080, 28.3420),
        "Longacres" to selo.Nachipa01.domain.route.LatLng(-15.4150, 28.3030),
        "Chelston" to selo.Nachipa01.domain.route.LatLng(-15.3700, 28.3700),
        "Matero" to selo.Nachipa01.domain.route.LatLng(-15.3800, 28.2500),
        "Ibex Hill" to selo.Nachipa01.domain.route.LatLng(-15.4400, 28.3500),
        "Roma" to selo.Nachipa01.domain.route.LatLng(-15.3700, 28.3150),
    )
    fun placeOf(address: String) = places.entries.firstOrNull { address.startsWith(it.key) }?.value
    /** Where the demo rider starts: Kabulonga Rd. */
    val startAt = selo.Nachipa01.domain.route.LatLng(-15.4205, 28.3330)

    suspend fun locate(ids: List<String>): Map<String, Pair<selo.Nachipa01.domain.route.LatLng?, selo.Nachipa01.domain.route.LatLng?>> {
        delay(100)
        return active.value.filter { it.id in ids }.associate { it.id to (placeOf(it.pickup) to placeOf(it.dropoff)) }
    }

    private val streets = listOf("Leopards Hill Rd", "Los Angeles Blvd", "Addis Ababa Dr", "Great East Rd", "Independence Ave", "Burma Rd", "Kabulonga Rd",
        "Chindo Rd", "Lagos Rd", "Bwinjimfumu Rd", "Church Rd", "Cairo Rd", "Kafue Rd", "Lumumba Rd")

    suspend fun directions(origin: selo.Nachipa01.domain.route.LatLng, stops: List<selo.Nachipa01.domain.route.Stop>): selo.Nachipa01.domain.route.Directions {
        delay(250)
        val out = mutableListOf<selo.Nachipa01.domain.route.NavStep>(); var cur = origin; var n = 0
        fun seg(a: selo.Nachipa01.domain.route.LatLng, b: selo.Nachipa01.domain.route.LatLng) = (0..12).map { selo.Nachipa01.domain.route.Geo.lerp(a, b, it / 12.0) }
        stops.forEachIndexed { leg, s ->
            val to = s.at ?: return@forEachIndexed
            // east/west first, then north/south, with a short final approach: a plausible city route
            val corner = selo.Nachipa01.domain.route.LatLng(cur.lat, to.lng)
            val a = streets[(n++) % streets.size]; val b = streets[(n++) % streets.size]
            val ew = if (to.lng > cur.lng) "east" else "west"
            val turn = if ((to.lng > cur.lng) == (to.lat < cur.lat)) "right" else "left"
            listOf(Triple("Head $ew on $a", "straight", seg(cur, corner)), Triple("Turn $turn onto $b", "turn-$turn", seg(corner, to)))
                .forEach { (ins, man, path) ->
                    val m = path.zipWithNext().sumOf { (p, q) -> selo.Nachipa01.domain.route.Geo.m(p, q) }.toInt()
                    if (m > 5) out += selo.Nachipa01.domain.route.NavStep(ins, man, m, (m / 7.2).toInt(), path, leg)
                }
            out += selo.Nachipa01.domain.route.NavStep(if (s.type == selo.Nachipa01.domain.route.StopType.PICKUP) "Arrive at pickup: ${s.label}" else "Arrive at drop-off: ${s.label}",
                "arrive", 0, 0, listOf(to, to), leg)
            cur = to
        }
        return selo.Nachipa01.domain.route.Directions(out, out.sumOf { it.distanceM }, out.sumOf { it.durationS }, "demo")
    }

    /** Lets a test or the demo accept a second job straight away to show a combined route. */
    fun offerNow() { if (online.value) incoming.value = newJob() }
}
