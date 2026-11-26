package selo.Nachipa01.ui.screens.seller

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.BoostReach
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.data.model.Promotion
import selo.Nachipa01.data.model.Targeting
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Operator
import selo.Nachipa01.domain.Taxonomy
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.payments.*
import selo.Nachipa01.ui.screens.discover.compact
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DURATIONS = listOf(1, 3, 7, 14)

/**
 * Boost and Feed Story (PRD v6.2): paid reach, a platform revenue line.
 * The seller picks who should see the listing, sees the estimated audience and the price, then pays with mobile money.
 * Nothing is shown to buyers until the payment is confirmed.
 */
@Composable
fun PromoteScreen(listingId: String, initialType: String, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<Listing?>(null) }
    LaunchedEffect(listingId) { listing = runCatching { app.backend.listing(listingId) }.getOrNull() }
    var type by remember { mutableStateOf(if (initialType == "story") "story" else "boost") }
    var reach by remember { mutableStateOf(BoostReach.LOCAL) }
    var days by remember { mutableIntStateOf(3) }
    var ages by remember { mutableStateOf(setOf<String>()) }
    var provinces by remember { mutableStateOf(setOf<String>()) }
    var districts by remember { mutableStateOf(setOf<String>()) }
    var interests by remember { mutableStateOf(setOf<String>()) }
    var searchIntent by remember { mutableStateOf(true) }
    var followers by remember { mutableStateOf(true) }
    var audience by remember { mutableStateOf<Int?>(null) }
    var paying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = rememberPaymentChoice(app.session.value?.msisdn)

    // sensible defaults from the listing: its category and where it is
    LaunchedEffect(listing?.id) {
        val l = listing ?: return@LaunchedEffect
        if (interests.isEmpty() && l.category.isNotBlank()) interests = setOf(Taxonomy.fromLabel(l.category) ?: l.category)
        val prov = l.province.ifBlank { "Lusaka" }
        if (provinces.isEmpty()) provinces = setOf(prov)
        if (districts.isEmpty()) districts = setOfNotNull(l.district.takeIf { it.isNotBlank() } ?: Taxonomy.DISTRICTS[prov]?.firstOrNull())
    }
    val targeting = Targeting(ages.toList(), if (reach == BoostReach.NATIONAL) emptyList() else provinces.toList(),
        if (reach == BoostReach.LOCAL) districts.toList() else emptyList(), interests.toList(), searchIntent, followers)
    LaunchedEffect(targeting, reach) { delay(250); audience = runCatching { app.backend.estimateAudience(targeting, reach) }.getOrNull() }
    val price = Fees.promoPriceNgwee(type, reach, days, cfg)

    Column(Modifier.fillMaxSize()) {
        SeloHeader(if (type == "story") "Feed Story" else "Boost listing", listing?.title ?: "", onBack = onBack,
            trailing = { Icon(if (type == "story") Icons.Outlined.AutoStories else Icons.Outlined.RocketLaunch, null, tint = Color.White) })
        FormColumn {
            // what are we buying
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProductTile("boost", Icons.Outlined.RocketLaunch, "Boost", "Placed in buyers' feeds and search as Sponsored",
                    "from ${Fees.formatK(Fees.kwachaToNgwee(cfg.boostLocalPerDay))}/day", type == "boost", Modifier.weight(1f)) { type = "boost" }
                ProductTile("story", Icons.Outlined.AutoStories, "Feed Story", "Top of the Home screen for 24 hours",
                    "${Fees.formatK(Fees.kwachaToNgwee(cfg.storyPrice24h))} / 24 h", type == "story", Modifier.weight(1f)) { type = "story" }
            }

            SectionLabel("Reach")
            BoostReach.entries.forEach { r ->
                val perDay = when (r) { BoostReach.LOCAL -> cfg.boostLocalPerDay; BoostReach.REGIONAL -> cfg.boostRegionalPerDay; BoostReach.NATIONAL -> cfg.boostNationalPerDay }
                RadioCard(reach == r, r.label + if (type == "boost") " · ${Fees.formatK(Fees.kwachaToNgwee(perDay))}/day" else "", r.blurb,
                    icon = when (r) { BoostReach.LOCAL -> Icons.Outlined.NearMe; BoostReach.REGIONAL -> Icons.Outlined.Map; BoostReach.NATIONAL -> Icons.Outlined.Public }) { reach = r }
            }
            if (type == "boost") {
                SectionLabel("Duration")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATIONS.forEach { d -> Box(Modifier.weight(1f)) { SeloChip(if (d == 1) "1 day" else "$d days", days == d) { days = d } } }
                }
            }

            SectionLabel("Who should see it")
            SeloCard {
                when (reach) {
                    BoostReach.LOCAL -> {
                        Text("Province", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        ChipGroup(Taxonomy.PROVINCES, provinces.take(1).toSet()) { p ->
                            provinces = setOf(p); districts = setOfNotNull(Taxonomy.DISTRICTS[p]?.firstOrNull()) }
                        Spacer(Modifier.height(6.dp))
                        Text("Districts", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        ChipGroup(Taxonomy.DISTRICTS[provinces.firstOrNull() ?: "Lusaka"].orEmpty(), districts) { d ->
                            districts = if (d in districts) (districts - d).ifEmpty { districts } else districts + d }
                    }
                    BoostReach.REGIONAL -> {
                        Text("Provinces", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        ChipGroup(Taxonomy.PROVINCES, provinces) { p -> provinces = if (p in provinces) (provinces - p).ifEmpty { provinces } else provinces + p }
                    }
                    BoostReach.NATIONAL -> Sub("Shown to matching buyers in all 10 provinces.")
                }
                Spacer(Modifier.height(10.dp))
                Text("Age", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                ChipGroup(listOf("All ages") + Taxonomy.AGE_BANDS, if (ages.isEmpty()) setOf("All ages") else ages) { a ->
                    ages = when { a == "All ages" -> emptySet(); a in ages -> ages - a; else -> ages + a } }
                Spacer(Modifier.height(10.dp))
                Text("Interests", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Sub("Buyers who watch, like and buy in these categories.")
                ChipGroup(Taxonomy.CATEGORIES.filter { it != "Other" }, interests) { c -> interests = if (c in interests) interests - c else interests + c }
                Spacer(Modifier.height(6.dp))
                SwitchRow("People looking for this product", searchIntent, { searchIntent = it }, "Buyers who searched for similar items in the last 7 days")
                SwitchRow("Your followers", followers, { followers = it }, "Always included when on")
            }

            // estimate
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(Selo.Red, Selo.RedDeep))).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, tint = Color.White, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Estimated audience", color = Color.White.copy(.85f), fontSize = 12.sp)
                    Text(audience?.let { "${compact(it)} buyers" } ?: "Calculating…", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (type == "story") "One story view each, for 24 hours" else "Up to ${cfg.boostFrequencyCap} times each per day · $days day${if (days == 1) "" else "s"}",
                        color = Color.White.copy(.85f), fontSize = 11.5.sp)
                }
            }

            PaymentMethodPicker(choice)
            AmountBreakdown(listOf(AmountLine(if (type == "story") "Feed Story · 24 hours · ${reach.label}"
                else "Boost · ${reach.label} · $days day${if (days == 1) "" else "s"}", price)), "Total")
            ErrorText(error)
            PrimaryButton("Pay ${Fees.formatK(price)}" + (choice.operator?.let { " with ${it.label}" } ?: ""), loading = paying,
                enabled = choice.msisdn != null && listing != null && (reach != BoostReach.LOCAL || districts.isNotEmpty())) {
                paying = true; error = null
                scope.launch {
                    runCatching { app.backend.createPromotion(type, listingId, reach, if (type == "story") 1 else days, targeting, choice.msisdn!!) }
                        .onSuccess(onStarted).onFailure { error = it.message ?: "Couldn't start the payment. Try again." }
                    paying = false
                }
            }
            Sub("Your ${if (type == "story") "story" else "boost"} starts as soon as ${choice.operator?.label ?: "mobile money"} confirms the payment. " +
                "Boosted listings are labelled Sponsored and still follow the content policy.")
        }
    }
}

@Composable
private fun ProductTile(key: String, icon: ImageVector, title: String, sub: String, price: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Selo.RedTint else Color.White)
        .border(1.6.dp, if (selected) Selo.Red else Selo.Line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(36.dp).background(if (key == "story") Selo.Purple else Selo.Red, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        Text(sub, color = Selo.Mute, fontSize = 11.5.sp, lineHeight = 15.sp, minLines = 2)
        Text(price, color = Selo.Red, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

/** Waits for the mobile money confirmation; on failure the seller can pay with another number. */
@Composable
fun PromotionStatusScreen(promoId: String, onDone: () -> Unit) {
    val app = LocalAppState.current
    val promo by remember { app.backend.promotion(promoId) }.collectAsState(null)
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = rememberPaymentChoice(app.session.value?.msisdn, startWithOther = true)
    val p = promo
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        val active = p?.status == "ACTIVE"; val failed = p?.status == "PAYMENT_FAILED"
        Box(Modifier.size(96.dp).background(when { active -> Selo.Green; failed -> Selo.Red; else -> Selo.Amber }, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            when {
                active -> Icon(if (p?.type == "story") Icons.Outlined.AutoStories else Icons.Outlined.RocketLaunch, null, tint = Color.White, modifier = Modifier.size(48.dp))
                failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(48.dp))
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
        val kind = if (p?.type == "story") "Feed Story" else "Boost"
        Text(when { active -> "Your $kind is live"; failed -> "Payment didn't go through"; else -> "Approve the prompt on your phone" },
            fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        if (p != null && !active) p.walletMsisdn?.let { w -> Operator.detect(w)?.let { op ->
            Row(verticalAlignment = Alignment.CenterVertically) { OperatorMark(op, 30.dp); Spacer(Modifier.width(8.dp)); Text("${op.label} · ${Validation.maskMsisdn(w)}", fontWeight = FontWeight.Bold) }
        } }
        Sub(when {
            p == null -> "Loading…"
            active -> "${p.listingTitle} is now shown to about ${compact(p.estimatedReach)} matching buyers until ${fmtDate(p.endsAt)}."
            failed -> p.failureReason ?: "The payment was declined."
            else -> "Enter your mobile money PIN. Your $kind starts the moment the payment is confirmed."
        })
        if (p != null) {
            Text(Fees.formatK(p.priceNgwee), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = if (active) Selo.Green else Selo.Ink)
            if (failed) {
                PaymentMethodPicker(choice, "Try again with", exclude = p.walletMsisdn)
                ErrorText(error)
                PrimaryButton("Pay ${Fees.formatK(p.priceNgwee)}" + (choice.operator?.let { " with ${it.label}" } ?: ""), loading = retrying, whyDisabled = "Choose or enter an Airtel, MTN or Zamtel number to pay with.", enabled = choice.msisdn != null) {
                    retrying = true; error = null
                    scope.launch { runCatching { app.backend.retryPromotionPayment(p.id, choice.msisdn!!) }.onFailure { error = it.message }; retrying = false }
                }
            } else SeloCard {
                KvRow("Type", kind); KvRow("Reach", p.reach.lowercase().replaceFirstChar { it.uppercase() })
                KvRow("Duration", if (p.type == "story") "24 hours" else "${p.days} day${if (p.days == 1) "" else "s"}")
                Text("Shown to", color = Selo.Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                Text(targetingSummary(p.targeting), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                HorizontalDivider(color = Selo.Line)
                KvRow("Reference", "SELO-${if (p.type == "story") "STY" else "BST"}-${p.id.take(14)}", mono = true)
            }
        }
        PrimaryButton(if (p?.status == "ACTIVE") "Done" else "Back to my listings", color = if (p?.status == "ACTIVE") Selo.Green else Selo.Red, onClick = onDone)
    }
}

fun targetingSummary(t: Targeting): String = listOfNotNull(
    t.districts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: t.provinces.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "All Zambia",
    t.ageBands.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "all ages",
    t.interests.takeIf { it.isNotEmpty() }?.joinToString(", "),
    "searchers".takeIf { t.searchIntent }, "followers".takeIf { t.followers },
).joinToString(" · ")

private fun fmtDate(ms: Long) = if (ms <= 0) "—" else SimpleDateFormat("d MMM, HH:mm", Locale.UK).format(Date(ms))

/** Seller home: running and recent paid promotions with their results. */
@Composable
fun MyPromotions(onOpen: (Promotion) -> Unit) {
    val app = LocalAppState.current
    val promos by app.backend.myPromotions().collectAsState(emptyList())
    if (promos.isEmpty()) return
    SectionLabel("My promotions")
    promos.take(4).forEach { p ->
        SeloCard(Modifier.clickable { onOpen(p) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(if (p.type == "story") Selo.Purple else Selo.Red, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (p.type == "story") Icons.Outlined.AutoStories else Icons.Outlined.RocketLaunch, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.listingTitle, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${if (p.type == "story") "Feed Story" else "Boost · ${p.reach.lowercase()}"} · ${Fees.formatK(p.priceNgwee)}", color = Selo.Mute, fontSize = 12.sp)
                }
                StatusChip(when (p.status) { "ACTIVE" -> "Live"; "PAYMENT_FAILED" -> "Payment failed"; "PENDING_PAYMENT" -> "Awaiting payment"; else -> "Ended" },
                    when (p.status) { "ACTIVE" -> Tone.OK; "PAYMENT_FAILED" -> Tone.BRAND; "PENDING_PAYMENT" -> Tone.WARN; else -> Tone.GREY })
            }
            if (p.status == "ACTIVE" || p.status == "ENDED") Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Reached" to p.impressions, "Opened" to p.clicks, "Likes" to p.likes, "Enquiries" to p.enquiries).forEach { (k, v) ->
                    Column(Modifier.weight(1f).background(Selo.Surface, RoundedCornerShape(8.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(compact(v), fontWeight = FontWeight.ExtraBold); Text(k, color = Selo.Mute, fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}
