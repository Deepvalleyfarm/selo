package selo.Nachipa01.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import selo.Nachipa01.data.model.AccountStatus
import selo.Nachipa01.data.model.OrderItem
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.data.repo.CheckoutRequest
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.data.repo.DemoData
import selo.Nachipa01.data.repo.ParcelRequest
import selo.Nachipa01.ui.screens.agent.AgentHomeScreen
import selo.Nachipa01.ui.screens.auth.*
import selo.Nachipa01.ui.screens.buyer.*
import selo.Nachipa01.ui.screens.rider.RiderShell
import selo.Nachipa01.ui.screens.seller.*
import selo.Nachipa01.ui.theme.SeloTheme

/** Renders each screen with demo data and writes PNGs to app/build/outputs/roborazzi for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class ScreensScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, role: Role? = null, status: AccountStatus = AccountStatus.ACTIVE, prep: suspend (AppState) -> Unit = {}, ui: @Composable () -> Unit) {
        val state = AppState(DemoBackend())
        if (role != null) state.signedIn(DemoData.demoUser(role, status = status))
        state.rememberedMsisdn.value = "+260971234512"; state.rememberedName.value = "Mwila"
        runBlocking { prep(state) }
        rule.setContent { SeloTheme { CompositionLocalProvider(LocalAppState provides state) { ui() } } }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private var orderId = ""

    @Test fun a1_welcome() = shot("A1-welcome") { WelcomeScreen({}, {}, {}) }
    @Test fun a2_role() = shot("A2-role") { RoleSelectScreen({}, {}) }
    @Test fun a3_buyerReg() = shot("A3-buyer-registration") { RegistrationWizard(Role.BUYER, {}, {}) }
    @Test fun sr1_sellerReg() = shot("SR1-seller-registration") { RegistrationWizard(Role.SELLER, {}, {}) }
    @Test fun ag1_agentReg() = shot("AG1-agent-registration") { RegistrationWizard(Role.AGENT, {}, {}) }
    @Test fun rr1_riderReg() = shot("RR1-rider-registration") { RegistrationWizard(Role.RIDER, {}, {}) }
    @Test fun a7_done() = shot("A7-account-created", Role.BUYER) { AccountCreatedScreen {} }
    @Test fun sr6_pending() = shot("SR6-seller-pending", Role.SELLER, AccountStatus.PENDING_KYC) { PendingScreen({}, {}) }
    @Test fun rr6_pending() = shot("RR6-rider-pending", Role.RIDER, AccountStatus.PENDING_VERIFICATION) { PendingScreen({}, {}) }
    @Test fun e3_forgot() = shot("E3-forgot-pin") { ForgotPinScreen({}, {}) }
    @Test fun b1_buyerHome() = shot("B1-buyer-home", Role.BUYER) { BuyerShell(rememberNavController()) {} }
    @Test fun b2_checkout() = shot("B2-checkout", Role.BUYER) { CheckoutScreen("l1", {}, {}) }
    @Test fun b3_escrow() = shot("B3-escrow-locked", Role.BUYER, prep = { s ->
        orderId = s.backend.checkout(CheckoutRequest(listOf(OrderItem("l1", "Fresh tomatoes 5 kg", 2, 8_000)), "s1", "Munali", "+260971234512", 6.1))
    }) { EscrowLockedScreen(orderId) {} }
    @Test fun b4_tracking() = shot("B4-tracking", Role.BUYER, prep = { s ->
        orderId = s.backend.checkout(CheckoutRequest(listOf(OrderItem("l1", "Fresh tomatoes 5 kg", 2, 8_000)), "s1", "Munali", "+260971234512", 6.1))
    }) { TrackingScreen(orderId) {} }
    @Test fun c1_parcel() = shot("C1-parcel", Role.BUYER) { ParcelScreen({}, {}) }
    @Test fun c2_parcelTrack() = shot("C2-parcel-tracking", Role.BUYER, prep = { s ->
        orderId = s.backend.sendParcel(ParcelRequest("Comesa Market", "Woodlands Ext.", "Mwila", "Joseph B.", "+260961234541", "Laptop in box", "Medium", true, 350_000, 7.4, "+260971234512"))
    }) { ParcelTrackScreen(orderId) {} }
    @Test fun d0_seller() = shot("D0-seller-dashboard", Role.SELLER) { SellerShell(rememberNavController()) {} }
    @Test fun d1_add() = shot("D1-add-listing", Role.SELLER) { AddListingScreen({}, {}, {}) }
    @Test fun d3_ai() = shot("D3-ai-processing", Role.SELLER) { AiProcessingScreen({}, {}) }
    @Test fun d4_review() = shot("D4-review", Role.SELLER, prep = { s -> s.aiDraft.value = s.backend.runAiPipeline(emptyList(), "tomatoes") {} }) { ReviewListingScreen({}, {}) }
    @Test fun e1_rider() = shot("E1-rider-home", Role.RIDER) { RiderShell {} }
    @Test fun ag4_agent() = shot("AG4-agent-pending", Role.AGENT, AccountStatus.PENDING_KYC) { AgentHomeScreen({}, {}) }
}
