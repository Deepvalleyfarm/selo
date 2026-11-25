package selo.Nachipa01.ui.screens.buyer

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.*
import selo.Nachipa01.domain.Cities
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.push.DeliveryCodeStore
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.payments.PaymentMethodPicker
import selo.Nachipa01.ui.payments.rememberPaymentChoice
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun k(n: Long) = Fees.formatK(n)

/* =================================================================== list */

/** Parcels I sent and parcels coming to me. */
@Composable
fun ParcelsHome(onBack: () -> Unit, onSend: () -> Unit, onOpen: (String) -> Unit) {
    val app = LocalAppState.current
    val parcels by app.backend.myParcels().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        SeloHeader("SeloExpress parcels", "Same-city delivery · up to 5 kg", onBack = onBack)
        FormColumn {
            PrimaryButton("Send a parcel", onClick = onSend)
            NoteBox("The receiver gets a 5-digit delivery code when the rider arrives. The rider is paid only after they enter it.", Tone.OK, Icons.Outlined.Shield)
            val incoming = parcels.filter { !it.iAmSender }
            if (incoming.isNotEmpty()) { SectionLabel("Coming to you"); incoming.forEach { ParcelRow(it) { onOpen(it.id) } } }
            SectionLabel("Sent by you")
            val sent = parcels.filter { it.iAmSender }
            if (sent.isEmpty()) Sub("No parcels yet.")
            sent.forEach { ParcelRow(it) { onOpen(it.id) } }
        }
    }
}

@Composable
private fun ParcelRow(p: Parcel, onClick: () -> Unit) {
    val code = DeliveryCodeStore.codes.collectAsState().value.any { it.orderId == p.id }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Selo.RedTint, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Inventory2, null, tint = Selo.Red) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (p.iAmSender) "To ${p.receiverName}" else "From ${p.senderName}", fontWeight = FontWeight.Bold)
            Text("${p.id} · ${p.pickup.neighbourhood ?: p.pickup.short} → ${p.dropoff.neighbourhood ?: p.dropoff.short}", color = Selo.Mute, fontSize = 12.sp, maxLines = 1)
        }
        StatusChip(if (code && !p.iAmSender) "Code ready" else p.status.label, when (p.status) {
            ParcelStatus.DELIVERED, ParcelStatus.COMPLETED -> Tone.OK; ParcelStatus.AWAITING_DELIVERY_CODE -> Tone.WARN
            ParcelStatus.CANCELLED, ParcelStatus.DELIVERY_FAILED -> Tone.GREY; ParcelStatus.DISPUTED -> Tone.BRAND; else -> Tone.INFO })
    }
}

/* =================================================================== send (§4) */

/** Step 1 parcel details → step 2 receiver → step 3 price & pay. Same-city only; ≤ 5 kg; fees from the server. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendParcelScreen(onBack: () -> Unit, onBooked: (String) -> Unit, initialStep: Int = 1) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var pickup by remember { mutableStateOf<Place?>(null) }
    var dropoff by remember { mutableStateOf<Place?>(null) }
    var weight by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var rName by rememberSaveable { mutableStateOf("") }
    var rPhone by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }
    var insured by rememberSaveable { mutableStateOf(false) }
    var declared by rememberSaveable { mutableStateOf("") }
    var schedule by rememberSaveable { mutableStateOf(false) }
    var scheduleDay by rememberSaveable { mutableIntStateOf(0) }
    var scheduleHour by rememberSaveable { mutableIntStateOf(10) }
    var quote by remember { mutableStateOf<ParcelQuoteResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = rememberPaymentChoice(app.session.value?.msisdn)
    val sameCity = if (pickup != null && dropoff != null) Cities.sameCityProblem(pickup!!.lat, pickup!!.lng, dropoff!!.lat, dropoff!!.lng) else null
    val suggested = remember(desc) { suggestCategory(desc) }
    LaunchedEffect(Unit) { if (pickup == null) app.backend.savedAddresses().collect { l -> if (pickup == null) pickup = l.firstOrNull { it.isDefault }?.place; return@collect } }
    LaunchedEffect(step, pickup, dropoff, insured, declared) {
        if (step != 3) return@LaunchedEffect
        quote = null; error = null
        val a = pickup ?: return@LaunchedEffect; val b = dropoff ?: return@LaunchedEffect
        busy = true
        runCatching { app.backend.parcelQuote(a, b, insured, ((declared.toDoubleOrNull() ?: 0.0) * 100).toLong()) }.onSuccess { quote = it }.onFailure { error = it.message }
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        SeloHeader("Send a parcel", "Step $step of 3 · ${listOf("Parcel details", "Receiver", "Price & payment")[step - 1]}", onBack = { if (step > 1) step -= 1 else onBack() }, steps = step to 3)
        FormColumn {
            when (step) {
                1 -> {
                    AddressField("Pickup", pickup, { pickup = it })
                    Text("Weight", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WEIGHT_BANDS.forEach { w -> SeloChip(w, weight == w) { weight = w } }
                    }
                    Text("Over 5 kg? Parcels must be 5 kg or less — use a marketplace delivery or split it.", color = Selo.Mute, fontSize = 12.sp)
                    SeloField("What's inside? (max 200)", desc, { desc = it.take(200) }, singleLine = false, placeholder = "e.g. Signed contract in an envelope")
                    Text("Category", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PARCEL_CATEGORIES.forEach { c -> SeloChip(if (c == suggested && category.isEmpty() && desc.isNotBlank()) "$c · suggested" else c, (category.ifEmpty { if (desc.isNotBlank()) suggested else "" }) == c) { category = c } }
                    }
                    SeloField("Handling notes (optional)", notes, { notes = it.take(200) }, placeholder = "e.g. Keep upright, fragile")
                }
                2 -> {
                    SeloField("Receiver's full name", rName, { rName = it.take(60) })
                    val m = Validation.normaliseMsisdn(rPhone)
                    SeloField("Receiver's phone", rPhone, { rPhone = it.take(16) }, keyboard = KeyboardType.Phone, placeholder = "097 123 4567",
                        error = if (rPhone.length >= 10 && m == null) "Enter an Airtel, MTN or Zamtel number (+260)." else null)
                    AddressField("Delivery address", dropoff, { dropoff = it }, cityId = pickup?.cityId, icon = Icons.Outlined.Flag, problem = sameCity)
                    SeloField("Delivery instructions (optional)", instructions, { instructions = it.take(300) }, placeholder = "e.g. Blue gate, call on arrival")
                    NoteBox("The receiver will get a 5-digit delivery code. They must give it to the rider to complete delivery.", Tone.WARN, Icons.Outlined.Pin)
                }
                else -> {
                    SeloCard {
                        KvRow("Pickup", pickup?.short ?: ""); KvRow("Delivery", dropoff?.short ?: ""); KvRow("To", "$rName · ${Validation.maskMsisdn(Validation.normaliseMsisdn(rPhone) ?: rPhone)}")
                        KvRow("Parcel", "$weight · ${category.ifEmpty { suggested }}")
                    }
                    SwitchRow("Insure this parcel", insured, { insured = it }, sub = "2% of the declared value (up to K5,000), minimum K5")
                    if (insured) SeloField("Declared value (K)", declared, { declared = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, keyboard = KeyboardType.Decimal)
                    SwitchRow("Schedule for later", schedule, { schedule = it }, sub = "Up to 7 days ahead. We find a rider 30 minutes before.")
                    if (schedule) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val days = (0..6).map { d -> SimpleDateFormat("EEE d", Locale.UK).format(Date(System.currentTimeMillis() + d * 86_400_000L)) }
                        var open by remember { mutableStateOf(false) }; var openH by remember { mutableStateOf(false) }
                        Box { OutlinedButton({ open = true }) { Text(if (scheduleDay == 0) "Today" else days[scheduleDay]) }
                            DropdownMenu(open, { open = false }) { days.forEachIndexed { i, d -> DropdownMenuItem({ Text(if (i == 0) "Today" else d) }, { scheduleDay = i; open = false }) } } }
                        Box { OutlinedButton({ openH = true }) { Text("%02d:00".format(scheduleHour)) }
                            DropdownMenu(openH, { openH = false }) { (7..20).forEach { h -> DropdownMenuItem({ Text("%02d:00".format(h)) }, { scheduleHour = h; openH = false }) } } }
                    }
                    when {
                        busy && quote == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Selo.Red); Text("  Getting the price…", color = Selo.Mute) }
                        quote != null -> {
                            val q = quote!!
                            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Selo.Line, RoundedCornerShape(14.dp)).padding(14.dp)) {
                                KvRow("Distance", "${if (q.approximate) "≈ " else ""}${"%.1f".format(q.distanceKm)} km · about ${q.etaMin} min")
                                KvRow("Delivery Fee", k(q.deliveryNgwee)); KvRow("Service Fee", k(q.serviceNgwee))
                                if (q.insuranceNgwee > 0) KvRow("Insurance", k(q.insuranceNgwee))
                                HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(vertical = 6.dp))
                                KvRow("Total", k(q.totalNgwee), strong = true, valueColor = Selo.Red)
                                if (q.approximate) Text("Distance is approximate because live routing isn't available right now.", color = Selo.AmberDark, fontSize = 11.5.sp)
                            }
                            PaymentMethodPicker(choice)
                        }
                    }
                }
            }
            ErrorText(error)
            val canNext = when (step) {
                1 -> pickup?.inServiceArea == true && weight.isNotEmpty() && desc.trim().length >= 3
                2 -> rName.trim().length >= 2 && Validation.normaliseMsisdn(rPhone) != null && dropoff?.inServiceArea == true && sameCity == null
                else -> quote != null && choice.msisdn != null && (!insured || (declared.toDoubleOrNull() ?: 0.0) > 0)
            }
            PrimaryButton(if (step < 3) "Next" else "Pay Now ${quote?.let { k(it.totalNgwee) } ?: ""}", enabled = canNext, loading = busy && step == 3 && quote != null,
                color = if (step == 3) Selo.Green else Selo.Red) {
                if (step < 3) { step++; return@PrimaryButton }
                busy = true; error = null
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, scheduleDay); set(java.util.Calendar.HOUR_OF_DAY, scheduleHour); set(java.util.Calendar.MINUTE, 0) }
                scope.launch {
                    runCatching { app.backend.createParcel(NewParcel(pickup!!, dropoff!!, weight, desc.trim(), category.ifEmpty { suggested }, notes, rName.trim(), rPhone, instructions,
                        insured, ((declared.toDoubleOrNull() ?: 0.0) * 100).toLong(), choice.msisdn!!, if (schedule) cal.timeInMillis.takeIf { it > System.currentTimeMillis() + 30 * 60_000 } else null)) }
                        .onSuccess(onBooked).onFailure { error = it.message ?: "We couldn't start the payment. Try again." }
                    busy = false
                }
            }
        }
    }
}

private fun suggestCategory(d: String): String { val s = d.lowercase()
    return when { Regex("\\b(letter|envelope|card)\\b").containsMatchIn(s) -> "Letter"; Regex("\\b(document|contract|certificate|passport|form|file|papers?)\\b").containsMatchIn(s) -> "Document"
        Regex("\\b(shirt|dress|chitenge|clothe?s?|shoes?|jacket|fabric|suit)\\b").containsMatchIn(s) -> "Clothing"; Regex("\\b(food|pizza|chicken|nshima|meal|lunch|cake|burger|snack)\\b").containsMatchIn(s) -> "Food"
        Regex("\\b(sample|swatch|specimen|tester)\\b").containsMatchIn(s) -> "Sample"; else -> "Other" } }

/* =================================================================== detail + tracking (§5, §13) */

@Composable
fun ParcelDetailScreen(id: String, onBack: () -> Unit, onOpenCode: (String) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val p by remember(id) { app.backend.parcel(id) }.collectAsState(null)
    val pin by remember(id) { app.backend.parcelPickupPin(id) }.collectAsState(null)
    val codes by DeliveryCodeStore.codes.collectAsState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val x = p ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
    val myCode = codes.firstOrNull { it.orderId == x.id }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Parcel ${x.id}", x.status.label, onBack = onBack, trailing = {
            if (x.trackingUrl != null) IconButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Track parcel ${x.id}: ${x.trackingUrl}") }, "Share tracking link")) }) { Icon(Icons.Outlined.Share, "Share tracking link", tint = Color.White) } })
        FormColumn {
            msg?.let { NoteBox(it, Tone.OK) }
            // status-specific banner
            when (x.status) {
                ParcelStatus.PENDING_PAYMENT -> if (x.paymentStatus == "FAILED") {
                    NoteBox(x.paymentFailure ?: "Payment didn't go through.", Tone.BRAND)
                    val choice = rememberPaymentChoice(app.session.value?.msisdn, startWithOther = true)
                    PaymentMethodPicker(choice, "Try again with", exclude = x.walletMsisdn)
                    PrimaryButton("Pay ${k(x.totalNgwee)}", enabled = choice.msisdn != null) { scope.launch { runCatching { app.backend.retryParcelPayment(x.id, choice.msisdn!!) }.onFailure { msg = it.message } } }
                } else NoteBox("Approve the mobile money prompt on your phone to pay ${k(x.totalNgwee)}.", Tone.WARN)
                ParcelStatus.AWAITING_DELIVERY_CODE -> if (!x.iAmSender) {
                    Column(Modifier.fillMaxWidth().background(Selo.Red, RoundedCornerShape(16.dp)).clickable { onOpenCode(x.id) }.padding(16.dp)) {
                        Text("Your rider has arrived", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text(if (myCode != null) "Tap to open your delivery code. Only give it to the rider once the parcel is in your hands." else "Your code was sent by SMS to ${Validation.maskMsisdn(x.receiverMsisdn)}.",
                            color = Color.White, fontSize = 13.sp)
                        if (myCode != null) Text("Open delivery code ›", color = Color.White, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    }
                } else NoteBox("Rider arrived, waiting for receiver code. ${x.receiverName} was sent a 5-digit code${x.codeChannel?.let { " by ${it.replace("+", " and ")}" } ?: ""}.", Tone.WARN, Icons.Outlined.Pin)
                ParcelStatus.DELIVERY_FAILED -> NoteBox("Delivery failed. ${if (x.returnStatus == "HOLD_AT_HUB") "It's being held at the hub." else "The rider will return it to you."} Support will contact you about any refund.", Tone.BRAND)
                else -> {}
            }
            if (x.unavailableDeadline != null && x.status == ParcelStatus.AWAITING_DELIVERY_CODE && x.iAmSender && x.unavailableResponse != "cancel") UnavailableActions(x)
            // pickup PIN (sender only), visually distinct from the delivery code
            if (x.iAmSender && pin != null && x.status in listOf(ParcelStatus.AWAITING_RIDER, ParcelStatus.RIDER_ASSIGNED)) {
                Row(Modifier.fillMaxWidth().background(Selo.TealTint, RoundedCornerShape(14.dp)).border(1.5.dp, Selo.Teal, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Inventory2, null, tint = Selo.Teal)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("PICKUP PIN", color = Selo.Teal, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Read it to the rider when they collect. This is not the delivery code.", fontSize = 12.sp, color = Selo.Mute)
                    }
                    Text(pin!!, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = 4.sp, color = Selo.Teal)
                }
            }
            if (x.riderName != null && x.status.active) SeloCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Selo.Red, CircleShape), contentAlignment = Alignment.Center) { Text(x.riderName.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = Color.White, fontWeight = FontWeight.ExtraBold) }
                    Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(x.riderName, fontWeight = FontWeight.Bold); Text("Plate ${x.riderPlate ?: "—"} · ETA about ${x.etaMin} min", color = Selo.Mute, fontSize = 12.sp) }
                }
                if (x.riderLat != null) Text("Live location updates every 30 seconds.", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            SeloCard {
                KvRow("From", x.pickup.short); KvRow("To", "${x.dropoff.short} · ${x.receiverName}")
                KvRow("Parcel", "${x.weightBand} · ${x.category}"); if (x.description.isNotBlank()) Sub(x.description)
                HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(vertical = 6.dp))
                KvRow("Delivery Fee", k(x.deliveryNgwee)); KvRow("Service Fee", k(x.serviceNgwee)); if (x.insuranceNgwee > 0) KvRow("Insurance", k(x.insuranceNgwee))
                KvRow("Total", k(x.totalNgwee), strong = true, valueColor = Selo.Red)
            }
            Timeline(x)
            // actions
            if (x.iAmSender && x.status in listOf(ParcelStatus.PENDING_PAYMENT, ParcelStatus.AWAITING_RIDER)) OutlineButton("Cancel parcel") { sheet = "cancel" }
            if (x.iAmSender && x.status in listOf(ParcelStatus.PENDING_PAYMENT, ParcelStatus.AWAITING_RIDER, ParcelStatus.RIDER_ASSIGNED)) OutlineButton("Change receiver's number") { sheet = "receiver" }
            if (x.status == ParcelStatus.DELIVERED && !x.iAmSender) PrimaryButton("Confirm receipt", color = Selo.Green) { scope.launch { runCatching { app.backend.confirmReceipt("parcel", x.id) }.onSuccess { msg = "Thanks! Delivery confirmed." } } }
            if (x.status in listOf(ParcelStatus.DELIVERED, ParcelStatus.COMPLETED)) OutlineButton("Rate the rider") { sheet = "rate" }
            if (x.status in listOf(ParcelStatus.DELIVERED, ParcelStatus.COMPLETED, ParcelStatus.DELIVERY_FAILED, ParcelStatus.IN_TRANSIT, ParcelStatus.AWAITING_DELIVERY_CODE)) OutlineButton("Report an issue") { sheet = "issue" }
        }
    }
    when (sheet) {
        "cancel" -> AlertDialog({ sheet = null }, title = { Text("Cancel this parcel?") }, text = { Text(if (x.status == ParcelStatus.AWAITING_RIDER) "You'll get a full refund of ${k(x.totalNgwee)} to your wallet." else "Nothing has been charged yet.") },
            confirmButton = { TextButton({ scope.launch { runCatching { app.backend.cancelParcel(x.id) }.onSuccess { r -> msg = if (r > 0) "Cancelled. ${k(r)} is on its way back to you." else "Cancelled." }.onFailure { msg = it.message }; sheet = null } }) { Text("Cancel parcel", color = Selo.Red) } },
            dismissButton = { TextButton({ sheet = null }) { Text("Keep it") } }, containerColor = Color.White)
        "receiver" -> ReceiverDialog(x, { sheet = null }) { m -> msg = m; sheet = null }
        "rate" -> RateDialog("parcel", x.id, x.riderName ?: "the rider", { sheet = null }) { msg = "Thanks for rating."; sheet = null }
        "issue" -> IssueDialog("parcel", x.id, { sheet = null }) { ref -> msg = "Issue $ref reported. Support replies within 24 hours."; sheet = null }
    }
}

@Composable
private fun Timeline(x: Parcel) {
    val order = listOf(ParcelStatus.PENDING_PAYMENT, ParcelStatus.AWAITING_RIDER, ParcelStatus.RIDER_ASSIGNED, ParcelStatus.IN_TRANSIT, ParcelStatus.AWAITING_DELIVERY_CODE, ParcelStatus.DELIVERED)
    val reached = x.timeline.associate { it.event to it.at }
    val labels = mapOf(ParcelStatus.PENDING_PAYMENT to "Booked", ParcelStatus.AWAITING_RIDER to "Paid · finding a rider", ParcelStatus.RIDER_ASSIGNED to "Rider assigned",
        ParcelStatus.IN_TRANSIT to "Picked up", ParcelStatus.AWAITING_DELIVERY_CODE to "Rider arrived · code sent", ParcelStatus.DELIVERED to "Delivered (code verified)")
    SeloCard {
        order.forEach { s ->
            val at = reached[s.name]; val done = at != null
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).background(if (done) Selo.Green else Selo.Line, CircleShape), contentAlignment = Alignment.Center) { if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                Text(labels[s] ?: s.label, Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold, color = if (done) Selo.Ink else Selo.Mute)
                Text(at?.let { SimpleDateFormat("HH:mm", Locale.UK).format(Date(it)) } ?: "", color = Selo.Mute, fontSize = 12.sp)
            }
        }
    }
}

/** §9.5: the receiver isn't answering — the sender chooses within the response window. */
@Composable
private fun UnavailableActions(x: Parcel) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var alt by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }
    val left = ((x.unavailableDeadline!! - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().background(Selo.AmberTint, RoundedCornerShape(14.dp)).border(1.dp, Selo.Amber, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The receiver isn't answering", fontWeight = FontWeight.ExtraBold)
        Text("Choose within $left min, or the delivery will be marked failed.", fontSize = 13.sp)
        done?.let { Text(it, color = Selo.GreenDark, fontWeight = FontWeight.Bold) }
        if (alt) {
            SeloField("Other contact's name", name, { name = it.take(60) }); SeloField("Their phone", phone, { phone = it.take(16) }, keyboard = KeyboardType.Phone)
            PrimaryButton("Send them a code", enabled = Validation.normaliseMsisdn(phone) != null) { scope.launch { runCatching { app.backend.respondUnavailable("parcel", x.id, "alt_contact", name, phone) }.onSuccess { done = "The rider will send a new code to $name."; alt = false }.onFailure { done = it.message } } }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ alt = true }, Modifier.weight(1f)) { Text("Other contact", color = Selo.Red, fontSize = 12.sp) }
            OutlinedButton({ scope.launch { runCatching { app.backend.respondUnavailable("parcel", x.id, "wait") }.onSuccess { done = "The rider will wait a little longer." }.onFailure { done = it.message } } }, Modifier.weight(1f)) { Text("Ask to wait", color = Selo.Red, fontSize = 12.sp) }
            OutlinedButton({ scope.launch { runCatching { app.backend.respondUnavailable("parcel", x.id, "cancel") }.onSuccess { done = "Delivery cancelled. The parcel comes back to you." } } }, Modifier.weight(1f)) { Text("Cancel", color = Selo.Red, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ReceiverDialog(x: Parcel, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(x.receiverName) }; var phone by remember { mutableStateOf(x.receiverMsisdn.replace("+260", "0")) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismiss, containerColor = Color.White, title = { Text("Receiver") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeloField("Name", name, { name = it.take(60) }); SeloField("Phone (gets the delivery code)", phone, { phone = it.take(16) }, keyboard = KeyboardType.Phone); ErrorText(err) } },
        confirmButton = { TextButton({ scope.launch { runCatching { app.backend.editParcelReceiver(x.id, name, phone) }.onSuccess { onDone("Receiver updated.") }.onFailure { err = it.message } } }) { Text("Save", color = Selo.Red) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun RateDialog(kind: String, id: String, who: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var stars by remember { mutableIntStateOf(0) }; var comment by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismiss, containerColor = Color.White, title = { Text("Rate $who") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row { (1..5).forEach { i -> Icon(if (i <= stars) Icons.Filled.Star else Icons.Outlined.StarBorder, "$i stars", tint = Color(0xFFF5B301), modifier = Modifier.size(40.dp).clickable { stars = i }) } }
        SeloField("Comment (optional)", comment, { comment = it.take(300) }); ErrorText(err) } },
        confirmButton = { TextButton({ scope.launch { runCatching { app.backend.rateDelivery(kind, id, stars, comment) }.onSuccess { onDone() }.onFailure { err = it.message } } }, enabled = stars > 0) { Text("Submit", color = Selo.Red) } },
        dismissButton = { TextButton(onDismiss) { Text("Not now") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueDialog(kind: String, id: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(ISSUE_TYPES.first()) }; var desc by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismiss, containerColor = Color.White, title = { Text("Report an issue") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { ISSUE_TYPES.forEach { t -> SeloChip(t, type == t) { type = t } } }
        SeloField("What happened?", desc, { desc = it.take(1000) }, singleLine = false)
        Text("Within 24 hours of delivery. Support reviews the delivery-code record for every report.", color = Selo.Mute, fontSize = 12.sp); ErrorText(err) } },
        confirmButton = { TextButton({ scope.launch { runCatching { app.backend.reportIssue(kind, id, type, desc) }.onSuccess(onDone).onFailure { err = it.message } } }, enabled = desc.trim().length >= 5) { Text("Send", color = Selo.Red) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/* =================================================================== receiver's Delivery Code screen (§9.8) */

/**
 * Full-screen code card. Screenshots and the app-switcher preview are blocked while it's open (FLAG_SECURE).
 * The code comes from the push this phone received; it is never fetched from the server (the server only has a hash).
 */
@Composable
fun DeliveryCodeScreen(kind: String, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalAppState.current
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val codes by DeliveryCodeStore.codes.collectAsState()
    val code = codes.firstOrNull { it.kind == kind && it.orderId == id }
    val parcel by remember(id) { if (kind == "parcel") app.backend.parcel(id) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val order by remember(id) { if (kind == "order") app.backend.order(id) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val delivered = parcel?.status in listOf(ParcelStatus.DELIVERED, ParcelStatus.COMPLETED) || order?.state in listOf(OrderState.DELIVERED, OrderState.COMPLETED)
    LaunchedEffect(delivered) { if (delivered) DeliveryCodeStore.clear(kind, id) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        SeloHeader("Delivery code", if (kind == "parcel") "Parcel $id" else "Order $id", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                delivered -> { Icon(Icons.Outlined.CheckCircle, null, tint = Selo.Green, modifier = Modifier.size(72.dp)); Text("Delivered — the code has been used.", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
                code == null -> {
                    Icon(Icons.Outlined.Sms, null, tint = Selo.Mute, modifier = Modifier.size(60.dp))
                    Text("No code on this phone yet", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("You get the code when the rider arrives — by notification, or by SMS if the notification doesn't arrive.", textAlign = TextAlign.Center, color = Selo.Mute)
                }
                else -> {
                    val left = ((code.expiresAt - now) / 1000).coerceAtLeast(0)
                    Text("Give this code to the rider", color = Selo.Mute)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        code.code.forEach { c -> Box(Modifier.size(56.dp, 72.dp).background(Selo.Surface, RoundedCornerShape(12.dp)).border(2.dp, Selo.Red, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(c.toString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp) } }
                    }
                    Text(if (left > 0) "Expires in %d:%02d".format(left / 60, left % 60) else "Expired — ask the rider to tap Resend Code", color = if (left > 0) Selo.Mute else Selo.Red, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().background(Selo.AmberTint, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = Selo.AmberDark)
                        Text("  Only give this code to the rider when you have your item in hand. Never share it by phone or message.", color = Selo.AmberDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            parcel?.let { p -> SeloCard { KvRow("From", p.senderName); KvRow("Rider", "${p.riderName ?: "—"} · ${p.riderPlate ?: ""}"); KvRow("Item", p.description.ifBlank { p.category }) } }
            order?.let { o -> SeloCard { KvRow("Seller", o.sellerName); KvRow("Rider", o.riderName ?: "—"); KvRow("Items", o.items.joinToString { "${it.qty} × ${it.title}" }) } }
        }
    }
}
