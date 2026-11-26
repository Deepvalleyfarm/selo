package selo.Nachipa01.data.maps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import selo.Nachipa01.data.model.Place
import selo.Nachipa01.domain.route.Geo
import selo.Nachipa01.domain.route.LatLng

/**
 * Every place the user confirms (typed, picked on the map or "Use my location") is remembered on the phone, newest
 * first, so it comes back instantly next time: when the field opens (no typing, no network) and as the first matches
 * while typing. Points within 25 m of each other are one place (the newest name wins). Up to 40 places.
 */
object RecentPlaces {
    private const val PREFS = "selo_recent_places"
    private const val MAX = 40
    private const val SAME_M = 25.0
    private var ctx: Context? = null
    private var mem: MutableList<Pair<Place, Long>>? = null

    fun init(context: Context) { ctx = context.applicationContext }

    @Synchronized private fun load(): MutableList<Pair<Place, Long>> = mem ?: runCatching {
        val a = JSONArray(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("v", null) ?: "[]")
        MutableList(a.length()) { i -> a.getJSONObject(i).let { o -> fromJson(o) to o.optLong("at") } }
    }.getOrDefault(mutableListOf()).also { mem = it }

    /** Newest first. */
    fun all(): List<Place> = load().map { it.first }

    @Synchronized fun remember(p: Place) {
        if (!p.inServiceArea) return
        val list = load()
        list.removeAll { (q, _) -> q.placeId != null && q.placeId == p.placeId || Geo.m(LatLng(q.lat, q.lng), LatLng(p.lat, p.lng)) < SAME_M }
        list.add(0, p to System.currentTimeMillis())
        while (list.size > MAX) list.removeAt(list.lastIndex)
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("v", JSONArray(list.map { (q, at) -> toJson(q).put("at", at) }).toString())?.apply()
    }

    /** Remembered places whose name or address contains every word typed (instant, offline). */
    fun matching(query: String, limit: Int = 4): List<Place> = matches(all(), query, limit)

    fun matches(places: List<Place>, query: String, limit: Int): List<Place> {
        val words = query.lowercase().split(Regex("[\\s,]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return places.filter { p -> val hay = "${p.label} ${p.address} ${p.neighbourhood ?: ""} ${p.landmark ?: ""}".lowercase(); words.all { it in hay } }.take(limit)
    }

    private fun toJson(p: Place) = JSONObject().put("id", p.id).put("label", p.label).put("address", p.address).put("lat", p.lat).put("lng", p.lng)
        .put("cityId", p.cityId).put("cityName", p.cityName).put("nb", p.neighbourhood).put("addressId", p.addressId).put("placeId", p.placeId).put("landmark", p.landmark)

    private fun JSONObject.str(k: String) = if (isNull(k) || !has(k)) null else getString(k)
    private fun fromJson(o: JSONObject) = Place(o.getString("id"), o.getString("label"), o.getString("address"), o.getDouble("lat"), o.getDouble("lng"),
        o.str("cityId"), o.str("cityName"), o.str("nb"), true, false, o.str("addressId"), o.str("placeId"), o.str("landmark"))
}
