package selo.Nachipa01.ui.screens.seller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.SellerStats
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.util.Calendar

private val tabs = listOf(
    NavItem("home", Icons.Outlined.Home, "Home"), NavItem("orders", Icons.Outlined.Inventory2, "Orders"),
    NavItem("earnings", Icons.Outlined.AccountBalanceWallet, "Earnings"), NavItem("more", Icons.Outlined.MoreHoriz, "More"),
)
private val addItem = NavItem("add", Icons.Outlined.Add, "Add listing")

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 0..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

/** D0: seller dashboard; centre button starts the AI listing flow. */
@Composable
fun SellerShell(nav: NavHostController, onLogout: () -> Unit) {
    val app = LocalAppState.current
    var tab by rememberSaveable { mutableStateOf("home") }
    val stats by app.backend.sellerStats().collectAsState(SellerStats(0, 0, 0, 0, 0, 0, 0))
    val orders by app.backend.sellerOrders().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val u = app.session.value
    Scaffold(bottomBar = { SeloBottomBar(tabs, addItem, tab) { if (it == "add") { app.resetListingFlow(); nav.navigate("addListing") } else tab = it } },
        containerColor = Selo.Surface) { pad ->
        Column(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize().verticalScroll(rememberScrollState())) {
            HomeBand("${greeting()}, ${u?.firstName ?: ""}", u?.storeName ?: "", badge = { StatusChip("Verified", Tone.OK) })
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    "home", "earnings" -> {
                        WalletCard("Earnings", "Paid out at rider pickup", stats.earningsNgwee, listOf(
                            QuickAction(Icons.AutoMirrored.Outlined.Logout, "Withdraw") {}, QuickAction(Icons.Outlined.BarChart, "Analytics") {},
                            QuickAction(Icons.Outlined.SwapHoriz, "Statement") {}))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard(Fees.formatK(stats.todayNgwee), "Today's revenue", Modifier.weight(1f))
                            StatCard("${stats.toPack}", "Orders to pack", Modifier.weight(1f), Selo.AmberDark, if (stats.toPack > 0) Selo.AmberTint else androidx.compose.ui.graphics.Color.White)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("${stats.liveListings}", "Live listings · ${stats.lowStock} low stock", Modifier.weight(1f))
                            StatCard(Fees.formatK(stats.monthNgwee), "This month · ${stats.monthOrders} orders", Modifier.weight(1f))
                        }
                        if (tab == "home") SellerOrderList(orders, scope, app)
                    }
                    "orders" -> SellerOrderList(orders, scope, app)
                    "more" -> {
                        SeloCard { Text("Store profile, agents, analytics, messages and settings", color = Selo.Mute) }
                        OutlineButton("Log out") { scope.launch { app.backend.logout(); onLogout() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SellerOrderList(orders: List<selo.Nachipa01.data.model.Order>, scope: kotlinx.coroutines.CoroutineScope, app: selo.Nachipa01.ui.AppState) {
    Text("New orders", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    if (orders.isEmpty()) NoteBox("No new orders. Share a listing to WhatsApp to bring in buyers.")
    orders.forEach { o ->
        var packed by remember(o.id) { mutableStateOf(false) }
        SeloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.items.joinToString { "${it.title} × ${it.qty}" }, fontWeight = FontWeight.Bold)
                    Text("${o.id} · ${o.dropoff}", color = Selo.Mute, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp)); StatusChip("Escrow held", Tone.OK)
                }
                if (packed) StatusChip("Packed", Tone.INFO)
                else Button({ scope.launch { app.backend.confirmPack(o.id); packed = true } }, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) { Text("Confirm & pack", fontSize = 12.sp) }
            }
        }
    }
}
