package selo.Nachipa01.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import selo.Nachipa01.data.model.SessionUser
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A1: welcome back / login with five sliding paid adverts and Create Profile. No OTP anywhere. */
@Composable
fun WelcomeScreen(onLoggedIn: (SessionUser) -> Unit, onCreateProfile: () -> Unit, onForgotPin: () -> Unit, initialMode: String = "login") {
    val app = LocalAppState.current
    val ads by app.adverts.collectAsState()
    val remembered = app.rememberedMsisdn.value
    var switchNumber by remember { mutableStateOf(remembered == null) }
    var number by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg by app.config.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(app.pinLockedUntil.value) { while (now < app.pinLockedUntil.value) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val locked = now < app.pinLockedUntil.value
    fun lock() {
        val until = System.currentTimeMillis() + 30 * 60_000L
        app.pinLockedUntil.value = until; now = System.currentTimeMillis(); pin = ""
        ctx.getSharedPreferences("selo", android.content.Context.MODE_PRIVATE).edit().putLong("pinLockedUntil", until).apply()
    }

    fun login() {
        if (locked) return
        val msisdn = if (switchNumber) Validation.normaliseMsisdn(number) else remembered
        if (msisdn == null) { error = "Enter a valid Airtel, MTN or Zamtel number."; return }
        if (pin.length != Validation.PIN_LENGTH) { error = "Enter your 5-digit PIN."; return }
        loading = true; error = null
        scope.launch {
            runCatching { app.backend.login(msisdn, pin) }
                .onSuccess(onLoggedIn)
                .onFailure { error = it.message; pin = ""; if (it.message?.contains("Locked", true) == true || it.message?.contains("Too many", true) == true) lock() }
            loading = false
        }
    }

    // Red behind the header and adverts, white behind the login sheet, so the sheet always reaches the bottom edge.
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Selo.Red, 0.55f to Selo.Red, 0.55f to Color.White, 1f to Color.White)).verticalScroll(rememberScrollState())) {
        HomeBand(
            title = if (!switchNumber && app.rememberedName.value != null) "Muli bwanji, ${app.rememberedName.value}" else "Muli bwanji!",
            subtitle = if (!switchNumber && remembered != null) Validation.maskMsisdn(remembered) else "Buy. Sell. Deliver. Earn.",
        )
        if (app.backend.isDemo) Text("DEMO MODE · add google-services.json to connect to Firebase", color = Color.White,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        AdvertCarousel(ads) { /* opens the advertised listing after login */ }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).background(Color.White, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val role = app.rememberedRole.value
            if (!switchNumber && role != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val full = app.rememberedFullName.value ?: app.rememberedName.value ?: ""
                Box(Modifier.size(64.dp).background(Selo.Red, CircleShape), contentAlignment = Alignment.Center) {
                    Text(full.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(full, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
                Text("${role.name.lowercase().replaceFirstChar { it.uppercase() }} account", fontSize = 12.sp, color = Selo.Mute)
            }
            // three clear entry points: Log In · Sign Up · Forgot PIN
            var mode by rememberSaveable { mutableStateOf(initialMode) }
            Row(Modifier.fillMaxWidth().background(Selo.Surface, RoundedCornerShape(50)).padding(4.dp)) {
                listOf("login" to "Log In", "signup" to "Sign Up").forEach { (k, l) ->
                    Text(l, Modifier.weight(1f).background(if (mode == k) Selo.Red else Color.Transparent, RoundedCornerShape(50)).clickable { mode = k }.padding(vertical = 10.dp),
                        color = if (mode == k) Color.White else Selo.Ink, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (mode == "signup") {
                Text("Create your SeloNaChipa account", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                listOf(Icons.Outlined.ShoppingBag to "Buy with escrow protection", Icons.Outlined.Storefront to "Sell with AI video listings",
                    Icons.Outlined.TwoWheeler to "Deliver and earn as a rider", Icons.Outlined.Handshake to "Grow sales as an agent").forEach { (i, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(i, null, tint = Selo.Red, modifier = Modifier.size(20.dp)); Text("  $t", fontSize = 14.sp) } }
                Sub("Takes about 2 minutes. We confirm your number with an SMS code, then you choose a 5-digit PIN and 3 security questions.")
                PrimaryButton("Sign up", onClick = onCreateProfile)
                TextButton({ mode = "login" }, Modifier.align(Alignment.CenterHorizontally)) { Text("Already have an account? Log in", color = Selo.Red, fontWeight = FontWeight.Bold) }
            } else {
            if (!switchNumber) Text("Last signed in: ${SimpleDateFormat("HH:mm | dd/MM/yy", Locale.UK).format(Date())}",
                color = Selo.Mute, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (switchNumber) SeloField("Mobile number", number, { number = it }, keyboard = KeyboardType.Phone, placeholder = "097 123 4567")
            Text("5-digit PIN", color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            var reveal by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PinBoxes(pin, reveal = reveal, error = error != null, locked = locked) }
                IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show PIN", tint = Selo.Red) }
            }
            if (locked) Text("Too many wrong PINs. Try again in ${((app.pinLockedUntil.value - now) / 60_000 + 1)} min, or reset your PIN with your security questions.",
                color = Selo.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            else ErrorText(error)
            NumberPad(enabled = !locked && !loading, onDigit = { d -> if (pin.length < 5) { pin += d; error = null } }, onDelete = { pin = pin.dropLast(1) })
            PrimaryButton("Login", enabled = pin.length == 5 && !locked, loading = loading, onClick = ::login)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text(if (switchNumber) "Use saved number" else "Not you? Switch number", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = remembered != null || !switchNumber) { switchNumber = !switchNumber })
            }
                if (error == selo.Nachipa01.data.repo.NETWORK_ERROR) TextButton({ login() }) { Text("Retry", color = Selo.Red, fontWeight = FontWeight.Bold) }
                OutlinedButton(onForgotPin, Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, Selo.Field)) {
                    Icon(Icons.Outlined.LockReset, null, tint = Selo.Red); Text("  Forgot PIN? Reset with security questions", color = Selo.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                FooterIcon(Icons.Outlined.Call, "Contact us") { uri.openUri("https://wa.me/260978070734") }
                FooterIcon(Icons.Outlined.ReportProblem, "Report fraud") { uri.openUri("https://wa.me/260978070734?text=Fraud%20report") }
            }
        }
    }
}

@Composable
private fun FooterIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).background(Selo.Red, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
        Text(label, color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}


/** Display-only PIN boxes; digits come from the on-screen pad (one-handed use, no system keyboard). */
@Composable
private fun PinBoxes(value: String, reveal: Boolean, error: Boolean, locked: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(Validation.PIN_LENGTH) { i ->
            val filled = i < value.length
            Box(Modifier.weight(1f).height(50.dp).background(if (locked) Color(0xFFF1F1F4) else Color.White, RoundedCornerShape(10.dp))
                .border(2.dp, when { locked -> Selo.Field; error -> Selo.Red; filled || i == value.length -> Selo.Red; else -> Selo.Field }, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                if (filled) { if (reveal) Text(value[i].toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold) else Box(Modifier.size(12.dp).background(Selo.Ink, CircleShape)) }
                else if (locked) Icon(Icons.Outlined.Lock, null, tint = Selo.Grey, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 3 × 4 number pad. Greyed out and inactive during a PIN lockout. */
@Composable
private fun NumberPad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(48.dp).background(if (k.isEmpty()) Color.Transparent else if (enabled) Color(0xFFF5F5F7) else Color(0xFFE4E4E8), RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled && k.isNotEmpty()) { if (k == "⌫") onDelete() else onDigit(k[0]) }, contentAlignment = Alignment.Center) {
                        if (k == "⌫") Icon(Icons.Outlined.Backspace, "Delete", tint = if (enabled) Selo.Ink else Selo.Grey)
                        else Text(k, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Selo.Ink else Selo.Grey)
                    }
                }
            }
        }
    }
}
