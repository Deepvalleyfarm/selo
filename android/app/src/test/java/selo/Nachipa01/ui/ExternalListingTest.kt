package selo.Nachipa01.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.data.repo.DemoData
import selo.Nachipa01.ui.components.ProductActions
import selo.Nachipa01.ui.theme.SeloTheme

/** Open Network listings are bought on the originating platform's checkout, never through the SeloNachipa cart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalListingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val mabala = DemoData.listings.first { it.id == "ext_mabala1" }

    @Test fun externalListingNeverEntersTheCart() {
        val s = AppState(DemoBackend())
        assertTrue(mabala.isExternal)
        val msg = s.addToCart(mabala)
        assertEquals(0, s.cart.size)
        assertTrue(msg!!.contains("Mabala"))
        s.buyNow(mabala)
        assertEquals(0, s.cart.size)
        assertEquals("https://mabala.example/products/eggs-tray-30", s.openExternal.value)
        assertEquals(null, s.navRequest.value)
    }

    @Test fun showsBuyOnPlatformInsteadOfCartButtons() {
        val s = AppState(DemoBackend())
        rule.setContent { SeloTheme { CompositionLocalProvider(LocalAppState provides s) { ProductActions(mabala) } } }
        rule.onNodeWithText("Buy on Mabala", substring = true).assertIsDisplayed().performClick()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Add to cart", substring = true)).fetchSemanticsNodes().size)
        assertEquals("https://mabala.example/products/eggs-tray-30", s.openExternal.value)
    }
}
