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
import androidx.compose.ui.draw.shadow
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
    /** Stop keys (orderId:type) of the route in [directions], used to trim finished legs instead of re-requesting. */
    internal var routeKeys: List<String> = emptyList()
    internal var policy: ReroutePolicy? = null
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

    val cfg by app.config.collectAsState()
    if (rc.policy == null) rc.policy = ReroutePolicy(cfg.rerouteMinIntervalSec * 1000L, cfg.rerouteMaxPerHour, cfg.offRouteFixes)
    // Route only for accepted jobs (this controller only exists with active jobs). A new route is requested when the
    // set of stops changes or the policy says the rider has really left the route; finished stops just trim legs.
    LaunchedEffect(progressKey, rc.coords, rc.reroute, rc.position == null) {
        if (active.isEmpty()) { rc.plan = null; rc.directions = null; rc.nav = null; rc.routeKeys = emptyList(); return@LaunchedEffect }
        val origin = rc.position ?: demo?.rider?.startAt ?: rc.stopsFor(active).firstNotNullOfOrNull { it.at } ?: return@LaunchedEffect
        val plan = RoutePlanner.plan(origin, rc.stopsFor(active), vehicle)
        rc.plan = plan
        val keys = plan.stops.map { "${it.orderId}:${it.type}" }
        val cur = rc.directions
        if (cur != null && cur.source != "straight_line" && isSuffix(rc.routeKeys, keys)) {
            rc.directions = cur.dropLegs(rc.routeKeys.size - keys.size); rc.routeKeys = keys; rc.nav = null; return@LaunchedEffect
        }
        rc.rerouting = true
        rc.directions = runCatching { app.backend.directions(origin, plan.stops.filter { it.at != null }, vehicle) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                rc.note = "Road directions are unavailable right now — showing straight-line guidance." }
            .onSuccess { rc.note = null; rc.policy?.routed(System.currentTimeMillis()) }
            .getOrElse { NavigationEngine.straightLine(origin, plan.stops, vehicle) }
            .let { d -> d.copy(steps = d.steps.map { s -> if (s.maneuver == "arrive") s.copy(instruction = plan.stops.getOrNull(s.legIndex)?.let { st ->
                (if (st.type == StopType.PICKUP) "Arrive at pickup: " else "Arrive at drop-off: ") + st.label } ?: s.instruction) else s }) }
        rc.routeKeys = keys
        rc.nav = null; rc.rerouting = false
    }
    // progress along the route
    LaunchedEffect(rc.position, rc.directions) {
        val d = rc.directions ?: return@LaunchedEffect; val p = rc.position ?: return@LaunchedEffect
        val st = NavigationEngine.progress(d, p, rc.nav?.stepIndex ?: 0)
        rc.nav = st
        val off = st.offRouteM > cfg.offRouteMeters
        if (rc.policy?.onFix(off, System.currentTimeMillis()) == true && !rc.rerouting && d.source != "straight_line") { rc.rerouting = true; rc.reroute++ }
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
                        Modifier.size(120.dp, 96.dp), schematic = true)
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
                OutlinedButton({ scope.launch { runCatching { app.backend.declineJob(o.id) }; app.info("Job declined — it goes to the next rider."); onDone(false) } }, Modifier.weight(1f).height(56.dp),
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
    var pinFor by remember { mutableStateOf<Order?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fee = active.sumOf { Fees.split(it.goodsNgwee, it.deliveryNgwee, cfg).riderNetNgwee }

    Column(Modifier.fillMaxSize()) {
        RiderTitle(if (active.size > 1) "Route · ${active.size} orders" else "Active delivery", trailing = {
            TextButton(onNavigate) { Icon(Icons.Outlined.Navigation, null, tint = Selo.Red, modifier = Modifier.size(18.dp)); Text(" Navigate", color = Selo.Red, fontSize = 13.sp) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                RouteMap(rc.directions?.path ?: emptyList(), mapStops(rc.plan, all), rc.position, Modifier.fillMaxWidth().height(220.dp), heading = rc.heading,
                    traffic = rc.directions?.traffic.orEmpty(), etaMin = (rc.remainingS / 60.0).roundToInt())
                Button(onNavigate, Modifier.align(Alignment.BottomEnd).padding(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Ink),
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Outlined.Navigation, null, modifier = Modifier.size(18.dp)); Text("  Start navigation", fontWeight = FontWeight.Bold) }
                if (app.backend.isDemo) Text("Demo · simulated GPS", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Selo.Ink.copy(.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            rc.note?.let { Text(it, color = Selo.AmberDark, fontSize = 12.sp) }
            rc.directions?.let { TrafficCard(it) }
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
                Button({ if (order.kind == "parcel") pinFor = order else { busy = true; scope.launch { runCatching { app.backend.markCollected(order.id) }.onFailure { error = it.message }; busy = false } } },
                    Modifier.weight(2f).height(52.dp), enabled = !busy, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) {
                    Icon(Icons.Outlined.Inventory2, null); Text("  Mark collected", fontWeight = FontWeight.ExtraBold) }
            } else {
                OutlinedButton({ call(ctx, order.buyerMsisdn) }, Modifier.weight(1.1f).height(52.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red), colors = ButtonDefaults.outlinedButtonColors(contentColor = Selo.Red, containerColor = Color.White)) {
                    Icon(Icons.Outlined.Call, null, modifier = Modifier.size(18.dp)); Text(" Call buyer", fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp) }
                val dest = next?.at; val here = rc.position
                val inside = dest == null || here == null || Geo.m(here, dest) <= 150
                Button({ confirming = order }, Modifier.weight(2f).height(52.dp), enabled = inside, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Green)) {
                    Icon(Icons.Outlined.CheckCircle, null); Text(if (inside) "  Confirm delivery" else "  ${dist(Geo.m(here!!, dest!!).toInt())} to go", fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
    confirming?.let { o -> ConfirmDeliveryDialog(o, rc.position, onDismiss = { confirming = null }, onDelivered = { confirming = null; rating = o }) }
    rating?.let { o -> RateBuyerDialog(o) { rating = null } }
    pinFor?.let { o -> PickupPinDialog(o, { pinFor = null }) { pinFor = null } }
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

/**
 * 🔒 Parcels v2 §9.8 rider flow. Opening it asks the server to send the receiver a 5-digit code (geofence enforced
 * server-side). The rider never sees the code: they type what the receiver reads out. Verification needs a connection.
 * On success: "Your Earnings", then an optional proof-of-delivery photo.
 */
@Composable
fun ConfirmDeliveryDialog(o: Order, position: LatLng?, onDismiss: () -> Unit, onDelivered: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var req by remember { mutableStateOf<CodeRequestResult?>(null) }
    var sending by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attemptsLeft by remember { mutableStateOf<Int?>(null) }
    var locked by remember { mutableStateOf(false) }
    var earnings by remember { mutableStateOf<Long?>(null) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var photoSaved by remember { mutableStateOf(false) }
    var smsUsed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val take = rememberPhotoCapture { photo = it; scope.launch { runCatching { app.backend.attachProofOfDelivery(o.kind, o.id, it) }.onSuccess { photoSaved = true } } }
    fun offline(t: Throwable) = (t.message ?: "").let { m -> if (m.contains("network", true) || m.contains("unavailable", true) && !m.contains("receiver", true) || t is java.io.IOException) "No connection. Move to an area with signal to verify." else m }
    fun request(resend: Boolean = false, smsOnly: Boolean = false) = scope.launch {
        sending = true; error = null
        runCatching { app.backend.requestDeliveryCode(o.kind, o.id, position?.lat, position?.lng, resend, smsOnly) }
            .onSuccess { req = it; attemptsLeft = it.attemptsLeft; locked = false; code = ""; if (smsOnly) smsUsed = true }
            .onFailure { error = offline(it) }
        sending = false
    }
    LaunchedEffect(Unit) { request() }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val demoInbox = (app.backend as? DemoBackend)?.v64?.demoReceiverInbox?.collectAsState()?.value

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (earnings != null) {
                Spacer(Modifier.height(30.dp))
                Box(Modifier.size(96.dp).background(Selo.Green, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(56.dp)) }
                Text("Delivery confirmed", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Your Earnings: ${Fees.formatK(earnings!!)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Selo.Green)
                Text("Released from escrow to your wallet.", color = Selo.Mute)
                Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)).background(Selo.Surface).clickable { take() }, contentAlignment = Alignment.Center) {
                    if (photo != null) AsyncImage(photo, "Proof of delivery", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.PhotoCamera, null, tint = Selo.Red, modifier = Modifier.size(34.dp))
                        Text("Add a proof-of-delivery photo (optional)", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
                if (photoSaved) Text("Photo saved to the delivery record.", color = Selo.GreenDark, fontSize = 12.sp)
                PrimaryButton("Done", color = Selo.Green, onClick = onDelivered)
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Confirm delivery", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "Close") }
            }
            Text("${o.buyerName.ifBlank { "Receiver" }} · ${o.dropoff}", color = Selo.Mute, textAlign = TextAlign.Center)
            when {
                sending -> { CircularProgressIndicator(color = Selo.Red); Text("Sending the receiver their delivery code…", color = Selo.Mute) }
                req == null -> { Icon(Icons.Outlined.ErrorOutline, null, tint = Selo.Red, modifier = Modifier.size(48.dp)); Text(error ?: "Couldn't send the code.", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    OutlineButton("Try again") { request() } }
                else -> {
                    val r = req!!
                    val left = ((r.expiresAt - now) / 1000).coerceAtLeast(0)
                    Text("Ask the receiver for their 5-digit delivery code.", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Sent by ${r.channel.replace("+", " and ")}" + if (r.state == "active") " (still valid)" else "", color = Selo.Mute, fontSize = 12.sp)
                    CodeBoxes(code, enabled = !locked && left > 0) { code = it; error = null }
                    Row { Text(if (left > 0) "Expires in %d:%02d".format(left / 60, left % 60) else "Code expired", color = if (left > 0) Selo.Mute else Selo.Red, fontWeight = FontWeight.Bold)
                        attemptsLeft?.let { Text("  ·  $it attempt${if (it == 1) "" else "s"} left", color = if (it <= 2) Selo.Red else Selo.Mute) } }
                    ErrorText(error)
                    if (locked) NoteBox("Too many wrong codes — this code is locked. Request a new one in 2 minutes.", Tone.BRAND)
                    PrimaryButton("Verify", color = Selo.Green, loading = busy, whyDisabled = when { locked -> "This code is locked after too many attempts. Ask for a new one."; left <= 0 -> "The code expired. Request a new one."; else -> "Enter all 5 digits the receiver reads to you." }, enabled = code.length == 5 && !locked && left > 0) {
                        busy = true; error = null
                        scope.launch {
                            runCatching { app.backend.verifyDeliveryCode(o.kind, o.id, code, position?.lat, position?.lng) }
                                .onSuccess { v -> when (v.result) {
                                    "verified" -> earnings = v.earningsNgwee ?: o.riderEarningsNgwee ?: 0
                                    "wrong" -> { attemptsLeft = v.attemptsLeft; error = "That code doesn't match. Ask the receiver to read it again."; code = "" }
                                    "locked" -> { locked = true; attemptsLeft = 0; code = "" }
                                    "expired" -> error = "The code expired. Tap Resend Code."
                                    else -> error = "No active code. Tap Resend Code."
                                } }.onFailure { error = offline(it) }
                            busy = false
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ request(resend = true) }, Modifier.weight(1f), enabled = !sending) { Text("Resend Code", color = Selo.Red) }
                        OutlinedButton({ request(smsOnly = true) }, Modifier.weight(1f), enabled = !sending && !smsUsed) { Text(if (smsUsed) "SMS sent" else "Resend by SMS", color = Selo.Red) }
                    }
                    var unavailableMsg by remember { mutableStateOf<String?>(null) }
                    TextButton({ scope.launch { runCatching { app.backend.receiverUnavailable(o.kind, o.id, position?.lat, position?.lng) }
                        .onSuccess { d -> unavailableMsg = "Sender and receiver notified. If nobody responds by ${clock(d)}, the delivery is marked failed and you'll be told what to do with the item." }
                        .onFailure { unavailableMsg = it.message } } }) { Text("Receiver Unavailable", color = Selo.Mute) }
                    unavailableMsg?.let { Text(it, color = Selo.AmberDark, fontSize = 12.sp, textAlign = TextAlign.Center) }
                    demoInbox?.get(o.id)?.let { c ->
                        Column(Modifier.fillMaxWidth().background(Selo.Ink, RoundedCornerShape(12.dp)).padding(12.dp)) {
                            Text("DEMO ONLY · the receiver's phone", color = Selo.Amber, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            Text("SMS: Your SeloNaChipa delivery code is $c.", color = Color.White, fontSize = 13.sp)
                            Text("In the live app the rider never sees this.", color = Color.White.copy(.6f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBoxes(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(value, { v -> onChange(v.filter(Char::isDigit).take(5)) }, enabled = enabled,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(5) { i ->
                    Box(Modifier.size(52.dp, 64.dp).background(if (enabled) Color.White else Selo.Surface, RoundedCornerShape(12.dp))
                        .border(2.dp, if (i == value.length && enabled) Selo.Red else Selo.Field, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text(value.getOrNull(i)?.toString() ?: "", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        })
}

/** Parcel jobs: the sender reads their 4-digit Pickup PIN to the rider (§7). */
@Composable
fun PickupPinDialog(o: Order, onDismiss: () -> Unit, onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
    val demoPin = (app.backend as? DemoBackend)?.rider?.parcelPins?.get(o.id)
    AlertDialog(onDismiss, containerColor = Color.White, title = { Text("Pickup PIN", fontWeight = FontWeight.ExtraBold) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ask ${o.sellerName} for the 4-digit Pickup PIN on their parcel screen.")
            PinInput(pin, { pin = it; err = null }, length = 4)
            ErrorText(err)
            demoPin?.let { Text("DEMO ONLY · sender's PIN: $it", color = Selo.AmberDark, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        } },
        confirmButton = { Button({ busy = true; scope.launch { runCatching { app.backend.confirmParcelPickup(o.id, pin) }.onSuccess { onDone() }.onFailure { err = it.message }; busy = false } },
            enabled = pin.length == 4 && !busy, colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Confirm pickup") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
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
        confirmButton = { Button({ scope.launch { if (stars > 0) app.attempt("Thanks for rating the buyer") { app.backend.rateBuyer(o.id, stars) }; onDone() } },
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
    var pinFor by remember { mutableStateOf<Order?>(null) }
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
        var follow by remember { mutableStateOf(true) }
        var panelHidden by remember { mutableStateOf(false) }
        RouteMap(d?.path ?: emptyList(), mapStops(rc.plan, rc.stopsFor(active)), rc.position, Modifier.fillMaxSize(), follow = follow, heading = rc.heading,
            traffic = d?.traffic.orEmpty(), etaMin = (rc.remainingS / 60.0).roundToInt())
        // round floating controls (overview / back to following, hide or show the trip panel)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = if (panelHidden) 24.dp else 210.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape).clickable { follow = !follow }, contentAlignment = Alignment.Center) {
                Icon(if (follow) Icons.Outlined.ZoomOutMap else Icons.Outlined.Navigation, if (follow) "Route overview" else "Follow me", tint = Selo.Ink) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.height(56.dp).shadow(4.dp, RoundedCornerShape(50)).background(Color.White, RoundedCornerShape(50)).clickable { panelHidden = !panelHidden }
                .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (panelHidden) Icons.Outlined.ExpandLess else Icons.Outlined.Close, null, tint = Selo.Ink)
                Text(if (panelHidden) "  Show" else "  Hide", fontSize = 18.sp, color = Selo.Ink) }
        }
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
        if (!panelHidden) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${clock(System.currentTimeMillis() + rc.remainingS * 1000L)} arrival", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Selo.Green)
                    Text("${(rc.remainingS / 60.0).roundToInt()} min · ${"%.1f".format(rc.remainingM / 1000.0)} km · ${rc.plan?.stops?.size ?: 0} stops left", color = Selo.Mute, fontSize = 13.sp)
                    d?.let { TrafficLine(it) }
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
                    if (s.type == StopType.PICKUP) PrimaryButton("Mark collected", whyDisabled = "Get to the pickup point first — the button unlocks when you arrive.", enabled = arrived || app.backend.isDemo) {
                        if (order.kind == "parcel") pinFor = order else scope.launch { app.attempt("Marked as collected — head to the drop-off") { app.backend.markCollected(order.id) } }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ call(ctx, order.buyerMsisdn) }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Red)) { Icon(Icons.Outlined.Call, null, tint = Selo.Red) }
                        Button({ confirming = order }, Modifier.weight(3f).height(50.dp), enabled = arrived || next.at == null, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Selo.Green)) {
                            Text("Confirm delivery", fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }
    }
    confirming?.let { o -> ConfirmDeliveryDialog(o, rc.position, onDismiss = { confirming = null }, onDelivered = { confirming = null; rating = o }) }
    rating?.let { o -> RateBuyerDialog(o) { rating = null; if (active.size <= 1) onExit() } }
    pinFor?.let { o -> PickupPinDialog(o, { pinFor = null }) { pinFor = null } }
}

/* =================================================================== handoff */

internal fun call(ctx: android.content.Context, msisdn: String) {
    if (msisdn.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${msisdn.replace(" ", "")}"))) }
}



/** Route quality: shortest of N routes, live traffic level and delay, with the colour legend. */
@Composable
fun TrafficCard(d: Directions) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Selo.Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Route, null, tint = Selo.Red, modifier = Modifier.size(18.dp))
            Text(if (d.alternatives > 0) "  Shortest of ${d.alternatives + 1} routes" else "  Best route", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("%.1f km".format(d.distanceM / 1000.0), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        TrafficLine(d)
        if (d.traffic.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("NORMAL" to "Clear", "SLOW" to "Slow", "TRAFFIC_JAM" to "Jam").forEach { (k, l) -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 16.dp, height = 5.dp).background(Color(trafficColor(k)), RoundedCornerShape(3.dp))); Text(" $l", fontSize = 11.sp, color = Selo.Mute) } }
        }
    }
}

@Composable
fun TrafficLine(d: Directions) {
    val (txt, col) = when (d.trafficLevel) {
        "heavy" -> "Heavy traffic" to Color(trafficColor("TRAFFIC_JAM")); "moderate" -> "Moderate traffic" to Color(0xFFB7791F)
        "clear" -> "Traffic is light" to Color(0xFF2E7D32); else -> "Traffic info unavailable" to Selo.Mute
    }
    Text(txt + (if (d.trafficDelayS >= 60) " · +${d.trafficDelayS / 60} min delay" else "") + " · arrive ${clock(System.currentTimeMillis() + d.durationS * 1000L)}",
        color = col, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
}
