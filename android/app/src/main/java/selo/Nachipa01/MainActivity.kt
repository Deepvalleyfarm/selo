package selo.Nachipa01

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import selo.Nachipa01.ui.AppState
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.nav.SeloNavHost
import selo.Nachipa01.ui.theme.SeloTheme

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppState((application as SeloApp).backend) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("selo", Context.MODE_PRIVATE)
        if (state.rememberedMsisdn.value == null) {
            state.rememberedMsisdn.value = prefs.getString("msisdn", null)
            state.rememberedName.value = prefs.getString("firstName", null)
            state.rememberedFullName.value = prefs.getString("fullName", null)
            state.rememberedRole.value = prefs.getString("role", null)?.let { r -> runCatching { selo.Nachipa01.data.model.Role.valueOf(r) }.getOrNull() }
            state.pinLockedUntil.value = prefs.getLong("pinLockedUntil", 0L)
        }
        // cart survives restarts (private storage, ids + quantities only; prices and stock are refreshed on restore)
        state.persistCart = { v -> prefs.edit { putString("cart", v) } }
        state.restoreCart(prefs.getString("cart", null))
        codeLink(intent)?.let { state.navRequest.value = it }
        sharedMedia(intent)?.let { state.capturedMedia.add(it) }
        deepLinkListing(intent)?.let { state.pendingListingId.value = it }
        setContent {
            SeloTheme {
                CompositionLocalProvider(LocalAppState provides state) {
                    SeloNavHost(onSignedIn = { u -> prefs.edit { putString("msisdn", u.msisdn); putString("firstName", u.firstName); putString("fullName", u.fullName); putString("role", u.role.name); putLong("pinLockedUntil", 0L) } })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkListing(intent)?.let { state.pendingListingId.value = it }
        codeLink(intent)?.let { state.navRequest.value = it }
    }

    /** Delivery-code notifications open the protected code screen: selo://code/{kind}/{id}. */
    private fun codeLink(i: Intent?): String? = i?.getStringExtra("deepLink")?.let { Regex("^selo://code/(parcel|order)/([A-Za-z0-9-]+)").find(it) }?.let { "code/${it.groupValues[1]}/${it.groupValues[2]}" }

    /** Session start/end events feed session-time KPIs and the daily-minutes guardrail. */
    override fun onStart() {
        super.onStart()
        if (state.session.value != null) state.tracker.sessionStart(android.os.Build.MODEL, BuildConfig.VERSION_NAME)
    }
    override fun onStop() { state.tracker.sessionEnd(); super.onStop() }

    /** selonachipa://listing/{id} or https://mabala-f2d65.web.app/l/{id} */
    private fun deepLinkListing(i: Intent?): String? {
        val d = i?.data ?: return null
        val id = when {
            d.scheme == "selonachipa" && d.host == "listing" -> d.pathSegments.firstOrNull()
            d.scheme == "https" && d.pathSegments.firstOrNull() == "l" -> d.pathSegments.getOrNull(1)
            else -> null
        }
        return id?.takeIf { Regex("[\\w-]{2,80}").matches(it) }
    }

    private fun sharedMedia(i: Intent?): Uri? {
        if (i?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
