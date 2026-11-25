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
import androidx.compose.ui.draw.clip
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
    Scaffold(bottomBar = { SeloBottomBar(buyerTabs, cartItem, tab, centerBadge = LocalAppState.current.cartCount) { tab = it } }, containerColor = Selo.Surface) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                "discover" -> selo.Nachipa01.ui.screens.discover.DiscoverScreen(onBuy = { nav.navigate("checkout/${it.id}") })
                "home" -> BuyerHome(onParcel = { nav.navigate("parcel") }, onCategory = { c -> tab = if (c == "cart") "cart" else "search" }, onOrders = { tab = "orders" }, onOpen = open)
                "search" -> selo.Nachipa01.ui.screens.discover.SearchScreen(onOpen = open)
                "cart" -> CartScreen(onCheckout = { nav.navigate("checkout/cart") }, onBrowse = { tab = "discover" })
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
                    IconButton({ app.navRequest.value = "account?r=profile" }) { Icon(Icons.Outlined.AccountCircle, "My profile", tint = Color.White) }
                    Spacer(Modifier.weight(1f)); LogoTile(); Spacer(Modifier.weight(1f))
                    IconButton({ app.navRequest.value = "account?r=inbox" }) { Icon(Icons.Outlined.Notifications, "Notifications", tint = Color.White) }
                }
            }
        }
        WalletCard("Selo Wallet", app.session.value?.msisdn?.let { selo.Nachipa01.domain.Validation.maskMsisdn(it) } ?: "", 124_050,
            listOf(QuickAction(Icons.Outlined.ShoppingCart, "Cart") { onCategory("cart") }, QuickAction(Icons.Outlined.Inventory2, "Send parcel", onParcel),
                QuickAction(Icons.Outlined.Inventory2, "Orders", onOrders)), Modifier.padding(horizontal = 14.dp).offset(y = (-10).dp))
        selo.Nachipa01.ui.screens.discover.StoriesRow(onOpen)
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

fun stateLabel(s: OrderState) = when (s) {
    OrderState.INITIATED -> "Awaiting payment" to Tone.GREY; OrderState.ESCROW_LOCKED, OrderState.BROADCASTING -> "Finding rider" to Tone.WARN
    OrderState.RIDER_ASSIGNED -> "Rider assigned" to Tone.INFO; OrderState.PICKED_UP, OrderState.IN_TRANSIT -> "En route" to Tone.FUND
    OrderState.DELIVERED -> "Delivered" to Tone.OK; OrderState.REFUNDED -> "Refunded" to Tone.GREY
    OrderState.CANCELLED -> "Cancelled" to Tone.GREY; OrderState.PAYOUT_PENDING_RETRY -> "Delivered" to Tone.OK
    OrderState.AWAITING_DELIVERY_CODE -> "Rider arrived" to Tone.WARN; OrderState.COMPLETED -> "Completed" to Tone.OK
    OrderState.DELIVERY_FAILED -> "Delivery failed" to Tone.BRAND; OrderState.DISPUTED -> "Under review" to Tone.BRAND
}

/** Cart (PRD v6.2): items from one seller; the total is worked out at checkout with delivery and fees. */
@Composable
private fun CartScreen(onCheckout: () -> Unit, onBrowse: () -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Your cart", if (app.cart.isEmpty()) "Nothing added yet" else "From ${app.cart.first().listing.sellerName}")
        FormColumn {
            if (app.cart.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.ShoppingCart, null, tint = Selo.Grey, modifier = Modifier.size(56.dp))
                    Text("Your cart is empty", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Sub("Open a listing's details in Discover and tap Add to cart.")
                    OutlineButton("Browse listings", onClick = onBrowse)
                }
                return@FormColumn
            }
            app.cart.toList().forEach { ci ->
                SeloCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(Selo.RedTint, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            if (ci.listing.thumbUrl != null) coil3.compose.AsyncImage(ci.listing.thumbUrl, null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Icon(Icons.Outlined.Inventory2, null, tint = Selo.Red)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ci.listing.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(Fees.formatK(ci.listing.priceNgwee * ci.qty), color = Selo.Red, fontWeight = FontWeight.ExtraBold)
                        }
                        TextButton({ app.setQty(ci.listing.id, ci.qty - 1) }) { Text(if (ci.qty == 1) "✕" else "−", color = Selo.Red, fontSize = 18.sp) }
                        Text("${ci.qty}", fontWeight = FontWeight.Bold)
                        TextButton({ app.setQty(ci.listing.id, ci.qty + 1) }, enabled = ci.qty < ci.listing.stock.coerceAtLeast(1)) { Text("+", color = Selo.Red, fontSize = 18.sp) }
                    }
                }
            }
            val goods = app.cart.sumOf { it.listing.priceNgwee * it.qty }
            SeloCard {
                KvRow("Items (${app.cartCount})", Fees.formatK(goods))
                KvRow("Delivery, platform and transaction fees", "at checkout")
            }
            PrimaryButton("Checkout · ${Fees.formatK(goods)} + fees", color = Selo.Green, onClick = onCheckout)
            if (cfg.paymentProcessingPct > 0 || cfg.buyerServiceFeePct > 0) Sub("Your registered mobile money number pays by default. You can switch numbers at checkout.")
        }
    }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("My profile", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton({ app.navRequest.value = "account?r=profile" }) { Icon(Icons.Outlined.Edit, "Edit profile", tint = Color.White) } }
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
                val myOrders by app.backend.buyerOrders().collectAsState(emptyList())
                val lists by app.backend.wishlists().collectAsState(emptyList())
                StatCard("${myOrders.size}", "Orders", Modifier.weight(1f))
                StatCard(Fees.formatK(myOrders.filter { it.state !in listOf(OrderState.REFUNDED, OrderState.CANCELLED, OrderState.INITIATED) }.sumOf { it.totalNgwee }).removeSuffix(".00"), "Total spent", Modifier.weight(1f))
                StatCard("${lists.sumOf { it.itemIds.size }}", "Saved", Modifier.weight(1f))
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
            selo.Nachipa01.ui.screens.account.AccountSettingsCard(open = { app.navRequest.value = "account?r=$it" },
                extra = listOf(Triple(Icons.Outlined.Inventory2, "My orders", onOrders), Triple(Icons.Outlined.LocalShipping, "My parcels") { app.navRequest.value = "parcel" }))
            OutlineButton("Log out") { confirm = true }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Are you sure you want to log out?") },
        confirmButton = { TextButton({ scope.launch { app.backend.logout(); onLogout() } }) { Text("Log out", color = Selo.Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
