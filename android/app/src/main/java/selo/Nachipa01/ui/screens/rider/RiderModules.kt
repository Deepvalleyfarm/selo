package selo.Nachipa01.ui.screens.rider

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAY = 86_400_000L

/* =================================================================== JOBS (6.6) */

@Composable
fun RiderJobs(active: List<Order>, online: Boolean, openDelivery: (String) -> Unit, openActive: () -> Unit) {
    val app = LocalAppState.current
    val history by app.backend.riderHistory().collectAsState(emptyList())
    val profile by app.backend.riderProfile().collectAsState(null)
    var sub by remember { mutableIntStateOf(if (active.isEmpty()) 1 else 0) }
    var range by remember { mutableIntStateOf(7) }
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Jobs")
        TabRow(sub, containerColor = Color.White, contentColor = Selo.Red) {
            listOf("Incoming", "History").forEachIndexed { i, t -> Tab(sub == i, { sub = i }, text = { Text(t, fontWeight = FontWeight.Bold, color = if (sub == i) Selo.Red else Inactive) }) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sub == 0) {
                if (active.isEmpty()) NoteBox(if (online) "You're online. New jobs pop up full-screen the moment they arrive." else "You're offline. Go online on Home to receive jobs.", if (online) Tone.OK else Tone.GREY)
                active.forEach { o ->
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).clickable(onClick = openActive).padding(14.dp)) {
                        Row { Text(o.id, color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            StatusChip(if (o.state == OrderState.RIDER_ASSIGNED) "To collect" else "On the way", if (o.state == OrderState.RIDER_ASSIGNED) Tone.INFO else Tone.FUND) }
                        Text("${o.pickup.substringBefore(",")} → ${o.dropoff.substringBefore(",")}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(zmw(o.deliveryNgwee), color = Selo.Green, fontWeight = FontWeight.Bold)
                    }
                }
                if (active.size > 1) PrimaryButton("Open combined route (${active.size} orders)", onClick = openActive)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (d, l) -> SeloChip(l, range == d) { range = d } } }
                val since = if (range == 1) java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
                    else System.currentTimeMillis() - range * DAY
                val rows = history.filter { it.at >= since }
                Row(Modifier.fillMaxWidth().background(Selo.Ink, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    listOf("${rows.size}" to "deliveries", km(rows.sumOf { it.km }) to "total distance",
                        (if (rows.isEmpty()) "—" else zmw(rows.sumOf { it.netNgwee } / rows.size)) to "avg per delivery").forEach { (v, l) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(v, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp); Text(l, color = Color.White.copy(.7f), fontSize = 11.sp) }
                    }
                }
                Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                    if (rows.isEmpty()) Text("No deliveries in this period.", color = Selo.Mute, modifier = Modifier.padding(14.dp))
                    rows.forEachIndexed { i, d -> if (i > 0) HorizontalDivider(color = Selo.Line); DeliveryRow(d) { openDelivery(d.orderId) } }
                }
            }
        }
        HeroBar(profile?.totalDeliveries ?: 0)
    }
}

@Composable
fun HeroBar(total: Int) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (total >= RiderTiers.HERO) {
            Icon(Icons.Filled.Star, null, tint = Selo.Amber); Text("  Hero tier ✓", color = Selo.Amber, fontWeight = FontWeight.ExtraBold)
        } else Column(Modifier.fillMaxWidth()) {
            Text("Hero tier: $total/${RiderTiers.HERO} deliveries", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator({ total / RiderTiers.HERO.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Selo.Red, trackColor = Selo.Line)
        }
    }
}

@Composable
fun DeliveryDetail(orderId: String, back: () -> Unit) {
    val app = LocalAppState.current
    val history by app.backend.riderHistory().collectAsState(emptyList())
    val d = history.firstOrNull { it.orderId == orderId }
    var pod by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(d?.podUrl) { pod = d?.podUrl?.let { if (it.startsWith("pod/")) app.backend.mediaUrl(it) else it } }
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Delivery $orderId", onBack = back)
        if (d == null) { Text("Loading…", Modifier.padding(16.dp)); return }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SeloCard {
                Text(d.orderId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK).format(Date(d.at)), color = Selo.Mute, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row { Icon(Icons.Outlined.Place, null, tint = Selo.Red, modifier = Modifier.size(18.dp)); Text(" ${d.pickupAddress}", fontSize = 14.sp) }
                Row { Icon(Icons.Outlined.Flag, null, tint = Selo.Ink, modifier = Modifier.size(18.dp)); Text(" ${d.dropAddress}", fontSize = 14.sp) }
                Text(d.item + " · " + km(d.km), color = Selo.Mute, fontSize = 13.sp)
            }
            SeloCard {
                Text("Fee breakdown", fontWeight = FontWeight.ExtraBold)
                KvRow("Gross delivery fee", zmw(d.grossNgwee))
                KvRow("Platform fee (8%)", "− " + zmw(d.platformNgwee))
                KvRow("Social Fund (5%)", "− " + zmw(d.fundNgwee), valueColor = Selo.Purple)
                if (d.bonusNgwee > 0) KvRow("Hero bonus", "+ " + zmw(d.bonusNgwee), valueColor = Selo.Amber)
                HorizontalDivider(color = Selo.Line)
                KvRow("Net paid to you", zmw(d.netNgwee), strong = true, valueColor = Selo.Green)
            }
            SeloCard {
                Text("Proof of delivery", fontWeight = FontWeight.ExtraBold)
                Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Selo.Surface), contentAlignment = Alignment.Center) {
                    if (pod != null) AsyncImage(pod, "Proof of delivery photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.PhotoCamera, null, tint = Selo.Grey, modifier = Modifier.size(40.dp))
                        Text("Delivered with the buyer's code", color = Selo.Mute, fontSize = 12.sp) }
                }
            }
        }
    }
}

/* =================================================================== EARNINGS (6.7) */

@Composable
fun RiderEarnings(profile: RiderProfile?, stats: RiderStats, open: (String) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val cfg by app.config.collectAsState()
    val history by app.backend.riderHistory().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var withdrawing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val target = profile?.weeklyTargetNgwee ?: 100_000
    val hero = RiderTiers.isHero(stats.tier)
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Earnings", trailing = { profile?.payoutWallet?.takeIf { it.isNotBlank() }?.let { w ->
            Row(Modifier.background(Selo.Surface, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                selo.Nachipa01.domain.Operator.detect(w)?.let { selo.Nachipa01.ui.payments.OperatorMark(it, 20.dp) }
                Text(" ${walletBadge(w)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Today", color = Selo.Mute)
                Text(zmw(stats.todayNetNgwee), color = Selo.Green, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("${stats.todayDeliveries} deliveries · ${km(stats.todayKm)} ridden", color = Selo.Mute, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat(zmw(stats.todayNetNgwee), "Today's earnings", Modifier.weight(1f), Selo.Green)
                MiniStat(zmw(stats.weekNetNgwee), "This week", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat(zmw(stats.bonusWeekNgwee), "Tier bonus earned", Modifier.weight(1f), Selo.Amber, if (hero) "+${cfg.heroBonusPct.toInt()}% Hero bonus" else "Unlocks at Hero tier")
                MiniStat(zmw(stats.fundTodayNgwee), "Social Fund added today", Modifier.weight(1f), Selo.Purple, "${cfg.socialFundPct.toInt()}% of each delivery fee")
            }
            SeloCard {
                Row { Text("Weekly target", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("${(stats.weekNetNgwee * 100 / target.coerceAtLeast(1)).coerceAtMost(999)}%", fontWeight = FontWeight.ExtraBold) }
                LinearProgressIndicator({ (stats.weekNetNgwee / target.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 6.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = Selo.Red, trackColor = Selo.Line)
                Text("${zmw(stats.weekNetNgwee)} / ${zmw(target)} target", color = Selo.Mute, fontSize = 12.sp)
                Text("Change target", color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp).clickable { open("settings") })
            }
            Row(Modifier.fillMaxWidth().background(Selo.GreenTint, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (profile?.autoSettle != false) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle, null, tint = Selo.GreenDark)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(if (profile?.autoSettle != false) "Auto-settle active — daily payout to ${profile?.payoutWallet?.let { walletBadge(it) } ?: "your wallet"} at midnight."
                        else "Auto-settle is off. Withdraw when you're ready.", color = Selo.GreenDark, fontSize = 13.sp)
                    Text(if (profile?.autoSettle != false) "Turn off" else "Turn on", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp).clickable { scope.launch { runCatching { app.backend.updateRiderProfile(mapOf("autoSettle" to (profile?.autoSettle == false))) } } })
                }
            }
            Text("Available now: ${zmw(stats.availableNgwee)}", color = Selo.Mute, fontSize = 12.sp)
            msg?.let { NoteBox(it, Tone.OK) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("Statement", Modifier.weight(1f)) {
                val csv = "order,date,pickup,dropoff,gross_zmw,platform_fee,social_fund,hero_bonus,net_zmw,km\n" + history.joinToString("\n") {
                    "${it.orderId},${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK).format(Date(it.at))},\"${it.pickupArea}\",\"${it.dropArea}\",${"%.2f".format(it.grossNgwee / 100.0)}," +
                        "${"%.2f".format(it.platformNgwee / 100.0)},${"%.2f".format(it.fundNgwee / 100.0)},${"%.2f".format(it.bonusNgwee / 100.0)},${"%.2f".format(it.netNgwee / 100.0)},${it.km}" }
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "SeloNaChipa rider statement"); putExtra(Intent.EXTRA_TEXT, csv) }, "Share statement")) }
            }
            PrimaryButton("Withdraw now", Modifier.weight(1.4f)) { withdrawing = true }
        }
    }
    if (withdrawing) WithdrawDialog(stats.availableNgwee, profile?.payoutWallet ?: "", onDone = { id -> withdrawing = false; if (id != null) msg = "Withdrawal $id sent to ${walletBadge(profile?.payoutWallet ?: "")}." })
}

@Composable
private fun WithdrawDialog(available: Long, wallet: String, onDone: (String?) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    var amount by remember { mutableStateOf("%.2f".format(Locale.UK, available / 100.0)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ngwee = ((amount.toDoubleOrNull() ?: 0.0) * 100).toLong()
    val fee = Fees.kwachaToNgwee(cfg.riderWithdrawalFee)
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text("Withdraw now") }, containerColor = Color.White, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Available ${zmw(available)}", color = Selo.Mute)
            SeloField("Amount (ZMW)", amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, keyboard = KeyboardType.Decimal)
            KvRow("Withdrawal fee", zmw(fee)); KvRow("You receive", zmw((ngwee - fee).coerceAtLeast(0)), strong = true, valueColor = Selo.Green)
            Text("Sent by Lipila to ${walletBadge(wallet)} straight away.", color = Selo.Mute, fontSize = 12.sp)
            ErrorText(error)
        }
    }, confirmButton = { Button({ busy = true; scope.launch { runCatching { app.backend.withdraw(ngwee) }.onSuccess { onDone(it) }.onFailure { error = it.message }; busy = false } },
        enabled = !busy && ngwee > 0, colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Withdraw") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } })
}

/* =================================================================== SOCIAL FUND (6.8) */

@Composable
fun RiderFund(stats: RiderStats, open: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val claims by app.backend.myClaims().collectAsState(emptyList())
    val hero = RiderTiers.isHero(stats.tier)
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Social Fund")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RingFencedBadge()
                Text(zmw(stats.socialFundNgwee), color = Selo.Purple, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Built from ${cfg.socialFundPct.toInt()}% of every delivery fee.", color = Selo.Mute, fontSize = 13.sp)
            }
            SeloCard {
                Text("Last 7 days", fontWeight = FontWeight.ExtraBold)
                FundChart(stats.weekFund)
                Text("See full history", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).clickable { open("fundHistory") })
            }
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What you can claim", color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                ClaimRow(Icons.Outlined.LocalHospital, Selo.Red, "Medical emergency", "All tiers")
                ClaimRow(Icons.Outlined.Build, Selo.Amber, "Bike repair or accident", "All tiers")
                ClaimRow(Icons.Outlined.LocalFlorist, Selo.Grey, "Funeral support", "All tiers")
                ClaimRow(Icons.Outlined.School, Blue, "School fees for children", "Hero tier only", if (!hero) "Unlock at Hero tier (300 deliveries)" else null)
            }
            Text("Tiers", fontWeight = FontWeight.ExtraBold)
            val tiers = listOf(Triple("Starter", "0–49 deliveries", "Social Fund from day one"), Triple("Rising", "50–299 deliveries", "Priority in busy zones"),
                Triple("Hero", "300+ deliveries", "+${cfg.heroBonusPct.toInt()}% bonus · school-fees claims"), Triple("Ambassador", "Hero + mentor", "Recruit and train riders"))
            val colors = listOf(Selo.Grey, Blue, Selo.Amber, Selo.Red)
            tiers.chunked(2).forEachIndexed { r, row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, (name, mile, benefit) ->
                    val current = stats.tier == name; val col = colors[r * 2 + c]
                    Column(Modifier.weight(1f).background(if (name == "Hero" && current) Selo.AmberTint else Color.White, RoundedCornerShape(12.dp))
                        .border(if (current) 2.dp else 1.dp, if (current) Selo.Red else Selo.Line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(10.dp).background(col, CircleShape))
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = col.takeIf { it != Selo.Grey } ?: Selo.Ink)
                        Text(mile, fontSize = 12.sp, color = Selo.Mute); Text(benefit, fontSize = 12.sp)
                        if (current) Text("Your current tier", fontSize = 10.5.sp, color = Selo.Red, fontWeight = FontWeight.ExtraBold)
                    }
                }
            } }
            if (claims.isNotEmpty()) {
                Text("Your claims", fontWeight = FontWeight.ExtraBold)
                claims.forEach { c -> Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(c.category, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(zmw(c.amountNgwee), color = Selo.Purple, fontSize = 12.sp) }
                    StatusChip(c.status.lowercase().replaceFirstChar { it.uppercase() }, when (c.status) { "PAID", "APPROVED" -> Tone.OK; "REJECTED" -> Tone.BRAND; else -> Tone.WARN })
                } }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("File a claim", Modifier.weight(1f)) { open("claim") }
            if (hero) PrimaryButton("Become ambassador", Modifier.weight(2f)) { open("ambassador") }
        }
    }
}

@Composable
private fun ClaimRow(icon: ImageVector, tint: Color, title: String, tiers: String, note: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (note != null) Text(note, color = Selo.Mute, fontSize = 12.sp)
        }
        Text(tiers, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (tiers.startsWith("Hero")) Selo.AmberDark else Selo.GreenDark,
            modifier = Modifier.background(if (tiers.startsWith("Hero")) Selo.AmberTint else Selo.GreenTint, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun FundChart(week: List<Long>) {
    val data = week.ifEmpty { List(7) { 0L } }.takeLast(7)
    val max = (data.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val labels = (6 downTo 0).map { SimpleDateFormat("EEE", Locale.UK).format(Date(System.currentTimeMillis() - it * DAY)) }
    Row(Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp)) {
        Column(Modifier.fillMaxHeight().padding(bottom = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(max, max / 2, 0L).forEach { Text("K${it / 100}", fontSize = 9.5.sp, color = Selo.Mute) }
        }
        Spacer(Modifier.width(6.dp))
        data.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth(.6f).fillMaxHeight((v / max.toFloat()).coerceAtLeast(.02f) * .85f)
                    .background(if (i == data.lastIndex) Selo.Purple else Selo.Purple.copy(.55f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                Text(labels[i], fontSize = 10.sp, color = Selo.Mute, modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** File a claim: category → amount + description → photo → review → submitted. */
@Composable
fun ClaimFlow(stats: RiderStats, back: () -> Unit) {
    val app = LocalAppState.current
    var step by remember { mutableIntStateOf(1) }
    var cat by remember { mutableStateOf<ClaimCategory?>(null) }
    var amount by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val take = rememberPhotoCapture { photo = it }
    val pick = rememberGalleryPick { photo = it }
    val hero = RiderTiers.isHero(stats.tier)
    Column(Modifier.fillMaxSize()) {
        RiderTitle(if (step == 5) "Claim submitted" else "File a claim · ${step.coerceAtMost(4)}/4", onBack = if (step in 2..4) ({ step -= 1 }) else back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                1 -> { Text("What is the claim for?", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    ClaimCategory.entries.forEach { c ->
                        val locked = c.heroOnly && !hero
                        RadioCard(cat == c, c.label, if (locked) "Unlock at Hero tier (300 deliveries)" else if (c.photoRequired) "Photo required" else "Photo optional") { if (!locked) cat = c }
                    } }
                2 -> { Text("How much do you need?", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text("Fund balance ${zmw(stats.socialFundNgwee)}", color = Selo.Purple, fontWeight = FontWeight.Bold)
                    SeloField("Amount (ZMW)", amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, keyboard = KeyboardType.Decimal)
                    SeloField("What happened?", desc, { desc = it.take(1000) }, singleLine = false, placeholder = "e.g. Chain and sprocket broke on Leopards Hill Rd") }
                3 -> { Text("Supporting photo", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text(if (cat?.photoRequired == true) "Required for bike repair claims: the damage or the mechanic's quote." else "Optional: a hospital slip, receipt or letter helps us decide faster.", color = Selo.Mute)
                    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Selo.Line, RoundedCornerShape(12.dp)).clickable { take() },
                        contentAlignment = Alignment.Center) {
                        if (photo != null) AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.PhotoCamera, null, tint = Selo.Red, modifier = Modifier.size(40.dp)); Text("Take photo", color = Selo.Red, fontWeight = FontWeight.Bold) }
                    }
                    TextButton(pick) { Text("Choose from gallery", color = Selo.Red) } }
                4 -> { Text("Review", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    SeloCard { KvRow("Category", cat?.label ?: ""); KvRow("Amount", "ZMW $amount", valueColor = Selo.Purple); KvRow("Photo", if (photo != null) "Attached" else "None")
                        Text(desc, color = Selo.Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Text("Claims are paid from your ring-fenced Social Fund after review.", color = Selo.Mute, fontSize = 12.sp) }
                else -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(84.dp).background(Selo.Purple, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
                    Text("Claim submitted. The Selonachipa team will review and respond within 48 hours.", textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OutlineButton("Back to Fund", onClick = back)
                }
            }
            ErrorText(error)
        }
        if (step < 5) Box(Modifier.background(Color.White).padding(12.dp)) {
            val ngwee = ((amount.toDoubleOrNull() ?: 0.0) * 100).toLong()
            val ok = when (step) { 1 -> cat != null; 2 -> ngwee > 0 && desc.trim().length >= 10; 3 -> photo != null || cat?.photoRequired != true; else -> true }
            PrimaryButton(if (step == 4) "Submit claim" else "Next", enabled = ok, loading = busy) {
                error = null
                if (step < 4) step++ else { busy = true
                    scope.launch { runCatching { app.backend.fileClaim(cat!!, ngwee, desc.trim(), photo) }.onSuccess { step = 5 }.onFailure { error = it.message }; busy = false } }
            }
        }
    }
}

@Composable
fun FundHistory(back: () -> Unit) {
    val app = LocalAppState.current
    val history by app.backend.riderHistory().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Social Fund history", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            history.groupBy { SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(it.at)) }.forEach { (day, rows) ->
                Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp)) {
                    Column(Modifier.weight(1f)) { Text(day, fontWeight = FontWeight.Bold); Text("${rows.size} deliveries", color = Selo.Mute, fontSize = 12.sp) }
                    Text("+ " + zmw(rows.sumOf { it.fundNgwee }), color = Selo.Purple, fontWeight = FontWeight.ExtraBold)
                }
                HorizontalDivider(color = Selo.Line)
            }
        }
    }
}

@Composable
fun AmbassadorScreen(back: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Become an ambassador", onBack = back)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ambassadors mentor new riders in their zone, run safety briefings and earn a referral share for every rider they bring on.", fontSize = 15.sp)
            NoteBox("Open to Hero riders with an acceptance rate above 80% and a 4.5★ rating.", Tone.OK, Icons.Outlined.Star)
            PrimaryButton("Apply on WhatsApp") { uri.openUri("https://wa.me/260978070734?text=" + Uri.encode("I'd like to become a SeloNaChipa rider ambassador.")) }
        }
    }
}
