package selo.Nachipa01.data.repo

import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import selo.Nachipa01.BuildConfig
import selo.Nachipa01.data.model.*
import java.util.UUID
import selo.Nachipa01.data.events.BehaviourEvent
import selo.Nachipa01.data.events.Keys
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions

/**
 * Production backend: Firebase project Mabala, named Firestore database
 * [BuildConfig.FIRESTORE_DB_ID]. Money, roles, PINs and config are only ever written by
 * Cloud Functions (see /functions); the app reads them through live listeners.
 */
class FirebaseBackend(app: FirebaseApp) : Backend {
    override val isDemo = false

    private val auth = FirebaseAuth.getInstance(app)
    private val db = FirebaseFirestore.getInstance(app, BuildConfig.FIRESTORE_DB_ID).apply {
        firestoreSettings = firestoreSettings { setLocalCacheSettings(PersistentCacheSettings.newBuilder().build()) }
    }
    private val functions = FirebaseFunctions.getInstance(app, BuildConfig.FUNCTIONS_REGION)
    private val storage = FirebaseStorage.getInstance(app)
    private val rtdb = FirebaseDatabase.getInstance(app, BuildConfig.RTDB_URL)

    private var session: SessionUser? = null
    override fun currentUser() = session

    private suspend fun call(name: String, data: Map<String, Any?>): Map<*, *> = try {
        functions.getHttpsCallable(name).call(data).await().getData() as? Map<*, *> ?: emptyMap<String, Any>()
    } catch (e: FirebaseFunctionsException) {
        throw BackendException(if (e.code == FirebaseFunctionsException.Code.UNAVAILABLE || e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED || e.cause is java.io.IOException)
            NETWORK_ERROR else e.message ?: "Something went wrong. Please try again.")
    } catch (e: java.io.IOException) { throw BackendException(NETWORK_ERROR) }

    private fun DocumentSnapshot.toSession(): SessionUser = SessionUser(
        uid = id,
        role = Role.from(getString("role")),
        firstName = getString("firstName") ?: "",
        fullName = getString("fullName") ?: "",
        msisdn = getString("msisdn") ?: "",
        status = AccountStatus.from(getString("accountStatus")),
        area = getString("area") ?: "",
        storeName = getString("storeName") ?: "",
        photoUrl = getString("photoUrl"), bannerUrl = getString("coverUrl"),
        vehicle = getString("vehicle") ?: "",
        memberSince = getString("memberSince") ?: "",
    )

    private suspend fun loadSession(uid: String): SessionUser =
        db.collection("users").document(uid).get().await().toSession().also { session = it }

    override suspend fun restoreSession(): SessionUser? = auth.currentUser?.uid?.let { runCatching { loadSession(it) }.getOrNull() }

    override fun config(): Flow<PlatformConfig> =
        db.collection("platformConfig").document("default").live()
            .map { PlatformConfig.fromMap(it.data ?: emptyMap()) }
            .catch { emit(PlatformConfig()) }

    override fun adverts(): Flow<List<Advert>> =
        db.collection("contentBlocks").whereEqualTo("type", "advert").whereEqualTo("active", true)
            .orderBy("slot").limit(5).live()
            .map { qs ->
                qs.documents.map { d ->
                    Advert(
                        id = d.id, title = d.getString("title") ?: "", seller = d.getString("seller") ?: "",
                        priceNgwee = d.getLong("priceNgwee") ?: 0, cta = d.getString("cta") ?: "Shop now",
                        category = d.getString("category") ?: "produce",
                        colorA = (d.getLong("colorA") ?: 0xFF7A0016), colorB = (d.getLong("colorB") ?: 0xFFE2334D),
                        listingId = d.getString("listingId"),
                    )
                }
            }.catch { emit(emptyList()) }

    /** Never empty: the built-in 12-question bank is used if Firestore can't be read (e.g. before login or seeding). */
    override suspend fun questionBank(): List<SecurityQuestion> = selo.Nachipa01.domain.SecurityQuestions.merge(
        runCatching {
            db.collection("securityQuestionBank").whereEqualTo("active", true).get().await()
                .documents.map { SecurityQuestion(it.id, it.getString("text_en") ?: "") }
        }.getOrDefault(emptyList())
    )

    override suspend fun login(msisdn: String, pin: String): SessionUser {
        val res = call("auth_loginWithPin", mapOf("msisdn" to msisdn, "pin" to pin))
        auth.signInWithCustomToken(res["token"] as String).await()
        return loadSession(auth.currentUser!!.uid)
    }

    override suspend fun register(req: RegistrationRequest, onProgress: (String) -> Unit): SessionUser {
        onProgress("Creating your account")
        val res = call("auth_register", mapOf(
            "role" to req.role.name, "msisdn" to req.msisdn, "pin" to req.pin, "otpToken" to req.otpToken,
            "questions" to req.questions.map { mapOf("questionId" to it.questionId, "answer" to it.answer) },
            "profile" to req.profile, "payoutWallet" to req.payoutWallet, "referralCode" to req.referralCode,
            "acceptedTermsVersion" to req.acceptedTermsVersion,
        ))
        auth.signInWithCustomToken(res["token"] as String).await()
        val uid = auth.currentUser!!.uid
        // Public images (profile picture, logo, shop banner) go to profiles/ and onto the account; the rest are private KYC.
        val public = setOf("profilePhoto", "logo", "shopBanner")
        req.kycDocs.filterKeys { it in public }.takeIf { it.isNotEmpty() }?.let { pics ->
            onProgress("Uploading your pictures")
            val urls = pics.mapValues { (key, uri) -> val ref = storage.reference.child("profiles/$uid/$key-${UUID.randomUUID()}.jpg"); ref.putFile(uri).await(); ref.downloadUrl.await().toString() }
            val upd = buildMap<String, Any> { (urls["logo"] ?: urls["profilePhoto"])?.let { put("photoUrl", it) }; urls["shopBanner"]?.let { put("coverUrl", it) } }
            if (upd.isNotEmpty()) runCatching { db.collection("users").document(uid).update(upd).await() }
        }
        val kyc = req.kycDocs.filterKeys { it !in public }
        if (kyc.isNotEmpty()) {
            onProgress("Uploading documents")
            val paths = kyc.mapValues { (key, uri) ->
                val ref = storage.reference.child("kyc/$uid/$key-${UUID.randomUUID()}.jpg")
                ref.putFile(uri).await(); ref.path
            }
            call("auth_submitKyc", mapOf("documents" to paths))
        }
        return loadSession(uid)
    }

    override suspend fun recoveryQuestions(msisdn: String): List<SecurityQuestion>? {
        val res = call("auth_getRecoveryQuestions", mapOf("msisdn" to msisdn))
        if (res["hasQuestions"] == false) return null
        @Suppress("UNCHECKED_CAST")
        return (res["questions"] as? List<Map<String, String>>)?.map { q ->
            val id = q["id"]!!
            SecurityQuestion(id, q["text"]?.takeIf { it.isNotBlank() && it != id } ?: selo.Nachipa01.domain.SecurityQuestions.textFor(id))
        }
    }

    override suspend fun verifyAnswers(msisdn: String, answers: List<QuestionAnswer>): String =
        call("auth_verifySecurityAnswers", mapOf("msisdn" to msisdn,
            "answers" to answers.map { mapOf("questionId" to it.questionId, "answer" to it.answer) }))["resetToken"] as String

    override suspend fun resetPin(resetToken: String, newPin: String) {
        call("auth_resetPin", mapOf("resetToken" to resetToken, "pin" to newPin))
    }

    override suspend fun lookupWallet(msisdn: String): String =
        call("wallet_lookup", mapOf("msisdn" to msisdn))["holderName"] as? String ?: throw BackendException("Wallet not found.")

    override suspend fun logout() { auth.signOut(); session = null }

    private fun DocumentSnapshot.toListing() = Listing(
        id = id, sellerUid = getString("sellerUid") ?: "", sellerName = getString("sellerName") ?: "",
        title = getString("title") ?: "", description = getString("description") ?: "",
        priceNgwee = getLong("priceNgwee") ?: 0, category = getString("category") ?: "",
        zone = getString("zoneName") ?: "", thumbUrl = getString("thumbUrl"), videoUrl = getString("videoUrl"),
        stock = (getLong("stock") ?: 0).toInt(), status = getString("status") ?: "live",
        views = (getLong("viewCount") ?: 0).toInt(), saves = (getLong("saveCount") ?: 0).toInt(),
        aiEnhanced = getBoolean("aiEnhanced") == true, aiGeneratedMotion = getBoolean("aiGeneratedMotion") == true,
    )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toOrder() = Order(
        id = id, buyerUid = getString("buyerUid") ?: "", sellerUid = getString("sellerUid") ?: "",
        sellerName = getString("sellerName") ?: "", riderUid = getString("riderUid"), riderName = getString("riderName"),
        items = (get("items") as? List<Map<String, Any?>>)?.map {
            OrderItem(it["listingId"] as? String ?: "", it["title"] as? String ?: "",
                (it["qty"] as? Number)?.toInt() ?: 1, (it["unitNgwee"] as? Number)?.toLong() ?: 0)
        } ?: emptyList(),
        goodsNgwee = getLong("goodsNgwee") ?: 0, deliveryNgwee = getLong("deliveryNgwee") ?: 0,
        totalNgwee = getLong("totalNgwee") ?: 0, state = OrderState.from(getString("state")),
        zone = getString("zoneName") ?: "", dropoff = getString("dropoff") ?: "", pickup = getString("pickup") ?: "",
        deliveryCode = getString("deliveryCodeDisplay"), distanceKm = getDouble("distanceKm") ?: 0.0,
        isParcel = getBoolean("isParcel") ?: false, insuranceId = getString("insuranceId"),
        declaredValueNgwee = getLong("declaredValueNgwee") ?: 0, lipilaRef = getString("lipilaCollectionRef"),
        timeline = (get("timeline") as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: emptyMap(),
        paymentStatus = getString("paymentStatus") ?: "PENDING", paymentFailure = getString("paymentFailure"), walletMsisdn = getString("walletMsisdn"),
        serviceFeeNgwee = getLong("serviceFeeNgwee") ?: 0, processingFeeNgwee = getLong("processingFeeNgwee") ?: 0,
        buyerName = getString("buyerName") ?: "", buyerMsisdn = getString("buyerMsisdn") ?: "",
        itemsSummary = (get("items") as? List<Map<String, Any?>>)?.joinToString { "${it["qty"]} × ${it["title"]}" } ?: "",
        pickupLat = getDouble("pickupGeo.lat"), pickupLng = getDouble("pickupGeo.lng"), dropLat = getDouble("dropoffGeo.lat"), dropLng = getDouble("dropoffGeo.lng"),
        codeState = getString("codeSummary.state"), arrivedAt = getTimestamp("arrivedAt")?.toDate()?.time,
        unavailableDeadline = getTimestamp("unavailable.deadline")?.toDate()?.time, distanceApproximate = getBoolean("distanceApproximate") == true,
        riderLat = getDouble("riderGeo.lat"), riderLng = getDouble("riderGeo.lng"), trackingPolyline = getString("trackingPolyline"),
        etaMinutes = (getLong("navigation.estimatedMinutes") ?: getLong("etaMinutes"))?.toInt(),
    )

    override fun listings(category: String?): Flow<List<Listing>> {
        var q: Query = db.collection("listings").whereEqualTo("status", "live")
        if (category != null) q = q.whereEqualTo("category", category)
        return q.orderBy("publishedAt", Query.Direction.DESCENDING).limit(30).live()
            .map { s -> s.documents.map { it.toListing() } }.catch { emit(emptyList()) }
    }

    private fun uid() = auth.currentUser?.uid

    override fun buyerOrders(): Flow<List<Order>> = uid()?.let { u ->
        db.collection("orders").whereEqualTo("buyerUid", u).orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .live().map { s -> s.documents.map { it.toOrder() } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override fun order(id: String): Flow<Order?> {
        val order = db.collection("orders").document(id).live().map { if (it.exists()) it.toOrder() else null }
        // The delivery code lives in a buyer-only document so riders can't read it from the order.
        val code = db.collection("deliveryCodes").document(id).live().map { it.getString("code") }.catch { emit(null) }
        return combine(order, kotlinx.coroutines.flow.merge(flowOf(null), code)) { o, c -> o?.copy(deliveryCode = c ?: o.deliveryCode) }
    }

    override suspend fun checkout(req: CheckoutRequest): String = call("order_create", mapOf(
        "sellerUid" to req.sellerUid, "dropoff" to req.dropoff, "walletMsisdn" to req.walletMsisdn,
        "items" to req.items.map { mapOf("listingId" to it.listingId, "qty" to it.qty) },
        "dropoffGeo" to req.dropoffPlace?.let { mapOf("lat" to it.lat, "lng" to it.lng) }, "addressId" to req.dropoffPlace?.addressId, "dropoffLandmark" to req.dropoffPlace?.landmark,
    ))["orderId"] as String


    override suspend fun confirmReceipt(orderId: String) { call("order_confirmDelivery", mapOf("orderId" to orderId)) }

    override fun sellerStats(): Flow<SellerStats> = uid()?.let { u ->
        db.collection("sellerStats").document(u).live().map {
            SellerStats(it.getLong("earningsNgwee") ?: 0, it.getLong("todayNgwee") ?: 0, (it.getLong("toPack") ?: 0).toInt(),
                (it.getLong("liveListings") ?: 0).toInt(), (it.getLong("lowStock") ?: 0).toInt(),
                it.getLong("monthNgwee") ?: 0, (it.getLong("monthOrders") ?: 0).toInt())
        }.catch { emit(DemoData.sellerStats) }
    } ?: emptyFlow()

    override fun sellerOrders(): Flow<List<Order>> = uid()?.let { u ->
        db.collection("orders").whereEqualTo("sellerUid", u).whereIn("state", listOf("ESCROW_LOCKED", "BROADCASTING", "RIDER_ASSIGNED"))
            .live().map { s -> s.documents.map { it.toOrder() } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override suspend fun confirmPack(orderId: String) { call("order_confirmPack", mapOf("orderId" to orderId)) }

    override suspend fun uploadMedia(uris: List<Uri>, onProgress: (Float) -> Unit): List<String> {
        val u = uid() ?: throw BackendException("Please log in again.")
        val batch = UUID.randomUUID().toString()
        return uris.mapIndexed { i, uri ->
            val ext = if (uri.toString().contains("video") || uri.toString().endsWith(".mp4")) "mp4" else "jpg"
            val ref = storage.reference.child("listing-uploads/$u/$batch/$i.$ext")
            ref.putFile(uri).addOnProgressListener { t ->
                if (t.totalByteCount > 0) onProgress((i + t.bytesTransferred.toFloat() / t.totalByteCount) / uris.size)
            }.await()
            ref.path
        }.also { onProgress(1f) }
    }

    override suspend fun runAiPipeline(mediaPaths: List<String>, prompt: String?, onStep: (Int) -> Unit, labels: List<Pair<String, Float>>, mediaType: String): AiDraft {
        onStep(3)
        val res = call("listing_runAiPipeline", mapOf("framePaths" to mediaPaths, "prompt" to prompt, "mediaType" to mediaType,
            "onDeviceLabels" to labels.map { mapOf("text" to it.first, "confidence" to it.second.toDouble()) }))
        onStep(5)
        @Suppress("UNCHECKED_CAST")
        return AiDraft(
            title = res["title"] as? String ?: "", description = res["description"] as? String ?: "",
            priceNgwee = (res["priceNgwee"] as? Number)?.toLong() ?: 0,
            priceLowNgwee = (res["priceLowNgwee"] as? Number)?.toLong() ?: 0,
            priceHighNgwee = (res["priceHighNgwee"] as? Number)?.toLong() ?: 0,
            category = res["category"] as? String ?: "", tags = res["tags"] as? List<String> ?: emptyList(),
            mediaPaths = mediaPaths, comparables = (res["comparables"] as? Number)?.toInt() ?: 0,
            condition = res["condition"] as? String ?: "Good", priceScope = res["priceScope"] as? String ?: "",
        )
    }

    override suspend fun publishListing(draft: AiDraft, asDraft: Boolean): String = call("listing_publish", mapOf(
        "title" to draft.title, "description" to draft.description, "priceNgwee" to draft.priceNgwee,
        "category" to draft.category, "tags" to draft.tags, "mediaPaths" to draft.mediaPaths,
        "stock" to draft.stock, "asDraft" to asDraft,
    ))["listingId"] as String

    override fun riderStats(): Flow<RiderStats> = uid()?.let { u ->
        combine(db.collection("riderWallets").document(u).live(), riderHistory()) { it, h ->
            val dayStart = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
            val today = h.filter { d -> d.at >= dayStart }; val week = h.filter { d -> d.at >= System.currentTimeMillis() - 7 * 86_400_000L }
            val weekFund = (6 downTo 0).map { d -> h.filter { x -> x.at in (dayStart - d * 86_400_000L) until (dayStart - d * 86_400_000L + 86_400_000L) }.sumOf { x -> x.fundNgwee } }
            RiderStats(today.sumOf { d -> d.netNgwee }, today.size,
                (it.getLong("activeCount") ?: 0).toInt(), (it.getLong("acceptanceRate") ?: 100).toInt(),
                it.getLong("socialFundNgwee") ?: 0, (it.getLong("completedDeliveries") ?: 0).toInt(),
                it.getLong("availableNgwee") ?: 0, it.getString("tier") ?: "Starter", weekFund,
                weekNetNgwee = week.sumOf { d -> d.netNgwee }, todayKm = today.sumOf { d -> d.km }, fundTodayNgwee = today.sumOf { d -> d.fundNgwee },
                bonusWeekNgwee = week.sumOf { d -> d.bonusNgwee })
        }.catch { emit(DemoData.riderStats) }
    } ?: emptyFlow()

    override fun availableJobs(): Flow<List<Order>> = combine(
        db.collection("orders").whereEqualTo("state", "BROADCASTING").whereArrayContains("notifiedRiders", uid() ?: "-").limit(10).live()
            .map { s -> s.documents.map { it.toOrder() } }.catch { emit(emptyList()) },
        db.collection("parcels").whereEqualTo("status", "AWAITING_RIDER").whereArrayContains("notifiedRiders", uid() ?: "-").limit(10).live()
            .map { s -> s.documents.map { it.parcelAsJob() } }.catch { emit(emptyList()) }) { a, b -> a + b }

    /** Parcels ride the same rider screens as marketplace orders (kind = "parcel"). Riders only ever see their earnings. */
    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.parcelAsJob(): Order {
        val p = toParcel(uid())
        val st = when (p.status) { ParcelStatus.AWAITING_RIDER -> OrderState.BROADCASTING; ParcelStatus.RIDER_ASSIGNED -> OrderState.RIDER_ASSIGNED
            ParcelStatus.IN_TRANSIT -> OrderState.IN_TRANSIT; ParcelStatus.AWAITING_DELIVERY_CODE -> OrderState.AWAITING_DELIVERY_CODE
            ParcelStatus.DELIVERED, ParcelStatus.COMPLETED -> OrderState.DELIVERED; else -> OrderState.CANCELLED }
        val earn = ((get("fees") as? Map<String, Any?>)?.get("riderEarningsNgwee") as? Number)?.toLong()
        return Order(id, getString("senderUid") ?: "", getString("senderUid") ?: "", p.senderName.ifBlank { "Sender" }, getString("riderUid"), p.riderName,
            listOf(OrderItem("", p.description.ifBlank { p.category }, 1, 0)), 0, p.deliveryNgwee, p.totalNgwee, st, getString("cityName") ?: "",
            p.dropoff.address.ifBlank { p.dropoff.label }, p.pickup.address.ifBlank { p.pickup.label }, distanceKm = p.distanceKm, isParcel = true,
            buyerName = p.receiverName, buyerMsisdn = p.receiverMsisdn, itemsSummary = "${p.description} · ${p.weightBand}",
            pickupLat = p.pickup.lat, pickupLng = p.pickup.lng, dropLat = p.dropoff.lat, dropLng = p.dropoff.lng, kind = "parcel", codeState = p.codeState,
            arrivedAt = getTimestamp("arrivedAt")?.toDate()?.time, riderEarningsNgwee = earn, weightBand = p.weightBand, distanceApproximate = p.approximate)
    }

    override suspend fun setOnline(online: Boolean) {
        val u = uid() ?: return
        val ref = rtdb.getReference("presence/$u")
        if (online) {
            ref.onDisconnect().updateChildren(mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP))
        }
        ref.updateChildren(mapOf("isOnline" to online, "lastSeen" to ServerValue.TIMESTAMP)).await()
        call("rider_setPresence", mapOf("online" to online))
    }

    override suspend fun acceptJob(orderId: String) {
        if (orderId.startsWith("PCL-")) call("rider_acceptParcel", mapOf("parcelId" to orderId)) else call("rider_acceptOrder", mapOf("orderId" to orderId))
    }

    override fun riderActiveJobs(): Flow<List<Order>> = uid()?.let { u ->
        combine(db.collection("orders").whereEqualTo("riderUid", u).whereIn("state", listOf("RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"))
            .live().map { s -> s.documents.map { it.toOrder() } }.catch { emit(emptyList()) },
            db.collection("parcels").whereEqualTo("riderUid", u).whereIn("status", listOf("RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"))
            .live().map { s -> s.documents.map { it.parcelAsJob() } }.catch { emit(emptyList()) }) { a, b -> a + b }
    } ?: flowOf(emptyList())

    override suspend fun markCollected(orderId: String) { call("rider_markCollected", mapOf("orderId" to orderId)) }


    /* ======================================================= v6.1 discovery */

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.toListing(): Listing {
        val st = this["stats"] as? Map<String, Any?> ?: emptyMap()
        fun n(k: String) = (this[k] as? Number)
        return Listing(
            id = this["id"] as String, sellerUid = this["sellerUid"] as? String ?: "", sellerName = this["sellerName"] as? String ?: "",
            title = this["title"] as? String ?: "", description = this["description"] as? String ?: "", priceNgwee = n("priceNgwee")?.toLong() ?: 0,
            category = this["category"] as? String ?: "", zone = this["district"] as? String ?: "", thumbUrl = this["thumbUrl"] as? String,
            videoUrl = this["videoUrl"] as? String, mediaType = this["mediaType"] as? String ?: "photo",
            photoUrls = (this["photoUrls"] as? List<String>) ?: emptyList(), sellerVerified = this["sellerVerified"] == true,
            condition = this["condition"] as? String ?: "", availability = this["availability"] as? String ?: "In Stock",
            province = this["province"] as? String ?: "", district = this["district"] as? String ?: "", tags = (this["tags"] as? List<String>) ?: emptyList(),
            likes = (st["likes"] as? Number)?.toInt() ?: 0, shares = (st["shares"] as? Number)?.toInt() ?: 0, views = (st["views"] as? Number)?.toInt() ?: 0,
            discountPct = n("discountPct")?.toDouble() ?: 0.0, discountNgwee = n("discountNgwee")?.toLong() ?: 0,
            rankSource = this["rankSource"] as? String, publishedAt = n("publishedAt")?.toLong() ?: 0, promoId = this["promoId"] as? String,
            aiEnhanced = this["aiEnhanced"] == true, aiGeneratedMotion = this["aiGeneratedMotion"] == true,
            promoLabel = (this["promo"] as? Map<*, *>)?.get("label") as? String, promoType = (this["promo"] as? Map<*, *>)?.get("type") as? String,
            promoEndsAt = ((this["promo"] as? Map<*, *>)?.get("endsAt") as? Number)?.toLong(),
        )
    }
    /** Runs [live]; with no connection it falls back to products already on the phone (OfflineCatalog). */
    private suspend fun <T> orOffline(live: suspend () -> T, cached: () -> T): T = try {
        live().also { OfflineCatalog.offline.value = false }
    } catch (e: BackendException) {
        if (e.message != NETWORK_ERROR || OfflineCatalog.count() == 0) throw e
        OfflineCatalog.offline.value = true; cached()
    }
    private fun cachedListings(records: List<Map<String, Any?>>) = records.mapNotNull { runCatching { it.toListing() }.getOrNull() }
    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.listings(key: String) = (this[key] as? List<Map<*, *>>)?.also { OfflineCatalog.remember(it) }?.map { it.toListing() } ?: emptyList()

    override suspend fun logEvents(events: List<BehaviourEvent>, variant: String?) {
        val u = uid() ?: return
        val batch = db.batch()
        events.forEach { batch.set(db.collection("behaviourEvents").document(Keys.eventKey(u)), it.toMap(u, variant)) }
        batch.commit().await()
    }

    override suspend fun feed(sessionId: String?, count: Int): FeedPage = orOffline({ liveFeed(sessionId, count) }) {
        // Offline: page through the products already seen, newest first.
        val from = sessionId?.removePrefix("offline:")?.toIntOrNull() ?: 0
        FeedPage("offline:${from + count}", cachedListings(OfflineCatalog.all().drop(from).take(count)), "offline")
    }
    private suspend fun liveFeed(sessionId: String?, count: Int): FeedPage {
        val r = call("feed_next", mapOf("sessionId" to sessionId?.takeUnless { it.startsWith("offline:") }, "count" to count))
        @Suppress("UNCHECKED_CAST") val g = r["guard"] as? Map<String, Any?> ?: emptyMap()
        fun gi(k: String, d: Int) = (g[k] as? Number)?.toInt() ?: d
        return FeedPage(r["sessionId"] as String, r.listings("items"), r["variant"] as? String ?: "control", (r["cohort"] as? Number)?.toInt() ?: 0,
            FeedGuard(gi("minutesToday", 0), g["warn"] == true, g["capReached"] == true, g["budgetCheck"] == true, gi("warnAtMinutes", 130), gi("capMinutes", 145)))
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun homeRails(): List<Rail> = orOffline({ (call("home_rails", emptyMap())["rails"] as? List<Map<*, *>>)
        ?.map { Rail(it["id"] as String, it["title"] as String, it.listings("items")) } ?: emptyList() }) {
        listOf(Rail("offline", "Saved on your phone", cachedListings(OfflineCatalog.all().take(20)))) }

    @Suppress("UNCHECKED_CAST")
    override suspend fun search(query: String, filters: SearchFilters): SearchResult = orOffline({ liveSearch(query, filters) }) {
        SearchResult(cachedListings(OfflineCatalog.search(query)), emptyList()) }
    @Suppress("UNCHECKED_CAST")
    private suspend fun liveSearch(query: String, filters: SearchFilters): SearchResult {
        val r = call("search_listings", mapOf("q" to query, "filters" to mapOf("minNgwee" to filters.minNgwee, "maxNgwee" to filters.maxNgwee,
            "province" to filters.province, "district" to filters.district, "condition" to filters.condition, "mediaType" to filters.mediaType,
            "availability" to filters.availability, "category" to filters.category)))
        return SearchResult(r.listings("results"), (r["alsoSearched"] as? List<String>) ?: emptyList())
    }

    override suspend fun listing(id: String): Listing? = try {
        val d = db.collection("listings").document(id).get().await()
        if (!d.exists()) null
        else (d.data!!.filterValues { it !is com.google.firebase.Timestamp } + ("id" to d.id) + ("publishedAt" to (d.getTimestamp("publishedAt")?.toDate()?.time ?: 0L)))
            .also { OfflineCatalog.remember(listOf(it)) }.toListing()
    } catch (e: Exception) {
        OfflineCatalog.get(id)?.let { OfflineCatalog.offline.value = true; it.toListing() } ?: throw (e as? BackendException ?: BackendException(NETWORK_ERROR))
    }

    override fun likedIds(): Flow<Set<String>> = uid()?.let { u ->
        db.collection("likes").document(u).collection("items").live().map { s -> s.documents.map { it.id }.toSet() }.catch { emit(emptySet()) }
    } ?: flowOf(emptySet())

    override fun likedListings(): Flow<List<Listing>> = likedIds().map { ids -> ids.take(30).mapNotNull { runCatching { listing(it) }.getOrNull() } }

    @Suppress("UNCHECKED_CAST")
    override fun wishlists(): Flow<List<Wishlist>> = uid()?.let { u ->
        db.collection("wishlists").document(u).collection("lists").live()
            .map { s -> s.documents.map { Wishlist(it.id, it.getString("name") ?: "", (it.get("itemIds") as? List<String>) ?: emptyList()) } }
            .catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override suspend fun createWishlist(name: String) {
        val u = uid() ?: return
        db.collection("wishlists").document(u).collection("lists").document(name.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(40))
            .set(mapOf("name" to name.take(40), "itemIds" to emptyList<String>()), SetOptions.merge()).await()
    }

    override suspend fun saveToWishlist(listingId: String, wishlistName: String) {
        val u = uid() ?: return
        val id = wishlistName.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(40)
        db.collection("wishlists").document(u).collection("lists").document(id)
            .set(mapOf("name" to wishlistName.take(40), "itemIds" to FieldValue.arrayUnion(listingId)), SetOptions.merge()).await()
    }

    override fun following(): Flow<Set<String>> = uid()?.let { u ->
        db.collection("follows").whereEqualTo("followerUid", u).live().map { s -> s.documents.mapNotNull { it.getString("sellerUid") }.toSet() }.catch { emit(emptySet()) }
    } ?: flowOf(emptySet())

    override suspend fun setFollow(sellerUid: String, follow: Boolean) {
        val u = uid() ?: return
        val ref = db.collection("follows").document("${u}_$sellerUid")
        if (follow) ref.set(mapOf("followerUid" to u, "sellerUid" to sellerUid, "at" to FieldValue.serverTimestamp())).await() else ref.delete().await()
    }

    override suspend fun shareToUser(listingId: String, phone: String) { call("share_toUser", mapOf("listingId" to listingId, "phone" to phone)) }

    override suspend fun setLeadConsent(consent: Boolean, ageBand: String?) {
        val u = uid() ?: return
        db.collection("users").document(u).update(mapOf("leadConsent" to consent, "leadConsentAt" to FieldValue.serverTimestamp(), "ageBand" to ageBand)).await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun sellerDashboard(): SellerDashboard {
        val r = call("seller_dashboard", emptyMap())
        fun card(m: Map<*, *>?) = m?.let { SellerListingCard(it["id"] as String, it["title"] as? String ?: "", it["status"] as? String ?: "", it["thumbUrl"] as? String,
            it["mediaType"] as? String ?: "photo", (it["priceNgwee"] as? Number)?.toLong() ?: 0, (it["views"] as? Number)?.toInt() ?: 0,
            (it["likes"] as? Number)?.toInt() ?: 0, (it["shares"] as? Number)?.toInt() ?: 0, (it["enquiries"] as? Number)?.toInt() ?: 0,
            (it["postedAt"] as? Number)?.toLong() ?: 0, it["studioStatus"] as? String, it["studioApplied"] == true, it["studioVideoUrl"] as? String, it["studioError"] as? String) }
        val w = r["week"] as Map<String, Number>; val f = r["funnel"] as Map<String, Number>; val t = r["top"] as Map<String, Map<*, *>?>
        val a = r["audience"] as Map<String, Map<String, Number>>
        return SellerDashboard((r["listings"] as List<Map<*, *>>).mapNotNull { card(it) }, w["views"]!!.toInt(), w["lastWeekViews"]!!.toInt(),
            w["likes"]!!.toInt(), w["shares"]!!.toInt(), w["enquiries"]!!.toInt(),
            listOf("Views" to f["views"]!!.toInt(), "Taps" to f["taps"]!!.toInt(), "Enquiries" to f["enquiries"]!!.toInt(), "Sales" to f["sales"]!!.toInt()),
            mapOf("views" to card(t["views"]), "enquiries" to card(t["enquiries"]), "likes" to card(t["likes"])),
            a["ageBand"]!!.mapValues { it.value.toInt() }, a["province"]!!.mapValues { it.value.toInt() },
            a["peakHours"]!!.mapKeys { it.key.toInt() }.mapValues { it.value.toInt() })
    }

    override suspend fun bulkListings(action: String, ids: List<String>?): Int =
        (call("seller_bulkListings", mapOf("action" to action, "ids" to ids))["updated"] as? Number)?.toInt() ?: 0

    override suspend fun updateListing(id: String, changes: Map<String, Any?>) { call("listing_update", mapOf("listingId" to id, "changes" to changes)) }

    override suspend fun saveSellerSettings(settings: Map<String, Any?>) {
        val u = uid() ?: return
        db.collection("users").document(u).update(settings.filterKeys { it in setOf("storeName", "photoUrl", "coverUrl", "province", "district", "area",
            "businessTags", "paymentPrefs", "notificationPrefs", "landmark") }).await()
    }

    override suspend fun publishListingV2(req: PublishRequest): String = call("listing_publish", mapOf(
        "mediaType" to req.mediaType, "videoPath" to req.videoPath, "photoPaths" to req.photoPaths, "thumbPath" to req.thumbPath,
        "durationMs" to req.durationMs, "title" to req.title, "description" to req.description, "priceNgwee" to req.priceNgwee,
        "category" to req.category, "condition" to req.condition, "availability" to req.availability, "province" to req.province,
        "district" to req.district, "location" to req.location, "tags" to req.tags, "discountPct" to req.discountPct, "aiUsed" to req.aiUsed,
        "asDraft" to req.asDraft,
    ))["listingId"] as String

    /* ======================================================= v6.2 payments + promotions */

    override suspend fun retryPayment(orderId: String, walletMsisdn: String) {
        call("order_retryPayment", mapOf("orderId" to orderId, "walletMsisdn" to walletMsisdn))
    }

    private fun Targeting.toMap() = mapOf("ageBands" to ageBands, "provinces" to provinces, "districts" to districts, "interests" to interests,
        "searchIntent" to searchIntent, "followers" to followers)

    override suspend fun estimateAudience(t: Targeting, reach: BoostReach): Int =
        (call("promo_estimate", mapOf("targeting" to t.toMap(), "reach" to reach.name))["audience"] as? Number)?.toInt() ?: 0

    override suspend fun createPromotion(type: String, listingId: String, reach: BoostReach, days: Int, t: Targeting, walletMsisdn: String): String =
        call("promo_create", mapOf("type" to type, "listingId" to listingId, "reach" to reach.name, "days" to days, "targeting" to t.toMap(),
            "walletMsisdn" to walletMsisdn))["promoId"] as String

    override suspend fun retryPromotionPayment(promoId: String, walletMsisdn: String) {
        call("promo_retryPayment", mapOf("promoId" to promoId, "walletMsisdn" to walletMsisdn))
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toPromotion(): Promotion {
        val t = get("targeting") as? Map<String, Any?> ?: emptyMap()
        val st = get("stats") as? Map<String, Number> ?: emptyMap()
        return Promotion(id, getString("type") ?: "boost", getString("listingId") ?: "", getString("listingTitle") ?: "", getString("status") ?: "",
            getLong("priceNgwee") ?: 0, (getLong("days") ?: 1).toInt(), getString("reach") ?: "LOCAL",
            Targeting((t["ageBands"] as? List<String>) ?: emptyList(), (t["provinces"] as? List<String>) ?: emptyList(), (t["districts"] as? List<String>) ?: emptyList(),
                (t["interests"] as? List<String>) ?: emptyList(), t["searchIntent"] != false, t["followers"] != false),
            getTimestamp("startsAt")?.toDate()?.time ?: 0, getTimestamp("endsAt")?.toDate()?.time ?: 0,
            st["impressions"]?.toInt() ?: 0, st["clicks"]?.toInt() ?: 0, st["likes"]?.toInt() ?: 0, st["enquiries"]?.toInt() ?: 0,
            getString("failureReason"), getString("walletMsisdn"), (getLong("estimatedReach") ?: 0).toInt())
    }

    override fun promotion(id: String): Flow<Promotion?> =
        db.collection("promotions").document(id).live().map { if (it.exists()) it.toPromotion() else null }

    override fun myPromotions(): Flow<List<Promotion>> = uid()?.let { u ->
        db.collection("promotions").whereEqualTo("sellerUid", u).orderBy("createdAt", Query.Direction.DESCENDING).limit(50).live()
            .map { s -> s.documents.map { it.toPromotion() } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    @Suppress("UNCHECKED_CAST")
    override suspend fun stories(): List<Story> = (call("stories_list", emptyMap())["stories"] as? List<Map<*, *>>)?.map {
        Story(it["promoId"] as String, (it["listing"] as Map<*, *>).toListing(), it["sellerName"] as? String ?: "", it["followed"] == true)
    } ?: emptyList()

    // ---------------- v6.3 rider post-login
    override suspend fun declineJob(orderId: String) {
        if (orderId.startsWith("PCL-")) call("rider_declineParcel", mapOf("parcelId" to orderId)) else call("rider_declineOrder", mapOf("orderId" to orderId))
    }
    override suspend fun rateBuyer(orderId: String, stars: Int) { call("rider_rateBuyer", mapOf("orderId" to orderId, "stars" to stars)) }

    override fun riderProfile(): Flow<RiderProfile?> = uid()?.let { u ->
        combine(db.collection("users").document(u).live(), db.collection("riderWallets").document(u).live()) { p, w ->
            @Suppress("UNCHECKED_CAST") val ec = p.get("emergencyContact") as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST") val rs = p.get("riderSettings") as? Map<String, Any?> ?: emptyMap()
            val prof: RiderProfile? = RiderProfile(u, p.getString("fullName") ?: "", p.getString("msisdn") ?: "", p.getString("area") ?: "", p.getString("city") ?: "Lusaka",
                p.getString("vehicle") ?: "Motorbike", p.getString("vehicleDetails.plate") ?: "", p.getString("photoPath")?.let { mediaUrl(it) } ?: p.getString("photoUrl"), w.getString("tier") ?: "Starter",
                w.getDouble("rating") ?: 0.0, (w.getLong("ratingCount") ?: 0).toInt(), (w.getLong("completedDeliveries") ?: 0).toInt(),
                (w.getLong("completionRate") ?: 100).toInt(), (w.getLong("acceptanceRate") ?: 100).toInt(),
                ec["name"] as? String ?: "", ec["phone"] as? String ?: "", p.getString("payoutWallet") ?: "",
                p.getString("memberSince") ?: "",
                (rs["weeklyTargetNgwee"] as? Number)?.toLong() ?: 100_000, rs["autoSettle"] as? Boolean ?: true)
            prof
        }.catch { emit(null) }
    } ?: flowOf(null)

    override suspend fun updateRiderProfile(changes: Map<String, Any?>, photo: Uri?) {
        val u = uid() ?: throw BackendException("Please log in again.")
        val path = photo?.let { p -> storage.reference.child("profiles/$u/portrait_${System.currentTimeMillis()}.jpg").also { it.putFile(p).await() }.path }
        call("rider_updateProfile", changes + (if (path != null) mapOf("photoPath" to path) else emptyMap()))
    }

    override fun riderHistory(): Flow<List<RiderDelivery>> = uid()?.let { u ->
        db.collection("ledgers").document(u).collection("entries").whereEqualTo("type", "delivery_credit")
            .orderBy("at", Query.Direction.DESCENDING).limit(400).live().map { s -> s.documents.map { d ->
                RiderDelivery(d.getString("orderId") ?: "", d.getString("pickupArea") ?: "", d.getString("dropArea") ?: "", d.getString("pickup") ?: "",
                    d.getString("dropoff") ?: "", d.getString("item") ?: "", d.getLong("gross") ?: 0, d.getLong("platformFee") ?: 0, d.getLong("socialLevy") ?: 0,
                    d.getLong("bonus") ?: 0, (d.getLong("net") ?: 0) + (d.getLong("bonus") ?: 0), d.getDouble("km") ?: 0.0,
                    d.getTimestamp("at")?.toDate()?.time ?: 0, d.getString("podUrl"))
            } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override fun riderZones(): Flow<List<DeliveryZone>> = uid()?.let { u ->
        combine(db.collection("zones").whereEqualTo("isActive", true).live(), db.collection("riderZones").document(u).live()) { z, mine ->
            @Suppress("UNCHECKED_CAST") val active = mine.get("activeZoneIds") as? List<String> ?: emptyList()
            val primary = mine.getString("primaryZoneId") ?: active.firstOrNull()
            z.documents.map { d -> @Suppress("UNCHECKED_CAST") DeliveryZone(d.id, d.getString("name") ?: d.id, d.getString("city") ?: "Lusaka",
                d.get("neighbourhoods") as? List<String> ?: emptyList(), d.getDouble("radiusKm") ?: 5.0, d.id in active, d.id == primary) }
                .sortedWith(compareByDescending<DeliveryZone> { it.primary }.thenByDescending { it.active })
        }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override suspend fun addZone(zoneId: String) {
        val u = uid() ?: return
        db.collection("riderZones").document(u).set(mapOf("activeZoneIds" to FieldValue.arrayUnion(zoneId), "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    override fun workingHours(): Flow<List<WorkingDay>> = uid()?.let { u ->
        db.collection("riderZones").document(u).live().map { d ->
            @Suppress("UNCHECKED_CAST") (d.get("workingHours") as? List<Map<String, Any?>>)?.map {
                WorkingDay((it["day"] as Number).toInt(), it["on"] == true, it["start"] as? String ?: "07:00", it["end"] as? String ?: "19:00") }
                ?.sortedBy { it.day } ?: WorkingDay.DEFAULT
        }.catch { emit(WorkingDay.DEFAULT) }
    } ?: flowOf(WorkingDay.DEFAULT)

    override suspend fun setWorkingHours(days: List<WorkingDay>) {
        val u = uid() ?: return
        db.collection("riderZones").document(u).set(mapOf("workingHours" to days.map { mapOf("day" to it.day, "on" to it.on, "start" to it.start, "end" to it.end) },
            "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    override suspend fun fileClaim(category: ClaimCategory, amountNgwee: Long, description: String, photo: Uri?): String {
        val u = uid() ?: throw BackendException("Please log in again.")
        val path = photo?.let { p -> storage.reference.child("claims/$u/${System.currentTimeMillis()}.jpg").also { it.putFile(p).await() }.path }
        return call("rider_fileClaim", mapOf("category" to category.name, "amountNgwee" to amountNgwee, "description" to description, "photoPath" to path))["claimId"] as String
    }

    override fun myClaims(): Flow<List<SocialFundClaim>> = uid()?.let { u ->
        db.collection("socialFundClaims").whereEqualTo("riderUid", u).orderBy("createdAt", Query.Direction.DESCENDING).limit(20).live().map { s -> s.documents.map {
            SocialFundClaim(it.id, it.getString("category") ?: "", it.getLong("amountNgwee") ?: 0, it.getString("description") ?: "", it.getString("status") ?: "PENDING",
                it.getTimestamp("createdAt")?.toDate()?.time ?: 0) } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override fun chatThreads(): Flow<List<ChatThread>> = uid()?.let { u ->
        db.collection("chats").whereArrayContains("participants", u).orderBy("lastAt", Query.Direction.DESCENDING).limit(30).live().map { s -> s.documents.map { d ->
            val kind = if (d.getString("buyerUid") != null && d.getString("buyerUid") != u && d.getString("kind") != "rider_seller") "buyer" else "seller"
            val ref = d.getString("orderId") ?: ""
            @Suppress("UNCHECKED_CAST") val unread = ((d.get("unread") as? Map<String, Number>)?.get(u))?.toInt() ?: 0
            ChatThread(d.id, if (kind == "buyer") "Buyer — $ref" else "Seller — ${d.getString("storeName") ?: ""} — $ref", kind, ref,
                d.getString("lastMessage") ?: "", d.getTimestamp("lastAt")?.toDate()?.time ?: 0, unread)
        } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override fun chatMessages(threadId: String): Flow<List<ChatMessage>> {
        val u = uid() ?: return flowOf(emptyList())
        db.collection("chats").document(threadId).collection("messages")
        return db.collection("chats").document(threadId).collection("messages").orderBy("at").limitToLast(200).live().map { s -> s.documents.map {
            ChatMessage(it.id, it.getString("text") ?: "", it.getString("senderUid") == u, it.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis()) } }
            .catch { emit(emptyList()) }
    }

    override suspend fun sendChat(threadId: String, text: String) {
        val u = uid() ?: return
        db.collection("chats").document(threadId).collection("messages").add(mapOf("senderUid" to u, "text" to text.take(1000), "at" to FieldValue.serverTimestamp())).await()
    }

    override fun notifications(): Flow<List<AppNotification>> = uid()?.let { u ->
        db.collection("notifications").document(u).collection("items").orderBy("createdAt", Query.Direction.DESCENDING).limit(100).live().map { s -> s.documents.map {
            AppNotification(it.id, it.getString("type") ?: "", it.getString("title") ?: "", it.getString("body") ?: "",
                it.getTimestamp("createdAt")?.toDate()?.time ?: 0, it.get("readAt") != null) } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override suspend fun withdrawFull(pin: String): WithdrawOutcome {
        val r = call("wallet_withdraw", mapOf("pin" to pin))
        return WithdrawOutcome(r["withdrawalId"] as String, r["status"] as? String ?: "PENDING", (r["amountNgwee"] as? Number)?.toLong() ?: 0, r["message"] as? String ?: "")
    }
    override suspend fun studioStart(listingId: String): String? = call("studio_start", mapOf("listingId" to listingId)).let { if (it["started"] == true) null else it["reason"] as? String }
    override suspend fun studioApply(listingId: String, apply: Boolean) { call("studio_apply", mapOf("listingId" to listingId, "apply" to apply)) }
    override suspend fun walletStatus(): WalletStatus {
        val r = call("wallet_status", emptyMap())
        val a = r["active"] as? Map<*, *>
        return WalletStatus((r["availableNgwee"] as? Number)?.toLong() ?: 0, (r["lockedNgwee"] as? Number)?.toLong() ?: 0, (r["minNgwee"] as? Number)?.toLong() ?: 2_000,
            r["block"] as? String, a?.let { ActiveWithdrawal(it["id"] as String, it["status"] as? String ?: "", (it["amountNgwee"] as? Number)?.toLong() ?: 0, it["message"] as? String) })
    }
    override suspend fun setPayoutWallet(msisdn: String) { call("wallet_setPayout", mapOf("msisdn" to msisdn)) }
    override suspend fun changePin(currentPin: String, newPin: String) { call("auth_changePin", mapOf("currentPin" to currentPin, "newPin" to newPin)) }
    override suspend fun updateSecurityQuestions(pin: String, answers: List<QuestionAnswer>) {
        call("auth_updateSecurityQuestions", mapOf("pin" to pin, "answers" to answers.map { mapOf("questionId" to it.questionId, "answer" to it.answer) }))
    }
    override suspend fun reportIncident(r: IncidentReport): String {
        val u = uid() ?: throw BackendException("Please log in again.")
        val path = r.photo?.let { p -> storage.reference.child("incidents/$u/${System.currentTimeMillis()}.jpg").also { it.putFile(p).await() }.path }
        return call("incident_report", mapOf("type" to r.type, "description" to r.description, "orderRef" to r.orderRef, "photoPath" to path))["incidentId"] as String
    }
    override suspend fun supportStatus(): Pair<Boolean, Int> = runCatching {
        val d = db.collection("platformConfig").document("support").get().await()
        (d.getBoolean("liveChatOnline") ?: false) to ((d.getLong("avgResponseMin") ?: 10).toInt())
    }.getOrDefault(false to 10)

    override suspend fun locateStops(orderIds: List<String>): Map<String, Pair<selo.Nachipa01.domain.route.LatLng?, selo.Nachipa01.domain.route.LatLng?>> {
        if (orderIds.isEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST") val res = call("route_locate", mapOf("orderIds" to orderIds))["stops"] as? Map<String, Map<String, Any?>> ?: return emptyMap()
        fun ll(v: Any?) = (v as? Map<*, *>)?.let { m -> val a = (m["lat"] as? Number)?.toDouble(); val b = (m["lng"] as? Number)?.toDouble()
            if (a != null && b != null) selo.Nachipa01.domain.route.LatLng(a, b) else null }
        return res.mapValues { (_, v) -> ll(v["pickup"]) to ll(v["dropoff"]) }
    }

    override suspend fun directions(origin: selo.Nachipa01.domain.route.LatLng, stops: List<selo.Nachipa01.domain.route.Stop>, vehicle: String): selo.Nachipa01.domain.route.Directions {
        val res = call("route_directions", mapOf("origin" to mapOf("lat" to origin.lat, "lng" to origin.lng), "vehicle" to vehicle,
            "stops" to stops.filter { it.at != null }.map { s -> mapOf("lat" to s.at!!.lat, "lng" to s.at.lng, "orderId" to s.orderId, "type" to s.type.name) }))
        @Suppress("UNCHECKED_CAST") val steps = (res["steps"] as? List<Map<String, Any?>> ?: emptyList()).map {
            selo.Nachipa01.domain.route.NavStep(it["instruction"] as? String ?: "", it["maneuver"] as? String ?: "straight",
                (it["distanceM"] as? Number)?.toInt() ?: 0, (it["durationS"] as? Number)?.toInt() ?: 0,
                selo.Nachipa01.domain.route.NavigationEngine.decodePolyline(it["polyline"] as? String ?: ""), (it["legIndex"] as? Number)?.toInt() ?: 0)
        }
        val t = res["traffic"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST") val segs = (t?.get("segments") as? List<Map<String, Any?>>).orEmpty().map {
            selo.Nachipa01.domain.route.TrafficSegment(selo.Nachipa01.domain.route.NavigationEngine.decodePolyline(it["polyline"] as? String ?: ""), it["speed"] as? String ?: "NORMAL") }
        return selo.Nachipa01.domain.route.Directions(steps, (res["distanceM"] as? Number)?.toInt() ?: steps.sumOf { it.distanceM },
            (res["durationS"] as? Number)?.toInt() ?: steps.sumOf { it.durationS }, res["source"] as? String ?: "google",
            segs, t?.get("level") as? String ?: "unknown", (t?.get("delayS") as? Number)?.toInt() ?: 0, (res["alternatives"] as? Number)?.toInt() ?: 0)
    }
    override suspend fun mediaUrl(path: String): String? = runCatching { storage.reference.child(path).downloadUrl.await().toString() }.getOrNull()

    /* ======================================================== v6.4 address system */
    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.toPlace(): Place = Place(this["id"] as? String ?: "", this["label"] as? String ?: "", this["address"] as? String ?: "",
        (this["lat"] as Number).toDouble(), (this["lng"] as Number).toDouble(), this["cityId"] as? String, this["cityName"] as? String, this["neighbourhood"] as? String,
        this["inServiceArea"] != false, this["approximate"] == true, (this["id"] as? String)?.takeIf { it.startsWith("addr:") }, this["placeId"] as? String, this["landmark"] as? String)
    private fun Place.wire() = mapOf("label" to label, "address" to address, "lat" to lat, "lng" to lng, "neighbourhood" to neighbourhood, "addressId" to addressId, "landmark" to landmark)

    private var preAuth: Map<String, String>? = null
    override fun setPreAuth(msisdn: String?, otpToken: String?) { preAuth = if (msisdn != null && otpToken != null) mapOf("msisdn" to msisdn, "otpToken" to otpToken) else null }
    private fun geo(m: Map<String, Any?>) = if (auth.currentUser == null && preAuth != null) m + ("preauth" to preAuth) else m

    override suspend fun addressSuggestions(input: String, cityId: String?, nearLat: Double?, nearLng: Double?): List<AddressSuggestion> {
        val cacheCity = listOfNotNull(cityId, nearLat?.let { "%.2f,%.2f".format(it, nearLng) }).joinToString("@").ifBlank { null }
        selo.Nachipa01.data.maps.PlacesCache.get(input, cacheCity)?.let { return it }
        @Suppress("UNCHECKED_CAST") val r = call("geo_autocomplete", geo(mapOf("input" to input, "cityId" to cityId, "sessionToken" to selo.Nachipa01.data.maps.PlacesCache.sessionToken,
            "near" to if (nearLat != null && nearLng != null) mapOf("lat" to nearLat, "lng" to nearLng) else null)))
        val list = (r["suggestions"] as? List<Map<String, Any?>> ?: emptyList()).map { AddressSuggestion(it["id"] as String, it["label"] as? String ?: "", it["secondary"] as? String ?: "",
            it["source"] as? String ?: "", (it["distanceM"] as? Number)?.toInt()) }
        if (r["degraded"] != true) selo.Nachipa01.data.maps.PlacesCache.put(input, cacheCity, list)
        return list
    }
    override suspend fun placeDetails(id: String) = call("geo_details", geo(mapOf("id" to id))).toPlace().also { selo.Nachipa01.data.maps.PlacesCache.endSession() }
    override suspend fun reverseGeocode(lat: Double, lng: Double) = call("geo_reverse", geo(mapOf("lat" to lat, "lng" to lng))).toPlace()
    override suspend fun saveMyLocation(p: Place) { call("location_save", mapOf("address" to p.address, "label" to p.label, "latitude" to p.lat, "longitude" to p.lng,
        "placeId" to p.placeId, "source" to "user")) }
    override suspend fun shopProfile(sellerUid: String): ShopProfile = orOffline({ liveShop(sellerUid) }) {
        val ls = cachedListings(OfflineCatalog.all().filter { it["sellerUid"] == sellerUid })
        ShopProfile(sellerUid, ls.firstOrNull()?.sellerName ?: "Shop", false, null, null, "Offline · products you've already seen", ls)
    }
    private suspend fun liveShop(sellerUid: String): ShopProfile {
        val r = call("shop_profile", mapOf("sellerUid" to sellerUid))
        @Suppress("UNCHECKED_CAST") val ls = (r["listings"] as? List<Map<*, *>>).orEmpty().map { it.toListing() }
        return ShopProfile(sellerUid, r["name"] as? String ?: "", r["verified"] == true, r["photoUrl"] as? String, r["bannerUrl"] as? String, r["address"] as? String ?: "", ls)
    }
    override suspend fun setProfilePhoto(uri: android.net.Uri): String {
        val u = uid() ?: throw BackendException("Please log in again.")
        val ref = storage.reference.child("profiles/$u/photo-${UUID.randomUUID()}.jpg")
        ref.putFile(uri).await()
        val url = ref.downloadUrl.await().toString()
        db.collection("users").document(u).update("photoUrl", url).await()
        return url
    }
    @Suppress("UNCHECKED_CAST")
    override suspend fun receipts(refId: String?): List<Receipt> {
        val r = call("receipt_get", mapOf("refId" to refId))
        return (r["receipts"] as? List<Map<String, Any?>>).orEmpty().map { x ->
            Receipt(x["id"] as String, x["number"] as? String ?: "", x["kind"] as? String ?: "", x["refId"] as? String ?: "", x["issuerNote"] as? String ?: "",
                x["payerName"] as? String ?: "", (x["onBehalfOf"] as? Map<*, *>)?.get("name") as? String ?: "",
                (x["lines"] as? List<Map<String, Any?>>).orEmpty().map { l -> ReceiptLine(l["label"] as? String ?: "", (l["amountNgwee"] as? Number)?.toLong() ?: 0, l["onBehalfOf"] as? String) },
                (x["totalNgwee"] as? Number)?.toLong() ?: 0, x["method"] as? String ?: "", x["wallet"] as? String ?: "", (x["paidAt"] as? Number)?.toLong() ?: 0,
                x["paymentReference"] as? String ?: "", x["token"] as? String ?: "")
        }
    }
    override suspend fun nearbyStores(lat: Double, lng: Double): List<StoreLocation> {
        val cells = selo.Nachipa01.domain.GeoHash.cellsAround(lat, lng)
        return db.collection("stores").whereIn("geohash5", cells).whereEqualTo("active", true).limit(300).get().await().documents.mapNotNull { d ->
            val la = d.getDouble("latitude") ?: return@mapNotNull null; val ln = d.getDouble("longitude") ?: return@mapNotNull null
            StoreLocation(d.id, d.getString("name") ?: "", d.getString("address") ?: "", la, ln) }
    }
    override suspend fun routeDistance(from: Place, to: Place): RouteDistance {
        val r = call("geo_distance", mapOf("from" to mapOf("lat" to from.lat, "lng" to from.lng), "to" to mapOf("lat" to to.lat, "lng" to to.lng)))
        return RouteDistance((r["distanceKm"] as Number).toDouble(), (r["durationMin"] as Number).toInt(), r["approximate"] == true, r["sameCity"] != false, r["fromCity"] as? String)
    }
    override suspend fun savePlace(p: Place, source: String): String =
        "addr:" + call("address_save", mapOf("label" to p.label, "address" to p.address, "lat" to p.lat, "lng" to p.lng, "source" to source, "approximate" to p.approximate,
            "placeId" to p.placeId))["id"] as String
    override fun savedAddresses(): Flow<List<SavedAddress>> = uid()?.let { u ->
        db.collection("users").document(u).collection("addresses").live().map { s -> s.documents.map { d ->
            SavedAddress(d.id, d.getString("nickname") ?: "", Place(d.getString("addressId") ?: d.id, d.getString("label") ?: "", d.getString("address") ?: "",
                d.getDouble("lat") ?: 0.0, d.getDouble("lng") ?: 0.0, d.getString("cityId"), selo.Nachipa01.domain.Cities.byId(d.getString("cityId"))?.name,
                null, true, false, d.getString("addressId")), d.getBoolean("isDefault") == true) } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())
    override suspend fun saveMyAddress(nickname: String, p: Place, makeDefault: Boolean): String {
        val u = uid() ?: throw BackendException("Please log in again.")
        val addressId = runCatching { savePlace(p) }.getOrNull()
        val col = db.collection("users").document(u).collection("addresses")
        if (makeDefault) col.whereEqualTo("isDefault", true).get().await().documents.forEach { it.reference.update("isDefault", false).await() }
        return col.add(mapOf("nickname" to nickname.take(40), "label" to p.label, "address" to p.address, "lat" to p.lat, "lng" to p.lng, "cityId" to p.cityId,
            "addressId" to addressId, "isDefault" to makeDefault, "updatedAt" to FieldValue.serverTimestamp())).await().id
    }
    override suspend fun deleteMyAddress(id: String) { uid()?.let { db.collection("users").document(it).collection("addresses").document(id).delete().await() } }
    override suspend fun orderQuote(items: List<OrderItem>, dropoff: Place): OrderQuoteResult {
        val r = call("order_quote", mapOf("items" to items.map { mapOf("listingId" to it.listingId, "qty" to it.qty) }, "dropoffGeo" to mapOf("lat" to dropoff.lat, "lng" to dropoff.lng)))
        fun l(k: String) = (r[k] as? Number)?.toLong() ?: 0
        @Suppress("UNCHECKED_CAST")
        return OrderQuoteResult(l("goodsNgwee"), l("deliveryNgwee"), l("serviceFeeNgwee"), l("processingFeeNgwee"), l("totalNgwee"), (r["distanceKm"] as? Number)?.toDouble() ?: 0.0,
            r["approximate"] == true, (r["etaMin"] as? Number)?.toInt() ?: 0, r["unavailable"] as? List<String> ?: emptyList(),
            fullGoodsNgwee = (r["fullGoodsNgwee"] as? Number)?.toLong() ?: l("goodsNgwee"), savingsNgwee = l("savingsNgwee"),
            deals = (r["deals"] as? List<Map<*, *>>).orEmpty().map { (it["title"] as? String ?: "Deal") to ((it["savingNgwee"] as? Number)?.toLong() ?: 0) },
            freeItems = (r["freeItems"] as? List<Map<*, *>>).orEmpty().map { "${(it["qty"] as? Number)?.toInt() ?: 1} × ${it["title"]} free" })
    }

    /* ======================================================== v6.4 parcels */
    override suspend fun parcelQuote(pickup: Place, dropoff: Place, insured: Boolean, declaredNgwee: Long): ParcelQuoteResult {
        val r = call("parcel_quote", mapOf("pickup" to pickup.wire(), "dropoff" to dropoff.wire(), "insured" to insured, "declaredValueNgwee" to declaredNgwee))
        fun l(k: String) = (r[k] as? Number)?.toLong() ?: 0
        return ParcelQuoteResult((r["distanceKm"] as Number).toDouble(), r["approximate"] == true, l("deliveryFeeNgwee"), l("serviceFeeNgwee"), l("insuranceNgwee"),
            l("totalNgwee"), (r["etaMin"] as? Number)?.toInt() ?: 0, r["cityName"] as? String ?: "")
    }
    override suspend fun createParcel(r: NewParcel): String = call("parcel_create", mapOf("pickup" to r.pickup.wire(), "dropoff" to r.dropoff.wire(),
        "weightBand" to r.weightBand, "description" to r.description, "category" to r.category, "handlingNotes" to r.handlingNotes,
        "receiver" to mapOf("name" to r.receiverName, "msisdn" to r.receiverMsisdn), "deliveryInstructions" to r.deliveryInstructions,
        "insured" to r.insured, "declaredValueNgwee" to r.declaredValueNgwee, "walletMsisdn" to r.walletMsisdn, "scheduledFor" to r.scheduledFor))["parcelId"] as String

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toParcel(me: String?): Parcel {
        fun pl(k: String): Place { val m = get(k) as? Map<String, Any?> ?: emptyMap()
            return Place(k, m["label"] as? String ?: "", m["address"] as? String ?: "", (m["lat"] as? Number)?.toDouble() ?: 0.0, (m["lng"] as? Number)?.toDouble() ?: 0.0,
                getString("cityId"), getString("cityName"), m["neighbourhood"] as? String) }
        val f = get("fees") as? Map<String, Any?> ?: emptyMap(); fun fl(k: String) = (f[k] as? Number)?.toLong() ?: 0
        val r = (get("codeRecipient") ?: get("receiver")) as? Map<String, Any?> ?: emptyMap()
        val geo = get("riderGeo") as? Map<String, Any?>
        val tl = (get("timeline") as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? com.google.firebase.Timestamp)?.let { TimelineEvent(k, it.toDate().time) } }?.sortedBy { it.at } ?: emptyList()
        return Parcel(id, ParcelStatus.from(getString("status")), getString("senderUid") == me, getString("senderName") ?: "", pl("pickup"), pl("dropoff"),
            getString("weightBand") ?: "", getString("description") ?: "", getString("category") ?: "", r["name"] as? String ?: "", r["msisdn"] as? String ?: "",
            getString("deliveryInstructions") ?: "", getDouble("distanceKm") ?: 0.0, getBoolean("distanceApproximate") == true, (getLong("etaMin") ?: 0).toInt(),
            fl("deliveryNgwee"), fl("sendingNgwee") - (getLong("creditAppliedNgwee") ?: 0), fl("insuranceNgwee"), fl("totalNgwee"),
            getString("riderName"), getString("riderPlate"), (geo?.get("lat") as? Number)?.toDouble(), (geo?.get("lng") as? Number)?.toDouble(),
            getString("paymentStatus") ?: "PENDING", getString("paymentFailure"), getString("walletMsisdn"), getString("codeSummary.state"), getString("codeSummary.channel"),
            getTimestamp("unavailable.deadline")?.toDate()?.time, getString("unavailable.response"),
            getString("trackingToken")?.let { "${BuildConfig.TRACKING_BASE_URL}$it" }, getTimestamp("createdAt")?.toDate()?.time ?: 0,
            getTimestamp("deliveredAt")?.toDate()?.time, getTimestamp("scheduledFor")?.toDate()?.time, tl, getString("returnStatus"),
            getString("trackingPolyline"), getLong("navigation.estimatedMinutes")?.toInt())
    }
    override fun myParcels(): Flow<List<Parcel>> = uid()?.let { u ->
        combine(db.collection("parcels").whereEqualTo("senderUid", u).orderBy("createdAt", Query.Direction.DESCENDING).limit(50).live(),
            db.collection("parcels").whereEqualTo("receiver.uid", u).orderBy("createdAt", Query.Direction.DESCENDING).limit(50).live()) { a, b ->
            (a.documents + b.documents).distinctBy { it.id }.map { it.toParcel(u) }.sortedByDescending { it.createdAt }
        }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())
    override fun parcel(id: String): Flow<Parcel?> = db.collection("parcels").document(id).live().map { if (it.exists()) it.toParcel(uid()) else null }.catch { emit(null) }
    override fun parcelPickupPin(id: String): Flow<String?> = db.collection("parcels").document(id).collection("private").document("pickup").live()
        .map { it.getString("pin") }.catch { emit(null) }
    override suspend fun retryParcelPayment(id: String, walletMsisdn: String) { call("parcel_retryPayment", mapOf("parcelId" to id, "walletMsisdn" to walletMsisdn)) }
    override suspend fun cancelParcel(id: String) = (call("parcel_cancel", mapOf("parcelId" to id))["refundedNgwee"] as? Number)?.toLong() ?: 0
    override suspend fun editParcelReceiver(id: String, name: String, msisdn: String) { call("parcel_editReceiver", mapOf("parcelId" to id, "name" to name, "msisdn" to msisdn)) }
    override suspend fun confirmParcelPickup(id: String, pin: String) { call("rider_confirmPickup", mapOf("parcelId" to id, "pin" to pin)) }
    override suspend fun setRiderAreas(cityId: String, neighbourhoods: List<String>) { call("rider_setAreas", mapOf("cityId" to cityId, "neighbourhoods" to neighbourhoods)) }
    override suspend fun pingLocation(lat: Double, lng: Double, accuracy: Float?) { runCatching { call("rider_ping", mapOf("lat" to lat, "lng" to lng, "accuracy" to accuracy?.toDouble())) } }

    /* ======================================================== v6.4 delivery confirmation */
    private fun pos(lat: Double?, lng: Double?) = if (lat != null && lng != null) mapOf("lat" to lat, "lng" to lng) else null
    override suspend fun requestDeliveryCode(kind: String, id: String, lat: Double?, lng: Double?, resend: Boolean, smsOnly: Boolean): CodeRequestResult {
        val r = call("requestDeliveryCode", mapOf("kind" to kind, "orderId" to id, "position" to pos(lat, lng), "resend" to resend, "smsOnly" to smsOnly))
        return CodeRequestResult(r["state"] as? String ?: "sent", r["channel"] as? String ?: "", (r["expiresAt"] as? Number)?.toLong() ?: 0,
            (r["attemptsLeft"] as? Number)?.toInt() ?: 5, (r["codesLeft"] as? Number)?.toInt() ?: 0)
    }
    override suspend fun verifyDeliveryCode(kind: String, id: String, code: String, lat: Double?, lng: Double?): CodeVerifyResult {
        val r = call("verifyDeliveryCode", mapOf("kind" to kind, "orderId" to id, "code" to code, "position" to pos(lat, lng)))
        return CodeVerifyResult(r["result"] as? String ?: "no_code", (r["attemptsLeft"] as? Number)?.toInt(), (r["riderEarningsNgwee"] as? Number)?.toLong())
    }
    override suspend fun receiverUnavailable(kind: String, id: String, lat: Double?, lng: Double?) =
        (call("receiverUnavailable", mapOf("kind" to kind, "orderId" to id, "position" to pos(lat, lng)))["deadline"] as? Number)?.toLong() ?: 0
    override suspend fun respondUnavailable(kind: String, id: String, action: String, name: String?, msisdn: String?) {
        call("senderRespondUnavailable", mapOf("kind" to kind, "orderId" to id, "action" to action, "name" to name, "msisdn" to msisdn))
    }
    override suspend fun confirmReceipt(kind: String, id: String) { call("confirmReceipt", mapOf("kind" to kind, "orderId" to id)) }
    override suspend fun rateDelivery(kind: String, id: String, stars: Int, comment: String) { call("delivery_rate", mapOf("kind" to kind, "orderId" to id, "stars" to stars, "comment" to comment)) }
    override suspend fun reportIssue(kind: String, id: String, type: String, description: String) =
        call("delivery_reportIssue", mapOf("kind" to kind, "orderId" to id, "type" to type, "description" to description))["disputeId"] as String
    override suspend fun ackDeliveryCode(kind: String, id: String) { runCatching { call("deliveryCode_ack", mapOf("kind" to kind, "orderId" to id)) } }
    override suspend fun attachProofOfDelivery(kind: String, id: String, photo: Uri) {
        val u = uid() ?: return
        val ref = storage.reference.child("pod/$id/${u}_${System.currentTimeMillis()}.jpg")
        ref.putFile(photo).await()
        call("delivery_attachPod", mapOf("kind" to kind, "orderId" to id, "photoPath" to ref.path))
    }

    /* ======================================================== v6.4 account + support */
    override suspend fun sendSignupOtp(msisdn: String, appContext: String): OtpChallenge {
        val r = call("auth_sendOtp", mapOf("msisdn" to msisdn, "appContext" to appContext))
        return OtpChallenge(r["pinId"] as String, (r["expiresInSeconds"] as? Number)?.toInt() ?: 60)
    }
    override suspend fun verifySignupOtp(msisdn: String, pinId: String, pin: String) =
        call("auth_verifyOtp", mapOf("msisdn" to msisdn, "pinId" to pinId, "pin" to pin))["otpToken"] as String
    @Suppress("UNCHECKED_CAST")
    override suspend fun myQuestions(): List<SecurityQuestion> = (call("auth_myQuestions", emptyMap())["questions"] as? List<Map<String, Any?>> ?: emptyList())
        .map { SecurityQuestion(it["id"] as String, it["text"] as? String ?: "") }
    override suspend fun updateProfile(changes: Map<String, Any?>) {
        val u = uid() ?: throw BackendException("Please log in again.")
        val allowed = changes.filterKeys { it in setOf("fullName", "area", "landmark") }.toMutableMap()
        (allowed["fullName"] as? String)?.let { if (it.trim().length < 3) throw BackendException("Enter your full name."); allowed["firstName"] = it.trim().substringBefore(" ") }
        db.collection("users").document(u).update(allowed).await()
    }
    @Suppress("UNCHECKED_CAST")
    override fun notificationPrefs(): Flow<Map<String, Boolean>> = uid()?.let { u ->
        db.collection("users").document(u).live().map { (it.get("notificationPrefs") as? Map<String, Boolean>) ?: emptyMap() }.catch { emit(emptyMap()) }
    } ?: flowOf(emptyMap())
    override suspend fun saveNotificationPrefs(prefs: Map<String, Boolean>) { uid()?.let { db.collection("users").document(it).update("notificationPrefs", prefs).await() } }
    @Suppress("UNCHECKED_CAST")
    override fun paymentWallets(): Flow<List<String>> = uid()?.let { u ->
        db.collection("users").document(u).live().map { ((it.get("paymentPrefs") as? Map<String, Any?>)?.get("wallets") as? List<String>) ?: emptyList() }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())
    override suspend fun savePaymentWallets(wallets: List<String>) {
        val clean = wallets.map { selo.Nachipa01.domain.Validation.normaliseMsisdn(it) ?: throw BackendException("$it isn't an Airtel, MTN or Zamtel number.") }.distinct()
        uid()?.let { db.collection("users").document(it).update("paymentPrefs.wallets", clean).await() }
    }
    override fun supportTickets(): Flow<List<SupportTicket>> = uid()?.let { u ->
        db.collection("supportTickets").whereEqualTo("uid", u).orderBy("updatedAt", Query.Direction.DESCENDING).limit(30).live().map { s -> s.documents.map {
            SupportTicket(it.id, it.getString("reference") ?: "", it.getString("subject") ?: "", it.getString("category") ?: "", it.getString("status") ?: "OPEN",
                it.getString("lastMessage") ?: "", it.getString("lastFrom") == "support", (it.getLong("unreadForUser") ?: 0).toInt(), it.getTimestamp("updatedAt")?.toDate()?.time ?: 0) } }
            .catch { emit(emptyList()) }
    } ?: flowOf(emptyList())
    override fun ticketMessages(id: String): Flow<List<TicketMessage>> {
        runCatching { functions.getHttpsCallable("support_markRead").call(mapOf("ticketId" to id)) }
        return db.collection("supportTickets").document(id).collection("messages").orderBy("at").live().map { s -> s.documents.map {
            TicketMessage(it.id, it.getString("from") == "support", it.getString("text") ?: "", it.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis(), it.getString("agent")) } }
            .catch { emit(emptyList()) }
    }
    override suspend fun createTicket(subject: String, category: String, message: String, orderRef: String) =
        call("support_createTicket", mapOf("subject" to subject, "category" to category, "message" to message, "orderRef" to orderRef))["ticketId"] as String
    override suspend fun replyTicket(id: String, text: String) { call("support_reply", mapOf("ticketId" to id, "text" to text)) }

    /* ======================================================== v6.9 growth & safety */
    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.toCampaign() = Campaign(this["id"] as String, runCatching { CampaignType.valueOf(this["type"] as String) }.getOrDefault(CampaignType.DISCOUNT),
        this["title"] as? String ?: "", (this["listingIds"] as? List<String>).orEmpty(), (this["discountPct"] as? Number)?.toInt(), (this["bundlePriceNgwee"] as? Number)?.toLong(),
        (this["startsAt"] as? Number)?.toLong() ?: 0, (this["endsAt"] as? Number)?.toLong(), (this["maxUnits"] as? Number)?.toInt(), this["status"] as? String ?: "ACTIVE",
        (this["unitsSold"] as? Number)?.toInt() ?: 0, (this["revenueNgwee"] as? Number)?.toLong() ?: 0, (this["orders"] as? Number)?.toInt() ?: 0,
        this["label"] as? String ?: "", this["live"] == true)
    @Suppress("UNCHECKED_CAST")
    override suspend fun campaigns(): List<Campaign> = (call("campaign_list", emptyMap())["campaigns"] as? List<Map<*, *>>).orEmpty().map { it.toCampaign() }
    override suspend fun saveCampaign(c: CampaignDraft): String = call("campaign_save", mapOf("id" to c.id, "type" to c.type.name, "title" to c.title, "listingIds" to c.listingIds,
        "discountPct" to c.discountPct, "bundlePriceNgwee" to c.bundlePriceNgwee, "startsAt" to c.startsAt, "endsAt" to c.endsAt, "maxUnits" to c.maxUnits))["id"] as String
    override suspend fun setCampaignStatus(id: String, status: String) { call("campaign_setStatus", mapOf("id" to id, "status" to status)) }

    @Suppress("UNCHECKED_CAST")
    override suspend fun generateAd(photo: android.net.Uri, productName: String, priceNgwee: Long, offer: String): AdPack {
        val u = uid() ?: throw BackendException("Please log in again.")
        val path = "adgen/$u/${UUID.randomUUID()}.jpg"
        try { storage.reference.child(path).putFile(photo).await() } catch (e: Exception) { throw BackendException("We couldn't upload the photo. Check your connection and try again.") }
        val r = call("ad_generate", mapOf("photoPath" to path, "productName" to productName, "priceNgwee" to priceNgwee, "offer" to offer))
        fun t(k: String) = r[k] as? String ?: ""
        return AdPack(t("id"), r["storyUrl"] as? String, r["squareUrl"] as? String, t("productName"), t("headline"), t("description"), t("whatsapp"), t("facebook"), t("tiktok"),
            (r["hashtags"] as? List<String>).orEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun performanceReport(month: String?): PerformanceReport {
        val r = call("performance_report", mapOf("month" to month))
        return PerformanceReport(r["month"] as? String ?: "", r["monthLabel"] as? String ?: "", r["role"] as? String ?: "",
            (r["metrics"] as? List<Map<*, *>>).orEmpty().map { ReportMetric(it["key"] as? String ?: "", it["label"] as? String ?: "", (it["value"] as? Number)?.toLong() ?: 0,
                (it["previous"] as? Number)?.toLong() ?: 0, it["unit"] as? String ?: "count") },
            (r["highlights"] as? List<String>).orEmpty(), (r["tips"] as? List<String>).orEmpty(),
            (r["top"] as? List<Map<*, *>>).orEmpty().map { TopProduct(it["title"] as? String ?: "", (it["units"] as? Number)?.toInt() ?: 0, (it["revenueNgwee"] as? Number)?.toLong() ?: 0) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.toSafety() = SafetyStatus(this["emergencyName"] as? String, this["emergencyPhone"] as? String, this["insurance"] == true,
        (this["premiumNgwee"] as? Number)?.toLong() ?: 0, (this["coverNgwee"] as? Number)?.toLong() ?: 0, (this["lessons"] as? List<String>).orEmpty().toSet(),
        (this["evidenceCount"] as? Number)?.toInt() ?: 0, this["openAlertId"] as? String, this["openAlertUrl"] as? String)
    override suspend fun safetyStatus() = call("rider_safety", emptyMap()).toSafety()
    override suspend fun setRiderInsurance(on: Boolean) = call("rider_safety", mapOf("insurance" to on)).toSafety()
    override suspend fun completeLesson(id: String) = call("rider_safety", mapOf("lessonDone" to id)).toSafety()
    override suspend fun sendSos(lat: Double, lng: Double, accuracy: Float?, kind: String, note: String): SosResult {
        val r = call("rider_sos", mapOf("lat" to lat, "lng" to lng, "accuracy" to accuracy, "kind" to kind, "note" to note))
        return SosResult(r["alertId"] as String, r["shareUrl"] as? String ?: "", r["mapsUrl"] as? String ?: "", r["contactNotified"] == true, r["hasContact"] == true, r["contactName"] as? String)
    }
    override suspend fun closeSos(alertId: String, safe: Boolean) { call("rider_sosUpdate", mapOf("alertId" to alertId, "status" to if (safe) "SAFE" else "CLOSED")) }
    override suspend fun riderIdCard(): RiderIdCard {
        val r = call("rider_idCard", emptyMap())
        fun t(k: String) = r[k] as? String ?: ""
        return RiderIdCard(t("code"), t("verifyUrl"), t("name"), r["photoUrl"] as? String, t("plate"), t("vehicle"), r["verified"] == true, t("status"), t("memberSince"))
    }
    override suspend fun addDeliveryEvidence(kind: String, id: String, photo: android.net.Uri, stage: String, lat: Double?, lng: Double?): Int {
        val u = uid() ?: throw BackendException("Please log in again.")
        val path = "evidence/$u/${kind}_${id}_${stage}_${System.currentTimeMillis()}.jpg"
        try { storage.reference.child(path).putFile(photo).await() } catch (e: Exception) { throw BackendException("We couldn't upload the photo. Check your connection and try again.") }
        return (call("delivery_addEvidence", mapOf("kind" to kind, "id" to id, "path" to path, "stage" to stage, "lat" to lat, "lng" to lng))["count"] as? Number)?.toInt() ?: 1
    }
}
