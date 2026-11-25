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
import selo.Nachipa01.ui.components.verticalScrollable

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
    // Security step for every role (page index of the Security step in each wizard)
    @Test fun a5_buyerSecurity() = shot("A5-buyer-security") { RegistrationWizard(Role.BUYER, {}, {}, initialPage = 2) }
    @Test fun sr_sellerSecurity() = shot("SR-seller-security") { RegistrationWizard(Role.SELLER, {}, {}, initialPage = 4) }
    @Test fun ag_agentSecurity() = shot("AG-agent-security") { RegistrationWizard(Role.AGENT, {}, {}, initialPage = 2) }
    @Test fun rr_riderSecurity() = shot("RR-rider-security") { RegistrationWizard(Role.RIDER, {}, {}, initialPage = 5) }
    @Test fun a7_done() = shot("A7-account-created", Role.BUYER) { AccountCreatedScreen {} }
    @Test fun sr6_pending() = shot("SR6-seller-pending", Role.SELLER, AccountStatus.PENDING_KYC) { PendingScreen({}, {}) }
    @Test fun rr6_pending() = shot("RR6-rider-pending", Role.RIDER, AccountStatus.PENDING_VERIFICATION) { PendingScreen({}, {}) }
    @Test fun e3_forgot() = shot("E3-forgot-pin") { ForgotPinScreen({}, {}) }
    @Test fun b1_buyerHome() = shot("B1-buyer-home", Role.BUYER) { BuyerShell(rememberNavController(), "home") {} }
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
    @Test fun d4_review() = shot("D4-review-ai-prefill", Role.SELLER, prep = { s ->
        val d = s.backend.runAiPipeline(emptyList(), "tomatoes", {}, listOf("Tomato" to .92f), "video")
        s.listingForm.value = ListingForm(d.title, d.description, "85.00", d.category, d.condition, tags = d.tags, aiUsed = true, province = "Lusaka", district = "Soweto",
            priceHint = "AI suggests K 78.00–K 92.00 from 31 listings in Lusaka", reviewed = setOf("title", "description", "price"))
    }) { ReviewListingScreen({}, {}) }
    @Test fun d4b_fallback() = shot("D4b-review-manual-fallback", Role.SELLER, prep = { s ->
        s.aiFallback.value = "Auto-fill took longer than 8 seconds, so please fill in the details yourself."
        s.listingForm.value = ListingForm(category = "Fresh produce", province = "Lusaka", district = "Soweto")
    }) { ReviewListingScreen({}, {}) }
    @Test fun d5_preview() = shot("D5-preview", Role.SELLER, prep = { s ->
        s.listingMediaType.value = "video"
        s.listingForm.value = ListingForm("Fresh Chongwe Tomatoes – 5 kg Bag", "Firm, deep-red tomatoes picked this morning.", "85.00", "Fresh produce", "New",
            province = "Lusaka", district = "Soweto", tags = listOf("tomatoes"), discountPct = "10", aiUsed = true, reviewed = ListingForm.FIELDS.toSet())
    }) { PreviewListingScreen({}, {}) }
    @Test fun f1_discover() = shot("F1-discover-feed", Role.BUYER) { selo.Nachipa01.ui.screens.discover.DiscoverScreen({}) }
    @Test fun f2_discoverGuard() = shot("F2-feed-guardrail", Role.BUYER, prep = { s -> (s.backend as DemoBackend).minutesTodayOverride = 131 }) {
        selo.Nachipa01.ui.screens.discover.DiscoverScreen({}) }
    @Test fun f3_search() = shot("F3-search-browse", Role.BUYER, prep = { s -> s.recentSearches.addAll(listOf("tomatoes", "chitenge", "solar")) }) {
        selo.Nachipa01.ui.screens.discover.SearchScreen {} }
    @Test fun f5_buyerHomeRails() = shot("F5-home-rails", Role.BUYER, prep = { s ->
        s.backend.logEvents(listOf(selo.Nachipa01.data.events.BehaviourEvent("like", "l3", "s2", "Phones & accessories")), null) }) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScrollable()) { selo.Nachipa01.ui.screens.discover.HomeRails {} } }
    @Test fun f6_profile() = shot("F6-buyer-profile", Role.BUYER, prep = { s ->
        s.backend.logEvents(listOf(selo.Nachipa01.data.events.BehaviourEvent("like", "l1", "s1", "Fresh produce"), selo.Nachipa01.data.events.BehaviourEvent("like", "l4", "s3", "Fashion")), null) }) {
        BuyerShell(rememberNavController(), "account") {} }
    @Test fun s1_sellerHome() = shot("S1-seller-dashboard", Role.SELLER) { SellerShell(rememberNavController(), "home") {} }
    @Test fun s2_sellerListings() = shot("S2-seller-listings", Role.SELLER) { SellerShell(rememberNavController(), "listings") {} }
    @Test fun s3_sellerSettings() = shot("S3-seller-settings", Role.SELLER) { SellerShell(rememberNavController(), "more") {} }
    @Test fun e1_rider() = shot("E1-rider-home", Role.RIDER) { RiderShell {} }
    @Test fun ag4_agent() = shot("AG4-agent-pending", Role.AGENT, AccountStatus.PENDING_KYC) { AgentHomeScreen({}, {}) }
}
