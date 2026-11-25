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
        throw BackendException(e.message ?: "Something went wrong. Please try again.")
    }

    private fun DocumentSnapshot.toSession(): SessionUser = SessionUser(
        uid = id,
        role = Role.from(getString("role")),
        firstName = getString("firstName") ?: "",
        fullName = getString("fullName") ?: "",
        msisdn = getString("msisdn") ?: "",
        status = AccountStatus.from(getString("accountStatus")),
        area = getString("area") ?: "",
        storeName = getString("storeName") ?: "",
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
            "role" to req.role.name, "msisdn" to req.msisdn, "pin" to req.pin,
            "questions" to req.questions.map { mapOf("questionId" to it.questionId, "answer" to it.answer) },
            "profile" to req.profile, "payoutWallet" to req.payoutWallet, "referralCode" to req.referralCode,
            "acceptedTermsVersion" to req.acceptedTermsVersion,
        ))
        auth.signInWithCustomToken(res["token"] as String).await()
        val uid = auth.currentUser!!.uid
        if (req.kycDocs.isNotEmpty()) {
            onProgress("Uploading documents")
            val paths = req.kycDocs.mapValues { (key, uri) ->
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
    ))["orderId"] as String

    override suspend fun sendParcel(req: ParcelRequest): String = call("parcel_create", mapOf(
        "pickup" to req.pickup, "dropoff" to req.dropoff, "recipientName" to req.recipientName,
        "recipientMsisdn" to req.recipientMsisdn, "contents" to req.contents, "size" to req.size,
        "insured" to req.insured, "declaredValueNgwee" to req.declaredValueNgwee, "walletMsisdn" to req.walletMsisdn,
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
        db.collection("riderWallets").document(u).live().map {
            @Suppress("UNCHECKED_CAST")
            RiderStats(it.getLong("todayNetNgwee") ?: 0, (it.getLong("todayDeliveries") ?: 0).toInt(),
                (it.getLong("activeCount") ?: 0).toInt(), (it.getLong("acceptanceRate") ?: 100).toInt(),
                it.getLong("socialFundNgwee") ?: 0, (it.getLong("completedDeliveries") ?: 0).toInt(),
                it.getLong("availableNgwee") ?: 0, it.getString("tier") ?: "Starter",
                (it.get("weekFund") as? List<Number>)?.map { n -> n.toLong() } ?: emptyList())
        }.catch { emit(DemoData.riderStats) }
    } ?: emptyFlow()

    override fun availableJobs(): Flow<List<Order>> =
        db.collection("orders").whereEqualTo("state", "BROADCASTING").whereArrayContains("notifiedRiders", uid() ?: "-")
            .limit(10).live().map { s -> s.documents.map { it.toOrder() } }.catch { emit(emptyList()) }

    override suspend fun setOnline(online: Boolean) {
        val u = uid() ?: return
        val ref = rtdb.getReference("presence/$u")
        if (online) {
            ref.onDisconnect().updateChildren(mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP))
        }
        ref.updateChildren(mapOf("isOnline" to online, "lastSeen" to ServerValue.TIMESTAMP)).await()
        call("rider_setPresence", mapOf("online" to online))
    }

    override suspend fun acceptJob(orderId: String) { call("rider_acceptOrder", mapOf("orderId" to orderId)) }

    override fun riderActiveJobs(): Flow<List<Order>> = uid()?.let { u ->
        db.collection("orders").whereEqualTo("riderUid", u).whereIn("state", listOf("RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT"))
            .live().map { s -> s.documents.map { it.toOrder() } }.catch { emit(emptyList()) }
    } ?: flowOf(emptyList())

    override suspend fun markCollected(orderId: String) { call("rider_markCollected", mapOf("orderId" to orderId)) }

    override suspend fun confirmDeliveryCode(orderId: String, code: String) {
        call("rider_confirmDelivery", mapOf("orderId" to orderId, "code" to code))
    }

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
            rankSource = this["rankSource"] as? String, publishedAt = n("publishedAt")?.toLong() ?: 0,
        )
    }
    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.listings(key: String) = (this[key] as? List<Map<*, *>>)?.map { it.toListing() } ?: emptyList()

    override suspend fun logEvents(events: List<BehaviourEvent>, variant: String?) {
        val u = uid() ?: return
        val batch = db.batch()
        events.forEach { batch.set(db.collection("behaviourEvents").document(Keys.eventKey(u)), it.toMap(u, variant)) }
        batch.commit().await()
    }

    override suspend fun feed(sessionId: String?, count: Int): FeedPage {
        val r = call("feed_next", mapOf("sessionId" to sessionId, "count" to count))
        @Suppress("UNCHECKED_CAST") val g = r["guard"] as? Map<String, Any?> ?: emptyMap()
        fun gi(k: String, d: Int) = (g[k] as? Number)?.toInt() ?: d
        return FeedPage(r["sessionId"] as String, r.listings("items"), r["variant"] as? String ?: "control", (r["cohort"] as? Number)?.toInt() ?: 0,
            FeedGuard(gi("minutesToday", 0), g["warn"] == true, g["capReached"] == true, g["budgetCheck"] == true, gi("warnAtMinutes", 130), gi("capMinutes", 145)))
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun homeRails(): List<Rail> = (call("home_rails", emptyMap())["rails"] as? List<Map<*, *>>)
        ?.map { Rail(it["id"] as String, it["title"] as String, it.listings("items")) } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    override suspend fun search(query: String, filters: SearchFilters): SearchResult {
        val r = call("search_listings", mapOf("q" to query, "filters" to mapOf("minNgwee" to filters.minNgwee, "maxNgwee" to filters.maxNgwee,
            "province" to filters.province, "district" to filters.district, "condition" to filters.condition, "mediaType" to filters.mediaType,
            "availability" to filters.availability, "category" to filters.category)))
        return SearchResult(r.listings("results"), (r["alsoSearched"] as? List<String>) ?: emptyList())
    }

    override suspend fun listing(id: String): Listing? {
        val d = db.collection("listings").document(id).get().await()
        if (!d.exists()) return null
        return (d.data!! + ("id" to d.id) + ("publishedAt" to (d.getTimestamp("publishedAt")?.toDate()?.time ?: 0L))).toListing()
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
            (it["postedAt"] as? Number)?.toLong() ?: 0) }
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
            "businessTags", "paymentPrefs", "notificationPrefs") }).await()
    }

    override suspend fun publishListingV2(req: PublishRequest): String = call("listing_publish", mapOf(
        "mediaType" to req.mediaType, "videoPath" to req.videoPath, "photoPaths" to req.photoPaths, "thumbPath" to req.thumbPath,
        "durationMs" to req.durationMs, "title" to req.title, "description" to req.description, "priceNgwee" to req.priceNgwee,
        "category" to req.category, "condition" to req.condition, "availability" to req.availability, "province" to req.province,
        "district" to req.district, "location" to req.location, "tags" to req.tags, "discountPct" to req.discountPct, "aiUsed" to req.aiUsed,
        "asDraft" to req.asDraft,
    ))["listingId"] as String
}
