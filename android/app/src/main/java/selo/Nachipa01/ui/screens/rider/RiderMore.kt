package selo.Nachipa01.ui.screens.rider

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.data.repo.QuestionAnswer
import selo.Nachipa01.domain.Operator
import selo.Nachipa01.domain.SecurityQuestions
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.payments.OperatorMark
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Routes for screens opened on top of the tabs. */
@Composable
fun MoreScreens(s: String, profile: RiderProfile?, stats: RiderStats, open: (String) -> Unit, back: () -> Unit, onLogout: () -> Unit) {
    when {
        s.startsWith("delivery:") -> DeliveryDetail(s.removePrefix("delivery:"), back)
        s.startsWith("chat:") -> ChatThreadScreen(s.removePrefix("chat:")) { open("messages") }
        s == "messages" -> MessagesScreen(open, back)
        s == "zones" -> ZonesScreen(back)
        s == "notifications" -> NotificationsScreen(back)
        s == "profile" -> ProfileScreen(profile, back)
        s == "settings" -> SettingsScreen(profile, back, onLogout, open)
        s == "incident" -> IncidentScreen { open("settings") }
        s == "safety" -> SafetyCentreScreen(back, open)
        s == "report" -> selo.Nachipa01.ui.screens.account.PerformanceReportScreen(null, back)
        s == "claim" -> ClaimFlow(stats, back)
        s == "fundHistory" -> FundHistory(back)
        s == "ambassador" -> AmbassadorScreen(back)
        s == "questions" || s == "pin" || s.startsWith("support") -> selo.Nachipa01.ui.screens.account.AccountHost(s, back)
        else -> MoreMenu(profile, open, onLogout)
    }
}

@Composable
fun MoreMenu(profile: RiderProfile?, open: (String) -> Unit, onLogout: () -> Unit) {
    val app = LocalAppState.current
    val threads by app.backend.chatThreads().collectAsState(emptyList())
    val notes by app.backend.notifications().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        RiderTitle("More")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            profile?.let { p -> Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).clickable { open("profile") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p, 48); Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(p.fullName, fontWeight = FontWeight.Bold); Text("${p.tier} rider · ${p.zoneName}", color = Selo.Mute, fontSize = 12.sp) }
                Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Mute) } }
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                MenuRow(Icons.Outlined.HealthAndSafety, "Safety Centre · SOS") { open("safety") }
                MenuRow(Icons.Outlined.Assessment, "Monthly performance report") { open("report") }
                MenuRow(Icons.Outlined.Chat, "Messages", threads.sumOf { it.unread }) { open("messages") }
                MenuRow(Icons.Outlined.Map, "Zone & availability") { open("zones") }
                MenuRow(Icons.Outlined.Notifications, "Notifications", notes.count { !it.read }) { open("notifications") }
                MenuRow(Icons.Outlined.Person, "Rider profile") { open("profile") }
                MenuRow(Icons.Outlined.Settings, "Settings & support", last = true) { open("settings") }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, badge: Int = 0, last: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Selo.Red); Text(label, Modifier.padding(start = 14.dp).weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (badge > 0) Text("$badge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Selo.Coral, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
        Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Mute)
    }
    if (!last) HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(start = 52.dp))
}

@Composable
fun Avatar(p: RiderProfile, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Selo.Red), contentAlignment = Alignment.Center) {
        if (p.photoUrl != null) AsyncImage(p.photoUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(p.fullName.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size / 2.6).sp)
    }
}

/* =================================================================== MESSAGES (6.9) */

private val dispatchTypes = setOf("NEW_ORDER_AVAILABLE", "TIER_MILESTONE", "SOCIAL_FUND_DAILY", "ACCEPTANCE_WARNING", "SOCIAL_FUND_UNLOCKED")

@Composable
fun MessagesScreen(open: (String) -> Unit, back: () -> Unit) {
    val app = LocalAppState.current
    val threads by app.backend.chatThreads().collectAsState(emptyList())
    val notes by app.backend.notifications().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Messages", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RedHeading("ACTIVE ORDER CHATS")
            if (threads.isEmpty()) Text("Chats with buyers and sellers appear when you accept a job.", color = Selo.Mute, fontSize = 13.sp)
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                threads.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Selo.Line)
                    Row(Modifier.fillMaxWidth().clickable { open("chat:${t.id}") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(if (t.kind == "buyer") Selo.RedTint else Selo.TealTint, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (t.kind == "buyer") Icons.Outlined.Person else Icons.Outlined.Storefront, null, tint = if (t.kind == "buyer") Selo.Red else Selo.Teal) }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(t.label, fontWeight = if (t.unread > 0) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                            Text(t.lastMessage, color = Selo.Mute, fontSize = 12.5.sp, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(clock(t.lastAt), color = Selo.Mute, fontSize = 11.sp)
                            if (t.unread > 0) Text("${t.unread}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp).background(Selo.Coral, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            RedHeading("DISPATCH NOTIFICATIONS")
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                notes.filter { it.type in dispatchTypes }.forEachIndexed { i, n -> if (i > 0) HorizontalDivider(color = Selo.Line); NotificationRow(n) }
            }
        }
    }
}

@Composable
fun ChatThreadScreen(id: String, back: () -> Unit) {
    val app = LocalAppState.current
    val threads by app.backend.chatThreads().collectAsState(emptyList())
    val msgs by remember(id) { app.backend.chatMessages(id) }.collectAsState(emptyList())
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        RiderTitle(threads.firstOrNull { it.id == id }?.label ?: "Chat", onBack = back)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(Modifier.widthIn(max = 280.dp).background(if (m.mine) Selo.Red else Color(0xFFECECF0),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (m.mine) 16.dp else 4.dp, bottomEnd = if (m.mine) 4.dp else 16.dp)).padding(10.dp)) {
                        Text(m.text, color = if (m.mine) Color.White else Selo.Ink, fontSize = 14.sp)
                        Text(clock(m.at), color = if (m.mine) Color.White.copy(.75f) else Selo.Mute, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("I'm outside", "5 minutes away").forEach { q -> AssistChip({ text = q }, { Text(q, fontSize = 12.sp) }, Modifier.padding(end = 6.dp)) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Message") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
            IconButton({ val t = text.trim(); if (t.isEmpty()) app.info("Type a message first.") else { text = ""; scope.launch {
                runCatching { app.backend.sendChat(id, t) }.onFailure { e -> text = t; app.fail(e.message ?: "Message not sent. Check your connection and try again.") } } } }) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Selo.Red) }
        }
    }
}

/* =================================================================== ZONE & AVAILABILITY (6.10) */

@Composable
fun ZonesScreen(back: () -> Unit) {
    val app = LocalAppState.current
    val zones by app.backend.riderZones().collectAsState(emptyList())
    val hours by app.backend.workingHours().collectAsState(WorkingDay.DEFAULT)
    val scope = rememberCoroutineScope()
    var edit by remember(hours) { mutableStateOf(hours) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Zone & availability", onBack = back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            zones.firstOrNull { it.primary }?.let { z ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Color.White, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(Selo.Red))
                    Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(z.name, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(z.city, fontSize = 14.sp)
                        Text("Covers: ${z.neighbourhoods.joinToString(", ")}", fontSize = 12.sp, color = Selo.Mute)
                        Text("Dispatch radius ${zones.filter { it.active }.sumOf { it.radiusKm }.let { "%.1f".format(Locale.UK, it) }} km across ${zones.count { it.active }} zone(s)", fontSize = 12.sp, color = Selo.Mute)
                    }
                    ZoneThumb(Modifier.size(110.dp).padding(10.dp))
                }
            }
            RedHeading("Expand your reach")
            zones.filter { !it.primary }.forEach { z ->
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(z.name, fontWeight = FontWeight.Bold); Text(z.neighbourhoods.joinToString(", "), color = Selo.Mute, fontSize = 12.sp, maxLines = 2)
                        Text("+${z.radiusKm} km dispatch radius", color = Selo.Mute, fontSize = 12.sp)
                    }
                    if (z.active) StatusChip("Added", Tone.OK)
                    else OutlinedButton({ scope.launch { app.attempt("${z.name} added to your zones") { app.backend.addZone(z.id) } } }, shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)) { Text("Add zone", color = Selo.Red) }
                }
            }
            ParcelAreasCard()
            RedHeading("My working hours")
            Text("Outside these hours you won't get jobs, even when you're online.", color = Selo.Mute, fontSize = 12.sp)
            Column(Modifier.background(Color.White, RoundedCornerShape(12.dp))) {
                edit.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Selo.Line)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(WorkingDay.NAMES[d.day], fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                        if (d.on) { TimePick(d.start) { t -> edit = edit.map { if (it.day == d.day) it.copy(start = t) else it } }; Text(" – ", color = Selo.Mute)
                            TimePick(d.end) { t -> edit = edit.map { if (it.day == d.day) it.copy(end = t) else it } } }
                        else Text("Off", color = Selo.Mute)
                        Spacer(Modifier.weight(1f))
                        Switch(d.on, { on -> edit = edit.map { if (it.day == d.day) it.copy(on = on) else it } }, colors = SwitchDefaults.colors(checkedTrackColor = Selo.Green))
                    }
                }
            }
            if (saved) NoteBox("Working hours saved.", Tone.OK)
        }
        Box(Modifier.background(Color.White).padding(12.dp)) {
            PrimaryButton("Save working hours", enabled = edit != hours, whyDisabled = "Change a day or time first.") { scope.launch { app.attempt("Working hours saved") { app.backend.setWorkingHours(edit) }?.let { saved = true } } }
        }
    }
}

@Composable
private fun TimePick(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.border(1.dp, Selo.Field, RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp))
        DropdownMenu(open, { open = false }) { (5..23).flatMap { h -> listOf("%02d:00".format(h), "%02d:30".format(h)) }.forEach { t ->
            DropdownMenuItem({ Text(t) }, { onPick(t); open = false }) } }
    }
}

/** Non-interactive zone outline (dashed boundary, neighbourhood dots). */
@Composable
private fun ZoneThumb(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFEFF1EC))) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2.6f
        val p = Path(); (0..7).forEach { i -> val a = i * Math.PI / 4; val rr = r * (if (i % 2 == 0) 1f else .82f)
            val pt = Offset(c.x + (cos(a) * rr).toFloat(), c.y + (sin(a) * rr).toFloat()); if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y) }
        p.close()
        drawPath(p, Selo.Red.copy(.12f)); drawPath(p, Selo.Red, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
        listOf(Offset(-.3f, -.2f), Offset(.25f, -.1f), Offset(0f, .3f), Offset(-.1f, .05f)).forEach { o -> drawCircle(Selo.Ink, 3.dp.toPx(), Offset(c.x + o.x * r * 2, c.y + o.y * r * 2)) }
    }
}

/* =================================================================== NOTIFICATIONS (6.11) */

@Composable
fun NotificationsScreen(back: () -> Unit) {
    val app = LocalAppState.current
    val notes by app.backend.notifications().collectAsState(emptyList())
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Notifications", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notes.groupBy { when { it.at >= today -> "Today"; it.at >= today - 86_400_000 -> "Yesterday"; else -> "Earlier" } }.forEach { (g, rows) ->
                Text(g, fontWeight = FontWeight.ExtraBold, color = Selo.Mute, fontSize = 13.sp)
                Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) { rows.forEachIndexed { i, n -> if (i > 0) HorizontalDivider(color = Selo.Line); NotificationRow(n) } }
            }
            if (notes.isEmpty()) Text("You're all caught up.", color = Selo.Mute)
        }
    }
}

@Composable
fun NotificationRow(n: AppNotification) {
    val (icon, tint) = when (n.type) {
        "NEW_ORDER_AVAILABLE" -> Icons.Outlined.TwoWheeler to Selo.Amber
        "PAYOUT", "DELIVERY_CONFIRMED" -> Icons.Outlined.CheckCircle to Selo.Green
        "SOCIAL_FUND_DAILY", "SOCIAL_FUND_UNLOCKED" -> Icons.Outlined.MonitorHeart to Selo.Purple
        "RATING" -> Icons.Filled.Star to Color(0xFFF5B301)
        "TIER_MILESTONE" -> Icons.Outlined.EmojiEvents to Selo.Red
        "ACCEPTANCE_WARNING" -> Icons.Outlined.Warning to Color(0xFFF97316)
        "INCIDENT_ACK" -> Icons.Outlined.Shield to Selo.Grey
        else -> Icons.Outlined.Notifications to Selo.Mute
    }
    Row(Modifier.fillMaxWidth().background(if (!n.read) Selo.RedTint.copy(.5f) else Color.Transparent).padding(12.dp)) {
        Box(Modifier.size(36.dp).background(tint.copy(.13f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row { Text(n.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(clock(n.at), color = Selo.Mute, fontSize = 11.sp) }
            Text(n.body, color = Selo.Mute, fontSize = 13.sp)
        }
    }
}

/* =================================================================== PROFILE (6.12) */

@Composable
fun ProfileScreen(profile: RiderProfile?, back: () -> Unit) {
    val app = LocalAppState.current
    val p = profile ?: return
    var editing by remember { mutableStateOf(false) }
    var vehicle by remember(p) { mutableStateOf(p.vehicle) }
    var plate by remember(p) { mutableStateOf(p.plate) }
    var eName by remember(p) { mutableStateOf(p.emergencyName) }
    var ePhone by remember(p) { mutableStateOf(p.emergencyPhone.replace("+260", "0")) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val take = rememberPhotoCapture { photo = it }
    Column(Modifier.fillMaxSize()) {
        RiderTitle("My profile", onBack = back, trailing = { IconButton({ editing = !editing }) { Icon(if (editing) Icons.Outlined.Close else Icons.Outlined.Edit, "Edit", tint = Selo.Red) } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.clickable(enabled = editing) { take() }) {
                    if (photo != null) AsyncImage(photo, null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop) else Avatar(p, 72)
                    if (editing) Icon(Icons.Outlined.PhotoCamera, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).background(Selo.Ink, CircleShape).padding(4.dp).size(16.dp))
                }
                Text(p.fullName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${p.zoneName} · ${p.vehicle} · ${p.plate}", color = Selo.Mute, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.tier} Rider", color = Selo.AmberDark, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.background(Selo.AmberTint, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
                    Spacer(Modifier.width(10.dp)); Icon(Icons.Filled.Star, null, tint = Color(0xFFF5B301), modifier = Modifier.size(16.dp))
                    Text(" ${"%.1f".format(Locale.UK, p.rating)} (${p.ratingCount})", fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("${p.totalDeliveries}", "Deliveries", Modifier.weight(1f), Selo.Red)
                MiniStat("${p.completionRate}%", "Completion", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("${p.acceptanceRate}%", "Acceptance", Modifier.weight(1f), if (p.acceptanceRate > 80) Selo.Green else Selo.Amber)
                MiniStat("${"%.1f".format(Locale.UK, p.rating)} ★", "Avg buyer rating", Modifier.weight(1f))
            }
            Text("Completion and acceptance rates count towards tier eligibility.", color = Selo.Mute, fontSize = 12.sp)
            if (!editing) SeloCard {
                KvRow("Phone", p.msisdn.replace("+260", "0")); KvRow("Vehicle", p.vehicle); KvRow("Number plate", p.plate)
                KvRow("Emergency contact", "${p.emergencyName} · ${p.emergencyPhone.replace("+260", "0")}")
                KvRow("Payout wallet", if (p.payoutWallet.isBlank()) "—" else walletBadge(p.payoutWallet)); KvRow("Member since", p.memberSince)
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Motorbike", "Bicycle", "Car").forEach { v -> SeloChip(v, vehicle == v) { vehicle = v } } }
                SeloField("Number plate", plate, { plate = it.uppercase().take(12) })
                SeloField("Emergency contact name", eName, { eName = it.take(60) })
                SeloField("Emergency contact phone", ePhone, { ePhone = it.take(16) }, keyboard = KeyboardType.Phone)
                Text("Change your payout wallet in Settings & support.", color = Selo.Mute, fontSize = 12.sp)
                ErrorText(error)
                PrimaryButton("Save changes", loading = busy) { busy = true; error = null
                    scope.launch { runCatching { app.backend.updateRiderProfile(mapOf("vehicle" to vehicle, "plate" to plate, "emergencyName" to eName,
                        "emergencyPhone" to (Validation.normaliseMsisdn(ePhone) ?: ePhone)), photo) }.onSuccess { editing = false; app.ok("Profile saved") }.onFailure { error = it.message; app.fail(it.message) }; busy = false } }
            }
        }
    }
}

/* =================================================================== SETTINGS & SUPPORT (6.13) */

@Composable
fun SettingsScreen(profile: RiderProfile?, back: () -> Unit, onLogout: () -> Unit, open: (String) -> Unit) {
    val app = LocalAppState.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<String?>(null) }
    var support by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var target by remember(profile) { mutableStateOf(((profile?.weeklyTargetNgwee ?: 100_000) / 100).toString()) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { support = runCatching { app.backend.supportStatus() }.getOrNull() }
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Settings & support", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            msg?.let { NoteBox(it, Tone.OK) }
            RedHeading("ACCOUNT")
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                SettingRow(Icons.Outlined.AccountBalanceWallet, "Change payout wallet", profile?.payoutWallet?.let { walletBadge(it) }) { sheet = "wallet" }
                SettingRow(Icons.Outlined.Pin, "Change PIN", "Current PIN required") { sheet = "pin" }
                SettingRow(Icons.Outlined.Quiz, "Security questions", "View or change · current PIN required") { open("questions") }
                SettingRow(Icons.Outlined.ContactEmergency, "Update emergency contact", profile?.let { "${it.emergencyName} · ${it.emergencyPhone.replace("+260", "0")}" }, last = true) { sheet = "emergency" }
            }
            RedHeading("WEEKLY EARNINGS TARGET")
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SeloField("ZMW per week", target, { target = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                Button({ scope.launch { runCatching { app.backend.updateRiderProfile(mapOf("weeklyTargetNgwee" to (target.toLongOrNull() ?: 1000) * 100)) }
                    .onSuccess { msg = "Weekly target set to ZMW $target." }.onFailure { msg = it.message } } }, colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Save") }
            }
            RedHeading("SUPPORT")
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                SettingRow(Icons.Outlined.SupportAgent, "Live chat", support?.let { (on, min) -> if (on) "● Online · replies in about $min min" else "● Offline · leave a message" },
                    subColor = if (support?.first == true) Selo.Green else Selo.Mute) { open("support/new") }
                SettingRow(Icons.Outlined.HelpOutline, "Help centre & my requests", "FAQs and replies from support") { open("support") }
                SettingRow(Icons.Outlined.Chat, "WhatsApp support", "+260 978 070 734") { uri.openUri("https://wa.me/260978070734") }
                SettingRow(Icons.Outlined.ReportProblem, "Report an incident", "Reviewed by operations within 24 hours") { open("incident") }
                SettingRow(Icons.Outlined.Gavel, "Terms of Use & policies", "Terms, Rider and Agent Terms, Privacy Policy", last = true) { app.navRequest.value = "legal/terms" }
            }
            Spacer(Modifier.height(8.dp))
            OutlineButton("Log out") { sheet = "logout" }
            Spacer(Modifier.height(32.dp))
            Text("Version ${selo.Nachipa01.BuildConfig.VERSION_NAME}", color = Selo.Mute, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    when (sheet) {
        "wallet" -> WalletSheet(profile) { m -> sheet = null; m?.let { msg = it } }
        "pin" -> PinSheet { m -> sheet = null; m?.let { msg = it } }
        "questions" -> QuestionsSheet { m -> sheet = null; m?.let { msg = it } }
        "emergency" -> EmergencySheet(profile) { m -> sheet = null; m?.let { msg = it } }
        "logout" -> LogoutSheet(onDismiss = { sheet = null }) { scope.launch { runCatching { app.backend.setOnline(false) }; runCatching { app.backend.logout() }; onLogout() } }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String? = null, last: Boolean = false, subColor: Color = Selo.Mute, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Selo.Red)
        Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); if (sub != null) Text(sub, color = subColor, fontSize = 12.sp) }
        Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Mute)
    }
    if (!last) HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(start = 52.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold); content()
        }
    }
}

@Composable
private fun WalletSheet(profile: RiderProfile?, done: (String?) -> Unit) {
    val app = LocalAppState.current
    var number by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val op = Operator.detect(number)
    Sheet("Payout wallet", { done(null) }) {
        profile?.payoutWallet?.takeIf { it.isNotBlank() }?.let { w -> Text("Current: ${walletBadge(w)}", color = Selo.Mute) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Operator.entries.forEach { OperatorMark(it, 32.dp) }; Text("Airtel Money · MTN MoMo · Zamtel Kwacha", color = Selo.Mute, fontSize = 12.sp) }
        SeloField("Mobile money number", number, { number = it.take(16) }, keyboard = KeyboardType.Phone, placeholder = "e.g. 0977 123 456",
            error = if (Operator.unsupported(number)) "Not an Airtel, MTN or Zamtel number." else null)
        op?.let { Row(verticalAlignment = Alignment.CenterVertically) { OperatorMark(it, 28.dp); Text("  ${it.label}", fontWeight = FontWeight.Bold) } }
        ErrorText(error)
        PrimaryButton("Switch payouts to this wallet", whyDisabled = "Enter a valid Airtel, MTN or Zamtel number.", enabled = Validation.normaliseMsisdn(number) != null && op != null, loading = busy) {
            busy = true; scope.launch { runCatching { app.backend.setPayoutWallet(number) }.onSuccess { done("Payouts now go to ${walletBadge(Validation.normaliseMsisdn(number)!!)}.") }.onFailure { error = it.message }; busy = false } }
    }
}

@Composable
private fun PinSheet(done: (String?) -> Unit) {
    val app = LocalAppState.current
    var cur by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }; var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Sheet("Change PIN", { done(null) }) {
        Text("Current PIN", fontWeight = FontWeight.Bold, fontSize = 13.sp); PinInput(cur, { cur = it })
        if (cur.length == 5) { Text("New PIN", fontWeight = FontWeight.Bold, fontSize = 13.sp); PinInput(new, { new = it })
            Text("Confirm new PIN", fontWeight = FontWeight.Bold, fontSize = 13.sp); PinInput(again, { again = it }, error = again.length == 5 && again != new) }
        ErrorText(error ?: Validation.pinProblem(new).takeIf { new.length == 5 })
        PrimaryButton("Change PIN", whyDisabled = when { cur.length < 5 -> "Enter your current 5-digit PIN."; new.length < 5 -> "Enter a new 5-digit PIN."; new != again -> "The new PINs don't match."; else -> Validation.pinProblem(new) ?: "Check your new PIN." }, enabled = cur.length == 5 && new.length == 5 && new == again && Validation.pinProblem(new) == null, loading = busy) {
            busy = true; scope.launch { runCatching { app.backend.changePin(cur, new) }.onSuccess { done("PIN changed.") }.onFailure { error = it.message }; busy = false } }
    }
}

@Composable
private fun QuestionsSheet(done: (String?) -> Unit) {
    val app = LocalAppState.current
    var pin by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf<List<SecurityQuestion>>(emptyList()) }
    val offered = remember(bank) { SecurityQuestions.offer(bank) }
    var picked by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { bank = SecurityQuestions.merge(runCatching { app.backend.questionBank() }.getOrDefault(emptyList())) }
    Sheet("Security questions", { done(null) }) {
        if (pin.length < 5) { Text("Enter your current PIN first", color = Selo.Mute); PinInput(pin, { pin = it }) }
        else {
            Text("Choose ${SecurityQuestions.REQUIRED} of these ${offered.size} and answer them.", color = Selo.Mute, fontSize = 13.sp)
            offered.forEach { q ->
                CheckRow(q.id in picked, { on -> picked = if (on && picked.size < SecurityQuestions.REQUIRED) picked + (q.id to "") else picked - q.id }, q.text)
                if (q.id in picked) SeloField("Answer", picked[q.id] ?: "", { a -> picked = picked + (q.id to a.take(60)) })
            }
            ErrorText(error)
            PrimaryButton("Save questions", whyDisabled = if (picked.size < SecurityQuestions.REQUIRED) "Choose ${SecurityQuestions.REQUIRED} questions." else "Answer each question (at least 2 characters).", enabled = picked.size == SecurityQuestions.REQUIRED && picked.values.all { it.trim().length >= 2 }, loading = busy) {
                busy = true; scope.launch { runCatching { app.backend.updateSecurityQuestions(pin, picked.map { QuestionAnswer(it.key, it.value) }) }
                    .onSuccess { done("Security questions updated.") }.onFailure { error = it.message }; busy = false } }
        }
    }
}

@Composable
private fun EmergencySheet(profile: RiderProfile?, done: (String?) -> Unit) {
    val app = LocalAppState.current
    var name by remember { mutableStateOf(profile?.emergencyName ?: "") }; var phone by remember { mutableStateOf(profile?.emergencyPhone?.replace("+260", "0") ?: "") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Sheet("Emergency contact", { done(null) }) {
        Text("Selonachipa operations call this person if you're in a road incident.", color = Selo.Mute, fontSize = 13.sp)
        SeloField("Name", name, { name = it.take(60) }); SeloField("Phone number", phone, { phone = it.take(16) }, keyboard = KeyboardType.Phone)
        ErrorText(error)
        PrimaryButton("Save", whyDisabled = if (name.isBlank()) "Enter your contact's name." else "Enter their mobile number.", enabled = name.isNotBlank() && phone.filter(Char::isDigit).length >= 9, loading = busy) {
            busy = true; scope.launch { runCatching { app.backend.updateRiderProfile(mapOf("emergencyName" to name, "emergencyPhone" to (Validation.normaliseMsisdn(phone) ?: phone))) }
                .onSuccess { done("Emergency contact saved.") }.onFailure { error = it.message }; busy = false } }
    }
}

@Composable
private fun LogoutSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Sheet("Log out?", onDismiss) {
        Text("You'll be set to OFFLINE when your session ends, so no new jobs will come to this phone.", color = Selo.Mute)
        PrimaryButton("Log out", onClick = onConfirm); OutlineButton("Stay logged in", onClick = onDismiss)
    }
}

/** Report an incident: type, description, optional photo, related order. Reviewed within 24 hours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncidentScreen(back: () -> Unit) {
    val app = LocalAppState.current
    val types = listOf("accident" to "Accident", "buyer_dispute" to "Buyer dispute", "seller_dispute" to "Seller dispute", "unsafe_order" to "Unsafe order", "other" to "Other")
    var type by remember { mutableStateOf("accident") }; var desc by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var done by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val take = rememberPhotoCapture { photo = it }
    Column(Modifier.fillMaxSize()) {
        RiderTitle("Report an incident", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (done != null) { NoteBox("Report $done received. Selonachipa operations will review it within 24 hours. If you're hurt, call 999 first.", Tone.OK, Icons.Outlined.Shield)
                OutlineButton("Back to settings", onClick = back); return@Column }
            Text("What happened?", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { types.forEach { (k, l) -> SeloChip(l, type == k) { type = k } } }
            SeloField("Description", desc, { desc = it.take(2000) }, singleLine = false, placeholder = "Where, when and what happened")
            SeloField("Order ref (if any)", ref, { ref = it.uppercase().take(20) }, placeholder = "SNC-48107")
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Selo.Line, RoundedCornerShape(12.dp)).clickable { take() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (photo != null) AsyncImage(photo, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.PhotoCamera, null, tint = Selo.Red)
                Text(if (photo != null) "  Photo attached · retake" else "  Add a photo (optional)", color = Selo.Red, fontWeight = FontWeight.SemiBold)
            }
            ErrorText(error)
            PrimaryButton("Send report", whyDisabled = "Describe what happened in at least 10 characters.", enabled = desc.trim().length >= 10, loading = busy) {
                busy = true; scope.launch { runCatching { app.backend.reportIncident(IncidentReport(type, desc.trim(), ref, photo)) }.onSuccess { done = it }.onFailure { error = it.message }; busy = false } }
        }
    }
}


/**
 * Parcel jobs area: the rider's city plus up to 10 neighbourhoods, or "Whole city" to take jobs from every
 * neighbourhood in that city.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParcelAreasCard() {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var cityId by remember { mutableStateOf("lusaka") }
    var whole by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { app.backend.riderAreas() }.getOrNull()?.let { a -> cityId = a.cityId.ifBlank { "lusaka" }; whole = a.wholeCity; picked = a.neighbourhoods.toSet() }
        loaded = true
    }
    val city = selo.Nachipa01.domain.Cities.byId(cityId) ?: selo.Nachipa01.domain.Cities.ALL.first()
    RedHeading("Neighbourhoods you cover")
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("City", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selo.Nachipa01.domain.Cities.ALL.forEach { c -> selo.Nachipa01.ui.components.SeloChip(c.name, c.id == cityId) { if (c.id != cityId) { cityId = c.id; picked = emptySet() } } }
        }
        Row(Modifier.fillMaxWidth().background(if (whole) Selo.GreenTint else Selo.Surface, RoundedCornerShape(12.dp)).clickable { whole = !whole }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Whole city", fontWeight = FontWeight.ExtraBold)
                Text("Take jobs from all ${city.neighbourhoods.size} neighbourhoods in ${city.name}", color = Selo.Mute, fontSize = 12.sp)
            }
            Switch(whole, { whole = it }, colors = SwitchDefaults.colors(checkedTrackColor = Selo.Green))
        }
        if (!whole) {
            Text("Or choose up to 10 neighbourhoods (${picked.size}/10)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            val list = if (showAll) city.neighbourhoods else (picked.toList() + city.neighbourhoods.filter { it !in picked }).take(18)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { n -> selo.Nachipa01.ui.components.SeloChip(n, n in picked) {
                    picked = if (n in picked) picked - n else if (picked.size < 10) picked + n else { app.fail("Up to 10 neighbourhoods — or switch on Whole city."); picked } } }
            }
            if (city.neighbourhoods.size > 18) TextButton({ showAll = !showAll }) { Text(if (showAll) "Show fewer" else "Show all ${city.neighbourhoods.size}", color = Selo.Red, fontWeight = FontWeight.Bold) }
        }
        selo.Nachipa01.ui.components.PrimaryButton(if (whole) "Save · whole of ${city.name}" else "Save ${picked.size} neighbourhood${if (picked.size == 1) "" else "s"}",
            enabled = loaded && !saving && (whole || picked.isNotEmpty()), loading = saving, whyDisabled = "Choose at least one neighbourhood, or switch on Whole city.") {
            saving = true
            scope.launch { runCatching { app.backend.setRiderAreas(city.id, picked.toList(), whole) }
                .onSuccess { app.ok(if (whole) "You'll get jobs from all of ${city.name}" else "Saved — you'll get jobs from ${picked.size} neighbourhoods") }
                .onFailure { app.fail(it.message ?: "Couldn't save your areas.") }; saving = false }
        }
    }
}
