package selo.Nachipa01.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Date
import java.util.Locale

/** A small back stack over the account screens, so e.g. Support → a request thread → back works anywhere it's embedded. */
@Composable
fun AccountHost(start: String, onExit: () -> Unit) {
    var stack by remember(start) { mutableStateOf(listOf(start)) }
    androidx.activity.compose.BackHandler { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }
    if (stack.last() == "inbox") selo.Nachipa01.ui.screens.rider.NotificationsScreen { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }
    else AccountScreen(stack.last(), onBack = { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }, open = { stack = stack + it })
}

/** Routes for the shared account screens. Every row in Account / More menus opens one of these. */
@Composable
fun AccountScreen(route: String, onBack: () -> Unit, open: (String) -> Unit) {
    when {
        route == "profile" -> ProfileEditScreen(onBack)
        route == "addresses" -> AddressesScreen(onBack)
        route == "wallets" -> WalletsScreen(onBack)
        route == "pin" -> ChangePinScreen(onBack)
        route == "questions" -> SecurityQuestionsScreen(onBack)
        route == "notifications" -> NotificationPrefsScreen(onBack)
        route == "support" -> SupportScreen(onBack, open)
        route == "support/new" -> ContactFormScreen(onBack) { id -> open("support/t/$id") }
        route.startsWith("support/t/") -> TicketThreadScreen(route.removePrefix("support/t/"), onBack)
        else -> onBack()
    }
}

/** Account settings list shared by buyer Account and seller More. Each row opens a working screen. */
@Composable
fun AccountSettingsCard(open: (String) -> Unit, extra: List<Triple<ImageVector, String, () -> Unit>> = emptyList()) {
    val app = LocalAppState.current
    val saved by app.backend.savedAddresses().collectAsState(emptyList())
    val wallets by app.backend.paymentWallets().collectAsState(emptyList())
    val tickets by app.backend.supportTickets().collectAsState(emptyList())
    SeloCard(padding = 4.dp) {
        val rows = extra + listOf(
            Triple(Icons.Outlined.Person, "Edit profile") { open("profile") },
            Triple(Icons.Outlined.Place, "Delivery addresses (${saved.size})") { open("addresses") },
            Triple(Icons.Outlined.AccountBalanceWallet, "Payment wallets (${1 + wallets.size})") { open("wallets") },
            Triple(Icons.Outlined.Lock, "Change 5-digit PIN") { open("pin") },
            Triple(Icons.Outlined.Shield, "Security questions") { open("questions") },
            Triple(Icons.Outlined.Notifications, "Notification settings") { open("notifications") },
            Triple(Icons.Outlined.SupportAgent, "Help & support" + (tickets.sumOf { it.unread }.takeIf { it > 0 }?.let { " ($it new)" } ?: "")) { open("support") },
        )
        rows.forEachIndexed { i, (icon, label, go) ->
            if (i > 0) HorizontalDivider(color = Selo.Line)
            Row(Modifier.fillMaxWidth().clickable(onClick = go).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Selo.Red, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Color(0xFFB5B5BE))
            }
        }
    }
}

/* ------------------------------------------------------------------ profile */

@Composable
private fun ProfileEditScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val u = app.session.value
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(u?.fullName ?: "") }
    var area by remember { mutableStateOf(u?.area ?: "") }
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }; var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Edit profile", onBack = onBack)
        FormColumn {
            SeloField("Full name", name, { name = it.take(80) })
            SeloField("Area / neighbourhood", area, { area = it.take(60) })
            SeloField("Mobile number (login)", u?.msisdn?.replace("+260", "0") ?: "", {}, trailing = Icons.Outlined.Lock)
            Sub("Your number is your login. To change it, contact support so we can verify it's you.")
            msg?.let { NoteBox(it, Tone.OK) }; ErrorText(err)
            PrimaryButton("Save", loading = busy, enabled = name.trim().length >= 3) {
                busy = true; err = null; msg = null
                scope.launch { runCatching { app.backend.updateProfile(mapOf("fullName" to name.trim(), "area" to area.trim())) }
                    .onSuccess { app.session.value = app.session.value?.copy(fullName = name.trim(), firstName = name.trim().substringBefore(" "), area = area.trim()); msg = "Profile saved." }
                    .onFailure { err = it.message }; busy = false }
            }
        }
    }
}

/* ------------------------------------------------------------------ addresses */

@Composable
private fun AddressesScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val saved by app.backend.savedAddresses().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(saved.isEmpty()) }
    var place by remember { mutableStateOf<Place?>(null) }
    var nick by remember { mutableStateOf("") }
    var def by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Delivery addresses", "Used for checkout and parcels", onBack = onBack)
        FormColumn {
            saved.forEach { a ->
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (a.isDefault) Icons.Outlined.Home else Icons.Outlined.Place, null, tint = Selo.Red)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(a.nickname + if (a.isDefault) " · default" else "", fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(a.place.short, a.place.cityName).joinToString(" · "), color = Selo.Mute, fontSize = 12.sp)
                    }
                    IconButton({ scope.launch { runCatching { app.backend.deleteMyAddress(a.id) } } }) { Icon(Icons.Outlined.Delete, "Delete ${a.nickname}", tint = Selo.Mute) }
                }
            }
            if (!adding) OutlineButton("Add an address") { adding = true }
            else {
                AddressField("New address", place, { place = it }, showSaved = false)
                SeloField("Name it (e.g. Home, Work)", nick, { nick = it.take(40) })
                CheckRow(def, { def = it }, "Make this my default delivery address")
                ErrorText(err)
                PrimaryButton("Save address", loading = busy, enabled = place?.inServiceArea == true && nick.isNotBlank()) {
                    busy = true; err = null
                    scope.launch { runCatching { app.backend.saveMyAddress(nick.trim(), place!!, def || saved.isEmpty()) }
                        .onSuccess { adding = false; place = null; nick = ""; def = false; app.toast.value = "Address saved." }.onFailure { err = it.message }; busy = false }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ wallets */

@Composable
private fun WalletsScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val u = app.session.value
    val wallets by app.backend.paymentWallets().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val op = Operator.detect(number)
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Payment wallets", "Pay with any of these at checkout", onBack = onBack)
        FormColumn {
            u?.msisdn?.let { m -> Operator.detect(m)?.let { o -> WalletRow(o, m, "Registered number · default") {} } }
            wallets.forEach { w -> Operator.detect(w)?.let { o -> WalletRow(o, w, "Saved") { scope.launch { runCatching { app.backend.savePaymentWallets(wallets - w) }.onFailure { err = it.message } } } } }
            SectionLabel("Add a wallet")
            SeloField("Mobile money number", number, { number = it.take(16) }, keyboard = KeyboardType.Phone, placeholder = "e.g. 0966 123 456",
                error = if (Operator.unsupported(number)) "Not an Airtel, MTN or Zamtel number." else null)
            op?.let { Row(verticalAlignment = Alignment.CenterVertically) { OperatorMark(it, 28.dp); Text("  ${it.label}", fontWeight = FontWeight.Bold) } }
            ErrorText(err)
            PrimaryButton("Add wallet", loading = busy, enabled = Validation.normaliseMsisdn(number) != null && op != null) {
                busy = true; err = null
                scope.launch { runCatching { app.backend.savePaymentWallets(wallets + number) }.onSuccess { number = ""; app.toast.value = "Wallet added." }.onFailure { err = it.message }; busy = false }
            }
            if (u?.role != Role.BUYER) Sub("Payouts go to your payout wallet. Change it from Settings & support (riders) or Business settings (sellers and agents).")
        }
    }
}

@Composable
private fun WalletRow(op: Operator, msisdn: String, label: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OperatorMark(op, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(op.label, fontWeight = FontWeight.Bold); Text("${Validation.maskMsisdn(msisdn)} · $label", color = Selo.Mute, fontSize = 12.sp) }
        if (label != "Registered number · default") IconButton(onRemove) { Icon(Icons.Outlined.Delete, "Remove", tint = Selo.Mute) }
    }
}

/* ------------------------------------------------------------------ PIN */

@Composable
fun ChangePinScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }; var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Change PIN", onBack = onBack)
        FormColumn {
            if (done) { NoteBox("PIN changed. Use your new PIN next time you log in.", Tone.OK, Icons.Outlined.CheckCircle); PrimaryButton("Done", onClick = onBack); return@FormColumn }
            Text("Current PIN", fontWeight = FontWeight.Bold); PinInput(cur, { cur = it; err = null })
            Text("New 5-digit PIN", fontWeight = FontWeight.Bold); PinInput(new, { new = it; err = null }, error = new.length == 5 && Validation.pinProblem(new) != null)
            if (new.length == 5) Validation.pinProblem(new)?.let { Text(it, color = Selo.Red, fontSize = 12.sp) }
            Text("Confirm new PIN", fontWeight = FontWeight.Bold); PinInput(again, { again = it; err = null }, error = again.length == 5 && again != new)
            if (again.length == 5 && again != new) Text("PINs don't match.", color = Selo.Red, fontSize = 12.sp)
            if (new.length == 5 && new == cur) Text("Choose a PIN different from your current one.", color = Selo.Red, fontSize = 12.sp)
            ErrorText(err)
            PrimaryButton("Change PIN", loading = busy, enabled = cur.length == 5 && new.length == 5 && new == again && new != cur && Validation.pinProblem(new) == null) {
                busy = true; err = null
                scope.launch { runCatching { app.backend.changePin(cur, new) }.onSuccess { done = true }.onFailure { err = it.message; cur = "" }; busy = false }
            }
        }
    }
}

/* ------------------------------------------------------------------ security questions */

/** View the chosen questions (never answers), and replace them after re-entering the current PIN. Answers are hashed server-side. */
@Composable
fun SecurityQuestionsScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<List<SecurityQuestion>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var bank by remember { mutableStateOf(SecurityQuestions.BANK) }
    var offered by remember { mutableStateOf<List<SecurityQuestion>>(emptyList()) }
    var picked by remember { mutableStateOf(mapOf<String, String>()) }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        current = runCatching { app.backend.myQuestions() }.getOrDefault(emptyList())
        bank = SecurityQuestions.merge(runCatching { app.backend.questionBank() }.getOrDefault(emptyList()))
    }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Security questions", "Used to reset your PIN", onBack = onBack)
        FormColumn {
            if (done) { NoteBox("Security questions updated. Use the new answers next time you reset your PIN.", Tone.OK, Icons.Outlined.CheckCircle); PrimaryButton("Done", onClick = onBack); return@FormColumn }
            SectionLabel("Your questions")
            if (current.isEmpty()) Sub("You haven't set any yet.")
            current.forEachIndexed { i, q -> Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, color = Selo.Red); Text("  ${q.text}", Modifier.weight(1f)); Text("•••••", color = Selo.Mute) } }
            Sub("Answers are stored scrambled (hashed), so nobody — including SeloNaChipa — can read them.")
            if (!editing) PrimaryButton(if (current.isEmpty()) "Set questions" else "Change questions") { editing = true; offered = SecurityQuestions.offer(bank, exclude = current.map { it.id }.toSet().takeIf { bank.size - it.size >= 4 } ?: emptySet()) }
            else if (!unlocked) {
                Text("Enter your current PIN to continue", fontWeight = FontWeight.Bold); PinInput(pin, { pin = it; err = null })
                ErrorText(err)
                PrimaryButton("Continue", enabled = pin.length == 5) { unlocked = true }
            } else {
                Text("Choose ${SecurityQuestions.REQUIRED} of these and answer them", fontWeight = FontWeight.Bold)
                offered.forEach { q ->
                    CheckRow(q.id in picked, { on -> picked = if (on && picked.size < SecurityQuestions.REQUIRED) picked + (q.id to "") else picked - q.id }, q.text)
                    if (q.id in picked) SeloField("Answer", picked[q.id] ?: "", { a -> picked = picked + (q.id to a.take(60)) }, secret = true)
                }
                TextButton({ offered = SecurityQuestions.offer(bank); picked = emptyMap() }) { Text("Show different questions", color = Selo.Red) }
                ErrorText(err)
                PrimaryButton("Save questions", loading = busy, enabled = picked.size == SecurityQuestions.REQUIRED && picked.values.all { Validation.answerProblem(it) == null }) {
                    busy = true; err = null
                    scope.launch { runCatching { app.backend.updateSecurityQuestions(pin, picked.map { QuestionAnswer(it.key, it.value) }) }
                        .onSuccess { done = true }.onFailure { e -> err = e.message; if (e.message?.contains("PIN", true) == true) { unlocked = false; pin = "" } }; busy = false }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ notifications */

@Composable
private fun NotificationPrefsScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val saved by app.backend.notificationPrefs().collectAsState(emptyMap())
    var prefs by remember(saved) { mutableStateOf(mapOf("orders" to true, "chat" to true, "promotions" to false, "weeklySummary" to true) + saved) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Notification settings", onBack = onBack)
        FormColumn {
            SeloCard {
                listOf("orders" to ("Orders and deliveries" to "Payment, rider, delivery-code alerts (always on for codes)"), "chat" to ("Messages" to "Riders, buyers and sellers about your orders"),
                    "promotions" to ("Deals and promotions" to "Offers from sellers you follow"), "weeklySummary" to ("Weekly summary" to "Your spending and savings each Monday")).forEach { (k, v) ->
                    SwitchRow(v.first, prefs[k] == true, { on -> prefs = prefs + (k to on)
                        scope.launch { runCatching { app.backend.saveNotificationPrefs(prefs) }.onSuccess { app.toast.value = "Saved." }.onFailure { app.toast.value = it.message } } }, sub = v.second)
                }
            }
            Sub("Delivery codes are always sent — you can't receive an order without one.")
        }
    }
}

/* ------------------------------------------------------------------ support */

private val FAQ = listOf(
    "How does escrow protect me?" to "Your payment is held by SeloNaChipa. The seller and rider are only paid after the rider enters the 5-digit code you receive when they arrive.",
    "When do I get my delivery code?" to "When the rider is at your door, by notification (or SMS if you don't have the app). Only give it to the rider once the item is in your hands.",
    "The rider is asking for my code by phone" to "Never share the code by phone or message. Riders must be with you in person. Report it from the order screen.",
    "How do refunds work?" to "Cancelled or undelivered orders are refunded to the wallet you paid with, usually within 2 hours. You'll get an SMS when it's sent.",
    "I forgot my PIN" to "On the login screen tap \"Forgot PIN?\" and answer your 3 security questions. The reset link works for 10 minutes and signs out other devices.",
    "How is the delivery fee worked out?" to "From the road distance between the seller (or pickup) and your delivery address. It's shown before you pay.",
    "Can I send a parcel to another city?" to "Not yet. Parcels are same-city only, up to 5 kg.",
)

@Composable
fun SupportScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val app = LocalAppState.current
    val uri = LocalUriHandler.current
    val tickets by app.backend.supportTickets().collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Help & support", "We reply within 24 hours", onBack = onBack)
        FormColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportTile(Icons.Outlined.Chat, "Message us", Modifier.weight(1f)) { open("support/new") }
                SupportTile(Icons.Outlined.Forum, "WhatsApp", Modifier.weight(1f)) { uri.openUri("https://wa.me/260978070734") }
                SupportTile(Icons.Outlined.Call, "Call", Modifier.weight(1f)) { uri.openUri("tel:+260978070734") }
            }
            if (tickets.isNotEmpty()) {
                SectionLabel("Your requests")
                tickets.forEach { t ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { open("support/t/${t.id}") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${t.reference} · ${t.subject}", fontWeight = FontWeight.Bold, maxLines = 1)
                            Text((if (t.lastFromSupport) "Support: " else "You: ") + t.lastMessage, color = Selo.Mute, fontSize = 12.sp, maxLines = 1)
                        }
                        if (t.unread > 0) Text("${t.unread}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Selo.Coral, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
                        Spacer(Modifier.width(6.dp))
                        StatusChip(when (t.status) { "RESOLVED" -> "Resolved"; "AWAITING_USER" -> "Replied"; else -> "Open" }, when (t.status) { "RESOLVED" -> Tone.OK; "AWAITING_USER" -> Tone.WARN; else -> Tone.INFO })
                    }
                }
            }
            SectionLabel("Frequently asked")
            SeloField("Search help", q, { q = it }, trailing = Icons.Outlined.Search)
            val shown = FAQ.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }
            if (shown.isEmpty()) Sub("No answers match. Tap \"Message us\" and we'll help.")
            shown.forEach { (question, answer) ->
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { expanded = if (expanded == question) null else question }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(question, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(if (expanded == question) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Selo.Mute) }
                    if (expanded == question) Text(answer, color = Selo.Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SupportTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(Color.White, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Selo.Red); Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ContactFormScreen(onBack: () -> Unit, onSent: (String) -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var subject by remember { mutableStateOf("") }; var category by remember { mutableStateOf(SUPPORT_CATEGORIES.first()) }
    var ref by remember { mutableStateOf("") }; var message by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Message support", onBack = onBack)
        FormColumn {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { SUPPORT_CATEGORIES.forEach { c -> SeloChip(c, category == c) { category = c } } }
            SeloField("Subject", subject, { subject = it.take(120) })
            SeloField("Order or parcel ref (optional)", ref, { ref = it.uppercase().take(20) }, placeholder = "e.g. SNC-48241 or PCL-7Q2M4X1")
            SeloField("How can we help?", message, { message = it.take(2000) }, singleLine = false)
            ErrorText(err)
            PrimaryButton("Send", loading = busy, enabled = subject.trim().length >= 3 && message.trim().length >= 10) {
                busy = true; err = null
                scope.launch { runCatching { app.backend.createTicket(subject.trim(), category, message.trim(), ref) }.onSuccess(onSent).onFailure { err = it.message }; busy = false }
            }
            Sub("We reply in the app within 24 hours. You'll get a notification.")
        }
    }
}

@Composable
private fun TicketThreadScreen(id: String, onBack: () -> Unit) {
    val app = LocalAppState.current
    val tickets by app.backend.supportTickets().collectAsState(emptyList())
    val msgs by remember(id) { app.backend.ticketMessages(id) }.collectAsState(emptyList())
    val t = tickets.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        SeloHeader(t?.reference ?: "Support", t?.subject ?: "", onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromSupport) Alignment.CenterStart else Alignment.CenterEnd) {
                    Column(Modifier.widthIn(max = 290.dp).background(if (m.fromSupport) Color(0xFFECECF0) else Selo.Red, RoundedCornerShape(14.dp)).padding(10.dp)) {
                        if (m.fromSupport) Text(m.agent ?: "Support", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Selo.Red)
                        Text(m.text, color = if (m.fromSupport) Selo.Ink else Color.White)
                        Text(SimpleDateFormat("d MMM, HH:mm", Locale.UK).format(Date(m.at)), fontSize = 10.sp, color = if (m.fromSupport) Selo.Mute else Color.White.copy(.75f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(2000) }, Modifier.weight(1f), placeholder = { Text(if (t?.status == "RESOLVED") "Reply to reopen" else "Reply") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
            IconButton({ val v = text.trim(); if (v.isNotEmpty()) { text = ""; scope.launch { runCatching { app.backend.replyTicket(id, v) }.onFailure { app.toast.value = it.message } } } }) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Selo.Red) }
        }
    }
}


/** Payout wallet (sellers, agents, riders): confirmed with a Lipila name lookup server-side. */
@Composable
fun PayoutWalletDialog(onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val op = Operator.detect(number)
    AlertDialog(onDone, containerColor = Color.White, title = { Text("Payout wallet") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Operator.entries.forEach { OperatorMark(it, 30.dp) } }
        SeloField("Mobile money number", number, { number = it.take(16) }, keyboard = KeyboardType.Phone, error = if (Operator.unsupported(number)) "Not an Airtel, MTN or Zamtel number." else null)
        op?.let { Text(it.label, fontWeight = FontWeight.Bold) }
        Sub("We check the wallet name with your mobile money provider before switching."); ErrorText(err) } },
        confirmButton = { TextButton({ busy = true; scope.launch { runCatching { app.backend.setPayoutWallet(number) }.onSuccess { app.toast.value = "Payouts will go to ${Validation.maskMsisdn(Validation.normaliseMsisdn(number)!!)}."; onDone() }
            .onFailure { err = it.message }; busy = false } }, enabled = !busy && op != null && Validation.normaliseMsisdn(number) != null) { Text("Switch", color = Selo.Red) } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
}
