package selo.Nachipa01.ui

import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import selo.Nachipa01.data.model.Advert
import selo.Nachipa01.data.model.PlatformConfig
import selo.Nachipa01.data.model.SessionUser
import selo.Nachipa01.data.repo.AiDraft
import selo.Nachipa01.data.repo.Backend

/** App-wide state: session, live platform config (synced from back office) and listing drafts. */
class AppState(val backend: Backend) : ViewModel() {
    val session = mutableStateOf<SessionUser?>(null)
    val config: StateFlow<PlatformConfig> = backend.config().stateIn(viewModelScope, SharingStarted.Eagerly, PlatformConfig())
    val adverts: StateFlow<List<Advert>> = backend.adverts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last number used on this device, shown masked on the welcome screen. */
    val rememberedMsisdn = mutableStateOf<String?>(null)
    val rememberedName = mutableStateOf<String?>(null)
    val rememberedFullName = mutableStateOf<String?>(null)
    val rememberedRole = mutableStateOf<selo.Nachipa01.data.model.Role?>(null)
    /** Set after 5 wrong PINs; the login keypad stays grey until then (epoch ms). */
    val pinLockedUntil = mutableStateOf(0L)

    // Listing creation flow (seller and agent)
    val capturedMedia = mutableStateListOf<Uri>()
    val listingPrompt = mutableStateOf<String?>(null)
    val aiDraft = mutableStateOf<AiDraft?>(null)

    fun signedIn(user: SessionUser) {
        val first = session.value == null
        session.value = user
        if (first) tracker.sessionStart(android.os.Build.MODEL ?: "android", selo.Nachipa01.BuildConfig.VERSION_NAME)
        rememberedMsisdn.value = user.msisdn
        rememberedName.value = user.firstName
        rememberedFullName.value = user.fullName
        rememberedRole.value = user.role
    }

    fun resetListingFlow() { capturedMedia.clear(); listingPrompt.value = null; aiDraft.value = null; listingMediaType.value = "video"; videoDurationMs.value = 0 }

    // ---------------- v6.1 discovery
    /** Behaviour events flow to the append-only log, tagged with the A/B variant the feed was served with. */
    val variant = mutableStateOf<String?>(null)
    val tracker = selo.Nachipa01.data.events.EventTracker { batch -> backend.logEvents(batch, variant.value) }
    fun track(e: selo.Nachipa01.data.events.BehaviourEvent) { if (session.value != null) tracker.log(e) }
    val recentSearches = mutableStateListOf<String>()
    /** Listing opened from a selonachipa:// or /l/ link; shown once the user is signed in. */
    val pendingListingId = mutableStateOf<String?>(null)
    val listingMediaType = mutableStateOf("video")
    val videoDurationMs = mutableStateOf(0L)
    /** Storage paths after upload: the video (or ordered photos), the cover frame and the frames sent to the AI. */
    val uploaded = mutableStateOf<UploadedMedia?>(null)
    /** Set when AI auto-fill failed or timed out; the review form then starts empty and says why. */
    val aiFallback = mutableStateOf<String?>(null)
    val onDeviceCategory = mutableStateOf<String?>(null)
    val listingForm = mutableStateOf<ListingForm?>(null)

    // ---------------- cart (v6.2, persistent since v6.4). One seller per order, so escrow and delivery stay one leg.
    val cart = mutableStateListOf<selo.Nachipa01.data.model.CartItem>()
    val cartCount get() = cart.sumOf { it.qty }
    /** Set by MainActivity: writes "listingId:qty,…" to private storage after every change (cart survives restarts). */
    var persistCart: (String) -> Unit = {}
    private fun saveCart() = persistCart(cart.joinToString(",") { "${it.listing.id}:${it.qty}" })
    fun inStock(l: selo.Nachipa01.data.model.Listing) = l.stock > 0 && !l.availability.equals("Sold out", true) && l.status == "live"

    /** Adds [qty] units. Returns a message for the toast (added, replaced the other seller's cart, or out of stock). */
    fun addToCart(l: selo.Nachipa01.data.model.Listing, qty: Int = 1): String? {
        if (!inStock(l)) return "${l.title} is out of stock."
        if (l.isExternal) return "${l.title} is sold on ${l.originPlatform ?: "another platform"}. Tap Buy on ${l.originPlatform ?: "platform"} to order it there."
        val notice = if (cart.isNotEmpty() && cart.first().listing.sellerUid != l.sellerUid) { cart.clear(); "Your cart now holds items from ${l.sellerName} only. One seller per order keeps delivery in one trip." } else null
        val i = cart.indexOfFirst { it.listing.id == l.id }
        val max = l.stock.coerceAtLeast(1)
        if (i >= 0) cart[i] = cart[i].copy(listing = l, qty = (cart[i].qty + qty).coerceAtMost(max)) else cart.add(selo.Nachipa01.data.model.CartItem(l, qty.coerceIn(1, max)))
        saveCart()
        return notice
    }
    fun setQty(listingId: String, qty: Int) {
        val i = cart.indexOfFirst { it.listing.id == listingId }; if (i < 0) return
        if (qty <= 0) cart.removeAt(i) else cart[i] = cart[i].copy(qty = qty.coerceAtMost(cart[i].listing.stock.coerceAtLeast(1)))
        saveCart()
    }
    fun removeFromCart(ids: Collection<String>) { cart.removeAll { it.listing.id in ids }; saveCart() }
    /** Restores the saved cart, refreshing each listing so price and stock are current. */
    fun restoreCart(saved: String?) {
        if (saved.isNullOrBlank() || cart.isNotEmpty()) return
        val wanted = saved.split(",").mapNotNull { e -> e.split(":").takeIf { it.size == 2 }?.let { (id, q) -> q.toIntOrNull()?.let { id to it } } }
        kotlinx.coroutines.MainScope().launch {
            wanted.forEach { (id, q) -> runCatching { backend.listing(id) }.getOrNull()?.let { l -> cart.add(selo.Nachipa01.data.model.CartItem(l, q.coerceAtLeast(1))) } }
        }
    }

    // ---------------- app-wide toast and navigation requests (product buttons live deep inside lists and sheets)
    val toast = mutableStateOf<String?>(null)
    /** "ok" (green tick), "error" (red, stays longer) or "info". Set through [ok] / [fail] / [info]. */
    val toastKind = mutableStateOf("info")
    fun ok(msg: String) { toastKind.value = "ok"; toast.value = msg }
    fun fail(msg: String?) { toastKind.value = "error"; toast.value = msg?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again." }
    fun info(msg: String) { toastKind.value = "info"; toast.value = msg }
    /**
     * Runs a user action and always tells the user how it went: [okMsg] on success, the server's reason on failure.
     * Returns the result, or null when it failed.
     */
    suspend fun <T> attempt(okMsg: String?, block: suspend () -> T): T? = try {
        block().also { if (okMsg != null) ok(okMsg) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { fail(e.message); null }
    val navRequest = mutableStateOf<String?>(null)
    /** Checkout link on another platform to open in the browser (Open Network listings). */
    val openExternal = mutableStateOf<String?>(null)
    /** Buy: put the item in the cart if it isn't there, then open checkout with just that item selected. */
    fun buyNow(l: selo.Nachipa01.data.model.Listing) {
        if (!inStock(l)) { toast.value = "${l.title} is out of stock."; return }
        if (l.isExternal) { openExternal.value = l.checkoutUrl; return }
        if (cart.none { it.listing.id == l.id }) addToCart(l)?.let { toast.value = it }
        navRequest.value = "checkout/cart?select=${l.id}"
    }
}

val LocalAppState = compositionLocalOf<AppState> { error("AppState not provided") }

data class UploadedMedia(val videoPath: String?, val photoPaths: List<String>, val thumbPath: String?, val framePaths: List<String>, val photoByUri: Map<String, String> = emptyMap())

/** The editable listing on the AI pre-fill screen; [reviewed] holds the fields the seller has confirmed. */
data class ListingForm(
    val title: String = "", val description: String = "", val price: String = "", val category: String = "", val condition: String = "",
    val availability: String = "In Stock", val province: String = "", val district: String = "", val location: String = "",
    val tags: List<String> = emptyList(), val discountPct: String = "", val priceHint: String? = null, val aiUsed: Boolean = false,
    val reviewed: Set<String> = emptySet(),
) {
    companion object { val FIELDS = listOf("title", "description", "price", "category", "condition", "availability", "location", "tags") }
    val allReviewed get() = FIELDS.all { it in reviewed }
}
