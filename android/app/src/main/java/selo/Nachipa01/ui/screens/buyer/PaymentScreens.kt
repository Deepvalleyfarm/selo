package selo.Nachipa01.ui.screens.buyer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Order
import selo.Nachipa01.data.model.OrderItem
import selo.Nachipa01.data.model.OrderState
import selo.Nachipa01.data.repo.CheckoutRequest
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** B2: product payment with the escrow-lock explainer. */
@Composable
fun CheckoutScreen(listingId: String, onBack: () -> Unit, onPaid: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val listing by remember { app.backend.listings().map { l -> l.firstOrNull { it.id == listingId } ?: l.firstOrNull() } }.collectAsState(null)
    var qty by remember { mutableIntStateOf(1) }
    var wallet by remember { mutableStateOf(0) }
    var dropoff by remember { mutableStateOf(app.session.value?.area ?: "") }
    var paying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val l = listing ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
    val distance = 6.1
    val quote = Fees.quote(l.priceNgwee * qty, distance, cfg)
    val split = Fees.split(quote.goodsNgwee, quote.deliveryNgwee, cfg)
    val wallets = listOfNotNull(app.session.value?.msisdn)

    Column(Modifier.fillMaxSize()) {
        SeloHeader("Checkout", "${l.zone} zone · 1 seller", onBack = onBack, trailing = { Icon(Icons.Outlined.Lock, null, tint = Color.White) })
        FormColumn {
            SeloCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.title}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton({ if (qty > 1) qty-- }) { Text("−", color = Selo.Red, fontSize = 18.sp) }
                    Text("$qty", fontWeight = FontWeight.Bold)
                    TextButton({ if (qty < l.stock.coerceAtLeast(1)) qty++ }) { Text("+", color = Selo.Red, fontSize = 18.sp) }
                }
                KvRow("Goods", Fees.formatK(quote.goodsNgwee))
                KvRow("Delivery · ${l.sellerName} → you, $distance km", Fees.formatK(quote.deliveryNgwee))
                KvRow("Service fee", Fees.formatK(quote.serviceFeeNgwee))
                HorizontalDivider(color = Selo.Line)
                KvRow("Total", Fees.formatK(quote.totalNgwee), strong = true, valueColor = Selo.Red)
            }
            SeloField("Deliver to (area and landmark)", dropoff, { dropoff = it }, trailing = Icons.Outlined.Place)
            SectionLabel("Pay with")
            wallets.forEachIndexed { i, w ->
                RadioCard(wallet == i, selo.Nachipa01.domain.Validation.network(w), selo.Nachipa01.domain.Validation.maskMsisdn(w),
                    badge = selo.Nachipa01.domain.Validation.network(w).take(2).uppercase()) { wallet = i }
            }
            Column(Modifier.fillMaxWidth().background(Selo.GreenTint, RoundedCornerShape(12.dp)).border(1.4.dp, Color(0xFFA7F0D2), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, tint = Selo.GreenDark, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Escrow lock protects this payment", color = Selo.GreenDark, fontWeight = FontWeight.ExtraBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1 · Locked" to "${Fees.formatK(quote.totalNgwee)} held by Selo", "2 · Pickup" to "Seller paid ${Fees.formatK(split.sellerNetNgwee)}",
                        "3 · Delivered" to "Rider paid ${Fees.formatK(quote.deliveryNgwee)}").forEach { (a, b) ->
                        Column(Modifier.weight(1f).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(a, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF065F46)); Text(b, fontSize = 10.sp, color = Color(0xFF065F46), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            ErrorText(error)
            PrimaryButton("Confirm & pay ${Fees.formatK(quote.totalNgwee)}", color = Selo.Green, loading = paying, enabled = dropoff.isNotBlank() && wallets.isNotEmpty()) {
                paying = true; error = null
                scope.launch {
                    runCatching { app.backend.checkout(CheckoutRequest(listOf(OrderItem(l.id, l.title, qty, l.priceNgwee)), l.sellerUid, dropoff, wallets[wallet], distance)) }
                        .onSuccess(onPaid).onFailure { error = it.message ?: "Payment could not be confirmed. Check your mobile money balance and try again." }
                    paying = false
                }
            }
            Sub("You'll get a mobile money prompt on your phone. Approve it to lock the payment in escrow.")
        }
    }
}

/** B3: shown once the Lipila webhook has moved the order to ESCROW_LOCKED. */
@Composable
fun EscrowLockedScreen(orderId: String, onTrack: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val order by remember { app.backend.order(orderId) }.collectAsState(null)
    val o = order
    val locked = o != null && o.state != OrderState.INITIATED
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(96.dp).background(if (locked) Selo.Green else Selo.Amber, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            if (locked) Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(48.dp))
            else CircularProgressIndicator(color = Color.White)
        }
        Text(if (locked) "Payment secured in escrow" else "Approve the prompt on your phone", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Sub(if (locked) "Lipila confirmed your mobile money payment." else "Waiting for Lipila to confirm. Do not close the app.")
        if (o != null) {
            Text(Fees.formatK(o.totalNgwee), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = if (locked) Selo.Green else Selo.Ink)
            val split = Fees.split(o.goodsNgwee, o.deliveryNgwee, cfg)
            SeloCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Held for seller (${o.sellerName})", Modifier.weight(1f), color = Selo.Mute, fontSize = 13.sp); StatusChip(if (locked) "Locked" else "Pending", if (locked) Tone.OK else Tone.WARN) }
                KvRow("  · released at rider pickup", Fees.formatK(o.goodsNgwee))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Held for rider", Modifier.weight(1f), color = Selo.Mute, fontSize = 13.sp); StatusChip(if (locked) "Locked" else "Pending", if (locked) Tone.OK else Tone.WARN) }
                KvRow("  · released after you confirm", Fees.formatK(o.deliveryNgwee))
                HorizontalDivider(color = Selo.Line)
                KvRow("Reference", o.lipilaRef ?: "SELO-COL-${o.id}", strong = true, mono = true)
                Sub("Seller receives ${Fees.formatK(split.sellerNetNgwee)} after the ${Fees.formatK(split.platformFeeNgwee)} platform fee.")
            }
        }
        NoteBox("Not delivered? Funds return to your wallet automatically. If no rider accepts in ${cfg.broadcastTimeoutMin} minutes you get a full refund.")
        PrimaryButton("Track my order", enabled = locked) { onTrack(orderId) }
    }
}

private val timeline = listOf(
    OrderState.ESCROW_LOCKED to "Payment secured", OrderState.RIDER_ASSIGNED to "Rider assigned",
    OrderState.PICKED_UP to "Picked up · seller paid", OrderState.IN_TRANSIT to "On the way to you", OrderState.DELIVERED to "Delivered",
)

@Composable
fun OrderTimeline(o: Order) {
    val order = OrderState.entries
    SeloCard {
        timeline.forEach { (st, label) ->
            val reached = order.indexOf(o.state) >= order.indexOf(st) && o.state !in listOf(OrderState.REFUNDED, OrderState.CANCELLED)
            val current = reached && timeline.indexOfFirst { it.first == st } == timeline.indexOfLast { order.indexOf(o.state) >= order.indexOf(it.first) }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).background(if (current && st != OrderState.DELIVERED) Selo.Purple else if (reached) Selo.Green else Color(0xFFE5E5EA), CircleShape),
                    contentAlignment = Alignment.Center) { if (reached && !(current && st != OrderState.DELIVERED)) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), color = if (reached) Selo.Ink else Selo.Mute)
                Text(o.timeline[st.name] ?: "", color = Selo.Mute, fontSize = 12.sp)
            }
        }
    }
}

/** B4: live tracking, delivery code and remaining escrow. */
@Composable
fun TrackingScreen(orderId: String, onBack: () -> Unit) {
    val app = LocalAppState.current
    val order by remember { app.backend.order(orderId) }.collectAsState(null)
    val scope = rememberCoroutineScope()
    val o = order ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Order ${o.id}", "${o.zone} zone · ${o.items.sumOf { it.qty }} items", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            MapPlaceholder(160)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (o.riderName != null) SeloCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Selo.Red, CircleShape), contentAlignment = Alignment.Center) {
                            Text(o.riderName.split(" ").mapNotNull { it.firstOrNull() }.joinToString(""), color = Color.White, fontWeight = FontWeight.ExtraBold) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(o.riderName, fontWeight = FontWeight.Bold); Text("★ 4.8 · Motorbike", color = Selo.Mute, fontSize = 12.sp) }
                        Icon(Icons.Outlined.Call, "Call rider", tint = Selo.Red)
                    }
                }
                OrderTimeline(o)
                if (o.deliveryCode != null && o.state != OrderState.DELIVERED) Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.4.dp, Selo.Red, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show this code to your rider", color = Selo.Mute, fontSize = 11.sp)
                        Text(o.deliveryCode.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 3.sp)
                    }
                    OutlinedButton({ scope.launch { app.backend.confirmReceipt(o.id) } }, enabled = o.state == OrderState.IN_TRANSIT) { Text("Confirm receipt", color = Selo.Red) }
                }
                if (o.state != OrderState.DELIVERED) NoteBox("Escrow active — ${Fees.formatK(o.deliveryNgwee)} still held. Rider is paid after you confirm.", Tone.OK, Icons.Outlined.Shield)
                else NoteBox("Delivered. Thank you for using Selo Escrow.", Tone.OK, Icons.Outlined.CheckCircle)
            }
        }
    }
}
