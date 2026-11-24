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
    private val rtdb = FirebaseDatabase.getInstance(app)

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

    override suspend fun questionBank(): List<SecurityQuestion> =
        db.collection("securityQuestionBank").whereEqualTo("active", true).get().await()
            .documents.map { SecurityQuestion(it.id, it.getString("text_en") ?: "") }
            .ifEmpty { DemoData.questions }

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
        return (res["questions"] as? List<Map<String, String>>)?.map { SecurityQuestion(it["id"]!!, it["text"]!!) }
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

    override suspend fun runAiPipeline(mediaPaths: List<String>, prompt: String?, onStep: (Int) -> Unit): AiDraft {
        onStep(0)
        val res = call("listing_runAiPipeline", mapOf("mediaPaths" to mediaPaths, "prompt" to prompt))
        onStep(5)
        @Suppress("UNCHECKED_CAST")
        return AiDraft(
            title = res["title"] as? String ?: "", description = res["description"] as? String ?: "",
            priceNgwee = (res["priceNgwee"] as? Number)?.toLong() ?: 0,
            priceLowNgwee = (res["priceLowNgwee"] as? Number)?.toLong() ?: 0,
            priceHighNgwee = (res["priceHighNgwee"] as? Number)?.toLong() ?: 0,
            category = res["category"] as? String ?: "", tags = res["tags"] as? List<String> ?: emptyList(),
            mediaPaths = mediaPaths, comparables = (res["comparables"] as? Number)?.toInt() ?: 0,
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
}
