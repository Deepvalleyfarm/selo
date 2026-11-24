package selo.Nachipa01.ui.screens.rider

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Order
import selo.Nachipa01.data.model.RiderStats
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

private val tabs = listOf(
    NavItem("home", Icons.Outlined.Home, "Home"), NavItem("jobs", Icons.Outlined.TwoWheeler, "Jobs"),
    NavItem("earnings", Icons.Outlined.AccountBalanceWallet, "Earnings"), NavItem("more", Icons.Outlined.MoreHoriz, "More"),
)
private val routeItem = NavItem("route", Icons.Outlined.Map, "Route")

@Composable
fun RiderShell(onLogout: () -> Unit) {
    val app = LocalAppState.current
    var tab by rememberSaveable { mutableStateOf("home") }
    val stats by app.backend.riderStats().collectAsState(RiderStats(0, 0, 0, 100, 0, 0, 0, "Starter"))
    val jobs by app.backend.availableJobs().collectAsState(emptyList())
    val active by app.backend.riderActiveJobs().collectAsState(emptyList())
    var online by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val u = app.session.value
    Scaffold(bottomBar = { SeloBottomBar(tabs, routeItem, tab) { tab = it } }, containerColor = Selo.Surface) { pad ->
        Column(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                "earnings" -> SocialFund(stats)
                "jobs", "route" -> Column(Modifier.statusBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    H(if (tab == "route") "Route planner (${active.size} deliveries)" else "My jobs")
                    if (tab == "route" && active.isNotEmpty()) { MapPlaceholder(180); NoteBox("Collect all parcels before delivering. Stops are ordered by the Directions API when a Maps key is configured.") }
                    if (active.isEmpty()) NoteBox("No active deliveries. Accept a job from Home.")
                    active.forEach { ActiveJobCard(it) }
                }
                "more" -> Column(Modifier.statusBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    H("More"); SeloCard { Text("Messages · Zones & availability · Notifications · Rider profile · Settings & support", color = Selo.Mute) }
                    OutlineButton("Log out") { scope.launch { app.backend.setOnline(false); app.backend.logout(); onLogout() } }
                }
                else -> {
                    HomeBand(u?.fullName ?: "", "${u?.area ?: ""} · ${u?.vehicle ?: ""}")
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val bg by animateColorAsState(if (online) Selo.Green else Selo.Grey, label = "toggle")
                        Row(Modifier.fillMaxWidth().height(68.dp).background(bg, RoundedCornerShape(16.dp))
                            .clickable { online = !online; scope.launch { app.backend.setOnline(online) } }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (online) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel, null, tint = Color.White)
                            Text(if (online) "  ONLINE — accepting jobs" else "  OFFLINE — not accepting jobs", color = Color.White, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Switch(online, null, colors = SwitchDefaults.colors(checkedTrackColor = Color.White.copy(alpha = .35f), checkedThumbColor = Color.White))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard(Fees.formatK(stats.todayNetNgwee), "Today · ${stats.todayDeliveries} deliveries", Modifier.weight(1f), Selo.Green)
                            StatCard("${stats.active} / ${app.config.collectAsState().value.maxConcurrentDeliveries}", "Active deliveries", Modifier.weight(1f), Selo.Ink)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("${stats.acceptanceRate}%", "Acceptance rate", Modifier.weight(1f), if (stats.acceptanceRate >= 80) Selo.Green else if (stats.acceptanceRate >= 60) Selo.Amber else Selo.Red)
                            StatCard(Fees.formatK(stats.socialFundNgwee), "Social Fund (locked)", Modifier.weight(1f), Selo.Purple)
                        }
                        active.forEach { ActiveJobCard(it) }
                        if (online) jobs.firstOrNull { j -> active.none { it.id == j.id } }?.let { DispatchCard(it) } ?: NoteBox("No jobs in your zones right now. Stay online — new paid orders appear here.")
                        else NoteBox("You are offline. Toggle Online to receive orders.", Tone.GREY)
                    }
                }
            }
        }
    }
}

/** 15-second dispatch card with gross, Social Fund and net. Accept is atomic server-side. */
@Composable
private fun DispatchCard(o: Order) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    var left by remember(o.id) { mutableIntStateOf(cfg.dispatchCountdownSec) }
    var state by remember(o.id) { mutableStateOf("open") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(o.id) { while (left > 0 && state == "open") { delay(1000); left-- }; if (state == "open") state = "expired" }
    val split = Fees.split(o.goodsNgwee, o.deliveryNgwee, cfg)
    Text("New job · ${if (state == "open") "$left s to decide" else state}", fontWeight = FontWeight.ExtraBold, color = Selo.Red)
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(0.dp, Color.Transparent, RoundedCornerShape(14.dp))) {
        LinearProgressIndicator({ left / cfg.dispatchCountdownSec.toFloat() }, Modifier.fillMaxWidth(), color = Selo.Red, trackColor = Color(0xFFF3D3D8))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Collect · ${o.pickup}", fontWeight = FontWeight.Bold)
            Text("Deliver · ${o.dropoff}", fontWeight = FontWeight.Bold)
            Row { Text("${o.distanceKm} km · ${o.items.sumOf { it.qty }} items", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f)); StatusChip("Buyer has paid", Tone.OK) }
            KvRow("Delivery fee", Fees.formatK(o.deliveryNgwee), valueColor = Selo.Green)
            KvRow("Social Fund contribution", "+ ${Fees.formatK(split.socialFundNgwee)}", valueColor = Selo.Purple)
            KvRow("Net to you", Fees.formatK(split.riderNetNgwee), strong = true)
            ErrorText(error)
            when (state) {
                "open" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("Decline", Modifier.weight(1f)) { state = "declined" }
                    PrimaryButton("Accept job", Modifier.weight(1.4f), color = Selo.Green) {
                        scope.launch { runCatching { app.backend.acceptJob(o.id) }.onSuccess { state = "accepted" }.onFailure { error = it.message ?: "Order already taken" } }
                    }
                }
                "accepted" -> NoteBox("Accepted. Head to ${o.pickup} and show order ID ${o.id} to the seller.", Tone.OK)
                else -> NoteBox("Job passed to the next rider.", Tone.GREY)
            }
        }
    }
}

/** E2: ring-fenced Social Fund (2% of every delivery fee, unlocks every 20 deliveries). */
@Composable
private fun SocialFund(stats: RiderStats) {
    val cfg by LocalAppState.current.config.collectAsState()
    val milestone = cfg.socialFundMilestoneDeliveries
    val progress = stats.completedDeliveries % milestone
    SeloHeader("Social Fund", "Your ring-fenced safety net", trailing = { StatusChip("Ring-fenced", Tone.WARN) })
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SeloCard {
            Text("Locked balance", color = Selo.Mute, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(Fees.formatK(stats.socialFundNgwee).replace("K ", "ZMW "), color = Selo.Purple, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("${cfg.socialFundPct}% of every delivery fee · not platform revenue", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator({ progress / milestone.toFloat() }, Modifier.fillMaxWidth().height(8.dp), color = Selo.Purple, trackColor = Selo.Line)
            Row { Text("$progress of $milestone deliveries", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("Unlocks in ${milestone - progress}", color = Selo.Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        SeloCard {
            Text("Last 7 days", fontWeight = FontWeight.ExtraBold)
            val max = (stats.weekFund.maxOrNull() ?: 1).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                stats.weekFund.forEach { v -> Box(Modifier.weight(1f).fillMaxHeight(v / max.toFloat()).background(Selo.Purple, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Starter" to "0–49", "Rising" to "50–299").forEach { (t, r) -> Tier(t, r, stats.tier == t, Modifier.weight(1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Hero" to "300+ · school-fees claims", "Ambassador" to "Hero + mentor riders").forEach { (t, r) -> Tier(t, r, stats.tier == t, Modifier.weight(1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("File a claim", Modifier.weight(1f)) {}
            PrimaryButton("Withdraw ${Fees.formatK(stats.availableNgwee).removeSuffix(".00")}", Modifier.weight(1f), enabled = stats.availableNgwee >= Fees.kwachaToNgwee(cfg.minWithdrawal + cfg.riderWithdrawalFee)) {}
        }
        Sub("Withdrawals cost K ${cfg.riderWithdrawalFee.toInt()} and are paid by Lipila to your linked wallet.")
    }
}

@Composable
private fun Tier(name: String, range: String, current: Boolean, modifier: Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(10.dp)).border(if (current) 2.dp else 1.dp, if (current) Selo.Red else Selo.Line, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(if (current) "$name · you" else name, fontWeight = FontWeight.Bold); Text(range, color = Selo.Mute, fontSize = 11.sp)
    }
}

/** Active delivery: collect from seller (releases seller payout), then deliver with the buyer's in-app code. */
@Composable
private fun ActiveJobCard(o: Order) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var code by remember(o.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    SeloCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Order ${o.id}", fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            StatusChip(if (o.state == selo.Nachipa01.data.model.OrderState.RIDER_ASSIGNED) "Collecting" else "In transit",
                if (o.state == selo.Nachipa01.data.model.OrderState.RIDER_ASSIGNED) Tone.INFO else Tone.FUND)
        }
        KvRow("Collect", o.pickup); KvRow("Deliver", o.dropoff)
        OutlineButton("Open navigation") { uri.openUri("https://www.google.com/maps/dir/?api=1&destination=" +
            java.net.URLEncoder.encode(if (o.state == selo.Nachipa01.data.model.OrderState.RIDER_ASSIGNED) o.pickup else o.dropoff, "UTF-8") + "&travelmode=driving") }
        Spacer(Modifier.height(6.dp))
        if (o.state == selo.Nachipa01.data.model.OrderState.RIDER_ASSIGNED) {
            Sub("Show order ID ${o.id} to the seller, then mark collected.")
            PrimaryButton("Mark as collected", loading = busy) {
                busy = true; scope.launch { runCatching { app.backend.markCollected(o.id) }.onFailure { error = it.message }; busy = false }
            }
        } else {
            SeloField("Buyer's 6-digit delivery code", code, { code = it.filter(Char::isDigit).take(6) }, keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
            PrimaryButton("Confirm delivery", color = Selo.Green, enabled = code.length == 6, loading = busy) {
                busy = true; scope.launch { runCatching { app.backend.confirmDeliveryCode(o.id, code) }.onFailure { error = it.message }; busy = false }
            }
        }
        ErrorText(error)
    }
}
