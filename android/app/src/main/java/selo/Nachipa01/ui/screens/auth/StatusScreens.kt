package selo.Nachipa01.ui.screens.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** A7: account created with confetti, auto sign-in, then the Buyer portal. */
@Composable
fun AccountCreatedScreen(onStart: () -> Unit) {
    val user = LocalAppState.current.session.value
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val colors = listOf(Selo.Red, Selo.Green, Selo.Amber, Selo.Purple, Selo.Teal, Selo.Coral)
            repeat(40) { k ->
                val x = (k * 37 % 100) / 100f * size.width
                val y = (k * 53 % 100) / 100f * size.height
                rotate((k * 47 % 180).toFloat(), Offset(x, y)) { drawRect(colors[k % 6], Offset(x, y), Size(14f, 24f)) }
            }
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BigBadge(Icons.Filled.Check, Selo.Green, Color(0xFFD9F7EC))
            Text("Welcome to SeloNaChipa, ${user?.firstName ?: ""}!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Sub("Your Buyer profile is ready. You are signed in on this phone.")
            SeloCard {
                ChecklistRow(StepState.DONE, "Profile created")
                ChecklistRow(StepState.DONE, "Security questions saved")
                ChecklistRow(StepState.DONE, "5-digit PIN active")
            }
            PrimaryButton("Start shopping", onClick = onStart)
        }
    }
}

/** SR6 / RR6: seller and rider pending verification. */
@Composable
fun PendingScreen(onLogout: () -> Unit, onDraftListing: () -> Unit) {
    val app = LocalAppState.current
    val user = app.session.value
    val scope = rememberCoroutineScope()
    val isRider = user?.role == Role.RIDER
    Column(Modifier.fillMaxSize()) {
        HomeBand(user?.fullName ?: "", if (isRider) "${user?.area} · ${user?.vehicle}" else user?.storeName ?: "",
            badge = { StatusChip("Pending", Tone.WARN) })
        FormColumn {
            if (isRider) Row(Modifier.fillMaxWidth().background(Selo.Grey, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = Color.White); Spacer(Modifier.width(10.dp))
                Text("OFFLINE — available after approval", color = Color.White, fontWeight = FontWeight.ExtraBold)
            } else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BigBadge(Icons.Outlined.Schedule, Selo.Amber, Selo.AmberTint, 70.dp) }
            H(if (isRider) "Application submitted" else "Application received, ${user?.firstName}", 18)
            Sub(if (isRider) "We'll verify your driver's licence within 24–48 hours. You'll get a push notification and an in-app message."
                else "We check your documents within 24–48 hours and notify you in the app.")
            SeloCard {
                ChecklistRow(StepState.DONE, if (isRider) "Personal details & NRC" else "Registration fee paid")
                ChecklistRow(StepState.DONE, "Wallet verified")
                ChecklistRow(StepState.DONE, "Security questions & PIN set")
                ChecklistRow(StepState.RUNNING, if (isRider) "Licence & photo review" else "Documents under review", "Compliance team")
                ChecklistRow(StepState.WAITING, if (isRider) "Choose delivery zones and go online" else "Store goes live")
            }
            if (!isRider) PrimaryButton("Start a draft listing", onClick = onDraftListing)
            LockedNote(if (isRider) "Jobs, Route and Earnings stay hidden until you are approved." else "Publishing, orders and payouts unlock after approval.")
            OutlineButton("Log out") { scope.launch { app.backend.logout(); onLogout() } }
        }
    }
}
