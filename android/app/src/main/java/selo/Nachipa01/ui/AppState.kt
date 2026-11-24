package selo.Nachipa01.ui

import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import selo.Nachipa01.data.model.Advert
import selo.Nachipa01.data.model.PlatformConfig
import selo.Nachipa01.data.model.SessionUser
import selo.Nachipa01.data.repo.AiDraft
import selo.Nachipa01.data.repo.Backend

/** App-wide state: session, live platform config (synced from back office) and listing drafts. */
class AppState(val backend: Backend) : ViewModel() {
    val session = mutableStateOf<SessionUser?>(null)
    val config: StateFlow<PlatformConfig> = backend.config().stateIn(viewModelScope, SharingStarted.Eagerly, PlatformConfig())
    val adverts: StateFlow<List<Advert>> = backend.adverts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last number used on this device, shown masked on the welcome screen. */
    val rememberedMsisdn = mutableStateOf<String?>(null)
    val rememberedName = mutableStateOf<String?>(null)

    // Listing creation flow (seller and agent)
    val capturedMedia = mutableStateListOf<Uri>()
    val listingPrompt = mutableStateOf<String?>(null)
    val aiDraft = mutableStateOf<AiDraft?>(null)

    fun signedIn(user: SessionUser) {
        session.value = user
        rememberedMsisdn.value = user.msisdn
        rememberedName.value = user.firstName
    }

    fun resetListingFlow() { capturedMedia.clear(); listingPrompt.value = null; aiDraft.value = null }
}

val LocalAppState = compositionLocalOf<AppState> { error("AppState not provided") }
