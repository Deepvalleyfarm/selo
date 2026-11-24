package selo.Nachipa01.ui.screens.buyer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.repo.ParcelRequest
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** C1: SeloExpress quote, optional insurance and payment in one screen. Rates come live from platformConfig. */
@Composable
fun ParcelScreen(onBack: () -> Unit, onBooked: (String) -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    var pickup by remember { mutableStateOf("") }
    var dropoff by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
    var recipientNo by remember { mutableStateOf("") }
    var contents by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("Medium") }
    var insured by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf(false) }
    var declared by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val distance = if (pickup.isNotBlank() && dropoff.isNotBlank()) 7.4 else 0.0 // replaced by Directions API distance from parcel_quote
    val declaredNgwee = Fees.kwachaToNgwee(declared.toDoubleOrNull() ?: 0.0)
    val q = Fees.quote(0, distance, cfg, if (insured) declaredNgwee else 0)
    val total = q.totalNgwee

    Column(Modifier.fillMaxSize()) {
        SeloHeader("Send a parcel", "SeloExpress · escrow protected", onBack = onBack, trailing = { Icon(Icons.Outlined.Inventory2, null, tint = Color.White) })
        FormColumn {
            SeloField("Pickup (market, street, landmark)", pickup, { pickup = it })
            SeloField("Drop-off", dropoff, { dropoff = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeloField("Recipient name", recipient, { recipient = it }, Modifier.weight(1f))
                SeloField("Recipient number", recipientNo, { recipientNo = it }, Modifier.weight(1f), keyboard = KeyboardType.Phone)
            }
            SeloField("What's inside", contents, { contents = it })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Small", "Medium", "Large").forEach { s -> SeloChip(s, size == s) { size = s } } }
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(1.3.dp, Selo.Teal, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = Selo.Teal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Insure my parcel", fontWeight = FontWeight.SemiBold)
                IconButton({ tip = !tip }) { Icon(Icons.Outlined.Info, "What is covered", tint = Selo.Teal, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.weight(1f))
                Switch(insured, { insured = it }, colors = SwitchDefaults.colors(checkedTrackColor = Selo.Green))
            }
            if (tip) Text("Covers loss or theft up to K ${"%,d".format(cfg.insuranceMaxDeclaredValue.toInt())}, full replacement for transit damage, priority dispatch and fast-track escrow refund.",
                color = Color.White, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Selo.Ink, RoundedCornerShape(10.dp)).padding(10.dp))
            if (insured) {
                SeloField("Declared goods value (max K ${"%,d".format(cfg.insuranceMaxDeclaredValue.toInt())})", declared, { declared = it }, keyboard = KeyboardType.Decimal,
                    error = if ((declared.toDoubleOrNull() ?: 0.0) > cfg.insuranceMaxDeclaredValue) "Maximum declared value is K ${cfg.insuranceMaxDeclaredValue.toInt()}." else null)
            }
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Selo.Teal, Selo.Green)), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(if (insured) "Protection summary" else "Quote", color = Color.White, fontWeight = FontWeight.ExtraBold)
                @Composable fun row(a: String, b: String, strong: Boolean = false) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(a, color = if (strong) Color.White else Color(0xFFD1FAE5), fontSize = 13.sp, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(b, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                if (insured) { row("Cargo value", Fees.formatK(declaredNgwee)); row("Premium rate", "${cfg.insurancePremiumPct}%"); row("Premium", "+ ${Fees.formatK(q.insurancePremiumNgwee)}") }
                row("Delivery · ${if (distance > 0) "$distance km × K ${cfg.deliveryFeePerKm.toInt()} + K ${cfg.deliveryBaseFee.toInt()} base" else "enter both addresses"}",
                    if (distance > 0) Fees.formatK(total - q.insurancePremiumNgwee) else "—")
                HorizontalDivider(color = Color.White.copy(alpha = .35f), modifier = Modifier.padding(vertical = 4.dp))
                row(if (insured) "Adjusted total" else "Total", if (distance > 0) Fees.formatK(total) else "—", true)
            }
            ErrorText(error)
            PrimaryButton(if (distance > 0) "Pay ${Fees.formatK(total)} & book rider" else "Pay & book rider", color = Selo.Green, loading = busy,
                enabled = distance > 0 && contents.isNotBlank() && recipient.isNotBlank() && (!insured || declaredNgwee > 0)) {
                val r = Validation.normaliseMsisdn(recipientNo) ?: run { error = "Enter the recipient's mobile number."; return@PrimaryButton }
                busy = true; error = null
                scope.launch {
                    runCatching { app.backend.sendParcel(ParcelRequest(pickup, dropoff, app.session.value?.fullName ?: "", recipient, r, contents, size,
                        insured, declaredNgwee, distance, app.session.value?.msisdn ?: "")) }
                        .onSuccess(onBooked).onFailure { error = it.message }
                    busy = false
                }
            }
        }
    }
}

/** C2: parcel tracking with the insured badge and INS ID. */
@Composable
fun ParcelTrackScreen(orderId: String, onBack: () -> Unit) {
    val backend = LocalAppState.current.backend
    val order by remember { backend.order(orderId) }.collectAsState(null)
    val o = order ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Parcel ${o.id}", "${o.pickup} → ${o.dropoff}", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MapPlaceholder(140, Selo.Teal)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SeloCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.id, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        if (o.insuranceId != null) StatusChip("Insured (${Fees.formatK(o.declaredValueNgwee).removeSuffix(".00")})", Tone.OK)
                    }
                    if (o.insuranceId != null) KvRow("Insurance ID", o.insuranceId, mono = true)
                    KvRow("Contents", o.items.firstOrNull()?.title ?: "")
                    KvRow("Rider", o.riderName ?: "Searching…")
                }
                OrderTimeline(o)
                if (o.deliveryCode != null) SeloCard {
                    Text("Recipient's delivery code (sent in-app)", color = Selo.Mute, fontSize = 11.sp)
                    Text(o.deliveryCode.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            }
        }
    }
}
