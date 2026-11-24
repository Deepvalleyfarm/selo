package selo.Nachipa01.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.SecurityQuestion
import selo.Nachipa01.data.repo.QuestionAnswer
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** Forgot PIN: number → 3 questions (checked together server-side) → new PIN → confirm → success. */
@Composable
fun ForgotPinScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var step by remember { mutableIntStateOf(1) }
    var number by remember { mutableStateOf(app.rememberedMsisdn.value?.let { "0" + it.removePrefix("+260") } ?: "") }
    var questions by remember { mutableStateOf<List<SecurityQuestion>?>(emptyList()) }
    val answers = remember { mutableStateListOf("", "", "") }
    var token by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mismatches by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun go(block: suspend () -> Unit) { busy = true; error = null; scope.launch { runCatching { block() }.onFailure { error = it.message }; busy = false } }

    Column(Modifier.fillMaxSize()) {
        SeloHeader("Reset your PIN", "Step ${step.coerceAtMost(4)} of 4", onBack = if (step == 5) null else ({ if (step > 1) step-- else onBack() }), steps = step.coerceAtMost(4) to 4)
        FormColumn {
            when (step) {
                1 -> {
                    H("Enter your mobile number"); Sub("If this number is registered, you'll see your security questions next.")
                    SeloField("Mobile number", number, { number = it }, keyboard = KeyboardType.Phone)
                    ErrorText(error)
                    PrimaryButton("Continue", loading = busy) {
                        val m = Validation.normaliseMsisdn(number) ?: run { error = "Enter a valid Airtel, MTN or Zamtel number."; return@PrimaryButton }
                        go { questions = app.backend.recoveryQuestions(m); step = 2 }
                    }
                }
                2 -> {
                    val qs = questions
                    if (qs == null) {
                        H("We don't have security questions on file for this account yet.")
                        Sub("Contact support. Our team will check your identity and reset your PIN.")
                        PrimaryButton("Contact support") { uri.openUri("https://wa.me/260978070734?text=PIN%20recovery") }
                        Text("WhatsApp +260 978 070 734", color = Selo.Mute, fontSize = 13.sp)
                    } else {
                        H("Answer your 3 security questions")
                        qs.forEachIndexed { i, q ->
                            Text(q.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            SeloField("Answer", answers[i], { answers[i] = it }, secret = true)
                        }
                        NoteBox("Answers are not case sensitive. All three must match. After 5 failed tries, reset locks for 30 minutes.")
                        ErrorText(error)
                        PrimaryButton("Verify answers", loading = busy) {
                            go { token = app.backend.verifyAnswers(Validation.normaliseMsisdn(number)!!, qs.mapIndexed { i, q -> QuestionAnswer(q.id, answers[i]) }); step = 3 }
                        }
                    }
                }
                3 -> {
                    H("Create a new 5-digit PIN"); PinInput(pin, { pin = it; error = null })
                    ErrorText(error)
                    PrimaryButton("Next", enabled = pin.length == 5) { error = Validation.pinProblem(pin); if (error == null) step = 4 }
                }
                4 -> {
                    H("Confirm your new PIN"); PinInput(confirm, { confirm = it; error = null }, error = error != null)
                    ErrorText(error)
                    PrimaryButton("Set new PIN", enabled = confirm.length == 5, loading = busy) {
                        if (confirm != pin) { mismatches++; confirm = ""
                            error = if (mismatches >= 5) "Too many mismatches. Start again." else "PINs don't match. Try again."
                            if (mismatches >= 5) { step = 1; pin = ""; mismatches = 0 }
                            return@PrimaryButton }
                        go { app.backend.resetPin(token, pin); step = 5 }
                    }
                }
                else -> {
                    Spacer(Modifier.height(30.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BigBadge(Icons.Filled.Check, Selo.Green, Color(0xFFD9F7EC)) }
                    H("Your PIN has been reset successfully.", 18)
                    Sub("All other sessions were signed out. Log in with your new PIN.")
                    PrimaryButton("Return to login", onClick = onDone)
                }
            }
        }
    }
}
