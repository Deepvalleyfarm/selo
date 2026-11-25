package selo.Nachipa01.ui.screens.buyer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.data.model.Order
import selo.Nachipa01.data.model.OrderState
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

private val buyerTabs = listOf(
    NavItem("discover", Icons.Outlined.PlayCircle, "Discover"), NavItem("home", Icons.Outlined.Home, "Home"),
    NavItem("search", Icons.Outlined.Search, "Search"), NavItem("account", Icons.Outlined.Person, "Account"),
)
private val cartItem = NavItem("cart", Icons.Outlined.ShoppingCart, "Cart")

@Composable
fun BuyerShell(nav: NavHostController, initialTab: String = "discover", onLogout: () -> Unit) {
    // The full-screen feed is the primary buyer experience (PRD v6.1 §5.1)
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val open: (Listing) -> Unit = { nav.navigate("listing/${it.id}") }
    Scaffold(bottomBar = { SeloBottomBar(buyerTabs, cartItem, tab) { tab = it } }, containerColor = Selo.Surface) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                "discover" -> selo.Nachipa01.ui.screens.discover.DiscoverScreen(onBuy = { nav.navigate("checkout/${it.id}") })
                "home" -> BuyerHome(onParcel = { nav.navigate("parcel") }, onCategory = { tab = "search" }, onOrders = { tab = "orders" }, onOpen = open)
                "search" -> selo.Nachipa01.ui.screens.discover.SearchScreen(onOpen = open)
                "cart" -> ListingFeed(title = "Your cart", onBuy = { nav.navigate("checkout/${it.id}") })
                "orders" -> BuyerOrders(onOpen = { o -> nav.navigate(if (o.isParcel) "parcelTrack/${o.id}" else "track/${o.id}") })
                "account" -> BuyerProfile(onLogout, onOrders = { tab = "orders" }, onOpen = open)
            }
        }
    }
}

@Composable
private fun BuyerHome(onParcel: () -> Unit, onCategory: (String) -> Unit, onOrders: () -> Unit, onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    var pill by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box {
            Column(Modifier.fillMaxWidth().background(Selo.Red).statusBarsPadding().padding(16.dp).height(64.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Color.Transparent, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AccountCircle, null, tint = Color.White) }
                    Spacer(Modifier.weight(1f)); LogoTile(); Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Notifications, "Notifications", tint = Color.White)
                }
            }
        }
        WalletCard("Selo Wallet", app.session.value?.msisdn?.let { selo.Nachipa01.domain.Validation.maskMsisdn(it) } ?: "", 124_050,
            listOf(QuickAction(Icons.Outlined.ShoppingCart, "Cart") { onCategory("cart") }, QuickAction(Icons.Outlined.Inventory2, "Send parcel", onParcel),
                QuickAction(Icons.Outlined.Inventory2, "Orders", onOrders)), Modifier.padding(horizontal = 14.dp).offset(y = (-10).dp))
        PillTabs(listOf("For you", "Nearby", "Deals"), pill) { pill = it }
        val tiles = listOf<Triple<ImageVector, String, String?>>(
            Triple(Icons.Outlined.Eco, "Fresh produce", null), Triple(Icons.Outlined.PhoneAndroid, "Electronics", null),
            Triple(Icons.Outlined.Checkroom, "Fashion", null), Triple(Icons.Outlined.LunchDining, "Fast food", null),
            Triple(Icons.Outlined.AutoAwesome, "SeloWizard", "New"), Triple(Icons.Outlined.Shield, "Insure parcel", null),
            Triple(Icons.Outlined.Handyman, "Hardware", null), Triple(Icons.Outlined.GridView, "All", null))
        tiles.chunked(4).forEach { row ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                row.forEach { (i, l, b) -> CategoryTile(i, l, b, Modifier.weight(1f)) { if (l == "Insure parcel") onParcel() else onCategory(l) } }
            }
        }
        selo.Nachipa01.ui.screens.discover.HomeRails(onOpen)
        Box(Modifier.padding(14.dp)) { PromoBanner("Pay safely with Selo Escrow", "Sellers are paid only after your rider collects.") }
    }
}

@Composable
private fun ListingFeed(title: String, onBuy: (Listing) -> Unit) {
    val app = LocalAppState.current
    val listings by app.backend.listings().collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SeloHeader(title, "Live listings near you")
        FormColumn {
            SeloField("Search listings near you…", q, { q = it }, trailing = Icons.Outlined.Search)
            listings.filter { q.isBlank() || it.title.contains(q, true) || it.category.contains(q, true) }.forEach { l ->
                SeloCard {
                    Box(Modifier.fillMaxWidth().height(140.dp).background(Selo.RedTint, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlayCircle, "Play advert", tint = Selo.Red, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(l.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(Fees.formatK(l.priceNgwee), color = Selo.Red, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("${l.sellerName} · ${l.zone}", color = Selo.Mute, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineButton("Message", Modifier.weight(1f)) {}
                        PrimaryButton("Buy now", Modifier.weight(1f)) { onBuy(l) }
                    }
                }
            }
        }
    }
}

fun stateLabel(s: OrderState) = when (s) {
    OrderState.INITIATED -> "Awaiting payment" to Tone.GREY; OrderState.ESCROW_LOCKED, OrderState.BROADCASTING -> "Finding rider" to Tone.WARN
    OrderState.RIDER_ASSIGNED -> "Rider assigned" to Tone.INFO; OrderState.PICKED_UP, OrderState.IN_TRANSIT -> "En route" to Tone.FUND
    OrderState.DELIVERED -> "Delivered" to Tone.OK; OrderState.REFUNDED -> "Refunded" to Tone.GREY
    OrderState.CANCELLED -> "Cancelled" to Tone.GREY; OrderState.PAYOUT_PENDING_RETRY -> "Delivered" to Tone.OK
}

@Composable
private fun BuyerOrders(onOpen: (Order) -> Unit) {
    val orders by LocalAppState.current.backend.buyerOrders().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        SeloHeader("My orders", "${orders.size} orders")
        FormColumn {
            if (orders.isEmpty()) NoteBox("No orders yet. Find something on Home and pay safely with Selo Escrow.")
            orders.forEach { o ->
                val (label, tone) = stateLabel(o.state)
                SeloCard(Modifier.clickable { onOpen(o) }) {
                    Row { Text(o.id, color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f)); StatusChip(label, tone) }
                    Text(o.items.joinToString { "${it.title} × ${it.qty}" }, fontWeight = FontWeight.Bold)
                    Text(Fees.formatK(o.totalNgwee), color = Selo.Red, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun BuyerProfile(onLogout: () -> Unit, onOrders: () -> Unit, onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    val u = app.session.value
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(Selo.Red).statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { Text("My profile", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.Edit, "Edit", tint = Color.White) }
            Box(Modifier.padding(top = 8.dp).size(76.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text(u?.fullName?.split(" ")?.mapNotNull { it.firstOrNull() }?.take(2)?.joinToString("") ?: "", color = Selo.Red, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            }
            Text(u?.fullName ?: "", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
            Text("${u?.area ?: ""} · Member since ${u?.memberSince ?: ""}", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("VERIFIED BUYER", color = Selo.GreenDark, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.background(Color.White, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("38", "Orders", Modifier.weight(1f)); StatCard("K 6,420", "Total spent", Modifier.weight(1f)); StatCard("12", "Saved", Modifier.weight(1f))
            }
            selo.Nachipa01.ui.screens.discover.LikedSavedTabs(onOpen)
            SectionLabel("Privacy")
            SeloCard {
                var consent by rememberSaveable { mutableStateOf(false) }
                var age by rememberSaveable { mutableStateOf<String?>(null) }
                SwitchRow("Let sellers contact me", consent, { consent = it; scope.launch { app.backend.setLeadConsent(it, age) } },
                    sub = "Off by default. When on, sellers of items you like, save or ask about may receive your name and number.")
                Text("Age range (optional, helps sellers understand their audience)", fontSize = 12.sp, color = Selo.Mute)
                @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    selo.Nachipa01.domain.Taxonomy.AGE_BANDS.forEach { b -> SeloChip(b, age == b) { age = if (age == b) null else b; scope.launch { app.backend.setLeadConsent(consent, age) } } }
                }
            }
            SectionLabel("Account settings")
            SeloCard(padding = 4.dp) {
                listOf(
                    Triple(Icons.Outlined.Inventory2, "My orders", ""),
                    Triple(Icons.Outlined.Place, "Delivery addresses", "2"), Triple(Icons.Outlined.AccountBalanceWallet, "Payment wallets", "Airtel, MTN"),
                    Triple(Icons.Outlined.Lock, "Change 5-digit PIN", ""), Triple(Icons.Outlined.Shield, "Security questions", "3 set"),
                    Triple(Icons.Outlined.Notifications, "Notifications", ""), Triple(Icons.Outlined.Language, "Language", "English"),
                    Triple(Icons.AutoMirrored.Outlined.HelpOutline, "Help & support", ""),
                ).forEachIndexed { i, (icon, label, v) ->
                    if (i > 0) HorizontalDivider(color = Selo.Line)
                    Row(Modifier.fillMaxWidth().clickable { if (label == "My orders") onOrders() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Selo.Red, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(v, color = Selo.Mute, fontSize = 12.sp); Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Color(0xFFB5B5BE))
                    }
                }
            }
            OutlineButton("Log out") { confirm = true }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Are you sure you want to log out?") },
        confirmButton = { TextButton({ scope.launch { app.backend.logout(); onLogout() } }) { Text("Log out", color = Selo.Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
