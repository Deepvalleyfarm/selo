package selo.Nachipa01.ui.payments

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.WalletStatus
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** Live wallet state for the Withdraw button: refreshes every few seconds while a withdrawal is in flight. */
@Composable
fun rememberWalletStatus(key: Any? = Unit): State<WalletStatus?> {
    val app = LocalAppState.current
    val st = remember { mutableStateOf<WalletStatus?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, tick) {
        st.value = runCatching { app.backend.walletStatus() }.getOrNull() ?: st.value
        delay(if (st.value?.active != null) 4_000 else 30_000); tick++
    }
    return st
}

/** Withdraw button label/enabled state from the server's view of the wallet. */
@Composable
fun WithdrawButton(status: WalletStatus?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = when { status == null -> "Withdraw"; status.active != null -> "Withdrawal in progress"; else -> "Withdraw ${Fees.formatK(status.availableNgwee)}" }
    PrimaryButton(label, modifier, enabled = status != null && status.block == null, onClick = onClick)
}

/**
 * Full-balance withdrawal: no amount to type — the whole available balance goes to the payout wallet via Lipila.
 * PIN prompt → submitted (waiting for the provider) → sent / failed (Lipila's reason) / still confirming.
 */
@Composable
fun WithdrawSheet(status: WalletStatus, payoutWallet: String?, onClose: (changed: Boolean) -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf("PIN") }
    var amount by remember { mutableLongStateOf(status.availableNgwee) }
    // follow the in-flight withdrawal until Lipila confirms it
    LaunchedEffect(state) {
        while (state == "PENDING" || state == "SUBMITTED") {
            delay(4_000)
            val s = runCatching { app.backend.walletStatus() }.getOrNull() ?: continue
            when {
                s.active == null && s.lockedNgwee == 0L -> { state = if (s.availableNgwee >= amount && amount > 0) "FAILED" else "COMPLETED"
                    result = if (state == "COMPLETED") "${Fees.formatK(amount)} has been sent to ${payoutWallet?.let(Validation::maskMsisdn) ?: "your wallet"}."
                        else "The withdrawal didn't go through and your balance has been restored. Check your notifications for the provider's reason." }
                s.active?.status == "MANUAL_REVIEW" -> { state = "MANUAL_REVIEW"; result = "We're confirming your withdrawal, this may take a few minutes longer." }
                s.active?.status == "FAILED" -> { state = "FAILED"; result = s.active.message }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose(state != "PIN") }, containerColor = Color.White,
        title = { Text(when (state) { "PIN" -> "Withdraw full balance"; "COMPLETED" -> "Withdrawal sent"; "FAILED" -> "Withdrawal failed"; else -> "Withdrawal in progress" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    "PIN" -> {
                        Text(Fees.formatK(amount), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Selo.Green)
                        Text("Your whole available balance goes to ${payoutWallet?.let(Validation::maskMsisdn) ?: "your payout wallet"} by mobile money.", color = Selo.Mute, fontSize = 13.sp)
                        Text("Enter your 5-digit PIN to confirm", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        PinInput(pin, { pin = it; error = null }, length = 5, error = error != null)
                        ErrorText(error)
                    }
                    "PENDING", "SUBMITTED" -> {
                        Row { Icon(Icons.Outlined.HourglassTop, null, tint = Selo.AmberDark); Text("  ${Fees.formatK(amount)} submitted", fontWeight = FontWeight.Bold) }
                        Text(result ?: "Waiting for your mobile money provider to confirm. Your balance is held until then.", color = Selo.Mute, fontSize = 13.sp)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Selo.Red)
                    }
                    "COMPLETED" -> NoteBox(result ?: "Sent.", Tone.OK, Icons.Outlined.CheckCircle)
                    "MANUAL_REVIEW" -> NoteBox(result ?: "We're confirming your withdrawal, this may take a few minutes longer.", Tone.WARN)
                    else -> NoteBox(result ?: "The withdrawal failed. Your balance has been restored.", Tone.BRAND)
                }
            }
        },
        confirmButton = {
            if (state == "PIN") Button({ busy = true; error = null; scope.launch {
                runCatching { app.backend.withdrawFull(pin) }
                    .onSuccess { r -> amount = r.amountNgwee; state = r.status; result = r.message }
                    .onFailure { error = it.message ?: "Something went wrong. Try again."; pin = "" }
                busy = false } }, enabled = !busy && pin.length == 5, colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text(if (busy) "Sending…" else "Withdraw") }
            else TextButton({ onClose(true) }) { Text("Close", color = Selo.Red) }
        },
        dismissButton = { if (state == "PIN") TextButton({ onClose(false) }, enabled = !busy) { Text("Cancel") } })
}
