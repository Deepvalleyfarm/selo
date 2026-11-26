package selo.Nachipa01.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun shot(name: String, role: Role? = null, status: AccountStatus = AccountStatus.ACTIVE, msisdn: String = "+260971234512",
                     advance: Long = 600, prep: suspend (AppState) -> Unit = {}, act: () -> Unit = {}, ui: @Composable () -> Unit) {
        val state = AppState(DemoBackend())
        if (role != null) DemoData.demoUser(role, msisdn, status = status).let { state.signedIn(it); (state.backend as DemoBackend).session = it }
        state.rememberedMsisdn.value = "+260971234512"; state.rememberedName.value = "Mwila"
        runBlocking { prep(state) }
        rule.setContent { SeloTheme { CompositionLocalProvider(LocalAppState provides state) { ui() } } }
        rule.mainClock.advanceTimeBy(advance)
        act(); rule.mainClock.advanceTimeBy(300)
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
    @Test fun a5_buyerSecurity() = shot("A5-buyer-security") { RegistrationWizard(Role.BUYER, {}, {}, initialPage = 3) }
    @Test fun sr_sellerSecurity() = shot("SR-seller-security") { RegistrationWizard(Role.SELLER, {}, {}, initialPage = 5) }
    @Test fun ag_agentSecurity() = shot("AG-agent-security") { RegistrationWizard(Role.AGENT, {}, {}, initialPage = 3) }
    @Test fun rr_riderSecurity() = shot("RR-rider-security") { RegistrationWizard(Role.RIDER, {}, {}, initialPage = 6) }
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
    // ---------------- v6.4 parcels v2, delivery code, address picker, account/support, login
    @Test fun c1_parcelsHome() = shot("C1-parcels-home", Role.BUYER, advance = 1_500) { selo.Nachipa01.ui.screens.buyer.ParcelsHome({}, {}, {}) }
    @Test fun c2_sendStep1() = shot("C2-send-parcel-1-route", Role.BUYER) { selo.Nachipa01.ui.screens.buyer.SendParcelScreen({}, {}) }
    @Test fun c3_sendStep2() = shot("C3-send-parcel-2-details", Role.BUYER) { selo.Nachipa01.ui.screens.buyer.SendParcelScreen({}, {}, initialStep = 2) }
    @Test fun c4_sendStep3() = shot("C4-send-parcel-3-pay", Role.BUYER) { selo.Nachipa01.ui.screens.buyer.SendParcelScreen({}, {}, initialStep = 3) }
    @Test fun c5_parcelAtDoor() = shot("C5-parcel-at-door", Role.BUYER, advance = 1_500) { selo.Nachipa01.ui.screens.buyer.ParcelDetailScreen("PCL-7Q2M4X1", {}, {}) }
    @Test fun c6_parcelDone() = shot("C6-parcel-completed", Role.BUYER, advance = 1_500) { selo.Nachipa01.ui.screens.buyer.ParcelDetailScreen("PCL-3K8D2Z9", {}, {}) }
    @Test fun c7_codeScreen() = shot("C7-receiver-delivery-code", Role.BUYER, advance = 1_500, prep = { s -> s.backend.myParcels().first() }) {
        selo.Nachipa01.ui.screens.buyer.DeliveryCodeScreen("parcel", "PCL-7Q2M4X1") {} }
    @Test fun c8_mapPicker() = shot("C8-map-pin-picker", Role.BUYER) {
        selo.Nachipa01.ui.components.MapPicker(selo.Nachipa01.data.model.Place("x", "Munali", "Great East Rd, Munali", -15.3960, 28.3610, "lusaka", "Lusaka", "Munali"), "lusaka", {}, {}) }
    @Test fun h1_account() = shot("H1-account-settings", Role.BUYER) { selo.Nachipa01.ui.screens.account.AccountHost("pin") {} }
    @Test fun h2_questions() = shot("H2-security-questions", Role.BUYER) { selo.Nachipa01.ui.screens.account.AccountHost("questions") {} }
    @Test fun h3_support() = shot("H3-help-support", Role.BUYER) { selo.Nachipa01.ui.screens.account.AccountHost("support") {} }
    @Test fun h4_contact() = shot("H4-contact-support", Role.BUYER) { selo.Nachipa01.ui.screens.account.AccountHost("support/new") {} }
    @Test fun h5_addresses() = shot("H5-saved-addresses", Role.BUYER) { selo.Nachipa01.ui.screens.account.AccountHost("addresses") {} }
    @Test fun a0_signup() = shot("A0-signup-tab") { WelcomeScreen({}, {}, {}, initialMode = "signup") }
    @Test fun a4_otp() = shot("A4-buyer-otp", advance = 1_500, prep = { s -> }) { RegistrationWizard(Role.BUYER, {}, {}, initialPage = 1) }
    // ---------------- v6.5 Maps, Beem OTP, Lipila withdrawals, Listing Studio
    @Test fun m1_shops() = shot("M1-shops-near-you", Role.BUYER, advance = 1_500) { ShopsMapScreen({}) }
    @Test fun m2_address() = shot("M2-address-field", Role.BUYER) { var p by remember { mutableStateOf<selo.Nachipa01.data.model.Place?>(null) }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(selo.Nachipa01.ui.theme.Selo.Surface).padding(16.dp)) {
            selo.Nachipa01.ui.components.AddressField("Delivery address", p, { p = it }) } }
    @Test fun w1_withdraw() = shot("W1-withdraw-full-balance", Role.RIDER) {
        selo.Nachipa01.ui.payments.WithdrawSheet(selo.Nachipa01.data.model.WalletStatus(48_760, 0, 2_000, null, null), "+260965551204") {} }
    @Test fun f7_aiBadges() = shot("F7-ai-enhanced-badges", Role.BUYER, advance = 1_000) {
        var items by remember { mutableStateOf<List<selo.Nachipa01.data.model.Listing>>(emptyList()) }
        val app = LocalAppState.current
        LaunchedEffect(Unit) { items = app.backend.listings().first().filter { it.id in listOf("l1", "l3", "l2") }.sortedBy { it.id } }
        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize().background(selo.Nachipa01.ui.theme.Selo.Surface).padding(10.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            items.forEach { l -> selo.Nachipa01.ui.screens.discover.ListingTile(l, androidx.compose.ui.Modifier.weight(1f)) {} } } }
    @Test fun a8_loginPinEntered() = shot("A8-login-pin-entered", prep = { s -> s.rememberedRole.value = Role.BUYER; s.rememberedFullName.value = "Mwila Chanda" }) {
        WelcomeScreen({}, {}, {}, previewPin = "24680") }
    @Test fun a9_signupId() = shot("A9-signup-id-nrc") { RegistrationWizard(Role.BUYER, {}, {}) }
    @Test fun a10_resume() = shot("A10-continue-signup", prep = { _ ->
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("selo_reg_draft", 0).edit()
            .putString("draft", """{"role":"SELLER","page":3,"savedAt":${System.currentTimeMillis()},"text":{"storeName":"Mama Bupe's Veg"}}""").commit() }) {
        WelcomeScreen({}, {}, {}, initialMode = "signup") }
    @Test fun w2_earnings() = shot("W2-rider-earnings-withdraw", Role.RIDER, advance = 1_500) { RiderShell("earnings") {} }
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

    // ---------------- v6.2 payments: default registered number, another number with operator detection, cart, retry
    private fun picker(name: String, registered: String, other: String?) = shot(name, Role.BUYER, msisdn = registered) {
        val c = selo.Nachipa01.ui.payments.rememberPaymentChoice(registered).also { if (other != null) { it.useDefault = false; it.other = other } }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(selo.Nachipa01.ui.theme.Selo.Surface).padding(16.dp)) {
            selo.Nachipa01.ui.payments.PaymentMethodPicker(c)
        }
    }
    @Test fun p1_defaultZamtel() = picker("P1-pay-default-zamtel", "+260955123456", null)
    @Test fun p2_otherAirtel() = picker("P2-pay-other-airtel-0977", "+260955123456", "0977 123 456")
    @Test fun p3_otherMtn() = picker("P3-pay-other-mtn-0966", "+260955123456", "0966 55")
    @Test fun p4_otherZamtel() = picker("P4-pay-other-zamtel-0955", "+260971234512", "0955 987 654")
    @Test fun p5_otherUnsupported() = picker("P5-pay-other-unsupported", "+260971234512", "0211 25")
    @Test fun b2b_cartCheckout() = shot("B2b-cart-checkout-zamtel", Role.BUYER, msisdn = "+260955123456", prep = { s ->
        val all = s.backend.listings().first(); val l = all.first { it.id == "l1" }
        s.addToCart(l); s.addToCart(l); all.firstOrNull { it.sellerUid == l.sellerUid && it.id != l.id }?.let { s.addToCart(it) }
    }) { CheckoutScreen("cart", {}, {}) }
    @Test fun b2c_cartTab() = shot("B2c-cart", Role.BUYER, prep = { s ->
        val l = s.backend.listings().first().first { it.id == "l1" }; s.addToCart(l); s.addToCart(l)
    }) { BuyerShell(rememberNavController(), "cart") {} }
    @Test fun b3b_paymentFailed() = shot("B3b-payment-failed-retry", Role.BUYER, msisdn = "+260955123400", prep = { s ->
        orderId = s.backend.checkout(CheckoutRequest(listOf(OrderItem("l1", "Fresh tomatoes 5 kg", 2, 8_000)), "s1", "Munali", "+260955123400", 6.1))
        kotlinx.coroutines.delay(2_800)
    }) { EscrowLockedScreen(orderId) {} }

    // ---------------- v6.2 Boost and Feed Story
    @Test fun g1_boost() = shot("G1-boost", Role.SELLER) { selo.Nachipa01.ui.screens.seller.PromoteScreen("l1", "boost", {}, {}) }
    @Test fun g2_story() = shot("G2-feed-story", Role.SELLER) { selo.Nachipa01.ui.screens.seller.PromoteScreen("l1", "story", {}, {}) }
    private var promoId = ""
    @Test fun g3_boostLive() = shot("G3-boost-live", Role.SELLER, prep = { s ->
        promoId = s.backend.createPromotion("boost", "l1", selo.Nachipa01.data.model.BoostReach.REGIONAL, 7,
            selo.Nachipa01.data.model.Targeting(provinces = listOf("Lusaka", "Copperbelt"), interests = listOf("Fresh produce")), "+260966123456")
        kotlinx.coroutines.delay(2_300)
    }) { selo.Nachipa01.ui.screens.seller.PromotionStatusScreen(promoId) {} }
    @Test fun g4_boostFailed() = shot("G4-boost-payment-failed", Role.SELLER, prep = { s ->
        promoId = s.backend.createPromotion("boost", "l1", selo.Nachipa01.data.model.BoostReach.LOCAL, 3,
            selo.Nachipa01.data.model.Targeting(districts = listOf("Lusaka")), "+260977000100")
        kotlinx.coroutines.delay(2_300)
    }) { selo.Nachipa01.ui.screens.seller.PromotionStatusScreen(promoId) {} }
    @Test fun g5_sellerHomePromos() = shot("G5-seller-home-promotions", Role.SELLER, prep = { s ->
        s.backend.createPromotion("story", "l1", selo.Nachipa01.data.model.BoostReach.LOCAL, 1, selo.Nachipa01.data.model.Targeting(), "+260955123456")
        kotlinx.coroutines.delay(2_300)
    }) { SellerShell(rememberNavController(), "home") {} }
    @Test fun g6_story() = shot("G6-story-viewer", Role.BUYER) {
        var stories by remember { mutableStateOf<List<selo.Nachipa01.data.model.Story>>(emptyList()) }
        val app = LocalAppState.current
        LaunchedEffect(Unit) { stories = app.backend.stories() }
        if (stories.isNotEmpty()) selo.Nachipa01.ui.screens.discover.StoryViewer(stories, 0, {}, {})
    }

    // ---------------- v6.3 rider post-login (PRD 6) + multi-stop routing
    private fun demo(s: AppState) = (s.backend as DemoBackend).rider
    private suspend fun twoJobs(s: AppState) {
        s.backend.setOnline(true); val r = demo(s)
        r.offerNow(); s.backend.acceptJob(s.backend.availableJobs().first().first().id)
        r.offerNow(); s.backend.acceptJob(s.backend.availableJobs().first().first().id)
        s.backend.setOnline(false)
    }
    @Test fun r01_homeOffline() = shot("R01-rider-home-offline", Role.RIDER) { RiderShell {} }
    @Test fun r02_homeOnline() = shot("R02-rider-home-online", Role.RIDER, advance = 2_500) { RiderShell(initialOnline = true) {} }
    @Test fun r03_dispatch() = shot("R03-dispatch-card", Role.RIDER, advance = 3_000, prep = { s -> s.backend.setOnline(true); demo(s).offerNow() }) { RiderShell(initialOnline = true) {} }
    @Test fun r04_dispatchCombine() = shot("R04-dispatch-combine", Role.RIDER, advance = 3_000, prep = { s ->
        s.backend.setOnline(true); demo(s).offerNow(); s.backend.acceptJob(s.backend.availableJobs().first().first().id); demo(s).offerNow()
    }) { RiderShell(initialOnline = true) {} }
    @Test fun r05_activeRoute() = shot("R05-active-multistop-route", Role.RIDER, advance = 4_000, prep = { s -> twoJobs(s) }) { RiderShell {} }
    @Test fun r06_turnByTurn() = shot("R06-turn-by-turn", Role.RIDER, advance = 6_000, prep = { s -> twoJobs(s) }) { RiderShell(initialNavigating = true) {} }
    @Test fun r07_deliverStop() = shot("R07-active-deliver-stop", Role.RIDER, advance = 4_000, prep = { s ->
        twoJobs(s); s.backend.riderActiveJobs().first().forEach { s.backend.markCollected(it.id) }
    }) { RiderShell {} }
    @Test fun r08_jobsHistory() = shot("R08-jobs-history", Role.RIDER) { RiderShell("jobs") {} }
    @Test fun r09_deliveryDetail() = shot("R09-delivery-detail", Role.RIDER) { RiderShell("jobs", initialScreen = "delivery:SNC-48100") {} }
    @Test fun r10_earnings() = shot("R10-earnings", Role.RIDER) { RiderShell("earnings") {} }
    @Test fun r11_fund() = shot("R11-social-fund", Role.RIDER) { RiderShell("fund") {} }
    @Test fun r12_claim() = shot("R12-file-claim", Role.RIDER) { RiderShell("fund", initialScreen = "claim") {} }
    @Test fun r13_more() = shot("R13-more", Role.RIDER) { RiderShell("more") {} }
    @Test fun r14_messages() = shot("R14-messages", Role.RIDER) { RiderShell("more", initialScreen = "messages") {} }
    @Test fun r15_chat() = shot("R15-chat", Role.RIDER) { RiderShell("more", initialScreen = "chat:t1") {} }
    @Test fun r16_zones() = shot("R16-zone-availability", Role.RIDER) { RiderShell("more", initialScreen = "zones") {} }
    @Test fun r17_notifications() = shot("R17-notifications", Role.RIDER) { RiderShell("more", initialScreen = "notifications") {} }
    @Test fun r18_profile() = shot("R18-rider-profile", Role.RIDER) { RiderShell("more", initialScreen = "profile") {} }
    @Test fun r19_settings() = shot("R19-settings-support", Role.RIDER) { RiderShell("more", initialScreen = "settings") {} }
    @Test fun r20_incident() = shot("R20-report-incident", Role.RIDER) { RiderShell("more", initialScreen = "incident") {} }
    @Test fun r21_login() = shot("R21-rider-login", prep = { s -> s.rememberedRole.value = Role.RIDER; s.rememberedName.value = "Chanda"; s.rememberedFullName.value = "Chanda Mulenga"; s.rememberedMsisdn.value = "+260971234514" }) {
        WelcomeScreen({}, {}, {}) }
    @Test fun r22_loginLocked() = shot("R22-rider-login-locked", prep = { s -> s.rememberedRole.value = Role.RIDER; s.rememberedName.value = "Chanda"; s.rememberedFullName.value = "Chanda Mulenga"
        s.rememberedMsisdn.value = "+260971234514"; s.pinLockedUntil.value = System.currentTimeMillis() + 29 * 60_000L }) { WelcomeScreen({}, {}, {}) }

    // v6.7
    @Test fun v1_shop() = shot("V1-shop-page", Role.BUYER, advance = 1_500) { selo.Nachipa01.ui.screens.shop.ShopScreen("s3") {} }
    @Test fun v2_receipts() = shot("V2-receipts", Role.BUYER, advance = 1_000) { selo.Nachipa01.ui.screens.account.ReceiptsScreen(null) {} }
    @Test fun v3_receipt() = shot("V3-receipt-detail", Role.BUYER, advance = 1_000) { selo.Nachipa01.ui.screens.account.ReceiptsScreen("SNC-4823390") {} }
    @Test fun v5_agentHome() = shot("V5-agent-home-ledger", Role.AGENT) { AgentHomeScreen({}, {}) }

    // v6.8: corporate vs individual sign-up
    private fun tap(text: String) = rule.onNode(androidx.compose.ui.test.hasText(text)).performClick()
    @Test fun w1_sellerCorporate() = shot("K1-seller-corporate", act = { tap("Corporate") }) { RegistrationWizard(Role.SELLER, {}, {}) }
    @Test fun w2_agentIndividual() = shot("K2-agent-individual") { RegistrationWizard(Role.AGENT, {}, {}) }
    @Test fun w3_agentCorporate() = shot("K3-agent-corporate", act = { tap("Agency (corporate)") }) { RegistrationWizard(Role.AGENT, {}, {}) }
    @Test fun w4_agentKycNrc() = shot("K4-agent-kyc-nrc") { RegistrationWizard(Role.AGENT, {}, {}, initialPage = 2) }
    @Test fun w5_sellerKycCorporate() = shot("K5-seller-kyc-corporate", act = { }) { RegistrationWizard(Role.SELLER, {}, {}, initialPage = 3) }
    @Test fun w6_agentHomeBottom() = shot("K6-agent-home", Role.AGENT, act = { rule.onNode(androidx.compose.ui.test.hasText("Log out")).performScrollTo() }) { AgentHomeScreen({}, {}) }
}
