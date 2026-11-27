package selo.Nachipa01.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import selo.Nachipa01.ui.screens.account.LegalScreen
import selo.Nachipa01.ui.theme.SeloTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegalSecureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun agreementsBlockScreenshotsOnlyWhileOpen() {
        val open = mutableStateOf(true)
        rule.setContent { SeloTheme { if (open.value) LegalScreen("terms") {} } }
        rule.waitForIdle()
        val secure = { rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        assertEquals(true, secure())
        rule.runOnUiThread { open.value = false }; rule.waitForIdle()
        assertEquals(false, secure())
    }
}
