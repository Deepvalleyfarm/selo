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
import selo.Nachipa01.domain.Operator
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.data.model.CartItem
import selo.Nachipa01.ui.payments.*
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/**
 * B2: product payment with the escrow-lock explainer (PRD v6.2).
 * [listingId] is a listing for "Buy now", or "cart" to pay for the cart. The amount is computed, never typed:
 * selected items + delivery + service fee + transaction fee. The registered number pays by default.
 */
@Composable
fun CheckoutScreen(listingId: String, onBack: () -> Unit, onPaid: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val fromCart = listingId == "cart"
    val listing by remember(listingId) { if (fromCart) kotlinx.coroutines.flow.flowOf(null) else app.backend.listings().map { l -> l.firstOrNull { it.id == listingId } } }.collectAsState(null)
    var buyNowQty by remember { mutableIntStateOf(1) }
    val items: List<CartItem> = if (fromCart) app.cart.toList() else listOfNotNull(listing?.let { CartItem(it, buyNowQty) })
    var dropoff by remember { mutableStateOf(app.session.value?.area ?: "") }
    var paying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = rememberPaymentChoice(app.session.value?.msisdn)
    val scope = rememberCoroutineScope()
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            SeloHeader("Checkout", onBack = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (fromCart) Text("Your cart is empty.", color = Selo.Mute) else CircularProgressIndicator(color = Selo.Red)
            }
        }
        return
    }
    val seller = items.first().listing
    val distance = 6.1
    val quote = Fees.quote(items.sumOf { it.listing.priceNgwee * it.qty }, distance, cfg)
    val split = Fees.split(quote.goodsNgwee, quote.deliveryNgwee, cfg)
    val lines = buildList {
        items.forEach { add(AmountLine("${it.listing.title} × ${it.qty}", it.listing.priceNgwee * it.qty)) }
        add(AmountLine("Delivery · $distance km", quote.deliveryNgwee))
        if (quote.serviceFeeNgwee > 0) add(AmountLine("Platform fee (${trimPct(cfg.buyerServiceFeePct)}%)", quote.serviceFeeNgwee))
        if (quote.processingFeeNgwee > 0) add(AmountLine("Transaction fee (${trimPct(cfg.paymentProcessingPct)}%)", quote.processingFeeNgwee))
    }
    val payer = choice.msisdn

    Column(Modifier.fillMaxSize()) {
        SeloHeader("Checkout", "${seller.sellerName} · ${items.sumOf { it.qty }} item${if (items.sumOf { it.qty } == 1) "" else "s"}", onBack = onBack,
            trailing = { Icon(Icons.Outlined.Lock, null, tint = Color.White) })
        FormColumn {
            SeloCard {
                items.forEach { ci ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ci.listing.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(Fees.formatK(ci.listing.priceNgwee) + " each", color = Selo.Mute, fontSize = 12.sp)
                        }
                        val set: (Int) -> Unit = { q -> if (fromCart) app.setQty(ci.listing.id, q) else buyNowQty = q.coerceIn(1, ci.listing.stock.coerceAtLeast(1)) }
                        TextButton({ set(ci.qty - 1) }) { Text("−", color = Selo.Red, fontSize = 18.sp) }
                        Text("${ci.qty}", fontWeight = FontWeight.Bold)
                        TextButton({ set(ci.qty + 1) }, enabled = ci.qty < ci.listing.stock.coerceAtLeast(1)) { Text("+", color = Selo.Red, fontSize = 18.sp) }
                    }
                }
            }
            SeloField("Deliver to (area and landmark)", dropoff, { dropoff = it }, trailing = Icons.Outlined.Place)
            PaymentMethodPicker(choice)
            AmountBreakdown(lines)
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
            PrimaryButton("Pay ${Fees.formatK(quote.totalNgwee)}" + (choice.operator?.let { " with ${it.label}" } ?: ""), color = Selo.Green, loading = paying,
                enabled = dropoff.isNotBlank() && payer != null) {
                paying = true; error = null
                scope.launch {
                    runCatching {
                        app.backend.checkout(CheckoutRequest(items.map { OrderItem(it.listing.id, it.listing.title, it.qty, it.listing.priceNgwee) },
                            seller.sellerUid, dropoff, payer!!, distance, seller.sellerName))
                    }.onSuccess { if (fromCart) app.cart.clear(); onPaid(it) }
                        .onFailure { error = it.message ?: "Payment could not be started. Try again." }
                    paying = false
                }
            }
            Sub("You'll get a ${choice.operator?.label ?: "mobile money"} prompt on ${payer?.let { Validation.maskMsisdn(it) } ?: "your phone"}. Enter your PIN to lock the payment in escrow.")
        }
    }
}

private fun trimPct(p: Double) = if (p % 1.0 == 0.0) p.toLong().toString() else p.toString()

/** B3: waits for the mobile money prompt; on failure offers another number, on success shows the escrow lock. */
@Composable
fun EscrowLockedScreen(orderId: String, onTrack: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val order by remember { app.backend.order(orderId) }.collectAsState(null)
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val o = order
    val locked = o != null && o.state != OrderState.INITIATED && o.state != OrderState.CANCELLED
    val failed = o != null && !locked && o.paymentStatus == "FAILED"
    val choice = rememberPaymentChoice(app.session.value?.msisdn, startWithOther = true)
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(96.dp).background(when { locked -> Selo.Green; failed -> Selo.Red; else -> Selo.Amber }, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            when {
                locked -> Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(48.dp))
                failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(48.dp))
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
        val payOp = o?.walletMsisdn?.let { Operator.detect(it) }
        Text(when { locked -> "Payment secured in escrow"; failed -> "Payment didn't go through"; else -> "Approve the prompt on your phone" },
            fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        if (!locked && payOp != null && o?.walletMsisdn != null) Row(verticalAlignment = Alignment.CenterVertically) {
            OperatorMark(payOp, 30.dp); Spacer(Modifier.width(8.dp))
            Text("${payOp.label} · ${Validation.maskMsisdn(o.walletMsisdn)}", fontWeight = FontWeight.Bold)
        }
        Sub(when {
            locked -> "Your mobile money payment is confirmed."
            failed -> o?.paymentFailure ?: "The payment was declined."
            else -> "Enter your mobile money PIN on the prompt. Do not close the app."
        })
        if (o != null) {
            Text(Fees.formatK(o.totalNgwee), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = if (locked) Selo.Green else Selo.Ink)
            if (failed) {
                PaymentMethodPicker(choice, "Try again with", exclude = o.walletMsisdn)
                ErrorText(error)
                PrimaryButton("Pay ${Fees.formatK(o.totalNgwee)}" + (choice.operator?.let { " with ${it.label}" } ?: ""), color = Selo.Green, loading = retrying,
                    enabled = choice.msisdn != null) {
                    retrying = true; error = null
                    scope.launch { runCatching { app.backend.retryPayment(o.id, choice.msisdn!!) }.onFailure { error = it.message }; retrying = false }
                }
                Sub("Same order, same amount. Nothing has been charged yet.")
            } else {
                val split = Fees.split(o.goodsNgwee, o.deliveryNgwee, cfg)
                SeloCard {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Held for seller (${o.sellerName})", Modifier.weight(1f), color = Selo.Mute, fontSize = 13.sp); StatusChip(if (locked) "Locked" else "Pending", if (locked) Tone.OK else Tone.WARN) }
                    KvRow("  · released at rider pickup", Fees.formatK(o.goodsNgwee))
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Held for rider", Modifier.weight(1f), color = Selo.Mute, fontSize = 13.sp); StatusChip(if (locked) "Locked" else "Pending", if (locked) Tone.OK else Tone.WARN) }
                    KvRow("  · released after you confirm", Fees.formatK(o.deliveryNgwee))
                    if (o.serviceFeeNgwee + o.processingFeeNgwee > 0) KvRow("Platform and transaction fees", Fees.formatK(o.serviceFeeNgwee + o.processingFeeNgwee))
                    HorizontalDivider(color = Selo.Line)
                    KvRow("Reference", o.lipilaRef ?: "SELO-COL-${o.id}", strong = true, mono = true)
                    Sub("Seller receives ${Fees.formatK(split.sellerNetNgwee)} after the ${Fees.formatK(split.platformFeeNgwee)} platform fee.")
                }
            }
        }
        if (!failed) {
            NoteBox("Not delivered? Funds return to your wallet automatically. If no rider accepts in ${cfg.broadcastTimeoutMin} minutes you get a full refund.")
            PrimaryButton("Track my order", enabled = locked) { onTrack(orderId) }
        }
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
