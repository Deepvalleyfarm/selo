package selo.Nachipa01.ui.screens.rider

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.theme.Selo

/**
 * Rider app after login (PRD 6). One-handed, minimum reading: going Online is the first thing on screen.
 * A job offer interrupts any screen; an active delivery replaces Home until it is done.
 * [initialTab]/[initialScreen] exist so screenshot tests can open a specific module.
 */
@Composable
fun RiderShell(initialTab: String = "home", initialScreen: String? = null, initialOnline: Boolean = false, initialNavigating: Boolean = false, onLogout: () -> Unit) {
    val app = LocalAppState.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    var navigating by rememberSaveable { mutableStateOf(initialNavigating) }
    val stats by app.backend.riderStats().collectAsState(RiderStats(0, 0, 0, 100, 0, 0, 0, "Starter"))
    val profile by app.backend.riderProfile().collectAsState(null)
    val jobs by app.backend.availableJobs().collectAsState(emptyList())
    val active by app.backend.riderActiveJobs().collectAsState(emptyList())
    var online by rememberSaveable { mutableStateOf(initialOnline) }
    var handled by remember { mutableStateOf(setOf<String>()) }
    val rc = rememberRouteController(active, profile?.vehicle ?: "Motorbike")
    // GPS every 30 s while online or on a delivery: geofence source of truth + live tracking for senders and receivers (§6, §9.1)
    LaunchedEffect(online, active.isNotEmpty()) {
        while (online || active.isNotEmpty()) { rc.position?.let { runCatching { app.backend.pingLocation(it.lat, it.lng, null) } }; kotlinx.coroutines.delay(30_000) }
    }
    val offer = if (online) jobs.firstOrNull { it.id !in handled && active.none { a -> a.id == it.id } } else null

    BackHandler(enabled = screen != null || navigating) { if (navigating) navigating = false else screen = null }

    Box(Modifier.fillMaxSize().background(Selo.Surface)) {
        Scaffold(bottomBar = { if (!navigating) RiderTabBar(tab, active.size + (if (offer != null) 1 else 0)) { tab = it; screen = null } },
            containerColor = Selo.Surface) { pad ->
            Box(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize()) {
                val s = screen
                when {
                    navigating && active.isNotEmpty() -> NavigationScreen(active, profile, rc, onExit = { navigating = false })
                    s != null -> MoreScreens(s, profile, stats, open = { screen = it }, back = { screen = null }, onLogout = onLogout)
                    tab == "home" && active.isNotEmpty() -> ActiveDeliveryScreen(active, profile, rc, onNavigate = { navigating = true })
                    tab == "home" -> RiderHome(profile, stats, online, onToggle = { on ->
                        online = on; if (!on) handled = emptySet()
                    }, openDelivery = { screen = "delivery:$it" })
                    tab == "jobs" -> RiderJobs(active, online, openDelivery = { screen = "delivery:$it" }, openActive = { tab = "home" })
                    tab == "earnings" -> RiderEarnings(profile, stats, open = { screen = it })
                    tab == "fund" -> RiderFund(stats, open = { screen = it })
                    else -> MoreMenu(profile, open = { screen = it }, onLogout = onLogout)
                }
            }
        }
        offer?.let { o ->
            DispatchOverlay(o, active, profile, rc, onDone = { accepted -> handled = handled + o.id; if (accepted) { tab = "home"; screen = null } })
        }
    }
}

/* =================================================================== HOME (6.3) */

@Composable
fun RiderHome(profile: RiderProfile?, stats: RiderStats, online: Boolean, onToggle: (Boolean) -> Unit, openDelivery: (String) -> Unit) {
    val app = LocalAppState.current
    val history by app.backend.riderHistory().collectAsState(emptyList())
    val u = app.session.value
    val scope = rememberCoroutineScope()
    var toggleError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = Color.White, shadowElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile?.fullName ?: u?.fullName ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(profile?.zoneName?.ifBlank { null } ?: u?.area ?: "", fontSize = 14.sp, color = Selo.Mute)
                }
                Text(profile?.vehicle ?: u?.vehicle ?: "", fontSize = 12.sp, color = Selo.Mute)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnlineToggle(online) { on ->
                onToggle(on); toggleError = null
                scope.launch { runCatching { app.backend.setOnline(on) }.onFailure { onToggle(!on); toggleError = it.message } }
            }
            toggleError?.let { Text(it, color = Selo.Red, fontSize = 12.sp) }
            if (online) Text("You're in the dispatch pool for ${profile?.zoneName ?: "your zone"}. Job requests can arrive at any moment.", color = Selo.GreenDark, fontSize = 12.sp)
            else Text("Your position is hidden from dispatch. Go online to receive jobs.", color = Selo.Mute, fontSize = 12.sp)

            val weekTarget = profile?.weeklyTargetNgwee ?: 100_000
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat(zmw(stats.todayNetNgwee), "Today's earnings", Modifier.weight(1f), Selo.Green, "${stats.todayDeliveries} deliveries")
                MiniStat(zmw(stats.weekNetNgwee), "This week", Modifier.weight(1f), Selo.Ink) {
                    LinearProgressIndicator({ (stats.weekNetNgwee / weekTarget.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Selo.Red, trackColor = Selo.Line)
                    Text("of ${zmw(weekTarget)} target", color = Selo.Mute, fontSize = 10.5.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ar = stats.acceptanceRate
                MiniStat("$ar%", "Acceptance rate", Modifier.weight(1f), if (ar > 80) Selo.Green else if (ar >= 60) Selo.Amber else Selo.Red,
                    if (ar > 80) "Priority dispatch" else "Stay above 80%")
                MiniStat(zmw(stats.socialFundNgwee), "Social Fund", Modifier.weight(1f).border(1.5.dp, Selo.Purple.copy(.35f), RoundedCornerShape(14.dp)),
                    Selo.Purple, "Your ring-fenced fund")
            }
            Text("Today's deliveries", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            val dayStart = remember { java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis }
            val today = history.filter { it.at >= dayStart }
            if (today.isEmpty()) Text("No deliveries yet today.", color = Selo.Mute, fontSize = 13.sp)
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                today.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Selo.Line)
                    DeliveryRow(d, showDate = false) { openDelivery(d.orderId) }
                }
            }
        }
    }
}

/** PRD 6.3.1: 72 dp, 16 dp corners, the tap is the action, with a 2-second left-to-right colour sweep. */
@Composable
fun OnlineToggle(online: Boolean, onChange: (Boolean) -> Unit) {
    val sweep = remember { Animatable(1f) }
    var from by remember { mutableStateOf(if (online) Selo.Green else Selo.Grey) }
    val to = if (online) Selo.Green else Selo.Grey
    LaunchedEffect(online) { if (from != to) { sweep.snapTo(0f); sweep.animateTo(1f, tween(2000, easing = LinearEasing)); from = to } }
    Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp))
        .drawBehind { drawRect(from); drawRect(to, size = Size(size.width * sweep.value, size.height)) }
        .clickable { onChange(!online) }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = if (online) .25f else 1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (online) Icons.Outlined.Check else Icons.Outlined.Close, null, tint = if (online) Color.White else Selo.Grey, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(if (online) "ONLINE — accepting jobs" else "OFFLINE — not accepting jobs", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
fun DeliveryRow(d: RiderDelivery, showDate: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.orderId, fontSize = 12.sp, color = Selo.Mute)
            Text("${d.pickupArea} → ${d.dropArea}", fontSize = 14.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(zmw(d.netNgwee), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Selo.Green)
            Text(if (showDate) java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.UK).format(java.util.Date(d.at)) else clock(d.at), fontSize = 12.sp, color = Selo.Mute)
        }
    }
}
