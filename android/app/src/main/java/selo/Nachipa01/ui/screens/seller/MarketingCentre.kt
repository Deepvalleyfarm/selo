package selo.Nachipa01.ui.screens.seller

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Campaign
import selo.Nachipa01.data.model.CampaignDraft
import selo.Nachipa01.data.model.CampaignType
import selo.Nachipa01.data.model.SellerListingCard
import selo.Nachipa01.data.model.campaignProblem
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun icon(t: CampaignType): ImageVector = when (t) {
    CampaignType.DISCOUNT -> Icons.Outlined.Percent; CampaignType.BOGO -> Icons.Outlined.AddShoppingCart
    CampaignType.FLASH -> Icons.Outlined.Timer; CampaignType.BUNDLE -> Icons.Outlined.Inventory2
}
private fun whenText(ms: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.UK).format(Date(ms))
fun timeLeft(endsAt: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((endsAt - now) / 60_000).coerceAtLeast(0)
    return when { m < 60 -> "${m}m left"; m < 48 * 60 -> "${m / 60}h ${m % 60}m left"; else -> "${m / 1440} days left" }
}

/**
 * Marketing Centre: discounts, Buy One Get One Free, limited-time offers and bundles. The server applies the deal at
 * checkout (buyers pay exactly what the card shows) and puts a deal badge on the products.
 */
@Composable
fun MarketingCentreScreen(onBack: () -> Unit, onAdGenerator: () -> Unit = {}) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Campaign>?>(null) }
    var products by remember { mutableStateOf<List<SellerListingCard>>(emptyList()) }
    var creating by remember { mutableStateOf<CampaignType?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        runCatching { app.backend.campaigns() }.onSuccess { list = it }.onFailure { list = emptyList(); app.fail(it.message ?: "Couldn't load campaigns.") }
        runCatching { app.backend.sellerDashboard() }.onSuccess { d -> products = d.listings.filter { it.status == "live" } }
    }
    creating?.let { t -> CampaignEditor(t, products, onClose = { creating = null }, onSaved = { creating = null; reload++ }); return }

    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader("Marketing Centre", "Deals that bring buyers back", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val live = list.orEmpty().filter { it.live }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("${live.size}", "Live deals", Modifier.weight(1f))
                StatCard("${list.orEmpty().sumOf { it.unitsSold }}", "Items sold on deals", Modifier.weight(1f), valueColor = Selo.GreenDark)
                StatCard(Fees.formatK(list.orEmpty().sumOf { it.revenueNgwee }), "Deal sales", Modifier.weight(1.2f), valueColor = Selo.Ink)
            }
            SectionLabel("Create a deal")
            CampaignType.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Column(Modifier.weight(1f).heightIn(min = 112.dp).background(Color.White, RoundedCornerShape(14.dp)).clickable {
                            if (products.isEmpty()) app.fail("Publish a product first — deals apply to your live products.") else creating = t }.padding(12.dp)) {
                            Icon(icon(t), null, tint = Selo.Red)
                            Text(t.label, fontWeight = FontWeight.ExtraBold, color = Selo.Red, modifier = Modifier.padding(top = 6.dp))
                            Text(t.blurb, color = Selo.Mute, fontSize = 12.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Selo.RedTint, RoundedCornerShape(14.dp)).clickable(onClick = onAdGenerator).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Red)
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text("AI Ad Generator", fontWeight = FontWeight.ExtraBold); Sub("Turn a product photo into a WhatsApp Status, Facebook and TikTok ad") }
                Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Red)
            }
            SectionLabel("Your deals")
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
                list!!.isEmpty() -> NoteBox("No deals yet. Sellers with a weekly deal get more repeat buyers — start with Buy 1 Get 1 Free on a fast mover.", Tone.INFO)
                else -> list!!.forEach { c -> CampaignRow(c, products) { status ->
                    scope.launch { runCatching { app.backend.setCampaignStatus(c.id, status) }.onSuccess { app.ok(when (status) { "PAUSED" -> "Deal paused"; "ENDED" -> "Deal ended"; else -> "Deal is live" }); reload++ }
                        .onFailure { app.fail(it.message ?: "Couldn't update the deal.") } } } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CampaignRow(c: Campaign, products: List<SellerListingCard>, onStatus: (String) -> Unit) {
    SeloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon(c.type), null, tint = Selo.Red)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(c.title, fontWeight = FontWeight.ExtraBold)
                Text(c.label.ifBlank { c.type.label }, color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            StatusChip(when { c.status == "ENDED" -> "Ended"; c.status == "PAUSED" -> "Paused"; c.live -> "Live"; else -> "Scheduled" },
                when { c.live -> Tone.OK; c.status == "PAUSED" -> Tone.WARN; else -> Tone.GREY })
        }
        val names = c.listingIds.mapNotNull { id -> products.firstOrNull { it.id == id }?.title }
        if (names.isNotEmpty()) Sub(names.joinToString(" · "))
        Sub(buildString {
            append("From ${whenText(c.startsAt)}")
            c.endsAt?.let { append(" to ${whenText(it)}"); if (c.live) append(" · ${timeLeft(it)}") }
            c.maxUnits?.let { append(" · ${c.unitsSold}/$it units") }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KvMini("Sold", "${c.unitsSold}"); KvMini("Orders", "${c.orders}"); KvMini("Sales", Fees.formatK(c.revenueNgwee))
        }
        if (c.status != "ENDED") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.status == "PAUSED") TextButton({ onStatus("ACTIVE") }) { Text("Resume", color = Selo.GreenDark, fontWeight = FontWeight.Bold) }
            else TextButton({ onStatus("PAUSED") }) { Text("Pause", color = Selo.AmberDark, fontWeight = FontWeight.Bold) }
            TextButton({ onStatus("ENDED") }) { Text("End now", color = Selo.Red, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable private fun KvMini(k: String, v: String) = Column { Text(v, fontWeight = FontWeight.ExtraBold); Text(k, color = Selo.Mute, fontSize = 11.sp) }

/** Create a deal: products, the offer, and when it runs. */
@Composable
private fun CampaignEditor(type: CampaignType, products: List<SellerListingCard>, onClose: () -> Unit, onSaved: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(when (type) { CampaignType.BOGO -> "Buy 1 Get 1 Free"; CampaignType.FLASH -> "Today only"; CampaignType.BUNDLE -> "Value bundle"; else -> "Special offer" }) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var pct by remember { mutableStateOf(if (type == CampaignType.FLASH) "20" else "10") }
    var bundleK by remember { mutableStateOf("") }
    var startNow by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf(if (type == CampaignType.FLASH) "6 hours" else "7 days") }
    var maxUnits by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val durations = if (type == CampaignType.FLASH) listOf("2 hours", "6 hours", "24 hours", "3 days", "7 days") else listOf("24 hours", "3 days", "7 days", "30 days", "No end")
    fun durMs(d: String): Long? = when (d) { "2 hours" -> 2 * 3_600_000L; "6 hours" -> 6 * 3_600_000L; "24 hours" -> 86_400_000L; "3 days" -> 3 * 86_400_000L
        "7 days" -> 7 * 86_400_000L; "30 days" -> 30 * 86_400_000L; else -> null }
    val sum = products.filter { it.id in picked }.sumOf { it.priceNgwee }
    val start = if (startNow) System.currentTimeMillis() else tomorrow8am()
    val draft = CampaignDraft(type = type, title = title, listingIds = picked.toList(),
        discountPct = pct.toIntOrNull().takeIf { type == CampaignType.DISCOUNT || type == CampaignType.FLASH },
        bundlePriceNgwee = bundleK.toDoubleOrNull()?.let { Fees.kwachaToNgwee(it) }.takeIf { type == CampaignType.BUNDLE },
        startsAt = start, endsAt = durMs(duration)?.let { start + it }, maxUnits = maxUnits.toIntOrNull()?.takeIf { it > 0 })
    val problem = campaignProblem(draft) ?: if (type == CampaignType.BUNDLE && draft.bundlePriceNgwee != null && draft.bundlePriceNgwee >= sum && sum > 0)
        "The bundle price must be below ${Fees.formatK(sum)} (the products bought separately)." else null

    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader(type.label, type.blurb, onBack = onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SeloField("Deal name (buyers see this)", title, { title = it.take(60) })
            H(if (type == CampaignType.BUNDLE) "Products in the bundle (2–5)" else "Products", 14)
            products.forEach { p ->
                val on = p.id in picked
                Row(Modifier.fillMaxWidth().background(if (on) Selo.RedTint else Color.White, RoundedCornerShape(12.dp))
                    .border(1.2.dp, if (on) Selo.Red else Selo.Line, RoundedCornerShape(12.dp)).clickable { picked = if (on) picked - p.id else picked + p.id }
                    .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { picked = if (on) picked - p.id else picked + p.id }, colors = CheckboxDefaults.colors(checkedColor = Selo.Red))
                    Text(p.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(Fees.formatK(p.priceNgwee), fontWeight = FontWeight.Bold)
                }
            }
            when (type) {
                CampaignType.DISCOUNT, CampaignType.FLASH -> {
                    H("Discount", 14)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("10", "15", "20", "30", "50").forEach { v -> SeloChip("$v%", pct == v) { pct = v } } }
                    SeloField("Or type a % (1–90)", pct, { pct = it.filter(Char::isDigit).take(2) }, keyboard = KeyboardType.Number)
                    pct.toIntOrNull()?.let { d -> products.filter { it.id in picked }.take(3).forEach { p ->
                        Sub("${p.title}: ${Fees.formatK(p.priceNgwee)} → ${Fees.formatK(p.priceNgwee * (100 - d) / 100)}") } }
                }
                CampaignType.BOGO -> NoteBox("Buyers who add 2 get 1 free, 4 get 2 free, and so on. It doesn't stack with other discounts on the same product.", Tone.INFO)
                CampaignType.BUNDLE -> {
                    SeloField("Bundle price (K)", bundleK, { bundleK = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, keyboard = KeyboardType.Decimal,
                        placeholder = if (sum > 0) "Bought separately: ${Fees.formatK(sum)}" else null)
                    if (sum > 0) bundleK.toDoubleOrNull()?.let { k -> val save = sum - Fees.kwachaToNgwee(k); if (save > 0) Sub("Buyers save ${Fees.formatK(save)} per bundle.") }
                }
            }
            H("When", 14)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SeloChip("Start now", startNow) { startNow = true }; SeloChip("Tomorrow 08:00", !startNow) { startNow = false } }
            Text("Runs for", fontSize = 12.sp, color = Selo.Mute, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { durations.forEach { d -> SeloChip(d, duration == d) { duration = d } } }
            if (type == CampaignType.FLASH || type == CampaignType.BOGO) SeloField("Limit: stop after this many items (optional)", maxUnits, { maxUnits = it.filter(Char::isDigit).take(5) }, keyboard = KeyboardType.Number)
            draft.endsAt?.let { Sub("Ends ${whenText(it)}. Buyers see a countdown on limited-time offers.") }
        }
        Column(Modifier.background(Color.White).padding(14.dp)) {
            PrimaryButton(if (startNow) "Launch deal" else "Schedule deal", enabled = problem == null && !saving, loading = saving, whyDisabled = problem) {
                saving = true
                scope.launch { runCatching { app.backend.saveCampaign(draft) }.onSuccess { app.ok(if (startNow) "Deal is live — buyers see it on your products now" else "Deal scheduled"); onSaved() }
                    .onFailure { app.fail(it.message ?: "Couldn't save the deal.") }; saving = false }
            }
        }
    }
}

private fun tomorrow8am(): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
