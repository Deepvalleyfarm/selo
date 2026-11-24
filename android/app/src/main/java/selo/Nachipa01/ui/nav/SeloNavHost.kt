package selo.Nachipa01.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    const val BUYER = "buyer"; const val CHECKOUT = "checkout/{listingId}"; const val ESCROW = "escrow/{orderId}"
    const val TRACK = "track/{orderId}"; const val PARCEL = "parcel"; const val PARCEL_TRACK = "parcelTrack/{orderId}"
    const val SELLER = "seller"; const val ADD_LISTING = "addListing"; const val CAMERA = "camera"; const val AI = "ai"
    const val REVIEW = "review"; const val RIDER = "rider"; const val AGENT = "agent"
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

    NavHost(nav, startDestination = R.WELCOME) {
        composable(R.WELCOME) {
            WelcomeScreen(
                onLoggedIn = { signedIn(it); nav.goHome(it) },
                onCreateProfile = { nav.navigate(R.ROLE) },
                onForgotPin = { nav.navigate(R.FORGOT) },
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
        composable(R.CHECKOUT) { e -> CheckoutScreen(e.arguments?.getString("listingId") ?: "", onBack = { nav.popBackStack() },
            onPaid = { id -> nav.navigate("escrow/$id") { popUpTo(R.BUYER) } }) }
        composable(R.ESCROW) { e -> EscrowLockedScreen(e.arguments?.getString("orderId") ?: "", onTrack = { id -> nav.navigate("track/$id") { popUpTo(R.BUYER) } }) }
        composable(R.TRACK) { e -> TrackingScreen(e.arguments?.getString("orderId") ?: "", onBack = { nav.popBackStack() }) }
        composable(R.PARCEL) { ParcelScreen(onBack = { nav.popBackStack() }, onBooked = { id -> nav.navigate("parcelTrack/$id") { popUpTo(R.BUYER) } }) }
        composable(R.PARCEL_TRACK) { e -> ParcelTrackScreen(e.arguments?.getString("orderId") ?: "", onBack = { nav.popBackStack() }) }

        composable(R.SELLER) { SellerShell(nav = nav, onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }) }
        composable(R.ADD_LISTING) { AddListingScreen(onClose = { nav.popBackStack() }, onCamera = { nav.navigate(R.CAMERA) }, onProcess = { nav.navigate(R.AI) }) }
        composable(R.CAMERA) { CameraScreen(onBack = { nav.popBackStack() }, onDone = { nav.navigate(R.AI) { popUpTo(R.ADD_LISTING) } }) }
        composable(R.AI) { AiProcessingScreen(onReady = { nav.navigate(R.REVIEW) { popUpTo(R.ADD_LISTING) } }, onFail = { nav.popBackStack() }) }
        composable(R.REVIEW) { ReviewListingScreen(onBack = { nav.popBackStack() }, onDone = {
            app.resetListingFlow(); app.session.value?.let { nav.goHome(it) } }) }

        composable(R.RIDER) { RiderShell(onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }) }
        composable(R.AGENT) { AgentHomeScreen(onLogout = { nav.navigate(R.WELCOME) { popUpTo(0) } }, onAddListing = { nav.navigate(R.ADD_LISTING) }) }
    }
}
