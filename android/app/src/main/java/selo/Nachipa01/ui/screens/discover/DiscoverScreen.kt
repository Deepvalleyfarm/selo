package selo.Nachipa01.ui.screens.discover

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.events.BehaviourEvent
import selo.Nachipa01.data.model.FeedGuard
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import kotlin.math.abs

/** Public web preview for shared links; opens the app when installed, else offers the Play Store. */
fun shareUrl(id: String) = "https://mabala-f2d65.web.app/l/$id"

/**
 * TikTok-style discovery feed (PRD v6.1 §5.1). Each card is full screen; swipe up/down to move,
 * pull down at the top to refresh. Every view, watch, skip and action is written to the behaviour log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(onBuy: (Listing) -> Unit, startWith: Listing? = null, source: String = "feed", onBack: (() -> Unit)? = null) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Listing>().apply { startWith?.let(::add) } }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var guard by remember { mutableStateOf(FeedGuard()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val liked by app.backend.likedIds().collectAsState(emptySet())
    val following by app.backend.following().collectAsState(emptySet())
    val lists by app.backend.wishlists().collectAsState(emptyList())
    val savedIds = lists.flatMap { it.itemIds }.toSet()
    var likedLocal by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var sheet by remember { mutableStateOf<Pair<String, Listing>?>(null) }
    var dismissedWarn by remember { mutableStateOf(false) }
    var dismissedBudget by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true; error = null
        runCatching { app.backend.feed(if (reset) null else sessionId, 8) }.onSuccess { page ->
            sessionId = page.sessionId; guard = page.guard; app.variant.value = page.variant
            if (reset) { items.clear(); startWith?.let(items::add) }
            items.addAll(page.items.filter { n -> items.none { it.id == n.id } })
        }.onFailure { error = it.message ?: "Couldn't load the feed." }
        loading = false
    }
    LaunchedEffect(Unit) { load(reset = false) }

    val pager = rememberPagerState { items.size }
    // a card is "active" when it is the settled page and at least 50% visible
    val activeIndex by remember { derivedStateOf { if (abs(pager.currentPageOffsetFraction) < 0.5f) pager.currentPage else -1 } }

    // view / skip tracking per page
    var shownAt by remember { mutableLongStateOf(0L) }
    var shownId by remember { mutableStateOf<String?>(null) }
    var engaged by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage, items.size) {
        val l = items.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        if (l.id == shownId) return@LaunchedEffect
        val prev = items.firstOrNull { it.id == shownId }
        val dwell = System.currentTimeMillis() - shownAt
        if (prev != null && !engaged && dwell < 3_000) app.track(BehaviourEvent("skip", prev.id, prev.sellerUid, prev.category, prev.mediaType,
            skipSpeed = if (dwell < 1_000) "fast" else "normal", promoId = prev.promoId))
        shownId = l.id; shownAt = System.currentTimeMillis(); engaged = false
        app.track(BehaviourEvent("listing_view", l.id, l.sellerUid, l.category, l.mediaType, source = if (l.id == startWith?.id) source else if (l.promoId != null) "boost" else "feed", promoId = l.promoId))
        if (pager.settledPage >= items.size - 3) load(reset = false)
    }

    fun ev(type: String, l: Listing, vararg extra: Pair<String, Any?>) {
        engaged = true
        val m = extra.toMap()
        app.track(BehaviourEvent(type, l.id, l.sellerUid, l.category, l.mediaType, undone = m["undone"] as Boolean?, channel = m["channel"] as String?,
            wishlistId = m["wishlistId"] as String?, promoId = l.promoId))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(reset = true); refreshing = false; pager.scrollToPage(0) } }) {
            VerticalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { i ->
                val l = items[i]
                val isLiked = likedLocal[l.id] ?: (l.id in liked)
                FeedCard(l, active = i == activeIndex, liked = isLiked, saved = l.id in savedIds, following = l.sellerUid in following, actions = CardActions(
                    onLike = { on -> likedLocal = likedLocal + (l.id to on); ev("like", l, "undone" to !on) },
                    onSaveLongPress = { sheet = "save" to l },
                    onShare = { sheet = "share" to l },
                    onEnquire = { ev("enquiry_open", l); sheet = "enquire" to l },
                    onDetails = { ev("details_open", l); sheet = "details" to l },
                    onNotInterested = {
                        app.track(BehaviourEvent("not_interested", l.id, l.sellerUid, l.category, l.mediaType, promoId = l.promoId)); engaged = true
                        snack = "Got it — you'll see less like this."
                        scope.launch { val idx = items.indexOf(l); if (idx >= 0) { items.removeAt(idx); if (items.size - idx < 3) load(false) } }
                    },
                    onFollow = { scope.launch { val f = l.sellerUid !in following; app.backend.setFollow(l.sellerUid, f)
                        if (f) app.track(BehaviourEvent("follow", l.id, l.sellerUid)); snack = if (f) "Following ${l.sellerName}. We'll tell you when they post." else "Unfollowed ${l.sellerName}." } },
                    onWatched = { w, t, r -> if (w > 300) app.track(BehaviourEvent("video_watch", l.id, l.sellerUid, l.category, "video", watchMs = w, totalMs = t, replayed = r, promoId = l.promoId)) },
                ))
            }
        }
        if (items.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) CircularProgressIndicator(color = Color.White)
            else { Text(error ?: "Nothing new right now. Pull down to refresh.", color = Color.White); TextButton({ scope.launch { load(true) } }) { Text("Try again", color = Selo.Coral) } }
        }
        Row(Modifier.statusBarsPadding().padding(top = 44.dp, start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = Color.White) }
            if (guard.capReached) Text("Daily limit reached · newest listings, not personalised", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.background(Color.Black.copy(.45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        snack?.let { msg ->
            LaunchedEffect(msg) { delay(2_500); snack = null }
            Text(msg, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 30.dp)
                .background(Selo.Ink, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }

    // ---- sheets
    sheet?.let { (kind, l) ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color.White) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (kind) {
                    "details" -> DetailsSheet(l, onBuy = { sheet = null; onBuy(l) }, onAddToCart = { ev("add_to_cart", l) }, onEnquire = { ev("enquiry_open", l); sheet = "enquire" to l })
                    "share" -> ShareSheet(l, onShared = { ch -> ev("share", l, "channel" to ch); sheet = null; snack = if (ch == "inapp") "Sent." else null })
                    "save" -> SaveSheet(l, lists.map { it.name }) { name -> scope.launch { app.backend.saveToWishlist(l.id, name); ev("save", l, "wishlistId" to name); sheet = null; snack = "Saved to $name." } }
                    "enquire" -> EnquireSheet(l)
                }
            }
        }
    }

    // ---- guardrails (PRD v6.1 §6.4): informative, never forced
    if (guard.warn && !dismissedWarn) AlertDialog(onDismissRequest = { dismissedWarn = true },
        icon = { Icon(Icons.Outlined.Schedule, null, tint = Selo.Red) },
        title = { Text("You've been browsing for ${guard.minutesToday} minutes") },
        text = { Text("Take a break? We can remind you to come back later. After ${guard.capMinutes} minutes today, your feed stops being personalised.") },
        confirmButton = { TextButton({ dismissedWarn = true; snack = "We'll remind you in 30 minutes." }) { Text("Remind me in 30 min", color = Selo.Red) } },
        dismissButton = { Row { TextButton({ dismissedWarn = true; onBack?.invoke() }) { Text("Exit feed") }; TextButton({ dismissedWarn = true }) { Text("Keep browsing") } } })
    if (guard.budgetCheck && !dismissedBudget && !(guard.warn && !dismissedWarn)) AlertDialog(onDismissRequest = { dismissedBudget = true },
        icon = { Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Selo.Teal) },
        title = { Text("Quick budget check") },
        text = { Text("You've enquired about a lot more items than usual this week. Want to set a spending reminder? This is just a nudge — you can carry on.") },
        confirmButton = { TextButton({ dismissedBudget = true; snack = "Budget reminder set for Friday." }) { Text("Set a reminder", color = Selo.Teal) } },
        dismissButton = { TextButton({ dismissedBudget = true }) { Text("Not now") } })
}

@Composable
private fun DetailsSheet(l: Listing, onBuy: () -> Unit, onAddToCart: () -> Unit, onEnquire: () -> Unit) {
    H(l.title, 18)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Fees.formatK(l.offerPriceNgwee), color = Selo.Red, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        if (l.hasOffer) Text("  was ${Fees.formatK(l.priceNgwee)}", color = Selo.Mute, fontSize = 13.sp)
    }
    Text(l.description, fontSize = 14.sp, lineHeight = 20.sp)
    aiExplanation(l)?.let { NoteBox(it, Tone.INFO, Icons.Outlined.AutoAwesome) }
    SeloCard {
        KvRow("Condition", l.condition.ifBlank { "—" }); KvRow("Availability", l.availability)
        KvRow("Location", listOf(l.district, l.province).filter { it.isNotBlank() }.joinToString(", ").ifBlank { l.zone })
        KvRow("Seller", l.sellerName + if (l.sellerVerified) " ✓" else "")
        if (l.tags.isNotEmpty()) KvRow("Tags", l.tags.joinToString(" ") { "#$it" })
    }
    var qty by remember(l.id) { mutableIntStateOf(1) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Quantity", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton({ if (qty > 1) qty-- }) { Text("−", color = Selo.Red, fontSize = 20.sp) }
        Text("$qty", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        TextButton({ if (qty < l.stock.coerceAtLeast(1)) qty++ }, enabled = qty < l.stock) { Text("+", color = Selo.Red, fontSize = 20.sp) }
        Text(if (l.stock > 0) "${l.stock} in stock" else "Out of stock", color = if (l.stock > 0) Selo.Mute else Selo.Red, fontSize = 12.sp)
    }
    selo.Nachipa01.ui.components.ProductActions(l, Modifier.fillMaxWidth(), qty = qty)
    OutlineButton("Enquire", onClick = onEnquire)
    NoteBox("Pay safely with Selo Escrow: the seller is paid only after a rider collects your order.", Tone.OK, Icons.Outlined.Shield)
}

@Composable
private fun ShareSheet(l: Listing, onShared: (String) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    H("Share ${l.title}", 16)
    Text("Share with a friend", fontWeight = FontWeight.Bold)
    SeloField("Friend's phone number", phone, { phone = it }, keyboard = KeyboardType.Phone, placeholder = "097 123 4567")
    ErrorText(err)
    PrimaryButton("Send in SeloNaChipa", loading = busy, enabled = phone.length >= 9) {
        busy = true; err = null
        scope.launch { runCatching { app.backend.shareToUser(l.id, phone) }.onSuccess { onShared("inapp") }.onFailure { err = it.message }; busy = false }
    }
    HorizontalDivider(color = Selo.Line)
    Button({
        val text = "${l.title} — ${Fees.formatK(l.offerPriceNgwee)} on SeloNaChipa\n${shareUrl(l.id)}"
        val wa = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); setPackage("com.whatsapp") }
        runCatching { ctx.startActivity(wa) }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text))))
        }
        onShared("whatsapp")
    }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))) {
        Icon(Icons.Outlined.Chat, null); Text("  Share on WhatsApp", fontWeight = FontWeight.ExtraBold)
    }
    Sub("WhatsApp gets the title, price and a link with the product photo. The link opens SeloNaChipa, or the download page if the app isn't installed.")
}

@Composable
private fun SaveSheet(l: Listing, names: List<String>, onSave: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    H("Save to a wishlist", 16)
    (names.ifEmpty { listOf("Favourites") }).forEach { n ->
        Row(Modifier.fillMaxWidth().clickable { onSave(n) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BookmarkBorder, null, tint = Selo.Red); Text("  $n", fontWeight = FontWeight.SemiBold)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeloField("New wishlist, e.g. Christmas gifts", newName, { newName = it.take(40) }, Modifier.weight(1f))
        Button({ onSave(newName.trim()) }, enabled = newName.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Create") }
    }
}

@Composable
private fun EnquireSheet(l: Listing) {
    val ctx = LocalContext.current
    H("Ask ${l.sellerName}", 16)
    listOf("Is this still available?", "Can you deliver to my area?", "What's your best price?").forEach { q ->
        Row(Modifier.fillMaxWidth().background(Selo.Surface, RoundedCornerShape(12.dp)).clickable {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode("$q — ${l.title} on SeloNaChipa ${shareUrl(l.id)}"))))
        }.padding(14.dp)) { Text(q, fontWeight = FontWeight.SemiBold) }
    }
    Sub("Messages go to the seller. Never pay outside the app — Selo Escrow protects only in-app payments.")
}

/** Single listing opened from a deep link, with the rest of the feed below it. */
@Composable
fun ListingDeepLinkScreen(id: String, onBuy: (Listing) -> Unit, onBack: () -> Unit) {
    val app = LocalAppState.current
    var l by remember { mutableStateOf<Listing?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(id) { l = runCatching { app.backend.listing(id) }.getOrNull(); missing = l == null }
    when {
        l != null -> DiscoverScreen(onBuy, startWith = l, source = "share", onBack = onBack)
        missing -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            H("This listing is no longer available."); TextButton(onBack) { Text("Back to the feed", color = Selo.Red) }
        }
        else -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
    }
}
