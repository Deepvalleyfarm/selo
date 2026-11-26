package selo.Nachipa01.data.maps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import selo.Nachipa01.data.model.AddressSuggestion
import java.util.UUID

/**
 * On-device cache of address predictions so typing the same thing again (or going back and forth) never costs a
 * Places request. In memory (LRU, 300 queries) and on disk for 7 days. Also holds the Places session token: one per
 * search session (field focus → pick), regenerated after a pick.
 */
object PlacesCache {
    private const val PREFS = "selo_places_cache"
    private const val TTL_MS = 7 * 86_400_000L
    private const val MAX = 300
    private var ctx: Context? = null
    private val mem = object : LinkedHashMap<String, Pair<Long, List<AddressSuggestion>>>(64, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, List<AddressSuggestion>>>) = size > MAX
    }

    fun init(context: Context) { ctx = context.applicationContext }

    fun key(query: String, cityId: String?) = "${query.trim().lowercase().replace(Regex("\\s+"), " ")}|${cityId ?: "zm"}"

    @Synchronized fun get(query: String, cityId: String?): List<AddressSuggestion>? {
        val k = key(query, cityId)
        mem[k]?.let { (at, v) -> if (System.currentTimeMillis() - at < TTL_MS) return v }
        val raw = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(k, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.getLong("at") > TTL_MS) return null
            val a = o.getJSONArray("v")
            List(a.length()) { i -> a.getJSONObject(i).let { s -> AddressSuggestion(s.getString("id"), s.getString("label"), s.getString("secondary"), s.getString("source"),
                if (s.has("d")) s.getInt("d") else null) } }
                .also { mem[k] = o.getLong("at") to it }
        }.getOrNull()
    }

    @Synchronized fun put(query: String, cityId: String?, v: List<AddressSuggestion>) {
        val k = key(query, cityId); val now = System.currentTimeMillis()
        mem[k] = now to v
        val arr = JSONArray(v.map { s -> JSONObject().put("id", s.id).put("label", s.label).put("secondary", s.secondary).put("source", s.source).apply { s.distanceM?.let { put("d", it) } } })
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(k, JSONObject().put("at", now).put("v", arr).toString())?.apply()
    }

    /** Places session token: ties the keystrokes of one search together. */
    var sessionToken: String = UUID.randomUUID().toString(); private set
    fun endSession() { sessionToken = UUID.randomUUID().toString() }

    /** Pure gate used by the field (and unit tests): only ≥ minChars of real text triggers a lookup. */
    fun shouldSearch(query: String, minChars: Int) = query.trim().length >= minChars
}
