package selo.Nachipa01.ui.screens.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.AccountStatus
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** AG4: agent home. Tools are visible but locked until compliance approves the agent. */
@Composable
fun AgentHomeScreen(onLogout: () -> Unit, onAddListing: () -> Unit) {
    val app = LocalAppState.current
    val u = app.session.value
    val approved = u?.status == AccountStatus.ACTIVE
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HomeBand("Welcome, ${u?.firstName ?: ""}", "Agent account · ${u?.area ?: ""}",
            badge = { StatusChip(if (approved) "Verified" else "Pending", if (approved) Tone.OK else Tone.WARN) })
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Selo Ledger: commission balance, withdrawn in full to the agent's mobile money wallet
            var withdrawing by remember { mutableStateOf(false) }
            var walletKey by remember { mutableIntStateOf(0) }
            val wallet by selo.Nachipa01.ui.payments.rememberWalletStatus(walletKey)
            if (withdrawing) wallet?.let { w -> selo.Nachipa01.ui.payments.WithdrawSheet(w, null) { withdrawing = false; walletKey++ } }
            WalletCard("Selo Ledger", "Your commission balance", wallet?.availableNgwee ?: 0, listOf(
                QuickAction(Icons.Outlined.Payments, if (wallet?.active != null) "In progress" else "Withdraw") {
                    val b = wallet?.block; if (b == null && wallet != null) withdrawing = true else app.fail(b ?: "Checking your balance…") },
                QuickAction(Icons.Outlined.Receipt, "Receipts") { app.navRequest.value = "receipts" },
                QuickAction(Icons.Outlined.Assessment, "Report") { app.navRequest.value = "report" }))
            if (!approved) {
                NoteBox("Verification in progress · 24–48 h", Tone.WARN)
                SeloCard {
                    ChecklistRow(StepState.DONE, "Fee paid · wallet verified")
                    ChecklistRow(StepState.RUNNING, "Documents under review")
                    ChecklistRow(StepState.WAITING, "Agent badge and referral links issued")
                }
                Text(if (approved) "Your tools" else "Unlocks after approval", fontWeight = FontWeight.ExtraBold)
            }
            val tools = listOf<Triple<ImageVector, String, String>>(
                Triple(Icons.Outlined.GroupAdd, "Onboard a seller", "In person, on their phone"), Triple(Icons.Outlined.Add, "List for a seller", "Video or photos"),
                Triple(Icons.Outlined.Link, "Referral links", "selo.zm/a/…"), Triple(Icons.Outlined.Payments, "Commissions", "Daily payouts"))
            tools.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (i, t, s) ->
                        SeloCard(Modifier.weight(1f).alpha(if (approved) 1f else .45f)) {
                            Icon(i, null, tint = Selo.Red); Text(t, fontWeight = FontWeight.ExtraBold, color = Selo.Red); Text(s, color = Selo.Mute)
                        }
                    }
                }
            }
            if (approved) PrimaryButton("Add listing for a seller", onClick = onAddListing)
            else LockedNote("You'll get a push notification once approved.")
            selo.Nachipa01.ui.screens.account.AccountSettingsCard(open = { app.navRequest.value = "account?r=$it" })
            OutlineButton("Log out") { scope.launch { app.backend.logout(); onLogout() } }
            Spacer(Modifier.height(32.dp))
        }
    }
}
