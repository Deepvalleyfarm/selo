package selo.Nachipa01.ui.screens.seller

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Order
import selo.Nachipa01.data.model.SellerDashboard
import selo.Nachipa01.data.model.SellerListingCard
import selo.Nachipa01.data.model.SellerStats
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Taxonomy
import selo.Nachipa01.ui.AppState
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.screens.discover.compact
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val tabs = listOf(
    NavItem("home", Icons.Outlined.Home, "Home"), NavItem("listings", Icons.Outlined.ViewList, "Listings"),
    NavItem("orders", Icons.Outlined.Inventory2, "Orders"), NavItem("more", Icons.Outlined.Settings, "Settings"),
)
private val addItem = NavItem("add", Icons.Outlined.Add, "Add listing")

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 0..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

/** Seller portal (PRD v6.1 §4.2–4.3): performance dashboard, listing management, persistent '+' and settings. */
@Composable
fun SellerShell(nav: NavHostController, initialTab: String = "home", onLogout: () -> Unit) {
    val app = LocalAppState.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val stats by app.backend.sellerStats().collectAsState(SellerStats(0, 0, 0, 0, 0, 0, 0))
    val orders by app.backend.sellerOrders().collectAsState(emptyList())
    var dash by remember { mutableStateOf<SellerDashboard?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { dash = runCatching { app.backend.sellerDashboard() }.getOrNull() }
    val scope = rememberCoroutineScope()
    val u = app.session.value
    var withdrawing by remember { mutableStateOf(false) }
    var walletKey by remember { mutableIntStateOf(0) }
    val wallet by selo.Nachipa01.ui.payments.rememberWalletStatus(walletKey)
    if (withdrawing) wallet?.let { w -> selo.Nachipa01.ui.payments.WithdrawSheet(w, null) { withdrawing = false; walletKey++ } }
    Scaffold(bottomBar = { SeloBottomBar(tabs, addItem, tab) { if (it == "add") { app.resetListingFlow(); nav.navigate("addListing") } else tab = it } },
        containerColor = Selo.Surface) { pad ->
        Column(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize().verticalScroll(rememberScrollState())) {
            HomeBand("${greeting()}, ${u?.firstName ?: ""}", u?.storeName ?: "", badge = { StatusChip("Verified", Tone.OK) })
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    "home" -> {
                        WalletCard("Earnings", "Paid out at rider pickup", stats.earningsNgwee, listOf(
                            QuickAction(Icons.AutoMirrored.Outlined.Logout, if (wallet?.active != null) "In progress" else "Withdraw") {
                                val b = wallet?.block; if (b == null && wallet != null) withdrawing = true else app.toast.value = b ?: "Checking your balance…" }, QuickAction(Icons.Outlined.Add, "New listing") { app.resetListingFlow(); nav.navigate("addListing") },
                            QuickAction(Icons.Outlined.Inventory2, "Orders (${stats.toPack})") { tab = "orders" }))
                        MyPromotions(onOpen = { nav.navigate("promotion/${it.id}") })
                        dash?.let { Performance(it) } ?: Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
                        SellerOrderList(orders.take(2), scope, app)
                    }
                    "listings" -> dash?.let { ListingManager(it, onPromote = { id, t -> nav.navigate("promote/$id/$t") }) { reload++ } }
                    "orders" -> SellerOrderList(orders, scope, app)
                    "more" -> SellerSettings(onLogout)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- performance */

@Composable
private fun Performance(d: SellerDashboard) {
    val up = d.weekViews >= d.lastWeekViews
    val pct = if (d.lastWeekViews > 0) ((d.weekViews - d.lastWeekViews) * 100 / d.lastWeekViews) else 0
    SectionLabel("This week")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.3f).background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp)) {
            Text("Views", color = Selo.Mute, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(compact(d.weekViews), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Icon(if (up) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, if (up) "Up" else "Down",
                    tint = if (up) Selo.Green else Selo.Red, modifier = Modifier.padding(start = 6.dp))
                Text("${if (pct >= 0) "+" else ""}$pct%", color = if (up) Selo.GreenDark else Selo.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Text("vs ${compact(d.lastWeekViews)} last week", color = Selo.Mute, fontSize = 11.sp)
        }
        StatCard(compact(d.likes), "Likes", Modifier.weight(1f), Selo.Coral)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(compact(d.shares), "Shares", Modifier.weight(1f), Selo.Teal); StatCard(compact(d.enquiries), "Enquiries", Modifier.weight(1f), Selo.Purple)
    }
    SeloCard {
        Text("Conversion funnel", fontWeight = FontWeight.ExtraBold)
        val max = (d.funnel.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        d.funnel.forEachIndexed { i, (name, n) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.width(78.dp), fontSize = 12.sp)
                Box(Modifier.weight(1f).height(16.dp).background(Selo.Line, RoundedCornerShape(8.dp))) {
                    Box(Modifier.fillMaxWidth((n / max.toFloat()).coerceAtLeast(.02f)).fillMaxHeight().background(Selo.Red.copy(alpha = 1f - i * .18f), RoundedCornerShape(8.dp)))
                }
                Text(compact(n), Modifier.width(50.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (i < d.funnel.lastIndex && n > 0) Text("  ↓ ${(d.funnel[i + 1].second * 100.0 / n).let { "%.1f".format(it) }}%", color = Selo.Mute, fontSize = 10.5.sp)
        }
    }
    var by by remember { mutableStateOf("views") }
    SeloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Top performing", fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            listOf("views", "enquiries", "likes").forEach { k -> Text(k.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (by == k) Color.White else Selo.Mute, modifier = Modifier.clip(RoundedCornerShape(50)).background(if (by == k) Selo.Ink else Color.Transparent)
                    .clickable { by = k }.padding(horizontal = 8.dp, vertical = 4.dp)) }
        }
        d.top[by]?.let { t -> Spacer(Modifier.height(6.dp)); ListingRow(t) } ?: Sub("Post your first listing to see this.")
    }
    SeloCard {
        Text("Your audience · last 7 days", fontWeight = FontWeight.ExtraBold)
        Sub("Totals only — you never see which buyers liked a listing.")
        Bars("Age range", d.ageBand.filterKeys { it != "Not given" })
        Bars("Location", d.province)
        val peak = d.peakHours.maxByOrNull { it.value }?.key
        Text("Peak hours", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val m = (d.peakHours.values.maxOrNull() ?: 1).coerceAtLeast(1)
            (0..23).forEach { h -> Box(Modifier.weight(1f).fillMaxHeight(((d.peakHours[h] ?: 0) / m.toFloat()).coerceAtLeast(.04f))
                .background(if (h == peak) Selo.Red else Selo.Red.copy(.3f), RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))) }
        }
        if (peak != null) Text("Most buyers are active around %02d:00 — post new listings shortly before.".format(peak), color = Selo.Mute, fontSize = 11.5.sp)
    }
}

@Composable
private fun Bars(title: String, data: Map<String, Int>) {
    if (data.isEmpty()) return
    Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    val total = data.values.sum().coerceAtLeast(1)
    data.entries.sortedByDescending { it.value }.take(6).forEach { (k, v) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
            Text(k, Modifier.width(86.dp), fontSize = 11.5.sp, maxLines = 1)
            Box(Modifier.weight(1f).height(10.dp).background(Selo.Line, RoundedCornerShape(5.dp))) { Box(Modifier.fillMaxWidth(v / total.toFloat()).fillMaxHeight().background(Selo.Teal, RoundedCornerShape(5.dp))) }
            Text(" ${v * 100 / total}%", Modifier.width(40.dp), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ListingRow(c: SellerListingCard, trailing: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFFB0281A), Color(0xFFF2B33D)))), contentAlignment = Alignment.Center) {
            if (c.thumbUrl != null) coil3.compose.AsyncImage(c.thumbUrl, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            if (c.mediaType == "video") Icon(Icons.Outlined.PlayCircle, "Video", tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("👁 ${compact(c.views)}  ♥ ${compact(c.likes)}  ↗ ${compact(c.shares)}  💬 ${compact(c.enquiries)}", fontSize = 11.5.sp, color = Selo.Mute)
            Text("${Fees.formatK(c.priceNgwee)} · posted ${SimpleDateFormat("d MMM", Locale.UK).format(Date(c.postedAt))}", fontSize = 11.sp, color = Selo.Mute)
        }
        trailing?.invoke()
    }
}

/* ---------------------------------------------------------------- listing manager */

@Composable
private fun ListingManager(d: SellerDashboard, onPromote: (String, String) -> Unit, onChanged: () -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var editing by remember { mutableStateOf<SellerListingCard?>(null) }
    var confirmDelete by remember { mutableStateOf<SellerListingCard?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun act(action: String, ids: List<String>?, done: String) = scope.launch {
        runCatching { app.backend.bulkListings(action, ids) }.onSuccess { msg = "$done ($it)"; onChanged() }.onFailure { msg = it.message }
    }
    val rows = d.listings.filter { filter == "all" || it.status == filter || (filter == "live" && it.status == "pending_review") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("all" to "All", "live" to "Active", "paused" to "Paused", "sold" to "Sold").forEach { (k, n) ->
            SeloChip("$n ${if (k == "all") d.listings.size else d.listings.count { it.status == k }}", filter == k) { filter = k } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ act("pause", null, "Paused all") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Pause all", color = Selo.Red, fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ act("resume", null, "Reactivated all") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Reactivate", color = Selo.Red, fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({
            val csv = "id,title,status,price_zmw,views,likes,shares,enquiries,posted\n" + d.listings.joinToString("\n") {
                "${it.id},\"${it.title.replace("\"", "\"\"")}\",${it.status},${"%.2f".format(it.priceNgwee / 100.0)},${it.views},${it.likes},${it.shares},${it.enquiries},${SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date(it.postedAt))}" }
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "SeloNaChipa listings"); putExtra(Intent.EXTRA_TEXT, csv) }, "Export listings"))
        }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Export CSV", color = Selo.Red, fontSize = 12.sp, maxLines = 1) }
    }
    msg?.let { NoteBox(it, Tone.OK) }
    if (rows.isEmpty()) NoteBox("No listings here yet. Tap + to record a video listing.")
    rows.forEach { c ->
        SeloCard {
            ListingRow(c) { StatusChip(c.status.replace('_', ' '), when (c.status) { "live" -> Tone.OK; "paused" -> Tone.WARN; "sold" -> Tone.INFO; "flagged" -> Tone.BRAND; else -> Tone.GREY }) }
            StudioStrip(c, onMsg = { msg = it }, onChanged = onChanged)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                QuickLink("Edit") { editing = c }
                if (c.status == "live") QuickLink("Pause") { act("pause", listOf(c.id), "Paused") }
                if (c.status == "paused") QuickLink("Resume") { act("resume", listOf(c.id), "Live again") }
                if (c.status in listOf("live", "paused")) QuickLink("Mark sold") { act("sold", listOf(c.id), "Marked as sold") }
                QuickLink("Delete") { confirmDelete = c }
            }
            if (c.status == "live") Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onPromote(c.id, "boost") }, Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) {
                    Icon(Icons.Outlined.RocketLaunch, null, Modifier.size(16.dp)); Text("  Boost", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                OutlinedButton({ onPromote(c.id, "story") }, Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.size(16.dp), tint = Selo.Purple); Text("  Feed Story", color = Selo.Purple, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
            }
        }
    }
    editing?.let { c -> EditListingDialog(c, { editing = null }) { changes -> scope.launch { runCatching { app.backend.updateListing(c.id, changes) }
        .onSuccess { msg = "Saved ${c.title}"; onChanged() }.onFailure { msg = it.message }; editing = null } } }
    confirmDelete?.let { c -> AlertDialog({ confirmDelete = null }, title = { Text("Delete ${c.title}?") },
        text = { Text("It disappears from the feed straight away. We keep an archived copy for your records.") },
        confirmButton = { TextButton({ act("archive", listOf(c.id), "Deleted"); confirmDelete = null }) { Text("Delete", color = Selo.Red) } },
        dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } }) }
}

/** Listing Studio status: AI-produced version (edit, voiceover, captions or product motion) — use it or keep the original. */
@Composable
private fun StudioStrip(c: SellerListingCard, onMsg: (String?) -> Unit, onChanged: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun go(block: suspend () -> String?) { busy = true; scope.launch { runCatching { block() }.onSuccess { onMsg(it); onChanged() }.onFailure { onMsg(it.message) }; busy = false } }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(Selo.Purple.copy(.07f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Purple, modifier = Modifier.size(16.dp))
        Text(when (c.studioStatus) { "processing" -> "  Studio is producing your pro video…"; "ready" -> if (c.studioApplied) "  Studio version is live" else "  Studio version ready"
            "failed" -> "  Studio couldn't finish — original is live"; else -> "  Make a studio-quality version" }, fontSize = 12.sp, color = Selo.Purple, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Selo.Purple)
            c.studioStatus == "processing" -> {}
            c.studioStatus == "ready" && c.studioApplied -> QuickLink("Use original") { go { app.backend.studioApply(c.id, false); "Original media restored." } }
            c.studioStatus == "ready" -> QuickLink("Use studio") { go { app.backend.studioApply(c.id, true); "Studio version is now live." } }
            else -> QuickLink(if (c.studioStatus == "failed") "Try again" else "Create") { go { app.backend.studioStart(c.id) ?: "Studio started. We'll update this listing when it's ready." } }
        }
    }
}

@Composable
private fun QuickLink(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Text(text, color = if (enabled) Selo.Red else Selo.Mute, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp))

@Composable
private fun EditListingDialog(c: SellerListingCard, onDismiss: () -> Unit, onSave: (Map<String, Any?>) -> Unit) {
    var title by remember { mutableStateOf(c.title) }
    var price by remember { mutableStateOf("%.2f".format(c.priceNgwee / 100.0)) }
    var offer by remember { mutableStateOf("") }
    var availability by remember { mutableStateOf("In Stock") }
    AlertDialog(onDismiss, title = { Text("Edit listing") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SeloField("Title", title, { title = it })
            SeloField("Price (ZMW)", price, { price = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            SeloField("Offer % (0 to remove)", offer, { offer = it.filter(Char::isDigit).take(2) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Taxonomy.AVAILABILITY.forEach { a -> SeloChip(a, availability == a) { availability = a } } }
        }
    }, confirmButton = { TextButton({ onSave(buildMap { put("title", title); put("priceNgwee", Fees.kwachaToNgwee(price.toDoubleOrNull() ?: 0.0)); put("availability", availability)
        if (offer.isNotBlank()) put("discountPct", offer.toDouble()) }) }) { Text("Save", color = Selo.Red) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/* ---------------------------------------------------------------- settings */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SellerSettings(onLogout: () -> Unit) {
    val app = LocalAppState.current
    val u = app.session.value
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(u?.storeName ?: "") }
    var province by rememberSaveable { mutableStateOf("Lusaka") }
    var district by rememberSaveable { mutableStateOf(u?.area ?: "") }
    var tags by remember { mutableStateOf(setOf("Fresh produce")) }
    var momo by rememberSaveable { mutableStateOf(true) }
    var shop by remember { mutableStateOf<selo.Nachipa01.data.model.Place?>(null) }
    var payout by remember { mutableStateOf(false) }
    var nLikes by rememberSaveable { mutableStateOf(true) }
    var nEnq by rememberSaveable { mutableStateOf(true) }
    var nAlgo by rememberSaveable { mutableStateOf(true) }
    var saved by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf(false) }
    // photos are uploaded and saved to the business profile straight away
    fun upload(uri: android.net.Uri, field: String, done: () -> Unit) = scope.launch {
        runCatching { val path = app.backend.uploadMedia(listOf(uri)) {}.first(); app.backend.saveSellerSettings(mapOf(field to path)) }
            .onSuccess { done(); saved = if (field == "photoUrl") "Profile photo saved." else "Cover image saved." }.onFailure { saved = it.message }
    }
    val pickPhoto = selo.Nachipa01.ui.screens.auth.rememberPhotoCapture { upload(it, "photoUrl") { photo = true } }
    val pickCover = selo.Nachipa01.ui.screens.auth.rememberPhotoCapture { upload(it, "coverUrl") { cover = true } }
    H("Business profile", 16)
    SeloCard {
        Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Selo.RedDeep, Selo.Red))).clickable { pickCover() }, contentAlignment = Alignment.Center) {
            Text(if (cover) "Cover image set ✓ · tap to change" else "Tap to add a cover image", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (photo) Selo.Green else Selo.RedTint).clickable { pickPhoto() }, contentAlignment = Alignment.Center) {
                Icon(if (photo) Icons.Outlined.Check else Icons.Outlined.PhotoCamera, "Profile photo", tint = if (photo) Color.White else Selo.Red) }
            Spacer(Modifier.width(10.dp)); SeloField("Business name", name, { name = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SeloField("Province", province, { province = it }, Modifier.weight(1f)); SeloField("District", district, { district = it }, Modifier.weight(1f)) }
        Text("Business categories (up to 5)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Taxonomy.CATEGORIES.forEach { c -> SeloChip(c, c in tags) { tags = if (c in tags) tags - c else if (tags.size < 5) tags + c else tags } }
        }
    }
    H("Payments", 16)
    SeloCard {
        SwitchRow("Mobile Money", momo, { momo = it }, sub = "Airtel Money, MTN MoMo, Zamtel Kwacha via Lipila")
        Row(Modifier.fillMaxWidth().clickable { payout = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Selo.Red); Text("  Payout wallet", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("Change", color = Selo.Red, fontWeight = FontWeight.Bold)
        }
    }
    H("Shop location", 16)
    SeloCard {
        Sub("Riders collect here, and buyers' delivery fees are worked out from this point.")
        selo.Nachipa01.ui.components.AddressField("Pickup point", shop, { p -> shop = p
            if (p != null && p.inServiceArea) scope.launch { runCatching { app.backend.saveMyLocation(p) }
                .onSuccess { saved = "Shop location saved." }.onFailure { saved = it.message } } }, showSaved = false, icon = Icons.Outlined.Storefront)
    }
    H("Notifications", 16)
    SeloCard {
        SwitchRow("New likes", nLikes, { nLikes = it }); SwitchRow("New enquiries", nEnq, { nEnq = it })
        SwitchRow("Algorithm performance alerts", nAlgo, { nAlgo = it }, sub = "e.g. a listing is trending or getting skipped")
    }
    H("Verified seller badge", 16)
    SeloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Verified, null, tint = if (u?.status == selo.Nachipa01.data.model.AccountStatus.ACTIVE) Color(0xFF29B6F6) else Selo.Mute)
            Text(if (u?.status == selo.Nachipa01.data.model.AccountStatus.ACTIVE) "  Verified with NRC and TPIN" else "  Upload your NRC and TPIN to get the badge", Modifier.weight(1f))
        }
        Sub("Verified sellers show a blue tick in the feed and rank higher in search.")
    }
    saved?.let { NoteBox(it, Tone.OK) }
    PrimaryButton("Save settings") { scope.launch {
        runCatching { app.backend.saveSellerSettings(mapOf("storeName" to name, "province" to province, "district" to district, "businessTags" to tags.toList(),
            "paymentPrefs" to mapOf("mobileMoney" to momo), "notificationPrefs" to mapOf("likes" to nLikes, "enquiries" to nEnq, "algorithm" to nAlgo))) }
            .onSuccess { saved = "Settings saved." }.onFailure { saved = it.message } } }
    H("Account & support", 16)
    selo.Nachipa01.ui.screens.account.AccountSettingsCard(open = { app.navRequest.value = "account?r=$it" })
    OutlineButton("Log out") { scope.launch { app.backend.logout(); onLogout() } }
    if (payout) selo.Nachipa01.ui.screens.account.PayoutWalletDialog { payout = false }
}

/* ---------------------------------------------------------------- orders */

@Composable
private fun SellerOrderList(orders: List<Order>, scope: CoroutineScope, app: AppState) {
    Text("New orders", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    if (orders.isEmpty()) NoteBox("No new orders. Share a listing to WhatsApp to bring in buyers.")
    orders.forEach { o ->
        var packed by remember(o.id) { mutableStateOf(false) }
        SeloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.items.joinToString { "${it.title} × ${it.qty}" }, fontWeight = FontWeight.Bold)
                    Text("${o.id} · ${o.dropoff}", color = Selo.Mute, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp)); StatusChip("Escrow held", Tone.OK)
                }
                if (packed) StatusChip("Packed", Tone.INFO)
                else Button({ scope.launch { app.backend.confirmPack(o.id); packed = true } }, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Confirm & pack", fontSize = 12.sp) }
            }
        }
    }
}
