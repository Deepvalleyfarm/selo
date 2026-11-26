package selo.Nachipa01.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Products the user has already loaded (feed, rails, search, shop pages, product pages) are kept on the phone so they
 * can still be browsed with no connection. The raw server records are stored (up to 400, newest first) and parsed by
 * the same code as live data; photos come from the image cache. [offline] drives the "You're offline" banner.
 */
object OfflineCatalog {
    private const val MAX = 400
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val items = LinkedHashMap<String, Map<String, Any?>>() // id → record, newest last
    val offline = MutableStateFlow(false)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "offline_catalog.json")
        // Back online: hide the banner straight away (the next screen refresh loads live data).
        runCatching { ctx.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { offline.value = false }
        }) }
        runCatching { JSONArray(file!!.readText()).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).toMap().let { m -> (m["id"] as? String)?.let { id -> items[id] = m } } } }
    }

    @Synchronized fun remember(records: List<Map<*, *>>) {
        if (records.isEmpty()) return
        records.forEach { r -> val id = r["id"] as? String ?: return@forEach; items.remove(id); @Suppress("UNCHECKED_CAST") items[id] = r as Map<String, Any?> }
        while (items.size > MAX) items.remove(items.keys.first())
        val snapshot = items.values.toList()
        val f = file ?: return
        io.execute { runCatching { f.writeText(JSONArray(snapshot.map { JSONObject(it) }).toString()) } }
    }

    /** Newest first. */
    @Synchronized fun all(): List<Map<String, Any?>> = items.values.reversed()
    @Synchronized fun get(id: String): Map<String, Any?>? = items[id]
    fun count() = items.size

    /** Offline search: every typed word must appear in the title, description, category, seller or tags. */
    fun search(query: String): List<Map<String, Any?>> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        return all().filter { r -> val hay = listOf("title", "description", "category", "sellerName", "tags").joinToString(" ") { r[it]?.toString().orEmpty() }.lowercase(); words.all { it in hay } }
    }

    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> unwrap(get(k)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.toMap()
        is JSONArray -> List(v.length()) { unwrap(v.get(it)) }
        else -> v
    }
}
