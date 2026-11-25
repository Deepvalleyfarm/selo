package selo.Nachipa01.push

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import selo.Nachipa01.data.model.ReceivedCode

/**
 * Delivery codes received by THIS phone (the receiver's). The server keeps only a hash, so the push payload is the one
 * place the code lives; it's kept in private app storage until it expires and is never uploaded or logged.
 */
object DeliveryCodeStore {
    private const val PREFS = "selo_delivery_codes"
    private val state = MutableStateFlow<List<ReceivedCode>>(emptyList())
    val codes: StateFlow<List<ReceivedCode>> = state
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = p.all.mapNotNull { (k, v) -> parse(k, v as? String) }.filter { it.expiresAt > System.currentTimeMillis() }.sortedByDescending { it.receivedAt }
    }

    fun put(code: ReceivedCode) {
        state.value = (listOf(code) + state.value.filterNot { it.kind == code.kind && it.orderId == code.orderId })
            .filter { it.expiresAt > System.currentTimeMillis() }
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("${code.kind}:${code.orderId}", "${code.code}|${code.expiresAt}|${code.receivedAt}")?.apply()
    }

    fun clear(kind: String, orderId: String) {
        state.value = state.value.filterNot { it.kind == kind && it.orderId == orderId }
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("$kind:$orderId")?.apply()
    }

    fun find(kind: String, orderId: String) = state.value.firstOrNull { it.kind == kind && it.orderId == orderId && it.expiresAt > System.currentTimeMillis() }

    private fun parse(key: String, v: String?): ReceivedCode? {
        val (kind, id) = key.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
        val parts = v?.split("|") ?: return null
        return ReceivedCode(kind, id, parts.getOrNull(0) ?: return null, parts.getOrNull(1)?.toLongOrNull() ?: 0, parts.getOrNull(2)?.toLongOrNull() ?: 0)
    }
}
