package selo.Nachipa01.ui.screens.buyer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import selo.Nachipa01.data.model.OrderQuoteResult
import selo.Nachipa01.data.model.Place
import selo.Nachipa01.ui.payments.*
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/**
 * B2: checkout (PRD v6.2 payments + v6.4 address system). [listingId] is "cart"; [select] pre-selects one item (Buy).
 * The delivery address comes from the shared address field; the server quotes the delivery fee from the road distance
 * between the seller and that address (labelled approximate when routing is unavailable). Totals are never typed.
 */
@Composable
fun CheckoutScreen(listingId: String, onBack: () -> Unit, onPaid: (String) -> Unit, select: String? = null) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    // legacy "checkout/{listingId}" links become Buy: cart + that item selected
    LaunchedEffect(listingId) { if (listingId != "cart" && listingId.isNotBlank()) runCatching { app.backend.listing(listingId) }.getOrNull()?.let { if (app.cart.none { c -> c.listing.id == it.id }) app.addToCart(it) } }
    val focus = select ?: listingId.takeIf { it != "cart" }
    var selected by remember(app.cart.size) { mutableStateOf(app.cart.map { it.listing.id }.filter { focus == null || it == focus }.toSet().ifEmpty { app.cart.map { it.listing.id }.toSet() }) }
    val items = app.cart.filter { it.listing.id in selected }
    val saved by app.backend.savedAddresses().collectAsState(emptyList())
    var dropoff by remember { mutableStateOf<Place?>(null) }
    LaunchedEffect(saved) { if (dropoff == null) dropoff = saved.firstOrNull { it.isDefault }?.place ?: saved.firstOrNull()?.place }
    var quote by remember { mutableStateOf<OrderQuoteResult?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = rememberPaymentChoice(app.session.value?.msisdn)
    val scope = rememberCoroutineScope()
    val key = items.joinToString { "${it.listing.id}:${it.qty}" } + dropoff?.let { "@${it.lat},${it.lng}" }
    LaunchedEffect(key) {
        quote = null; quoteError = null
        val d = dropoff ?: return@LaunchedEffect
        if (items.isEmpty() || !d.inServiceArea) return@LaunchedEffect
        quoting = true
        runCatching { app.backend.orderQuote(items.map { OrderItem(it.listing.id, it.listing.title, it.qty, it.listing.priceNgwee) }, d) }
            .onSuccess { quote = it }.onFailure { quoteError = it.message ?: "Couldn't work out the delivery fee. Check your connection." }
        quoting = false
    }
    if (app.cart.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            SeloHeader("Checkout", onBack = onBack)
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Your cart is empty.", color = Selo.Mute); Spacer(Modifier.height(12.dp)); OutlineButton("Back to shopping", onClick = onBack)
            }
        }
        return
    }
    val seller = app.cart.first().listing
    val unavailable = quote?.unavailable.orEmpty().toSet() + items.filter { !app.inStock(it.listing) }.map { it.listing.id }
    val payer = choice.msisdn
    val q = quote

    Column(Modifier.fillMaxSize()) {
        SeloHeader("Checkout", "${seller.sellerName} · ${items.sumOf { it.qty }} of ${app.cartCount} selected", onBack = onBack,
            trailing = { Icon(Icons.Outlined.Lock, null, tint = Color.White) })
        FormColumn {
            SeloCard {
                app.cart.toList().forEach { ci ->
                    val out = ci.listing.id in unavailable
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ci.listing.id in selected, { on -> selected = if (on) selected + ci.listing.id else selected - ci.listing.id },
                            colors = CheckboxDefaults.colors(checkedColor = Selo.Red))
                        Column(Modifier.weight(1f)) {
                            Text(ci.listing.title, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (out) Selo.Mute else Selo.Ink)
                            Text(if (out) "Out of stock — remove to continue" else Fees.formatK(ci.listing.priceNgwee) + " each", color = if (out) Selo.Red else Selo.Mute, fontSize = 12.sp)
                        }
                        TextButton({ app.setQty(ci.listing.id, ci.qty - 1) }) { Text(if (ci.qty == 1) "✕" else "−", color = Selo.Red, fontSize = 18.sp) }
                        Text("${ci.qty}", fontWeight = FontWeight.Bold)
                        TextButton({ app.setQty(ci.listing.id, ci.qty + 1) }, enabled = ci.qty < ci.listing.stock.coerceAtLeast(1)) { Text("+", color = Selo.Red, fontSize = 18.sp) }
                    }
                }
            }
            AddressField("Deliver to", dropoff, { dropoff = it }, icon = Icons.Outlined.Flag)
            PaymentMethodPicker(choice)
            when {
                dropoff == null -> NoteBox("Choose a delivery address to see the delivery fee and total.")
                quoting -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Selo.Red); Text("  Working out the delivery fee…", color = Selo.Mute) }
                quoteError != null -> NoteBox(quoteError!!, Tone.BRAND)
                q != null -> {
                    AmountBreakdown(buildList {
                        items.forEach { add(AmountLine("${it.listing.title} × ${it.qty}", it.listing.priceNgwee * it.qty)) }
                        add(AmountLine("Delivery · ${if (q.approximate) "≈ " else ""}${"%.1f".format(q.distanceKm)} km", q.deliveryNgwee))
                        if (q.serviceFeeNgwee > 0) add(AmountLine("Platform fee (${trimPct(cfg.buyerServiceFeePct)}%)", q.serviceFeeNgwee))
                        if (q.processingFeeNgwee > 0) add(AmountLine("Transaction fee (${trimPct(cfg.paymentProcessingPct)}%)", q.processingFeeNgwee))
                    })
                    if (q.approximate) Text("Distance is approximate (straight line × road factor) because live routing isn't available right now.", color = Selo.AmberDark, fontSize = 12.sp)
                    Text("Arrives in about ${q.etaMin} min after a rider collects it.", color = Selo.Mute, fontSize = 12.sp)
                }
            }
            q?.let { qq ->
                val split = Fees.split(qq.goodsNgwee, qq.deliveryNgwee, cfg)
                Column(Modifier.fillMaxWidth().background(Selo.GreenTint, RoundedCornerShape(12.dp)).border(1.4.dp, Color(0xFFA7F0D2), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null, tint = Selo.GreenDark, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Escrow lock protects this payment", color = Selo.GreenDark, fontWeight = FontWeight.ExtraBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("1 · Locked" to "${Fees.formatK(qq.totalNgwee)} held by Selo", "2 · Delivered" to "You give the rider your 5-digit code",
                            "3 · Released" to "Seller ${Fees.formatK(split.sellerNetNgwee)}, rider paid").forEach { (a, b) ->
                            Column(Modifier.weight(1f).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(a, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF065F46)); Text(b, fontSize = 10.sp, color = Color(0xFF065F46), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            if (unavailable.isNotEmpty()) OutlineButton("Remove out-of-stock items") { app.removeFromCart(unavailable); selected = selected - unavailable }
            ErrorText(error)
            PrimaryButton("Pay ${q?.let { Fees.formatK(it.totalNgwee) } ?: ""}" + (choice.operator?.let { " with ${it.label}" } ?: ""), color = Selo.Green, loading = paying,
                enabled = q != null && payer != null && items.isNotEmpty() && unavailable.none { it in selected } && dropoff?.inServiceArea == true) {
                paying = true; error = null
                val d = dropoff!!
                scope.launch {
                    runCatching {
                        app.backend.checkout(CheckoutRequest(items.map { OrderItem(it.listing.id, it.listing.title, it.qty, it.listing.priceNgwee) },
                            seller.sellerUid, listOfNotNull(d.short, d.address.takeIf { a -> a != d.short }).joinToString(", "), payer!!, q!!.distanceKm, seller.sellerName, d))
                    }.onSuccess { app.removeFromCart(items.map { it.listing.id }); onPaid(it) }
                        .onFailure { error = it.message ?: "Payment could not be started. Try again." }
                    paying = false
                }
            }
            Sub("You'll get a ${choice.operator?.label ?: "mobile money"} prompt on ${payer?.let { Validation.maskMsisdn(it) } ?: "your phone"}. When the rider arrives you'll get a 5-digit delivery code — share it only once you have your order.")
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
            PrimaryButton("Track my order", whyDisabled = "Waiting for your payment to be confirmed…", enabled = locked) { onTrack(orderId) }
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
            val ll = { a: Double?, b: Double? -> if (a != null && b != null) selo.Nachipa01.domain.route.LatLng(a, b) else null }
            selo.Nachipa01.ui.maps.LiveTrackingMap(ll(o.pickupLat, o.pickupLng), ll(o.dropLat, o.dropLng), ll(o.riderLat, o.riderLng), o.trackingPolyline, o.etaMinutes)
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
                // Parcels v2 §9: the buyer is the receiver — a 5-digit code arrives when the rider is at the door
                val codes by selo.Nachipa01.push.DeliveryCodeStore.codes.collectAsState()
                val myCode = codes.firstOrNull { it.kind == "order" && it.orderId == o.id }
                var dialog by remember { mutableStateOf<String?>(null) }
                when (o.state) {
                    OrderState.AWAITING_DELIVERY_CODE -> Column(Modifier.fillMaxWidth().background(Selo.Red, RoundedCornerShape(16.dp))
                        .clickable { app.navRequest.value = "code/order/${o.id}" }.padding(16.dp)) {
                        Text("Your rider has arrived", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text(if (myCode != null) "Tap to open your 5-digit delivery code. Give it to the rider only once you have your order." else "Your code was sent by SMS to your number.",
                            color = Color.White, fontSize = 13.sp)
                        if (myCode != null) Text("Open delivery code ›", color = Color.White, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    OrderState.DELIVERED -> {
                        NoteBox("Delivered and confirmed with your code. The seller and rider have been paid from escrow.", Tone.OK, Icons.Outlined.CheckCircle)
                        PrimaryButton("Confirm receipt", color = Selo.Green) { scope.launch { app.attempt("Receipt confirmed. Thank you!") { app.backend.confirmReceipt("order", o.id) } } }
                    }
                    OrderState.COMPLETED -> NoteBox("Completed. Thank you for using Selo Escrow.", Tone.OK, Icons.Outlined.CheckCircle)
                    OrderState.DELIVERY_FAILED -> NoteBox("The delivery couldn't be completed. Support will contact you about your refund.", Tone.BRAND)
                    else -> NoteBox("Escrow active — ${Fees.formatK(o.totalNgwee)} held. When the rider arrives you'll get a 5-digit code; nobody is paid until the rider enters it.", Tone.OK, Icons.Outlined.Shield)
                }
                if (o.state in listOf(OrderState.DELIVERED, OrderState.COMPLETED)) OutlineButton("Rate the rider") { dialog = "rate" }
                if (o.state in listOf(OrderState.DELIVERED, OrderState.COMPLETED, OrderState.DELIVERY_FAILED, OrderState.IN_TRANSIT, OrderState.AWAITING_DELIVERY_CODE)) OutlineButton("Report an issue") { dialog = "issue" }
                when (dialog) {
                    "rate" -> RateDialog("order", o.id, o.riderName ?: "the rider", { dialog = null }) { dialog = null }
                    "issue" -> IssueDialog("order", o.id, { dialog = null }) { dialog = null; app.toast.value = "Issue reported. Support replies within 24 hours." }
                }
            }
        }
    }
}
