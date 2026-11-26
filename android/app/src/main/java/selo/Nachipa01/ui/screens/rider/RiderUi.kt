package selo.Nachipa01.ui.screens.rider

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.route.*
import selo.Nachipa01.ui.theme.Selo
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/* ------------------------------------------------------------------ tokens */

internal val Inactive = Color(0xFF999999)
internal val Blue = Color(0xFF2563EB)
internal fun zmw(ngwee: Long) = "ZMW " + "%,.2f".format(Locale.UK, ngwee / 100.0)
internal fun km(v: Double) = if (v < 10) "%.1f km".format(Locale.UK, v) else "${v.toInt()} km"

data class RiderTab(val route: String, val icon: ImageVector, val label: String)
internal val riderTabs = listOf(
    RiderTab("home", Icons.Outlined.Home, "HOME"), RiderTab("jobs", Icons.Outlined.TwoWheeler, "JOBS"),
    RiderTab("earnings", Icons.Outlined.Paid, "EARNINGS"), RiderTab("fund", Icons.Outlined.MonitorHeart, "FUND"),
    RiderTab("more", Icons.Outlined.MoreHoriz, "MORE"),
)

/** PRD 6.1: five flat tabs; active = red icon, label and underline; Jobs carries an amber count badge. */
@Composable
fun RiderTabBar(current: String, jobsBadge: Int, onSelect: (String) -> Unit) {
    Surface(shadowElevation = 10.dp, color = Color.White) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            riderTabs.forEach { t ->
                val on = current == t.route
                val tint = if (on) Selo.Red else Inactive
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t.route) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(.6f).height(3.dp).background(if (on) Selo.Red else Color.Transparent, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)))
                    Spacer(Modifier.height(7.dp))
                    Box {
                        // Social Fund keeps its own purple accent even when inactive, so it never reads as "Earnings"
                        Icon(t.icon, t.label, tint = if (t.route == "fund" && !on) Selo.Purple.copy(alpha = .75f) else tint, modifier = Modifier.size(25.dp))
                        if (t.route == "jobs" && jobsBadge > 0) Text("$jobsBadge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp).background(Selo.Amber, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                    Text(t.label, color = tint, fontSize = 10.5.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, letterSpacing = .3.sp)
                }
            }
        }
    }
}

/** White title bar used across rider modules. */
@Composable
fun RiderTitle(title: String, onBack: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Selo.Ink) } else Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable fun RedHeading(text: String) = Text(text, color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)

@Composable
fun RingFencedBadge() = Text("Ring-fenced — not platform revenue", color = Selo.AmberDark, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
    modifier = Modifier.background(Selo.AmberTint, RoundedCornerShape(50)).border(1.dp, Selo.Amber, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))

@Composable
fun MiniStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = Selo.Ink, sub: String? = null, content: (@Composable () -> Unit)? = null) {
    Column(modifier.background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Selo.Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Selo.Mute, fontSize = 12.sp)
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        if (sub != null) Text(sub, color = Selo.Mute, fontSize = 11.sp, maxLines = 2)
        content?.invoke()
    }
}

/* ------------------------------------------------------------------ route map */

data class MapStop(val at: LatLng, val type: StopType, val number: Int, val done: Boolean = false)

/**
 * Vector route map (no Maps SDK key needed): street grid backdrop, route as a red dashed line through every stop,
 * red pins for pickups, flags for drop-offs, and the rider as a pulsing green dot. [follow] zooms in around the rider
 * for turn-by-turn.
 */
@Composable
fun RouteMap(path: List<LatLng>, stops: List<MapStop>, rider: LatLng?, modifier: Modifier = Modifier, follow: Boolean = false, heading: Double? = null, schematic: Boolean = false,
             traffic: List<TrafficSegment> = emptyList(), etaMin: Int? = null) {
    if (schematic || !selo.Nachipa01.ui.maps.Maps.enabled) { SchematicRouteMap(path, stops, rider, modifier, follow, heading, traffic, etaMin); return }
    val g = { p: LatLng -> com.google.android.gms.maps.model.LatLng(p.lat, p.lng) }
    val open = stops.filter { !it.done }
    val dest = open.maxByOrNull { it.number }?.at ?: path.lastOrNull()
    // Yango-style: ring at the start and end, numbered pins only for stops in between, red ETA bubble over the end,
    // the rider as a car, and the route coloured by live traffic (green free-flowing · yellow slow · red jam).
    val pins = buildList {
        (rider ?: path.firstOrNull())?.let { if (rider == null) add(selo.Nachipa01.ui.maps.MapPin("start", g(it), selo.Nachipa01.ui.maps.PinKind.RING)) }
        stops.filter { it.at != dest || it.done }.forEach { s -> add(selo.Nachipa01.ui.maps.MapPin("${s.type}:${s.number}:${s.at.lat}", g(s.at),
            if (s.done) selo.Nachipa01.ui.maps.PinKind.DONE else if (s.type == StopType.PICKUP) selo.Nachipa01.ui.maps.PinKind.PICKUP else selo.Nachipa01.ui.maps.PinKind.DROPOFF,
            label = s.number.takeIf { it > 0 }?.toString())) }
        dest?.let { add(selo.Nachipa01.ui.maps.MapPin("end", g(it), selo.Nachipa01.ui.maps.PinKind.RING))
            if (etaMin != null) add(selo.Nachipa01.ui.maps.MapPin("eta", g(it), selo.Nachipa01.ui.maps.PinKind.ETA, label = etaMin.coerceAtLeast(1).toString())) }
        rider?.let { add(selo.Nachipa01.ui.maps.MapPin("rider", g(it), selo.Nachipa01.ui.maps.PinKind.CAR, rotation = (heading ?: 0.0).toFloat())) }
    }
    val lines = buildList {
        if (path.size >= 2) add(selo.Nachipa01.ui.maps.MapLine("route", path.map(g), if (traffic.isEmpty()) trafficColor("NORMAL") else android.graphics.Color.rgb(189, 189, 189), 6f))
        traffic.forEachIndexed { i, t -> if (t.path.size >= 2) add(selo.Nachipa01.ui.maps.MapLine("t$i", t.path.map(g), trafficColor(t.speed), 6.5f)) }
    }
    val fitKey = "${stops.size}:${path.size}:${path.firstOrNull()}:${path.lastOrNull()}"
    val camera = if (follow && rider != null) selo.Nachipa01.ui.maps.MapCamera.Follow(g(rider), (heading ?: 0.0).toFloat())
        else selo.Nachipa01.ui.maps.MapCamera.Fit((path.ifEmpty { stops.map { it.at } } + listOfNotNull(rider)).map(g), fitKey, paddingDp = 64)
    selo.Nachipa01.ui.maps.SeloMap(selo.Nachipa01.ui.maps.MapScene(pins, lines, camera), modifier.clip(RoundedCornerShape(if (follow) 0.dp else 14.dp))) {
        SchematicRouteMap(path, stops, rider, modifier, follow, heading, traffic, etaMin)
    }
}

/** Live-traffic colours (match the legend in the route card). */
fun trafficColor(speed: String): Int = when (speed) {
    "TRAFFIC_JAM" -> android.graphics.Color.rgb(229, 72, 77); "SLOW" -> android.graphics.Color.rgb(242, 201, 76); else -> android.graphics.Color.rgb(108, 203, 118)
}

/** Drawn stand-in used for tiny previews (no route yet), tests, and builds without a Maps key. */
@Composable
fun SchematicRouteMap(path: List<LatLng>, stops: List<MapStop>, rider: LatLng?, modifier: Modifier = Modifier, follow: Boolean = false, heading: Double? = null,
                      traffic: List<TrafficSegment> = emptyList(), etaMin: Int? = null) {
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Canvas(modifier.clip(RoundedCornerShape(if (follow) 0.dp else 14.dp)).background(Color(0xFFEFF1EC))) {
        val pts = if (follow && rider != null) listOf(LatLng(rider.lat - 0.004, rider.lng - 0.004), LatLng(rider.lat + 0.004, rider.lng + 0.004))
            else (path + stops.map { it.at } + listOfNotNull(rider)).ifEmpty { listOf(LatLng(-15.41, 28.31)) }
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }; val minLng = pts.minOf { it.lng }; val maxLng = pts.maxOf { it.lng }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = max((maxLng - minLng) * k, 0.002); val spanY = max(maxLat - minLat, 0.002)
        val pad = 28.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val cx = (minLng + maxLng) / 2; val cy = (minLat + maxLat) / 2
        fun o(p: LatLng) = Offset(size.width / 2 + ((p.lng - cx) * k * scale).toFloat(), size.height / 2 - ((p.lat - cy) * scale).toFloat())

        // street grid backdrop
        val step = 34.dp.toPx()
        var x = -((cx * k * scale) % step).toFloat(); while (x < size.width) { drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 6f); x += step }
        var y = ((cy * scale) % step).toFloat(); while (y < size.height) { drawLine(Color.White, Offset(0f, y), Offset(size.width, y), 6f); y += step }
        drawLine(Color(0xFFFFE8A3), Offset(0f, size.height * .62f), Offset(size.width, size.height * .48f), 14f)

        if (path.size >= 2) {
            val p = Path().apply { moveTo(o(path[0]).x, o(path[0]).y); path.drop(1).forEach { lineTo(o(it).x, o(it).y) } }
            drawPath(p, Color.White, style = Stroke(11.dp.toPx() / 2, cap = StrokeCap.Round))
            drawPath(p, if (traffic.isEmpty()) Color(trafficColor("NORMAL")) else Color(0xFFBDBDBD), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        }
        traffic.forEach { t -> if (t.path.size >= 2) drawPath(Path().apply { moveTo(o(t.path[0]).x, o(t.path[0]).y); t.path.drop(1).forEach { lineTo(o(it).x, o(it).y) } },
            Color(trafficColor(t.speed)), style = Stroke(5.5.dp.toPx(), cap = StrokeCap.Round)) }
        if (etaMin != null) path.lastOrNull()?.let { e -> val c = o(e)
            drawRoundRect(Color(0xFFE5322D), Offset(c.x - 22.dp.toPx(), c.y - 58.dp.toPx()), androidx.compose.ui.geometry.Size(44.dp.toPx(), 44.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText("$etaMin min", c.x, c.y - 30.dp.toPx(), android.graphics.Paint().apply { color = android.graphics.Color.WHITE
                textAlign = android.graphics.Paint.Align.CENTER; textSize = 13.dp.toPx(); isFakeBoldText = true; isAntiAlias = true }) }
        stops.forEach { s -> if (s.type == StopType.PICKUP) pin(o(s.at), s.done) else flag(o(s.at), s.done) }
        rider?.let { r ->
            val c = o(r)
            drawCircle(Selo.Green.copy(alpha = .25f * pulse.value), 22.dp.toPx() * pulse.value, c)
            drawCircle(Color.White, 10.dp.toPx(), c); drawCircle(Selo.Green, 7.dp.toPx(), c)
            heading?.let { h ->
                val a = Math.toRadians(h - 90); val tip = Offset(c.x + (cos(a) * 16.dp.toPx()).toFloat(), c.y + (kotlin.math.sin(a) * 16.dp.toPx()).toFloat())
                drawLine(Selo.Green, c, tip, 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.pin(c: Offset, done: Boolean) {
    val col = if (done) Selo.Grey else Selo.Red
    drawCircle(col, 9.dp.toPx(), Offset(c.x, c.y - 14.dp.toPx()))
    drawPath(Path().apply { moveTo(c.x - 7.dp.toPx(), c.y - 11.dp.toPx()); lineTo(c.x + 7.dp.toPx(), c.y - 11.dp.toPx()); lineTo(c.x, c.y); close() }, col)
    drawCircle(Color.White, 3.5.dp.toPx(), Offset(c.x, c.y - 14.dp.toPx()))
}
private fun DrawScope.flag(c: Offset, done: Boolean) {
    val col = if (done) Selo.Grey else Selo.Ink
    drawLine(col, c, Offset(c.x, c.y - 24.dp.toPx()), 2.5.dp.toPx())
    drawPath(Path().apply { moveTo(c.x, c.y - 24.dp.toPx()); lineTo(c.x + 15.dp.toPx(), c.y - 19.dp.toPx()); lineTo(c.x, c.y - 13.dp.toPx()); close() },
        if (done) Selo.Grey else Selo.Green)
}

/* ------------------------------------------------------------------ device helpers */

/** Takes a full-resolution photo with the system camera (proof of delivery, claims, incidents). */
@Composable
fun rememberPhotoCapture(onPhoto: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> pending?.let { if (ok) onPhoto(it) } }
    return {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        pending = uri
        runCatching { launcher.launch(uri) }
    }
}

@Composable
fun rememberGalleryPick(onPhoto: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(onPhoto) }
    return { runCatching { launcher.launch("image/*") } }
}

/**
 * Rider position. Live builds use the GPS (falling back to network location); demo builds and tests drive the
 * rider along [simulatePath] so turn-by-turn can be seen end to end.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberRiderPosition(simulate: Boolean, simulatePath: List<LatLng>, start: LatLng?, metresPerTick: Double = 45.0): State<Pair<LatLng?, Double?>> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Pair<LatLng?, Double?>>(start to null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.any { it } }
    if (simulate) {
        LaunchedEffect(simulatePath) {
            if (simulatePath.size < 2) return@LaunchedEffect
            var seg = 0; var pos = simulatePath[0]; state.value = pos to null
            while (isActive && seg < simulatePath.size - 1) {
                delay(1000)
                var budget = metresPerTick
                while (budget > 0 && seg < simulatePath.size - 1) {
                    val next = simulatePath[seg + 1]; val d = Geo.m(pos, next)
                    if (d <= budget) { budget -= d; pos = next; seg++ } else { pos = Geo.lerp(pos, next, budget / d); budget = 0.0 }
                }
                state.value = pos to (if (seg < simulatePath.size - 1) Geo.bearing(pos, simulatePath[seg + 1]) else null)
            }
        }
    } else {
        LaunchedEffect(Unit) { if (!granted) runCatching { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } }
        DisposableEffect(granted) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val l = LocationListener { loc: Location -> state.value = LatLng(loc.latitude, loc.longitude) to (if (loc.hasBearing()) loc.bearing.toDouble() else null) }
            if (granted) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }.forEach { p ->
                    runCatching { lm.getLastKnownLocation(p)?.let { loc -> if (state.value.first == null || state.value.first == start) state.value = LatLng(loc.latitude, loc.longitude) to null } }
                    runCatching { lm.requestLocationUpdates(p, 1000L, 3f, l) }
                }
            }
            onDispose { runCatching { lm.removeUpdates(l) } }
        }
    }
    return state
}

/** Voice prompts for turn-by-turn (Android text-to-speech, English, Zambia locale when available). */
@Composable
fun rememberVoice(): (String) -> Unit {
    val ctx = LocalContext.current
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { status -> if (status == TextToSpeech.SUCCESS) { t?.language = Locale("en", "ZM").takeIf { t?.isLanguageAvailable(it) ?: -1 >= 0 } ?: Locale.UK; tts.value = t } }
        onDispose { t.stop(); t.shutdown() }
    }
    return { text -> tts.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav") }
}

internal fun maneuverIcon(m: String): ImageVector = when (m) {
    "turn-left", "sharp-left" -> Icons.Outlined.TurnLeft; "turn-right", "sharp-right" -> Icons.Outlined.TurnRight
    "slight-left", "fork-left" -> Icons.Outlined.TurnSlightLeft; "slight-right", "fork-right" -> Icons.Outlined.TurnSlightRight
    "uturn" -> Icons.Outlined.UTurnLeft; "roundabout" -> Icons.Outlined.RoundaboutRight; "merge" -> Icons.Outlined.Merge
    "arrive" -> Icons.Outlined.Flag; else -> Icons.Outlined.Straight
}

internal fun dist(m: Int) = if (m >= 1000) "%.1f km".format(Locale.UK, m / 1000.0) else "${(m / 10) * 10} m"
internal fun clock(epochMs: Long) = java.text.SimpleDateFormat("HH:mm", Locale.UK).format(java.util.Date(epochMs))
internal fun walletBadge(m: String): String {
    val op = selo.Nachipa01.domain.Operator.detect(m)?.label?.substringBefore(" ") ?: "Wallet"
    return "$op ●●●●${m.takeLast(4)}"
}
@Suppress("unused") internal fun money(n: Long) = Fees.formatK(n)
