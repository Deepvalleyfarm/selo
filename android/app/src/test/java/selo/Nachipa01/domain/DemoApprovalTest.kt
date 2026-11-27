package selo.Nachipa01.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import selo.Nachipa01.data.model.AccountStatus
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.data.repo.RegistrationRequest
import selo.Nachipa01.ui.nav.R
import selo.Nachipa01.ui.nav.portalFor

class DemoApprovalTest {
    @Test fun demoAccountsAreActiveRightAfterRegistration() = runBlocking {
        for ((role, portal) in listOf(Role.RIDER to R.RIDER, Role.SELLER to R.SELLER, Role.AGENT to R.AGENT)) {
            val u = DemoBackend().register(RegistrationRequest(role, "+260971234519", "13579", emptyList(), mapOf("fullName" to "Test User", "storeName" to "Test Shop"), null, null, emptyMap())) {}
            assertEquals("$role status", AccountStatus.ACTIVE, u.status)
            assertEquals("$role opens its full portal, not the pending screen", portal, portalFor(u))
        }
    }
}
