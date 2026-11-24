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
        }
        sharedMedia(intent)?.let { state.capturedMedia.add(it) }
        setContent {
            SeloTheme {
                CompositionLocalProvider(LocalAppState provides state) {
                    SeloNavHost(onSignedIn = { u -> prefs.edit { putString("msisdn", u.msisdn); putString("firstName", u.firstName) } })
                }
            }
        }
    }

    private fun sharedMedia(i: Intent?): Uri? {
        if (i?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
