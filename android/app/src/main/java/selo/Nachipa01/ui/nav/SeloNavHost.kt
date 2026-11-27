package selo.Nachipa01.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import selo.Nachipa01.data.model.AccountStatus
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.data.model.SessionUser
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.screens.agent.AgentHomeScreen
import selo.Nachipa01.ui.screens.auth.*
import selo.Nachipa01.ui.screens.buyer.*
import selo.Nachipa01.ui.screens.rider.RiderShell
import selo.Nachipa01.ui.screens.seller.*

object R {
    const val WELCOME = "welcome"; const val ROLE = "role"; const val REGISTER = "register/{role}"
    const val REG_DONE = "regDone"; const val FORGOT = "forgot"; const val PENDING = "pending"
    const val BUYER = "buyer"; const val CHECKOUT = "checkout/{listingId}?select={select}"; const val PROMOTE = "promote/{listingId}/{type}"; const val PROMOTION = "promotion/{promoId}"; const val ESCROW = "escrow/{orderId}"
    const val TRACK = "track/{orderId}"; const val PARCEL = "parcel"; const val PARCEL_SEND = "parcelSend"; const val PARCEL_TRACK = "parcelTrack/{orderId}"; const val CODE = "code/{kind}/{id}"
    const val SELLER = "seller"; const val ADD_LISTING = "addListing"; const val CAMERA = "camera"; const val AI = "ai"
    const val REVIEW = "review"; const val RIDER = "rider"; const val AGENT = "agent"
    const val PHOTOS = "photos"; const val PREVIEW = "previewListing"; const val LISTING = "listing/{id}"
}

/** The one place that decides which portal a role may enter (PRD Part 8: never the wrong portal). */
fun portalFor(u: SessionUser): String = when {
    u.role == Role.BUYER -> R.BUYER
    u.status != AccountStatus.ACTIVE && u.role == Role.RIDER -> R.PENDING
    u.status != AccountStatus.ACTIVE && u.role == Role.SELLER -> R.PENDING
    u.role == Role.SELLER -> R.SELLER
    u.role == Role.AGENT -> R.AGENT
    else -> R.RIDER
}

fun NavHostController.goHome(u: SessionUser) = navigate(portalFor(u)) { popUpTo(0) { inclusive = true } }

@Composable
fun SeloNavHost(onSignedIn: (SessionUser) -> Unit) {
    val nav = rememberNavController()
    val app = LocalAppState.current
    LaunchedEffect(Unit) {
        app.backend.restoreSession()?.let { app.signedIn(it); nav.goHome(it) }
    }
    val signedIn: (SessionUser) -> Unit = { u -> app.signedIn(u); onSignedIn(u) }
    // selonachipa://listing/{id} and https://…/l/{id} open the listing once a session exists
    val pending = app.pendingListingId.value
    LaunchedEffect(pending, app.session.value) {
        if (pending != null && app.session.value != null) { app.pendingListingId.value = null; nav.navigate("listing/$pending") }
    }

    // product buttons and notifications ask for navigation through AppState
    val req = app.navRequest.value
    LaunchedEffect(req) { if (req != null) { app.navRequest.value = null; runCatching { nav.navigate(req) } } }
    // Edge-to-edge: keep every screen's last button above the phone's navigation bar and the keyboard (the bar area is
    // painted white so it blends with the bottom tab bar). Status-bar space is still handled per screen for full-bleed headers.
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
            androidx.compose.foundation.layout.WindowInsetsSides.Bottom + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
    NavHost(nav, startDestination = R.WELCOME) {
        composable(R.WELCOME) {
            WelcomeScreen(
                onLoggedIn = { signedIn(it); nav.goHome(it) },
                onCreateProfile = { nav.navigate(R.ROLE) },
                onForgotPin = { nav.navigate(R.FORGOT) },
                onResumeSignup = { role -> nav.navigate("register/${role.name}") },
            )
        }
        composable(R.ROLE) { RoleSelectScreen(onBack = { nav.popBackStack() }, onContinue = { nav.navigate("register/${it.name}") }) }
        composable(R.REGISTER) { e ->
            val role = Role.from(e.arguments?.getString("role"))
            RegistrationWizard(role, onExit = { nav.popBackStack() }, onRegistered = { u ->
                signedIn(u); nav.navigate(if (u.role == Role.BUYER) R.REG_DONE else portalFor(u)) { popUpTo(R.WELCOME) }
            })
        }
        composable(R.REG_DONE) { AccountCreatedScreen(onStart = { app.session.value?.let { nav.goHome(it) } }) }
        composable(R.FORGOT) { ForgotPinScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() }) }
        composable(R.PENDING) { PendingScreen(onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }, onDraftListing = { nav.navigate(R.ADD_LISTING) }) }

        composable(R.BUYER) {
            BuyerShell(nav = nav, onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } })
        }
        composable(R.CHECKOUT, arguments = listOf(androidx.navigation.navArgument("select") { nullable = true; defaultValue = null })) { e ->
            CheckoutScreen(e.arguments?.getString("listingId") ?: "", select = e.arguments?.getString("select"), onBack = { nav.popBackStack() },
            onPaid = { id -> nav.navigate("escrow/$id") { popUpTo(R.BUYER) } }) }
        composable(R.ESCROW) { e -> EscrowLockedScreen(e.arguments?.getString("orderId") ?: "", onTrack = { id -> nav.navigate("track/$id") { popUpTo(R.BUYER) } }) }
        composable(R.TRACK) { e -> TrackingScreen(e.arguments?.getString("orderId") ?: "", onBack = { nav.popBackStack() }) }
        composable(R.PARCEL) { ParcelsHome(onBack = { nav.popBackStack() }, onSend = { nav.navigate(R.PARCEL_SEND) }, onOpen = { nav.navigate("parcelTrack/$it") }) }
        composable(R.PARCEL_SEND) { SendParcelScreen(onBack = { nav.popBackStack() }, onBooked = { id -> nav.navigate("parcelTrack/$id") { popUpTo(R.PARCEL) } }) }
        composable(R.PARCEL_TRACK) { e -> ParcelDetailScreen(e.arguments?.getString("orderId") ?: "", onBack = { nav.popBackStack() }, onOpenCode = { nav.navigate("code/parcel/$it") }) }
        composable("account?r={r}", arguments = listOf(androidx.navigation.navArgument("r") { defaultValue = "support" })) { e ->
            selo.Nachipa01.ui.screens.account.AccountHost(e.arguments?.getString("r") ?: "support") { nav.popBackStack() } }
        composable("shops") { ShopsMapScreen(onBack = { nav.popBackStack() }, onOpenShop = { nav.navigate("shop/$it") }) }
        composable("shop/{sellerUid}") { e -> selo.Nachipa01.ui.screens.shop.ShopScreen(e.arguments?.getString("sellerUid") ?: "") { nav.popBackStack() } }
        composable("marketing") { selo.Nachipa01.ui.screens.seller.MarketingCentreScreen({ nav.popBackStack() }, onAdGenerator = { nav.navigate("adgen") }) }
        composable("adgen") { selo.Nachipa01.ui.screens.seller.AdGeneratorScreen { nav.popBackStack() } }
        composable("report") { selo.Nachipa01.ui.screens.account.PerformanceReportScreen(null) { nav.popBackStack() } }
        composable("report/{month}") { e -> selo.Nachipa01.ui.screens.account.PerformanceReportScreen(e.arguments?.getString("month")) { nav.popBackStack() } }
        composable("legal/{doc}") { e -> selo.Nachipa01.ui.screens.account.LegalScreen(e.arguments?.getString("doc")) { nav.popBackStack() } }
        composable("receipts") { selo.Nachipa01.ui.screens.account.ReceiptsScreen(null) { nav.popBackStack() } }
        composable("receipt/{refId}") { e -> selo.Nachipa01.ui.screens.account.ReceiptsScreen(e.arguments?.getString("refId")) { nav.popBackStack() } }
        composable(R.CODE) { e -> DeliveryCodeScreen(e.arguments?.getString("kind") ?: "order", e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() }) }

        composable(R.PROMOTE) { e -> selo.Nachipa01.ui.screens.seller.PromoteScreen(e.arguments?.getString("listingId") ?: "", e.arguments?.getString("type") ?: "boost",
            onBack = { nav.popBackStack() }, onStarted = { id -> nav.navigate("promotion/$id") { popUpTo(R.SELLER) } }) }
        composable(R.PROMOTION) { e -> selo.Nachipa01.ui.screens.seller.PromotionStatusScreen(e.arguments?.getString("promoId") ?: "", onDone = { nav.popBackStack() }) }
        composable(R.SELLER) { SellerShell(nav = nav, onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }) }
        composable(R.ADD_LISTING) { AddListingScreen(onClose = { nav.popBackStack() }, onCamera = { nav.navigate(R.CAMERA) }, onProcess = { nav.navigate(R.AI) },
            onPhotos = { nav.navigate(R.PHOTOS) }) }
        composable(R.PHOTOS) { PhotoPickScreen(onBack = { nav.popBackStack() }, onDone = { nav.navigate(R.AI) }) }
        composable(R.PREVIEW) { PreviewListingScreen(onBack = { nav.popBackStack() }, onPosted = { app.session.value?.let { nav.goHome(it) } }) }
        composable(R.LISTING) { e -> selo.Nachipa01.ui.screens.discover.ListingDeepLinkScreen(e.arguments?.getString("id") ?: "",
            onBuy = { nav.navigate("checkout/${it.id}") }, onBack = { if (!nav.popBackStack()) app.session.value?.let { nav.goHome(it) } }) }
        composable(R.CAMERA) { CameraScreen(onBack = { nav.popBackStack() }, onDone = { nav.navigate(R.AI) { popUpTo(R.ADD_LISTING) } }) }
        composable(R.AI) { AiProcessingScreen(onReady = { nav.navigate(R.REVIEW) { popUpTo(R.ADD_LISTING) } }, onFail = { nav.popBackStack() }) }
        composable(R.REVIEW) { ReviewListingScreen(onBack = { nav.popBackStack() }, onDone = {
            app.resetListingFlow(); app.session.value?.let { nav.goHome(it) } }, onPreview = { nav.navigate(R.PREVIEW) }) }

        composable(R.RIDER) { RiderShell(onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }) }
        composable(R.AGENT) { AgentHomeScreen(onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }, onAddListing = { nav.navigate(R.ADD_LISTING) }) }
    }
    ToastHost()
    OfflineBanner()
    }
}

/** Shown while there is no connection: products already loaded stay browsable (OfflineCatalog). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.OfflineBanner() {
    val off by selo.Nachipa01.data.repo.OfflineCatalog.offline.collectAsState()
    if (!off) return
    val n = selo.Nachipa01.data.repo.OfflineCatalog.count()
    androidx.compose.material3.Text(if (n > 0) "You're offline · showing $n products you've already seen" else "You're offline · check your connection",
        color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
            .background(selo.Nachipa01.ui.theme.Selo.Ink.copy(alpha = .88f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp))
}

/** App-wide confirmation toast (e.g. "Added to cart · 3 items") shown without leaving the page. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ToastHost() {
    val app = LocalAppState.current
    val msg = app.toast.value ?: return
    val kind = app.toastKind.value
    LaunchedEffect(msg, kind) { kotlinx.coroutines.delay(if (kind == "error") 4_500 else 2_600); if (app.toast.value == msg) { app.toast.value = null; app.toastKind.value = "info" } }
    val bg = when (kind) { "ok" -> selo.Nachipa01.ui.theme.Selo.GreenDark; "error" -> selo.Nachipa01.ui.theme.Selo.Red; else -> selo.Nachipa01.ui.theme.Selo.Ink }
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding()
        .padding(bottom = 96.dp, start = 20.dp, end = 20.dp).background(bg, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .clickable { app.toast.value = null }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(when (kind) { "ok" -> androidx.compose.material.icons.Icons.Outlined.CheckCircle; "error" -> androidx.compose.material.icons.Icons.Outlined.ErrorOutline
            else -> androidx.compose.material.icons.Icons.Outlined.Info }, null, tint = androidx.compose.ui.graphics.Color.White, modifier = androidx.compose.ui.Modifier.size(20.dp))
        androidx.compose.material3.Text("  $msg", color = androidx.compose.ui.graphics.Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}
