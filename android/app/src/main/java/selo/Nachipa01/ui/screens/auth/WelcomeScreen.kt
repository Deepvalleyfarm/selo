package selo.Nachipa01.ui.screens.auth

import androidx.compose.foundation.background
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
fun WelcomeScreen(onLoggedIn: (SessionUser) -> Unit, onCreateProfile: () -> Unit, onForgotPin: () -> Unit) {
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

    fun login() {
        val msisdn = if (switchNumber) Validation.normaliseMsisdn(number) else remembered
        if (msisdn == null) { error = "Enter a valid Airtel, MTN or Zamtel number."; return }
        if (pin.length != Validation.PIN_LENGTH) { error = "Enter your 5-digit PIN."; return }
        loading = true; error = null
        scope.launch {
            runCatching { app.backend.login(msisdn, pin) }
                .onSuccess(onLoggedIn)
                .onFailure { error = it.message; pin = "" }
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
            if (!switchNumber) Text("Last signed in: ${SimpleDateFormat("HH:mm | dd/MM/yy", Locale.UK).format(Date())}",
                color = Selo.Mute, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (switchNumber) SeloField("Mobile number", number, { number = it }, keyboard = KeyboardType.Phone, placeholder = "097 123 4567")
            Text("5-digit PIN", color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            var reveal by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PinInput(pin, { pin = it; error = null }, reveal = reveal, error = error != null) }
                IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show PIN", tint = Selo.Red) }
            }
            ErrorText(error)
            PrimaryButton("Login", enabled = pin.length == 5, loading = loading, onClick = ::login)
            Row(Modifier.fillMaxWidth()) {
                Text("Forgot PIN?", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onForgotPin))
                Spacer(Modifier.weight(1f))
                Text(if (switchNumber) "Use saved number" else "Not you? Switch number", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = remembered != null || !switchNumber) { switchNumber = !switchNumber })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = Selo.Line)
                Text("  New to SeloNaChipa?  ", color = Selo.Mute, fontSize = 12.sp)
                HorizontalDivider(Modifier.weight(1f), color = Selo.Line)
            }
            OutlineButton("CREATE PROFILE", sub = "Buy · Sell · Deliver · Earn", onClick = onCreateProfile)
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
