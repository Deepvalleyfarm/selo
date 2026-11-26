package selo.Nachipa01.ui.screens.auth

import android.Manifest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.data.model.SecurityQuestion
import selo.Nachipa01.data.model.SessionUser
import selo.Nachipa01.data.repo.QuestionAnswer
import selo.Nachipa01.data.repo.RegistrationRequest
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.SecurityQuestions
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** Everything the wizard collects; survives page changes. */
class RegState(val role: Role) {
    val text = mutableStateMapOf<String, String>()
    val chips = mutableStateMapOf<String, Set<String>>()
    val docs = mutableStateMapOf<String, Uri>()
    /** The 4 shuffled questions on offer, the ids picked (in order) and an answer per picked id. */
    val offered = mutableStateListOf<SecurityQuestion>()
    val questionIds = mutableStateListOf<String>()
    val answers = mutableStateMapOf<String, String>()
    var pin by mutableStateOf(""); var pinConfirm by mutableStateOf("")
    var biometric by mutableStateOf(true)
    var walletNetwork by mutableStateOf("Airtel Money")
    var walletHolder by mutableStateOf<String?>(null)
    val ticks = mutableStateMapOf<String, Boolean>()
    val hours = mutableStateMapOf<String, Boolean>().apply { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { put(it, true) }; put("Sun", false) }
    operator fun get(k: String) = text[k] ?: ""
    fun set(k: String, v: String) { text[k] = v }
    fun toggle(group: String, v: String) { val s = chips[group] ?: emptySet(); chips[group] = if (v in s) s - v else s + v }
    fun msisdn() = Validation.normaliseMsisdn(this["mobile"])
    /** Set when the SMS code for [otpFor] was verified; changing the number afterwards needs a new code. */
    var otpToken by mutableStateOf<String?>(null); var otpFor by mutableStateOf<String?>(null); var otpAt = 0L
    /** Set by the wizard: writes the draft to private storage right away (photo picks, ID type changes). */
    var saver: (() -> Unit)? = null
    fun autosave() { saver?.invoke() }
    /** Everything that should trigger an auto-save (read inside snapshotFlow). */
    fun fingerprint(): String = "${text.toMap()}|${chips.toMap()}|${docs.toMap()}|${questionIds.toList()}|$walletNetwork|$walletHolder|${ticks.toMap()}|${hours.toMap()}|$biometric|$otpToken"
}

/** One wizard screen = one or more numbered steps of the role's flow. */
private class Page(val title: String, val from: Int, val to: Int, val validate: (RegState) -> String?, val body: @Composable (RegState) -> Unit)

private fun total(role: Role) = when (role) { Role.BUYER -> 6; Role.SELLER -> 9; Role.AGENT -> 8; Role.RIDER -> 11 }

@Composable
fun RegistrationWizard(role: Role, onExit: () -> Unit, onRegistered: (SessionUser) -> Unit, initialPage: Int = 0) {
    val app = LocalAppState.current
    val state = remember { RegState(role) }
    // Built-in bank first so the questions always show; Firestore wording replaces it once loaded.
    var bank by remember { mutableStateOf(SecurityQuestions.BANK) }
    LaunchedEffect(Unit) { bank = runCatching { app.backend.questionBank() }.getOrDefault(SecurityQuestions.BANK).ifEmpty { SecurityQuestions.BANK } }
    LaunchedEffect(bank) {
        if (state.offered.isEmpty()) state.offered.addAll(SecurityQuestions.offer(bank))
        else state.offered.replaceAll { q -> bank.firstOrNull { it.id == q.id } ?: q }
    }
    val pages = remember(bank) { pagesFor(role, bank) }
    var index by rememberSaveable { mutableIntStateOf(initialPage) }
    val ctx = LocalContext.current
    // Auto-save: restore an unfinished sign-up for this role, then save every change (debounced) and every page move.
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(bank) {
        if (restored) return@LaunchedEffect
        restored = true
        RegDraft.restore(ctx, state, bank)?.let { saved ->
            // PIN and security answers are never stored: reopen at the security page if they are needed again.
            val sec = pages.indexOfFirst { it.title == "Security" }
            index = if (sec in 0 until saved && state.pin.isEmpty()) sec else saved.coerceIn(0, pages.lastIndex)
            if (state.otpToken != null && state.otpFor != null) app.backend.setPreAuth(state.otpFor, state.otpToken)
            app.info("Welcome back — we kept what you'd entered. Continue from step ${pages[index].from}.")
        }
    }
    LaunchedEffect(state) {
        state.saver = { RegDraft.save(ctx, state, index) }
        androidx.compose.runtime.snapshotFlow { state.fingerprint() to index }.collect { (_, i) -> kotlinx.coroutines.delay(400); if (restored) RegDraft.save(ctx, state, i) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val page = pages[index]
    val last = index == pages.lastIndex

    fun back() { error = null; if (index == 0) onExit() else index-- }
    BackHandler(onBack = ::back)

    fun next() {
        error = page.validate(state)
        if (error != null) { app.fail(error); return }
        if (!last) { index++; return }
        val msisdn = state.msisdn()!!
        submitting = "Submitting"
        scope.launch {
            runCatching {
                app.backend.register(RegistrationRequest(
                    role = role, msisdn = msisdn, pin = state.pin,
                    questions = state.questionIds.map { QuestionAnswer(it, state.answers[it].orEmpty()) },
                    profile = state.text.toMap() + state.chips.mapValues { it.value.toList() } +
                        mapOf("workingDays" to state.hours.filterValues { it }.keys.toList(), "biometric" to state.biometric,
                            "walletNetwork" to state.walletNetwork),
                    payoutWallet = Validation.normaliseMsisdn(state["wallet"]),
                    referralCode = state["referral"].ifBlank { null },
                    kycDocs = state.docs.toMap(), otpToken = state.otpToken,
                )) { submitting = it }
            }.onSuccess { RegDraft.clear(ctx); onRegistered(it) }.onFailure { error = it.message ?: "We couldn't create your account. Check your connection and try again."; app.fail(error) }
            submitting = null
        }
    }

    val label = if (page.from == page.to) "Step ${page.from} of ${total(role)}" else "Steps ${page.from}–${page.to} of ${total(role)}"
    val titles = mapOf(Role.BUYER to "Buyer profile", Role.SELLER to "Seller profile", Role.AGENT to "Agent profile", Role.RIDER to "Rider application")
    Column(Modifier.fillMaxSize()) {
        SeloHeader(titles[role]!!, "$label · ${page.title}", onBack = ::back, steps = page.to to total(role),
            trailing = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CloudDone, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(14.dp))
                Text(" Autosaved", color = Color.White.copy(alpha = .85f), fontSize = 11.sp) } })
        FormColumn {
            page.body(state)
            ErrorText(error)
            if (last) {
                val cta = when (role) { Role.BUYER -> "Create my account"; Role.RIDER -> "Submit application"
                    else -> "Authorise K ${app.config.collectAsState().value.sellerRegistrationFee.toInt()} & submit" }
                PrimaryButton(cta, loading = submitting != null, onClick = ::next)
                if (submitting != null) Sub("$submitting…")
            } else BackNextRow(onBack = ::back, onNext = ::next)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* =========================================================== page catalogue */

private fun pagesFor(role: Role, bank: List<SecurityQuestion>): List<Page> {
    val security = Page("Security", 0, 0, ::validateSecurity) { SecurityPage(it, bank) }
    fun sec(n: Int) = Page(security.title, n, n, security.validate, security.body)
    val otp = Page("Confirm your number", 1, 1, { s -> if (s.otpToken == null || s.otpFor != s.msisdn()) "Confirm your number with the SMS code first." else null }) { OtpPage(it) }
    return withOtp(otp, when (role) {
        Role.BUYER -> listOf(
            Page("Personal information", 1, 1, { s -> req(s, "fullName" to "full name") ?: mobileProblem(s) ?: idProblem(s) }) { BuyerPersonal(it) },
            Page("Address & interests", 2, 3, { s -> if (s["lat"].isBlank()) "Choose your delivery address." else null }) { AddressPage(it, "What do you shop for?", buyerCats, "Landmark (helps riders)") },
            sec(4),
            Page("Review & terms", 5, 6, { s -> terms(s, "terms", "privacy") }) { BuyerReview(it) },
        )
        Role.SELLER -> listOf(
            Page("Your business", 1, 1, { s -> req(s, "storeName" to "store name", "fullName" to "your full name") ?: mobileProblem(s) }) { BusinessPage(it, false) },
            Page("Location & categories", 2, 3, { s -> (if (s["lat"].isBlank()) "Set your store location on the map." else null) ?: if ((s.chips["categories"] ?: emptySet()).isEmpty()) "Pick at least one category." else null }) {
                AddressPage(it, "What do you sell?", sellerCats, "Pickup landmark for riders") },
            Page("Identity (KYC)", 4, 4, { s -> idProblem(s) ?: req(s, "tpin" to "TPIN") ?: docs(s, "portrait") }) { KycPage(it, "Business registration\n(corporate only)") },
            Page("Payout wallet", 5, 5, ::validateWallet) { WalletPage(it, "Where should we pay you?") },
            sec(6),
            Page("Review, terms & fee", 7, 9, { s -> terms(s, "terms", "fee") }) { SellerReview(it) },
        )
        Role.AGENT -> listOf(
            Page("Agency & hub", 1, 2, { s -> req(s, "storeName" to "agent name", "fullName" to "your full name", "hub" to "operating hub") ?: mobileProblem(s) }) { AgentHubPage(it) },
            Page("Identity & wallet", 3, 4, { s -> idProblem(s) ?: req(s, "tpin" to "TPIN") ?: docs(s, "portrait") ?: validateWallet(s) }) {
                KycPage(it, "Agency certificate\n(corporate only)"); WalletPage(it, "Commission wallet") },
            sec(5),
            Page("Agreement & fee", 6, 8, { s -> terms(s, "conduct", "terms") }) { AgentAgreement(it) },
        )
        Role.RIDER -> listOf(
            Page("You & your NRC", 1, 2, { s -> req(s, "firstName" to "first name", "surname" to "surname") ?: mobileProblem(s) ?: idProblem(s) }) { RiderPersonal(it) },
            Page("Licence & photo", 3, 4, { s -> docs(s, "licenceFront", "licenceBack", "portrait") }) { RiderLicence(it) },
            Page("Your vehicle", 5, 5, { s -> req(s, "make" to "make", "model" to "model", "plate" to "number plate") ?: docs(s, "vehicle") }) { RiderVehicle(it) },
            Page("Area & hours", 6, 7, { s -> req(s, "area" to "home area") ?: if (s.hours.none { it.value }) "Choose at least one working day." else null }) { RiderArea(it) },
            Page("Payout wallet", 8, 8, ::validateWallet) { WalletPage(it, "Where should we pay you?") },
            sec(9),
            Page("Terms & referral", 10, 11, { s -> terms(s, "terms", "fund", "withdrawFee") }) { RiderTerms(it) },
        )
    })
}

/** The SMS confirmation sits right after the page where the number is entered (always the first page). */
private fun withOtp(otp: Page, pages: List<Page>) = listOf(pages.first(), Page(otp.title, pages.first().to, pages.first().to, otp.validate, otp.body)) + pages.drop(1)

/** Beem OTP: auto-detects the SMS PIN or takes it typed; proves the number before the account exists. */
@Composable
private fun OtpPage(s: RegState) {
    val app = LocalAppState.current
    val m = s.msisdn() ?: run { ErrorText("Go back and enter a valid mobile number."); return }
    OtpVerification(m, verified = s.otpToken != null && s.otpFor == m) { token -> s.otpToken = token; s.otpFor = m; s.otpAt = System.currentTimeMillis(); app.backend.setPreAuth(m, token)
        s.autosave(); app.ok("Number verified") }
}

/* ============================================================ validation */

private fun req(s: RegState, vararg f: Pair<String, String>): String? = f.firstOrNull { s[it.first].isBlank() }?.let { "Enter your ${it.second}." }
private fun mobileProblem(s: RegState) = if (s.msisdn() == null) "Enter a valid Airtel, MTN or Zamtel number." else null
private fun docs(s: RegState, vararg keys: String) = keys.firstOrNull { it !in s.docs }?.let { "Add a photo of your ${docLabel(it)}." }
private fun terms(s: RegState, vararg keys: String) = if (keys.any { s.ticks[it] != true }) "Tick each agreement to continue." else null
private fun validateWallet(s: RegState): String? = when {
    Validation.normaliseMsisdn(s["wallet"]) == null -> "Enter a valid wallet number."
    s["wallet"].filter { it.isDigit() } != s["walletConfirm"].filter { it.isDigit() } -> "Wallet numbers don't match."
    s.walletHolder == null -> "Look up and confirm the wallet holder."
    else -> null
}
private fun validateSecurity(s: RegState): String? {
    if (s.questionIds.toSet().size != SecurityQuestions.REQUIRED) return "Choose ${SecurityQuestions.REQUIRED} of the ${SecurityQuestions.OFFERED} questions."
    s.questionIds.forEach { id -> Validation.answerProblem(s.answers[id].orEmpty())?.let { return "Answer \"${SecurityQuestions.textFor(id)}\": $it" } }
    Validation.pinProblem(s.pin)?.let { return it }
    if (s.pin != s.pinConfirm) return "PINs don't match. Try again."
    return null
}
/** ID type, number (format checked) and the photos that type needs. */
private fun idProblem(s: RegState): String? = Validation.idProblem(s["idType"], s["idNumber"])
    ?: Validation.idPhotos(s["idType"]).firstOrNull { it.first !in s.docs }?.let { "Add a photo of your ${it.second.lowercase()} — take one or choose it from your gallery." }

private fun docLabel(k: String) = mapOf("nrcFront" to "NRC (front)", "nrcBack" to "NRC (back)", "portrait" to "face (portrait)",
    "licenceFront" to "driver's licence (front)", "licenceBack" to "driver's licence (back)", "vehicle" to "vehicle")[k] ?: k

private val buyerCats = listOf("Fresh produce", "Fast food", "Electronics", "Fashion", "Home", "Beauty", "Hardware", "Baby", "Groceries")
private val sellerCats = listOf("Fresh produce", "Groceries", "Fast food", "Electronics", "Fashion", "Hardware", "Beauty", "Home")

/* ================================================================= pages */

@Composable private fun F(s: RegState, key: String, label: String, kb: KeyboardType = KeyboardType.Text, m: Modifier = Modifier, ph: String? = null) =
    SeloField(label, s[key], { s.set(key, it) }, m, keyboard = kb, placeholder = ph)

@Composable private fun MobileField(s: RegState) =
    F(s, "mobile", "Mobile number * (your login)", KeyboardType.Phone, ph = "097 123 4567")

@Composable
private fun BuyerPersonal(s: RegState) {
    val pick = rememberPhotoSource("Profile photo") { s.docs["profilePhoto"] = it; s.autosave() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { pick() }) {
        Box(Modifier.size(60.dp).background(if ("profilePhoto" in s.docs) Selo.Green else Selo.RedTint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PhotoCamera, null, tint = if ("profilePhoto" in s.docs) Color.White else Selo.Red)
        }
        Spacer(Modifier.width(12.dp)); Column { H("Profile photo", 14); Sub("Optional · camera or gallery") }
    }
    F(s, "fullName", "Full name *")
    MobileField(s)
    F(s, "email", "Email (optional)", KeyboardType.Email)
    IdentitySection(s)
    Text("Gender", fontSize = 12.sp, color = Selo.Mute, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Female", "Male", "Prefer not to say").forEach { g -> SeloChip(g, s["gender"] == g) { s.set("gender", g) } }
    }
    NoteBox("Next we send an SMS code to confirm this number is yours.")
}

@Composable
private fun AddressPage(s: RegState, catTitle: String, cats: List<String>, landmarkLabel: String) {
    val place = s["lat"].toDoubleOrNull()?.let { lat ->
        selo.Nachipa01.data.model.Place(s["placeRef"].ifBlank { "pin" }, s["addressLabel"], s["address"], lat, s["lng"].toDoubleOrNull() ?: 0.0,
            selo.Nachipa01.domain.Cities.cityOf(lat, s["lng"].toDoubleOrNull() ?: 0.0)?.id, s["district"], s["area"], true, false, null, s["placeId"].ifBlank { null })
    }
    // Search (Places API New, Zambia only), "Use my location" or "Pick on map"; geocoded once and saved with the account.
    selo.Nachipa01.ui.components.AddressField(if (s.role == Role.SELLER) "Store location *" else if (s.role == Role.AGENT) "Your location *" else "Delivery address *",
        place, { p -> if (p == null) { listOf("lat", "lng", "address", "addressLabel", "placeId").forEach { s.set(it, "") } } else {
            s.set("lat", p.lat.toString()); s.set("lng", p.lng.toString()); s.set("address", p.address); s.set("addressLabel", p.label)
            s.set("placeId", p.placeId ?: ""); s.set("placeRef", p.id); s.set("area", p.neighbourhood ?: p.label); s.set("district", p.cityName ?: s["district"])
            selo.Nachipa01.domain.Cities.byId(p.cityId)?.let { s.set("province", it.province) }
        } }, showSaved = false)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        F(s, "province", "Province", m = Modifier.weight(1f)); F(s, "district", "District", m = Modifier.weight(1f))
    }
    F(s, "landmark", landmarkLabel)
    if (catTitle.isNotBlank()) { H(catTitle, 14); ChipGroup(cats, s.chips["categories"] ?: emptySet()) { s.toggle("categories", it) } }
}

@Composable
private fun SecurityPage(s: RegState, bank: List<SecurityQuestion>) {
    H("Choose 3 security questions", 14)
    Sub("Tap 3 of these 4 questions and answer them. We'll ask the same 3 if you ever forget your PIN.")
    s.offered.forEach { q ->
        val picked = q.id in s.questionIds
        val order = s.questionIds.indexOf(q.id) + 1
        val full = !picked && s.questionIds.size >= SecurityQuestions.REQUIRED
        Column(Modifier.fillMaxWidth().background(if (picked) Selo.RedTint else Color.White, RoundedCornerShape(12.dp))
            .border(1.5.dp, if (picked) Selo.Red else Selo.Field, RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !full) {
                if (picked) { s.questionIds.remove(q.id); s.answers.remove(q.id) } else s.questionIds.add(q.id)
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(if (picked) Selo.Red else Color.White, CircleShape)
                    .border(1.5.dp, if (picked) Selo.Red else Selo.Field, CircleShape), contentAlignment = Alignment.Center) {
                    if (picked) Text("$order", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(10.dp))
                Text(q.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (full) Selo.Mute else Selo.Ink, modifier = Modifier.weight(1f))
            }
            if (picked) SeloField("Your answer", s.answers[q.id].orEmpty(), { s.answers[q.id] = it }, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), secret = true)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${s.questionIds.size} of ${SecurityQuestions.REQUIRED} chosen", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton({
            val previous = s.offered.map { it.id }.toSet()
            s.offered.clear(); s.questionIds.clear(); s.answers.clear()
            s.offered.addAll(SecurityQuestions.offer(bank, exclude = previous))
        }) { Text("Show 4 other questions", color = Selo.Red, fontWeight = FontWeight.Bold) }
    }
    Sub("These questions help us verify it's really you if you ever forget your PIN. Answers are not case sensitive.")
    H("Create a 5-digit PIN", 14); PinInput(s.pin, { s.pin = it })
    H("Confirm PIN", 14); PinInput(s.pinConfirm, { s.pinConfirm = it }, error = s.pinConfirm.length == 5 && s.pin != s.pinConfirm)
    if (s.role != Role.RIDER) SwitchRow("Unlock with fingerprint", s.biometric, { s.biometric = it })
}

@Composable
private fun ReviewCard(rows: List<Pair<String, String>>) = SeloCard { rows.forEach { KvRow(it.first, it.second) } }

@Composable
private fun Tick(s: RegState, key: String, text: String) = CheckRow(s.ticks[key] == true, { s.ticks[key] = it }, text)

@Composable
private fun BuyerReview(s: RegState) {
    ReviewCard(listOf("Name" to s["fullName"], "Mobile (login)" to (s.msisdn()?.let(Validation::maskMsisdn) ?: ""),
        "Address" to s["area"], "Interests" to (s.chips["categories"]?.joinToString() ?: "—"), "Security" to "3 questions · PIN set"))
    Tick(s, "terms", "I have read the Terms & Conditions and Marketplace Rules")
    Tick(s, "privacy", "I accept the Privacy Policy and Data Policy")
    Tick(s, "updates", "I agree to receive order updates in the app")
    NoteBox("Payments are protected by Selo Escrow: sellers are paid only after a rider collects your order.")
}

@Composable
private fun BusinessPage(s: RegState, agent: Boolean) {
    Text("Business type", fontSize = 12.sp, color = Selo.Mute, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Individual", "Corporate").forEach { t -> SeloChip(t, (s["businessType"].ifBlank { "Individual" }) == t) { s.set("businessType", t) } }
    }
    val pick = rememberPhotoSource("Business logo") { s.docs["logo"] = it; s.autosave() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { pick() }) {
        Box(Modifier.size(58.dp).background(if ("logo" in s.docs) Selo.Green else Selo.RedTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Storefront, null, tint = if ("logo" in s.docs) Color.White else Selo.Red)
        }
        Spacer(Modifier.width(12.dp)); Column { H("Business logo", 14); Sub("Square image · shown on your store") }
    }
    F(s, "storeName", "Store name *")
    F(s, "fullName", "Your full name (as on NRC) *")
    F(s, "description", "Describe what you sell")
    MobileField(s)
    NoteBox("This number can't already be registered as a Buyer, Agent or Rider.")
}

/**
 * Identity on every sign-up form: NRC, passport or driver's licence. NRC numbers are formatted as XXXXXX/XX/X while
 * typing; photos come from the live camera or the gallery.
 */
@Composable
private fun IdentitySection(s: RegState) {
    H("Your ID *", 14)
    val type = s["idType"].ifBlank { "NRC" }
    LaunchedEffect(Unit) { if (s["idType"].isBlank()) s.set("idType", "NRC") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Validation.ID_TYPES.forEach { t -> SeloChip(t, type == t) {
            if (t != type) { s.set("idType", t); s.set("idNumber", Validation.formatIdNumber(t, s["idNumber"])); s.docs.remove("idFront"); s.docs.remove("idBack"); s.autosave() } } }
    }
    val number = s["idNumber"]
    val complete = when (type) { "NRC" -> number.length == 11; else -> number.length >= 5 }
    SeloField(when (type) { "NRC" -> "NRC number *"; "Passport" -> "Passport number *"; else -> "Driver's licence number *" }, number,
        { s.set("idNumber", Validation.formatIdNumber(type, it)); if (type == "NRC") s.set("nrc", Validation.formatIdNumber(type, it)) },
        keyboard = if (type == "NRC") KeyboardType.Number else KeyboardType.Ascii, placeholder = when (type) { "NRC" -> "XXXXXX/XX/X"; "Passport" -> "e.g. ZN123456"; else -> "As printed on the licence" },
        error = if (complete) Validation.idProblem(type, number) else null)
    if (type == "NRC") Sub("Type the 9 digits — the slashes are added for you.")
    DocGrid(s, Validation.idPhotos(type))
}

@Composable
private fun DocGrid(s: RegState, items: List<Pair<String, String>>) {
    items.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (key, label) ->
                val pick = rememberPhotoSource(label) { s.docs[key] = it; s.autosave() }
                DocTile(label, key in s.docs, Modifier.weight(1f)) { pick() }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun KycPage(s: RegState, corporateDoc: String) {
    Sub("Take clear photos in good light, or choose saved photos from your gallery. Only our compliance team can see these.")
    IdentitySection(s)
    F(s, "tpin", "TPIN *", KeyboardType.Number)
    DocGrid(s, listOf("portrait" to "Portrait selfie", "businessReg" to corporateDoc))
}

@Composable
private fun WalletPage(s: RegState, title: String) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var looking by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    H(title, 14)
    listOf("Airtel Money" to ("AM" to Color(0xFFE11D48)), "MTN MoMo" to ("MM" to Color(0xFFCA8A04)), "Zamtel Kwacha" to ("ZK" to Color(0xFF15803D))).forEach { (n, b) ->
        RadioCard(s.walletNetwork == n, n, badge = b.first, badgeColor = b.second) { s.walletNetwork = n; s.walletHolder = null }
    }
    F(s, "wallet", "Wallet number", KeyboardType.Phone)
    F(s, "walletConfirm", "Confirm wallet number", KeyboardType.Phone)
    val holder = s.walletHolder
    if (holder == null) {
        OutlineButton(if (looking) "Looking up…" else "Look up wallet") {
            val m = Validation.normaliseMsisdn(s["wallet"])
            if (m == null || s["wallet"].filter { it.isDigit() } != s["walletConfirm"].filter { it.isDigit() }) { err = "Enter and confirm a valid wallet number."; return@OutlineButton }
            looking = true; err = null
            scope.launch { runCatching { app.backend.lookupWallet(m) }.onSuccess { s.walletHolder = it }.onFailure { err = it.message }; looking = false }
        }
        ErrorText(err)
    } else Column(Modifier.fillMaxWidth().background(Selo.GreenTint, RoundedCornerShape(12.dp)).border(1.4.dp, Color(0xFFA7F0D2), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("Lipila found this wallet:", color = Selo.GreenDark, fontSize = 12.sp)
        Text(holder, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        Text("Is this you? Tap No if it isn't.", color = Selo.GreenDark, fontSize = 12.sp)
        TextButton({ s.walletHolder = null }) { Text("No, change number", color = Selo.Red) }
    }
}

@Composable
private fun SellerReview(s: RegState) {
    val cfg by LocalAppState.current.config.collectAsState()
    ReviewCard(listOf("Store" to s["storeName"], "Location" to s["area"], "Documents" to "NRC, TPIN, portrait",
        "Payout" to "${s.walletNetwork} · ${s.walletHolder ?: ""}", "Security" to "3 questions · PIN set"))
    Tick(s, "terms", "I accept the Seller Terms, Marketplace Rules and Privacy Policy")
    Tick(s, "fee", "I understand a K ${cfg.flatPlatformFeeKwacha.toInt()} platform fee is deducted from each order when the rider collects")
    SeloCard {
        Text("One-time registration fee", fontWeight = FontWeight.ExtraBold)
        KvRow("Amount", Fees.formatK(Fees.kwachaToNgwee(cfg.sellerRegistrationFee)))
        KvRow("Charged to", s.walletNetwork)
        Sub("You'll get a Lipila prompt on your phone to approve.")
    }
}

@Composable
private fun AgentHubPage(s: RegState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Individual agent", "Agency (corporate)").forEach { t -> SeloChip(t, (s["businessType"].ifBlank { "Individual agent" }) == t) { s.set("businessType", t) } }
    }
    F(s, "storeName", "Agent or agency name *")
    F(s, "fullName", "Your full name (as on NRC) *")
    MobileField(s)
    H("Your operating hub", 14)
    listOf("Soweto Market" to "Lusaka", "Comesa Market" to "Lusaka CBD", "Katanjinjin Zone" to "Lusaka", "City Market" to "Lusaka").forEach { (h, c) ->
        RadioCard(s["hub"] == h, h, c, icon = Icons.Outlined.Storefront) { s.set("hub", h); s.set("area", h) }
    }
    Text("Area you cover", fontSize = 12.sp, color = Selo.Mute, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("2 km", "5 km", "10 km").forEach { r -> SeloChip(r, s["coverage"].ifBlank { "5 km" } == r) { s.set("coverage", r) } } }
}

@Composable
private fun AgentAgreement(s: RegState) {
    val cfg by LocalAppState.current.config.collectAsState()
    val (gross, net) = Fees.agentCommission(20_000, 10.0, cfg)
    H("How you earn", 14)
    SeloCard {
        KvRow("Sale you make for a seller", Fees.formatK(20_000))
        KvRow("Seller's rate for you (set by seller)", "10% · ${Fees.formatK(gross)}")
        KvRow("SeloNaChipa share (${cfg.agentPlatformCutPct.toInt()}% of commission)", "− ${Fees.formatK(gross - net)}", valueColor = Selo.Red)
        KvRow("You receive, daily at 00:00", Fees.formatK(net), strong = true, valueColor = Selo.Green)
    }
    Tick(s, "conduct", "I agree to the Agent Code of Conduct: sellers always enter their own PIN and security answers")
    Tick(s, "terms", "I accept the Terms, Marketplace Rules and Privacy Policy")
    SeloCard { KvRow("One-time registration fee · ${s.walletNetwork}", Fees.formatK(Fees.kwachaToNgwee(cfg.sellerRegistrationFee))) }
}

@Composable
private fun RiderPersonal(s: RegState) {
    F(s, "firstName", "First name *")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { F(s, "otherName", "Other name", m = Modifier.weight(1f)); F(s, "surname", "Surname *", m = Modifier.weight(1f)) }
    MobileField(s)
    IdentitySection(s)
    NoteBox("Your name and ID are locked once verified. Contact support to change them later.")
    LaunchedEffect(s["firstName"], s["surname"]) { s.set("fullName", listOf(s["firstName"], s["otherName"], s["surname"]).filter { it.isNotBlank() }.joinToString(" ")) }
}

@Composable
private fun RiderLicence(s: RegState) {
    H("Driver's licence", 14)
    DocGrid(s, listOf("licenceFront" to "Licence front", "licenceBack" to "Licence back"))
    H("Profile photo", 14)
    val pick = rememberPhotoSource("Profile photo") { s.docs["portrait"] = it; s.autosave() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp).background(if ("portrait" in s.docs) Selo.Green else Color(0xFFE9E3DC), CircleShape)
            .border(2.5.dp, Selo.Red, CircleShape).clickable { pick() }, contentAlignment = Alignment.Center) {
            Icon(if ("portrait" in s.docs) Icons.Outlined.Check else Icons.Outlined.Face, null, tint = if ("portrait" in s.docs) Color.White else Selo.Ink, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column { Text("Buyers and sellers see this", fontWeight = FontWeight.Bold); Sub("✓ Face clearly visible\n✓ Plain background\n✗ No hats or sunglasses") }
    }
    NoteBox("Photos are compressed to under 1 MB before upload. Our team checks the face matches your NRC.")
}

@Composable
private fun RiderVehicle(s: RegState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Motorbike" to "Up to 10 km jobs", "Bicycle" to "Short trips, up to 4 km").forEach { (v, sub) ->
            val on = s["vehicleType"].ifBlank { "Motorbike" } == v
            Column(Modifier.weight(1f).background(if (on) Selo.RedTint else Color.White, RoundedCornerShape(12.dp))
                .border(1.6.dp, if (on) Selo.Red else Selo.Line, RoundedCornerShape(12.dp)).clickable { s.set("vehicleType", v) }.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (v == "Motorbike") Icons.Outlined.TwoWheeler else Icons.Outlined.PedalBike, null, tint = Selo.Red, modifier = Modifier.size(30.dp))
                Text(v, fontWeight = FontWeight.ExtraBold, color = if (on) Selo.Red else Selo.Ink); Text(sub, fontSize = 11.sp, color = Selo.Mute)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { F(s, "make", "Make", m = Modifier.weight(1f), ph = "Honda"); F(s, "model", "Model", m = Modifier.weight(1f), ph = "CB125") }
    F(s, "plate", "Number plate", ph = "ABC 1234 ZM")
    DocGrid(s, listOf("vehicle" to "Vehicle photo", "blueBook" to "Blue book"))
    NoteBox("Changing your vehicle later sends it back for re-verification.")
}

@Composable
private fun RiderArea(s: RegState) {
    AddressPage(s, "", emptyList(), "Nearest landmark")
    H("When can you ride?", 14)
    SeloCard(padding = 6.dp) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { d ->
            SwitchRow(d, s.hours[d] == true, { s.hours[d] = it }, sub = if (s.hours[d] == true) (if (d == "Sat") "08:00 – 16:00" else "06:00 – 18:00") else "Off")
        }
    }
    Sub("Only cities activated by SeloNaChipa are offered. Delivery zones are chosen after approval.")
}

@Composable
private fun RiderTerms(s: RegState) {
    val cfg by LocalAppState.current.config.collectAsState()
    Tick(s, "terms", "I accept the Rider Terms and Privacy Policy, including live location sharing only during deliveries")
    Tick(s, "fund", "I understand ${cfg.socialFundPct}% of each delivery fee goes to my locked Social Fund, released to me every ${cfg.socialFundMilestoneDeliveries} deliveries")
    Tick(s, "withdrawFee", "Withdrawals cost K ${cfg.riderWithdrawalFee.toInt()}; minimum payout K ${cfg.minWithdrawal.toInt()}")
    F(s, "referral", "Referral code (optional)")
}
