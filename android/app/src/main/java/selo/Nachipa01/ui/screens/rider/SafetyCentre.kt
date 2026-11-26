package selo.Nachipa01.ui.screens.rider

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Lesson
import selo.Nachipa01.data.model.RiderIdCard
import selo.Nachipa01.data.model.SafetyStatus
import selo.Nachipa01.data.model.SosResult
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

val SAFETY_LESSONS = listOf(
    Lesson("helmet", "Helmet & visibility", 2, listOf("Wear a fastened helmet on every trip — and carry one for passengers you never take.",
        "Reflective vest after 17:30 and in rain.", "Headlight on, day and night.")),
    Lesson("road", "Defensive riding in town", 3, listOf("Assume minibuses will stop or pull out without warning.", "Keep two seconds behind the vehicle ahead; more in rain.",
        "Never ride between two moving trucks or buses.", "No phone in your hand while moving — the app reads turns aloud.")),
    Lesson("night", "Riding at night", 2, listOf("Stick to lit main roads even if the route is longer.", "Tell your emergency contact when you start a late shift.",
        "If a drop-off point is dark and empty, call the buyer to meet you somewhere lit.")),
    Lesson("robbery", "If you're threatened", 2, listOf("Your life is worth more than any parcel — hand it over, don't fight.",
        "Press SOS as soon as it's safe. Your contact and SeloNaChipa get your live location.", "Report the incident in the app; goods taken by force are covered by the Social Fund claim process.")),
    Lesson("cash", "Never carry cash", 1, listOf("All SeloNaChipa payments are mobile money in escrow — never accept cash for an order.",
        "Never share the delivery code yourself; only the receiver gives it to you.")),
    Lesson("weather", "Rain season", 2, listOf("Brake earlier — painted lines and potholes are slippery.", "Wrap parcels in the waterproof bag.", "Pause if you can't see the road; tell the buyer in chat.")),
    Lesson("first-aid", "Basic first aid", 3, listOf("After a crash, don't remove a rider's helmet unless they can't breathe.", "Press firmly on bleeding with a clean cloth.",
        "Ambulance 991 · Police 999 · Fire 993.")),
)

/**
 * Rider Safety Centre: SOS (texts the emergency contact a live-location link and alerts operations), incident location
 * sharing, verified rider ID with QR, delivery evidence, optional insurance cover and short safety lessons.
 */
@Composable
fun SafetyCentreScreen(back: () -> Unit, open: (String) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<SafetyStatus?>(null) }
    var id by remember { mutableStateOf<RiderIdCard?>(null) }
    var showId by remember { mutableStateOf(false) }
    var lesson by remember { mutableStateOf<Lesson?>(null) }
    var confirmSos by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<String?>(null) }
    var alert by remember { mutableStateOf<SosResult?>(null) }
    val locator = selo.Nachipa01.ui.components.rememberLocator()
    LaunchedEffect(Unit) {
        runCatching { app.backend.safetyStatus() }.onSuccess { st = it }.onFailure { app.fail(it.message ?: "Couldn't load the Safety Centre.") }
        runCatching { app.backend.riderIdCard() }.onSuccess { id = it }
    }
    fun send(kind: String) {
        sending = kind
        locator.locate { fix, problem ->
            if (fix == null) { sending = null; app.fail("$problem If you're in danger call 999 now."); return@locate }
            scope.launch {
                runCatching { app.backend.sendSos(fix.lat, fix.lng, fix.accuracyM, kind) }.onSuccess { r ->
                    alert = r; st = st?.copy(openAlertId = r.alertId, openAlertUrl = r.shareUrl)
                    if (kind == "SOS") app.ok(if (r.contactNotified) "SOS sent · ${r.contactName ?: "your emergency contact"} has your live location" else "SOS sent to SeloNaChipa operations")
                    else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "My live location (SeloNaChipa rider): ${r.shareUrl}\nMap: ${r.mapsUrl}"), "Share my location"))
                }.onFailure { app.fail((it.message ?: "Couldn't send.") + " If you're in danger call 999 now.") }
                sending = null
            }
        }
    }
    lesson?.let { l -> LessonScreen(l, done = l.id in (st?.lessons ?: emptySet()), onBack = { lesson = null }) {
        scope.launch { runCatching { app.backend.completeLesson(l.id) }.onSuccess { st = it; app.ok("Lesson complete") }; lesson = null } }; return }
    if (showId) { id?.let { RiderIdScreen(it) { showId = false } } ?: run { showId = false }; return }

    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        RiderTitle("Safety Centre", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // SOS
            val openUrl = alert?.shareUrl ?: st?.openAlertUrl
            Column(Modifier.fillMaxWidth().background(if (openUrl != null) Selo.Red else Color.White, RoundedCornerShape(18.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (openUrl == null) {
                    Box(Modifier.size(132.dp).clip(CircleShape).background(Selo.Red).border(8.dp, Selo.RedTint, CircleShape).clickable { confirmSos = true }, contentAlignment = Alignment.Center) {
                        if (sending == "SOS") CircularProgressIndicator(color = Color.White) else Text("SOS", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Emergency SOS", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text(st?.emergencyPhone?.let { "Texts ${st?.emergencyName ?: "your contact"} your live location and alerts SeloNaChipa." }
                        ?: "Alerts SeloNaChipa operations with your live location. Add an emergency contact so they're told too.",
                        color = Selo.Mute, fontSize = 13.sp, textAlign = TextAlign.Center)
                } else {
                    Icon(Icons.Outlined.Sos, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Text("Alert active — help is being told", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text("Your live location keeps updating while the alert is open.", color = Color.White.copy(.9f), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:999"))) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) { Text("Call 999", color = Selo.Red, fontWeight = FontWeight.Bold) }
                        Button({ val aid = alert?.alertId ?: st?.openAlertId ?: return@Button
                            scope.launch { runCatching { app.backend.closeSos(aid, safe = true) }.onSuccess { alert = null; st = st?.copy(openAlertId = null, openAlertUrl = null); app.ok("Marked safe. We've let your contact know.") } } },
                            colors = ButtonDefaults.buttonColors(containerColor = Selo.GreenDark)) { Text("I'm safe", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            // contacts + share location
            SeloCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ContactPhone, null, tint = Selo.Red)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Emergency contact", fontWeight = FontWeight.Bold)
                        Text(st?.emergencyPhone?.let { "${st?.emergencyName ?: ""} · $it" } ?: "Not set — add one in your rider profile", color = if (st?.emergencyPhone == null) Selo.Red else Selo.Mute, fontSize = 12.sp)
                    }
                    TextButton({ open("profile") }) { Text(if (st?.emergencyPhone == null) "Add" else "Change", color = Selo.Red, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = Selo.Line)
                Row(Modifier.fillMaxWidth().clickable { if (sending == null) send("SHARE") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ShareLocation, null, tint = Selo.Red)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) { Text("Share my incident location", fontWeight = FontWeight.Bold)
                        Text("Live link for police, family or a buyer — no SMS sent", color = Selo.Mute, fontSize = 12.sp) }
                    if (sending == "SHARE") CircularProgressIndicator(Modifier.size(18.dp), color = Selo.Red, strokeWidth = 2.dp) else Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Mute)
                }
                HorizontalDivider(color = Selo.Line)
                Row(Modifier.fillMaxWidth().clickable { open("incident") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ReportProblem, null, tint = Selo.Red); Text("  Report an incident", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Mute)
                }
            }
            // verified ID
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).clickable { if (id != null) showId = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Badge, null, tint = Selo.Red)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Verified rider ID", fontWeight = FontWeight.Bold)
                    Text(id?.let { "${it.code} · ${if (it.verified) "verified" else "verification pending"} · show the QR at pickup" } ?: "Loading…", color = Selo.Mute, fontSize = 12.sp)
                }
                if (id?.verified == true) StatusChip("Verified", Tone.OK)
            }
            // evidence
            SeloCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhotoCamera, null, tint = Selo.Red)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) { Text("Delivery evidence", fontWeight = FontWeight.Bold)
                        Text("${st?.evidenceCount ?: 0} geotagged photos on file. Take one at pickup and at the door — it protects you in disputes.", color = Selo.Mute, fontSize = 12.sp) }
                }
            }
            // insurance
            st?.let { s -> SeloCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.HealthAndSafety, null, tint = Selo.Red)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Rider accident cover (optional)", fontWeight = FontWeight.Bold)
                        Text("${Fees.formatK(s.premiumNgwee)} per completed delivery · up to ${Fees.formatK(s.coverNgwee)} medical and accident cover", color = Selo.Mute, fontSize = 12.sp)
                    }
                    Switch(s.insurance, { on -> scope.launch { runCatching { app.backend.setRiderInsurance(on) }.onSuccess { st = it; app.ok(if (on) "Cover is on from your next delivery" else "Cover switched off") }
                        .onFailure { app.fail(it.message ?: "Couldn't change your cover.") } } }, colors = SwitchDefaults.colors(checkedTrackColor = Selo.Green))
                }
                if (s.insurance) Sub("The premium comes off each delivery's earnings and shows in your statement. Claims go through Social Fund → File a claim.")
            } }
            // lessons
            val done = st?.lessons ?: emptySet()
            SectionLabel("Safety lessons · ${done.size}/${SAFETY_LESSONS.size} done")
            Column(Modifier.background(Color.White, RoundedCornerShape(14.dp))) {
                SAFETY_LESSONS.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider(color = Selo.Line)
                    Row(Modifier.fillMaxWidth().clickable { lesson = l }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (l.id in done) Icons.Outlined.CheckCircle else Icons.Outlined.School, null, tint = if (l.id in done) Selo.Green else Selo.Red)
                        Text(l.title, Modifier.padding(start = 12.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("${l.minutes} min", color = Selo.Mute, fontSize = 12.sp)
                    }
                }
            }
            Sub("Emergency numbers: Police 999 · Ambulance 991 · Fire 993 · SeloNaChipa +260 978 070 734")
            Spacer(Modifier.height(20.dp))
        }
    }
    if (confirmSos) AlertDialog({ confirmSos = false }, title = { Text("Send SOS?") },
        text = { Text("We'll send your live location to ${st?.emergencyName ?: "SeloNaChipa operations"}${if (st?.emergencyPhone != null) " and SeloNaChipa operations" else ""}.") },
        confirmButton = { Button({ confirmSos = false; send("SOS") }, colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Send SOS", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmSos = false }) { Text("Cancel") } })
}

@Composable
private fun LessonScreen(l: Lesson, done: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        RiderTitle(l.title, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            l.points.forEachIndexed { i, p -> Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.background(Selo.Red, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp))
                Text(p, Modifier.padding(start = 12.dp), fontSize = 15.sp, lineHeight = 21.sp) } }
        }
        Column(Modifier.background(Color.White).padding(14.dp)) { PrimaryButton(if (done) "Done ✓ — back" else "I've read this", onClick = if (done) onBack else onDone) }
    }
}

/** Full-screen verified ID card with the QR code customers scan to check the rider. */
@Composable
private fun RiderIdScreen(c: RiderIdCard, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        RiderTitle("Verified rider ID", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).border(2.dp, if (c.verified) Selo.Green else Selo.Amber, RoundedCornerShape(20.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SeloNaChipa", color = Selo.Red, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                selo.Nachipa01.ui.components.Avatar(c.photoUrl, c.name, 96.dp)
                Text(c.name, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
                StatusChip(if (c.verified) "✔ Verified rider" else "Verification pending", if (c.verified) Tone.OK else Tone.WARN)
                Spacer(Modifier.height(8.dp))
                KvRow("Rider ID", c.code, mono = true); KvRow("Plate", c.plate.ifBlank { "—" }); KvRow("Vehicle", c.vehicle.ifBlank { "—" }); KvRow("Rider since", c.memberSince)
                Spacer(Modifier.height(10.dp))
                QrCode(c.verifyUrl, Modifier.size(200.dp))
                Text("Scan to check this rider on selonachipa", color = Selo.Mute, fontSize = 12.sp)
            }
            Sub("Show this at pickup and drop-off. Sellers and buyers should never hand goods to someone whose QR doesn't open a green \"Verified\" page.")
        }
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier) {
    val m = remember(text) { runCatching { com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() }
    Canvas(modifier.background(Color.White)) {
        if (m == null) return@Canvas
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

/** Geotagged delivery photo (pickup or drop-off), used on the active delivery screen. */
@Composable
fun EvidenceButton(kind: String, id: String, stage: String, modifier: Modifier = Modifier) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var count by remember(id, stage) { mutableIntStateOf(0) }
    val pick = rememberPhotoSource(if (stage == "pickup") "Photo at pickup" else "Photo at the door") { uri ->
        busy = true
        scope.launch {
            val at = selo.Nachipa01.ui.components.LastFix.fresh()
            runCatching { app.backend.addDeliveryEvidence(kind, id, uri, stage, at?.first, at?.second) }
                .onSuccess { count++; app.ok("Evidence saved with time and location") }.onFailure { app.fail(it.message ?: "Couldn't save the photo.") }
            busy = false
        }
    }
    OutlinedButton({ if (!busy) pick() }, modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(50)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Selo.Red, strokeWidth = 2.dp)
        else Icon(if (count > 0) Icons.Outlined.CheckCircle else Icons.Outlined.PhotoCamera, null, tint = if (count > 0) Selo.Green else Selo.Red, modifier = Modifier.size(18.dp))
        Text(if (count > 0) " Evidence ($count)" else " Photo evidence", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
