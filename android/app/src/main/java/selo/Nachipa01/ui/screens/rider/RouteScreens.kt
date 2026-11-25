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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.route.*
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import kotlin.math.roundToInt

/* =================================================================== route controller */

/**
 * Keeps the rider's combined route: geocodes stops, orders them (pickups before their drop-offs), fetches road
 * directions, follows the rider's position, and re-routes when they leave the route or finish a stop.
 */
class RouteController {
    var coords by mutableStateOf<Map<String, Pair<LatLng?, LatLng?>>>(emptyMap())
    var plan by mutableStateOf<PlannedRoute?>(null)
    var directions by mutableStateOf<Directions?>(null)
    var position by mutableStateOf<LatLng?>(null)
    var heading by mutableStateOf<Double?>(null)
    var nav by mutableStateOf<NavState?>(null)
    var rerouting by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    internal var reroute by mutableIntStateOf(0)
    val nextStop: Stop? get() = plan?.stops?.firstOrNull()
    val remainingM: Int get() = nav?.remainingM ?: ((plan?.totalKm ?: 0.0) * 1000).roundToInt()
    val remainingS: Int get() = nav?.remainingS ?: ((plan?.etaMin ?: 0) * 60)
    fun stopsFor(active: List<Order>): List<Stop> = active.flatMap { o ->
        val c = coords[o.id]
        listOf(Stop(o.id, StopType.PICKUP, o.sellerName, o.pickup, c?.first ?: o.pickupLat?.let { LatLng(it, o.pickupLng ?: 0.0) }, done = o.state != OrderState.RIDER_ASSIGNED),
            Stop(o.id, StopType.DROPOFF, o.buyerName.ifBlank { "Buyer" }, o.dropoff, c?.second ?: o.dropLat?.let { LatLng(it, o.dropLng ?: 0.0) }))
    }
}

@Composable
fun rememberRouteController(active: List<Order>, vehicle: String): RouteController {
    val app = LocalAppState.current
    val rc = remember { RouteController() }
    val demo = app.backend as? DemoBackend
    val ids = active.map { it.id }
    val progressKey = active.joinToString { "${it.id}:${it.state}" }
    LaunchedEffect(ids) { if (ids.isNotEmpty()) rc.coords = runCatching { app.backend.locateStops(ids) }.getOrDefault(emptyMap()) }

    val pos by rememberRiderPosition(simulate = app.backend.isDemo, simulatePath = rc.directions?.path ?: emptyList(), start = demo?.rider?.startAt)
    LaunchedEffect(pos) { pos.first?.let { rc.position = it }; rc.heading = pos.second }

    // plan + directions whenever the set of stops changes, or after leaving the route
    LaunchedEffect(progressKey, rc.coords, rc.reroute, rc.position == null) {
        if (active.isEmpty()) { rc.plan = null; rc.directions = null; rc.nav = null; return@LaunchedEffect }
        val origin = rc.position ?: demo?.rider?.startAt ?: rc.stopsFor(active).firstNotNullOfOrNull { it.at } ?: return@LaunchedEffect
        val plan = RoutePlanner.plan(origin, rc.stopsFor(active), vehicle)
        rc.plan = plan
        rc.rerouting = true
        rc.directions = runCatching { app.backend.directions(origin, plan.stops, vehicle) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                rc.note = "Road directions unavailable — showing straight-line guidance. Use Google Maps for the roads." }
            .onSuccess { rc.note = null }
            .getOrElse { NavigationEngine.straightLine(origin, plan.stops, vehicle) }
            .let { d -> d.copy(steps = d.steps.map { s -> if (s.maneuver == "arrive") s.copy(instruction = plan.stops.getOrNull(s.legIndex)?.let { st ->
                (if (st.type == StopType.PICKUP) "Arrive at pickup: " else "Arrive at drop-off: ") + st.label } ?: s.instruction) else s }) }
        rc.nav = null; rc.rerouting = false
    }
    // progress along the route
    LaunchedEffect(rc.position, rc.directions) {
        val d = rc.directions ?: return@LaunchedEffect; val p = rc.position ?: return@LaunchedEffect
        val st = NavigationEngine.progress(d, p, rc.nav?.stepIndex ?: 0)
        rc.nav = st
        if (st.offRoute && !rc.rerouting && d.source != "straight_line") { rc.rerouting = true; rc.reroute++ }
    }
    return rc
}

private fun mapStops(plan: PlannedRoute?, all: List<Stop>): List<MapStop> {
    val order = plan?.stops.orEmpty()
    return all.filter { it.at != null }.map { s -> MapStop(s.at!!, s.type, order.indexOfFirst { it.id == s.id } + 1, s.done) }
}

/* =================================================================== DISPATCH CARD (6.4) */

@Composable
fun DispatchOverlay(o: Order, active: List<Order>, profile: RiderProfile?, rc: RouteController, onDone: (accepted: Boolean) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val total = cfg.dispatchCountdownSec
    var left by remember(o.id) { mutableFloatStateOf(total.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val demo = app.backend as? DemoBackend
    LaunchedEffect(o.id) {
        while (left > 0f) { delay(100); left -= 0.1f }
        runCatching { app.backend.declineJob(o.id) }; onDone(false)  // timed out: passes to the next rider
    }
    val split = Fees.split(o.goodsNgwee, o.deliveryNgwee, cfg)
    val pickup = o.pickupLat?.let { LatLng(it, o.pickupLng ?: 0.0) } ?: demo?.rider?.placeOf(o.pickup)
    val drop = o.dropLat?.let { LatLng(it, o.dropLng ?: 0.0) } ?: demo?.rider?.placeOf(o.dropoff)
    val me = rc.position ?: demo?.rider?.startAt
    val away = if (me != null && pickup != null) Geo.km(me, pickup) * Geo.ROAD_FACTOR else null
    val adds = if (active.isNotEmpty() && me != null && pickup != null && drop != null)
        RoutePlanner.insertionKm(me, rc.stopsFor(active), Stop(o.id, StopType.PICKUP, o.sellerName, o.pickup, pickup), Stop(o.id, StopType.DROPOFF, "", o.dropoff, drop), profile?.vehicle ?: "Motorbike")
        else null

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(8.dp).background(Color(0xFFF7D5DB))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((left / total).coerceIn(0f, 1f)).background(Selo.Red))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${left.toInt().coerceAtLeast(0) + if (left % 1 > 0) 1 else 0} seconds to decide", color = Selo.Red, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (active.isNotEmpty()) Text("Combine with your ${active.size} active ${if (active.size == 1) "delivery" else "deliveries"}" +
                    (adds?.let { " · adds ${km(it)} to your route" } ?: ""), color = Selo.Teal, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Selo.TealTint, RoundedCornerShape(10.dp)).padding(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.sellerName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text(o.pickup, color = Selo.Mute, fontSize = 13.sp)
                        away?.let { Text("${km(it)} away", color = Selo.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                    RouteMap(listOfNotNull(me, pickup, drop), listOfNotNull(pickup?.let { MapStop(it, StopType.PICKUP, 1) }, drop?.let { MapStop(it, StopType.DROPOFF, 2) }), me,
                        Modifier.size(120.dp, 96.dp))
                }
                Column(Modifier.fillMaxWidth().background(Selo.Surface, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Place, null, tint = Selo.Red); Text("  ${o.pickup}", fontWeight = FontWeight.Bold) }
                    Box(Modifier.padding(start = 11.dp).width(2.dp).height(14.dp).background(Selo.Line))
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Flag, null, tint = Selo.Ink); Text("  ${o.dropoff}", fontWeight = FontWeight.Bold) }
                    Text("Total route ${km(o.distanceKm)}", color = Selo.Mute, fontSize = 13.sp)
                }
                Text(o.items.joinToString(" · ") { "${it.title}${if (it.qty > 1) " × ${it.qty}" else ""}" }, fontSize = 14.sp)
                HorizontalDivider(color = Selo.Line)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Delivery fee", color = Selo.Mute, modifier = Modifier.weight(1f))
                    Text(zmw(o.deliveryNgwee), color = Selo.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text("Social Fund contribution: ${zmw(split.socialFundNgwee)}", color = Selo.Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("You keep ${zmw(split.riderNetNgwee)} after the ${cfg.riderPlatformFeePct.toInt()}% platform fee and ${cfg.socialFundPct.toInt()}% Social Fund.", color = Selo.Mute, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = Selo.GreenDark, modifier = Modifier.size(16.dp))
                    Text("  Buyer has paid — funds in escrow.", color = Selo.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                ErrorText(error)
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ scope.launch { runCatching { app.backend.declineJob(o.id) }; onDone(false) } }, Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Selo.Red)) { Text("Decline", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Button({ busy = true; scope.launch { runCatching { app.backend.acceptJob(o.id) }.onSuccess { onDone(true) }.onFailure { error = it.message ?: "Order already taken."; busy = false } } },
                    Modifier.weight(2f).height(56.dp), enabled = !busy, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Green)) {
                    Icon(Icons.Outlined.Check, null); Text("  Accept job", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                }
            }
        }
    }
}

/* =================================================================== ACTIVE DELIVERY (6.5) */

@Composable
fun ActiveDeliveryScreen(active: List<Order>, profile: RiderProfile?, rc: RouteController, onNavigate: () -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val cfg by app.config.collectAsState()
    val scope = rememberCoroutineScope()
    val all = rc.stopsFor(active)
    val next = rc.nextStop ?: all.firstOrNull { !it.done }
    val order = active.firstOrNull { it.id == next?.orderId } ?: active.first()
    var confirming by remember { mutableStateOf<Order?>(null) }
    var rating by remember { mutableStateOf<Order?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fee = active.sumOf { Fees.split(it.goodsNgwee, it.deliveryNgwee, cfg).riderNetNgwee }

    Column(Modifier.fillMaxSize()) {
        RiderTitle(if (active.size > 1) "Route · ${active.size} orders" else "Active delivery", trailing = {
            TextButton({ openGoogleMaps(ctx, rc) }) { Icon(Icons.Outlined.Map, null, tint = Selo.Red, modifier = Modifier.size(18.dp)); Text(" Google Maps", color = Selo.Red, fontSize = 13.sp) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                RouteMap(rc.directions?.path ?: emptyList(), mapStops(rc.plan, all), rc.position, Modifier.fillMaxWidth().height(200.dp), heading = rc.heading)
                Button(onNavigate, Modifier.align(Alignment.BottomEnd).padding(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Ink),
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Outlined.Navigation, null, modifier = Modifier.size(18.dp)); Text("  Start navigation", fontWeight = FontWeight.Bold) }
                if (app.backend.isDemo) Text("Demo · simulated GPS", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Selo.Ink.copy(.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            rc.note?.let { Text(it, color = Selo.AmberDark, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Remaining", "%.1f km".format(rc.remainingM / 1000.0), Modifier.weight(1f))
                Tile("Arrival", clock(System.currentTimeMillis() + rc.remainingS * 1000L), Modifier.weight(1f))
                Tile("Fee", zmw(fee), Modifier.weight(1f), Selo.Green)
            }
            // next stop card
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Selo.Line, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val atPickup = next?.type == StopType.PICKUP
                Text(if (atPickup) "NEXT: COLLECT" else "NEXT: DELIVER", color = if (atPickup) Selo.Red else Selo.Green, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                if (atPickup) {
                    Text(order.pickup, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(order.sellerName, fontSize = 14.sp)
                    Text("Show order ${order.id} to the seller.", fontSize = 14.sp, color = Selo.Mute)
                } else {
                    Text(order.dropoff, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(order.buyerName.ifBlank { "Buyer" }, fontSize = 14.sp)
                    if (order.buyerMsisdn.isNotBlank()) Text(order.buyerMsisdn.replace("+260", "0"),
                        fontSize = 14.sp, color = Selo.Red, modifier = Modifier.clickable { call(ctx, order.buyerMsisdn) })
                }
                Text(order.itemsSummary.ifBlank { order.items.joinToString { "${it.qty} × ${it.title}" } }, fontSize = 14.sp, color = Selo.Mute)
                Spacer(Modifier.height(6.dp))
                StopTimeline(rc.plan, all)
            }
            ErrorText(error)
            Spacer(Modifier.height(64.dp))
        }
    }
    // persistent action buttons
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (next?.type == StopType.PICKUP) {
                OutlinedButton({ /* chat with seller lives in Messages */ }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red), colors = ButtonDefaults.outlinedButtonColors(contentColor = Selo.Red, containerColor = Color.White)) {
                    Icon(Icons.Outlined.Chat, null, modifier = Modifier.size(18.dp)); Text(" Seller", fontWeight = FontWeight.Bold) }
                Button({ busy = true; scope.launch { runCatching { app.backend.markCollected(order.id) }.onFailure { error = it.message }; busy = false } },
                    Modifier.weight(2f).height(52.dp), enabled = !busy, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) {
                    Icon(Icons.Outlined.Inventory2, null); Text("  Mark collected", fontWeight = FontWeight.ExtraBold) }
            } else {
                OutlinedButton({ call(ctx, order.buyerMsisdn) }, Modifier.weight(1.1f).height(52.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red), colors = ButtonDefaults.outlinedButtonColors(contentColor = Selo.Red, containerColor = Color.White)) {
                    Icon(Icons.Outlined.Call, null, modifier = Modifier.size(18.dp)); Text(" Call buyer", fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp) }
                Button({ confirming = order }, Modifier.weight(2f).height(52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Green)) {
                    Icon(Icons.Outlined.CheckCircle, null); Text("  Confirm delivery", fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
    confirming?.let { o -> ConfirmDeliveryDialog(o, onDismiss = { confirming = null }, onDelivered = { confirming = null; rating = o }) }
    rating?.let { o -> RateBuyerDialog(o) { rating = null } }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, color: Color = Selo.Ink) {
    Column(modifier.background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Selo.Line, RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = color, maxLines = 1)
        Text(label, color = Selo.Mute, fontSize = 11.sp)
    }
}

/** Mini timeline across every order on the route, in planned order: Pickup 1 ✓ → Pickup 2 → Deliver 1 → Deliver 2. */
@Composable
fun StopTimeline(plan: PlannedRoute?, all: List<Stop>) {
    val done = all.filter { it.done }
    val ordered = done + (plan?.stops ?: all.filter { !it.done })
    val nums = mutableMapOf<String, Int>()
    ordered.forEachIndexed { i, s ->
        val n = nums.getOrPut(s.orderId) { nums.size + 1 }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
            Box(Modifier.size(22.dp).background(if (s.done) Selo.Green else if (i == done.size) (if (s.type == StopType.PICKUP) Selo.Red else Selo.Ink) else Selo.Line, CircleShape),
                contentAlignment = Alignment.Center) {
                if (s.done) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                else Icon(if (s.type == StopType.PICKUP) Icons.Outlined.Place else Icons.Outlined.Flag, null, tint = if (i == done.size) Color.White else Selo.Mute, modifier = Modifier.size(13.dp))
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("${if (s.type == StopType.PICKUP) "Pickup" else "Deliver"} $n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (s.done) Selo.Mute else Selo.Ink)
                Text(s.address, fontSize = 12.sp, color = Selo.Mute, maxLines = 1)
            }
            if (i == done.size) Text("NEXT", fontSize = 10.sp, color = Selo.Red, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** Proof-of-delivery photo + the buyer's in-app code, then "Mark as delivered?" — releases escrow. */
@Composable
fun ConfirmDeliveryDialog(o: Order, onDismiss: () -> Unit, onDelivered: () -> Unit, initialPhoto: Uri? = null) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    var photo by remember { mutableStateOf(initialPhoto) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val take = rememberPhotoCapture { photo = it }
    val pick = rememberGalleryPick { photo = it }
    LaunchedEffect(Unit) { if (photo == null && !app.backend.isDemo) take() }
    val split = Fees.split(o.goodsNgwee, o.deliveryNgwee, cfg)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mark as delivered?", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)).background(Selo.Surface).clickable { take() }, contentAlignment = Alignment.Center) {
                    if (photo != null) AsyncImage(photo, "Proof of delivery", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.PhotoCamera, null, tint = Selo.Red, modifier = Modifier.size(36.dp))
                        Text("Take a proof-of-delivery photo", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                if (photo != null) Row { TextButton(take) { Text("Retake", color = Selo.Red) }; TextButton(pick) { Text("Choose another", color = Selo.Red) } }
                else TextButton(pick) { Text("No camera? Choose a photo", color = Selo.Red, fontSize = 12.sp) }
                SeloField("Buyer's 6-digit delivery code", code, { code = it.filter(Char::isDigit).take(6) }, keyboard = KeyboardType.NumberPassword)
                Text("Releases ${zmw(split.riderNetNgwee)} to you and ${zmw(split.socialFundNgwee)} to your Social Fund.", color = Selo.Mute, fontSize = 12.sp)
                ErrorText(error)
            }
        },
        confirmButton = {
            Button({ busy = true; error = null
                scope.launch { runCatching { app.backend.confirmDeliveryCode(o.id, code, photo) }.onSuccess { onDelivered() }.onFailure { error = it.message }; busy = false } },
                enabled = !busy && code.length == 6 && (photo != null || app.backend.isDemo), colors = ButtonDefaults.buttonColors(containerColor = Selo.Green)) { Text("Mark as delivered") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }, containerColor = Color.White)
}

@Composable
fun RateBuyerDialog(o: Order, onDone: () -> Unit) {
    val app = LocalAppState.current
    var stars by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDone, title = { Text("Delivered!", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("How was ${o.buyerName.ifBlank { "the buyer" }}?", color = Selo.Mute)
                Row(Modifier.padding(top = 10.dp)) { (1..5).forEach { i -> Icon(if (i <= stars) Icons.Filled.Star else Icons.Outlined.StarBorder, "$i stars",
                    tint = Color(0xFFF5B301), modifier = Modifier.size(40.dp).clickable { stars = i }) } }
            }
        },
        confirmButton = { Button({ scope.launch { if (stars > 0) runCatching { app.backend.rateBuyer(o.id, stars) }; onDone() } },
            colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text(if (stars > 0) "Submit" else "Skip") } }, containerColor = Color.White)
}

/* =================================================================== TURN-BY-TURN */

@Composable
fun NavigationScreen(active: List<Order>, profile: RiderProfile?, rc: RouteController, onExit: () -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val speak = rememberVoice()
    var muted by remember { mutableStateOf(false) }
    var spoken by remember { mutableStateOf<Pair<Int, Set<Int>>>(-1 to emptySet()) }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Order?>(null) }
    var rating by remember { mutableStateOf<Order?>(null) }
    val d = rc.directions; val st = rc.nav
    val step = d?.steps?.getOrNull(st?.stepIndex ?: 0)
    val upcoming = d?.steps?.getOrNull((st?.stepIndex ?: 0) + 1)
    val next = rc.nextStop
    val order = active.firstOrNull { it.id == next?.orderId }
    val arrived = next?.at != null && rc.position != null && Geo.m(rc.position!!, next.at) <= NavigationEngine.ARRIVED_M
    // the instruction for the manoeuvre at the end of the current step is the *next* step's instruction
    val maneuverStep = upcoming ?: step

    LaunchedEffect(st?.stepIndex, st?.toManeuverM, muted) {
        val s = st ?: return@LaunchedEffect; val m = maneuverStep ?: return@LaunchedEffect
        val (idx, said) = spoken
        val already = if (idx == s.stepIndex) said else emptySet()
        NavigationEngine.promptFor(s.toManeuverM, already)?.let { dist ->
            if (!muted) speak(NavigationEngine.spoken(m, s.toManeuverM))
            spoken = s.stepIndex to (already + dist)
        } ?: run { if (idx != s.stepIndex) spoken = s.stepIndex to emptySet() }
    }
    LaunchedEffect(arrived, next?.id) { if (arrived && !muted && next != null) speak(if (next.type == StopType.PICKUP) "You have arrived at ${next.label}. Collect order ${next.orderId}." else "You have arrived. Deliver to ${next.label}.") }
    LaunchedEffect(rc.rerouting) { if (rc.rerouting && st != null && !muted) speak("Rerouting") }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RouteMap(d?.path ?: emptyList(), mapStops(rc.plan, rc.stopsFor(active)), rc.position, Modifier.fillMaxSize(), follow = true, heading = rc.heading)
        // instruction banner
        Column(Modifier.fillMaxWidth().background(Color(0xFF0F5132)).statusBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(maneuverIcon(maneuverStep?.maneuver ?: "straight"), null, tint = Color.White, modifier = Modifier.size(54.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (rc.rerouting) "Rerouting…" else st?.let { dist(it.toManeuverM) } ?: "Getting your location…", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text(maneuverStep?.instruction ?: "Follow the route", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                IconButton({ muted = !muted }) { Icon(if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White) }
            }
            d?.steps?.getOrNull((st?.stepIndex ?: 0) + 2)?.let { then -> Row(Modifier.padding(top = 8.dp).background(Color.White.copy(.15f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Then ", color = Color.White.copy(.85f), fontSize = 13.sp); Icon(maneuverIcon(then.maneuver), null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(" ${then.instruction}", color = Color.White, fontSize = 13.sp, maxLines = 1)
            } }
            if (d?.source == "straight_line") Text("Straight-line guidance: road directions unavailable", color = Color(0xFFFFE08A), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        // bottom panel
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${clock(System.currentTimeMillis() + rc.remainingS * 1000L)} arrival", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Selo.Green)
                    Text("${(rc.remainingS / 60.0).roundToInt()} min · ${"%.1f".format(rc.remainingM / 1000.0)} km · ${rc.plan?.stops?.size ?: 0} stops left", color = Selo.Mute, fontSize = 13.sp)
                }
                OutlinedButton(onExit, shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red)) { Text("Exit", color = Selo.Red, fontWeight = FontWeight.Bold) }
            }
            next?.let { s ->
                Row(Modifier.fillMaxWidth().background(if (arrived) Selo.GreenTint else Selo.Surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (s.type == StopType.PICKUP) Icons.Outlined.Place else Icons.Outlined.Flag, null, tint = if (s.type == StopType.PICKUP) Selo.Red else Selo.Ink)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(if (arrived) "You've arrived" else if (s.type == StopType.PICKUP) "Next: collect" else "Next: deliver", fontSize = 11.sp, color = Selo.Mute, fontWeight = FontWeight.Bold)
                        Text("${s.label} · ${s.address}", fontWeight = FontWeight.Bold, maxLines = 2, fontSize = 14.sp)
                    }
                }
                if (order != null) {
                    if (s.type == StopType.PICKUP) PrimaryButton("Mark collected", enabled = arrived || app.backend.isDemo) {
                        scope.launch { runCatching { app.backend.markCollected(order.id) } }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ call(ctx, order.buyerMsisdn) }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red)) { Icon(Icons.Outlined.Call, null, tint = Selo.Red) }
                        Button({ confirming = order }, Modifier.weight(3f).height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Green)) {
                            Text("Confirm delivery", fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }
    }
    confirming?.let { o -> ConfirmDeliveryDialog(o, onDismiss = { confirming = null }, onDelivered = { confirming = null; rating = o }) }
    rating?.let { o -> RateBuyerDialog(o) { rating = null; if (active.size <= 1) onExit() } }
}

/* =================================================================== handoff */

internal fun call(ctx: android.content.Context, msisdn: String) {
    if (msisdn.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${msisdn.replace(" ", "")}"))) }
}

/** Same planned order in Google Maps (up to 9 waypoints) for riders who prefer it. */
internal fun openGoogleMaps(ctx: android.content.Context, rc: RouteController) {
    val stops = rc.plan?.stops.orEmpty()
    if (stops.isEmpty()) return
    fun w(s: Stop) = s.at?.let { "${it.lat},${it.lng}" } ?: s.address
    val url = "https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=" + Uri.encode(w(stops.last())) +
        (if (stops.size > 1) "&waypoints=" + Uri.encode(stops.dropLast(1).take(9).joinToString("|") { w(it) }) else "") +
        (rc.position?.let { "&origin=${it.lat},${it.lng}" } ?: "")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
