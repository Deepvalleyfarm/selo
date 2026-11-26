package selo.Nachipa01.ui.screens.auth

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import selo.Nachipa01.data.model.Role
import java.io.File

/**
 * Sign-up auto-save. Everything typed or picked is written to private storage as it changes, so closing the app
 * (or the phone killing it) never loses progress; the welcome screen offers "Continue sign-up" next time.
 * For safety the PIN and security answers are never saved — the user re-enters them when they come back.
 * Drafts older than 14 days are discarded.
 */
object RegDraft {
    private const val PREFS = "selo_reg_draft"
    private const val MAX_AGE_MS = 14 * 86_400_000L
    private const val OTP_TOKEN_MAX_AGE_MS = 25 * 60_000L // server tokens last 30 min

    data class Summary(val role: Role, val step: Int, val savedAt: Long, val name: String)

    fun save(ctx: Context, s: RegState, page: Int) {
        val o = JSONObject()
            .put("role", s.role.name).put("page", page).put("savedAt", System.currentTimeMillis())
            .put("text", JSONObject(s.text.toMap()))
            .put("chips", JSONObject(s.chips.mapValues { JSONArray(it.value.toList()) }))
            .put("docs", JSONObject(s.docs.mapValues { it.value.toString() }))
            .put("offered", JSONArray(s.offered.map { it.id })).put("questionIds", JSONArray(s.questionIds.toList()))
            .put("walletNetwork", s.walletNetwork).put("walletHolder", s.walletHolder ?: JSONObject.NULL)
            .put("ticks", JSONObject(s.ticks.toMap())).put("hours", JSONObject(s.hours.toMap())).put("biometric", s.biometric)
            .put("otpToken", s.otpToken ?: JSONObject.NULL).put("otpFor", s.otpFor ?: JSONObject.NULL).put("otpAt", s.otpAt)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("draft", o.toString()).apply()
    }

    private fun load(ctx: Context): JSONObject? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("draft", null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - o.optLong("savedAt") > MAX_AGE_MS) { clear(ctx); return null }
        return o
    }

    fun summary(ctx: Context): Summary? = load(ctx)?.let { o ->
        val t = o.optJSONObject("text")
        Summary(Role.from(o.optString("role")), o.optInt("page") + 1, o.optLong("savedAt"),
            t?.optString("storeName")?.takeIf { it.isNotBlank() } ?: t?.optString("fullName")?.takeIf { it.isNotBlank() } ?: t?.optString("firstName") ?: "")
    }

    /** Restores a draft for [role] into [s]; returns the page to reopen, or null when there is none for that role. */
    fun restore(ctx: Context, s: RegState, bank: List<selo.Nachipa01.data.model.SecurityQuestion>): Int? {
        val o = load(ctx) ?: return null
        if (o.optString("role") != s.role.name) return null
        o.optJSONObject("text")?.let { t -> t.keys().forEach { k -> s.text[k] = t.optString(k) } }
        o.optJSONObject("chips")?.let { c -> c.keys().forEach { k -> val a = c.getJSONArray(k); s.chips[k] = List(a.length()) { a.getString(it) }.toSet() } }
        o.optJSONObject("docs")?.let { d -> d.keys().forEach { k -> val u = Uri.parse(d.getString(k)); if (u.scheme != "file" || File(u.path ?: "").exists()) s.docs[k] = u } }
        o.optJSONArray("offered")?.let { a -> val ids = List(a.length()) { a.getString(it) }; val qs = ids.mapNotNull { id -> bank.firstOrNull { it.id == id } }
            if (qs.size == ids.size && qs.isNotEmpty()) { s.offered.clear(); s.offered.addAll(qs) } }
        o.optJSONArray("questionIds")?.let { a -> s.questionIds.clear(); s.questionIds.addAll(List(a.length()) { a.getString(it) }) }
        s.walletNetwork = o.optString("walletNetwork", s.walletNetwork)
        s.walletHolder = o.optString("walletHolder").takeIf { it.isNotBlank() && it != "null" }
        o.optJSONObject("ticks")?.let { t -> t.keys().forEach { k -> s.ticks[k] = t.optBoolean(k) } }
        o.optJSONObject("hours")?.let { h -> h.keys().forEach { k -> s.hours[k] = h.optBoolean(k) } }
        s.biometric = o.optBoolean("biometric", true)
        val at = o.optLong("otpAt")
        if (System.currentTimeMillis() - at < OTP_TOKEN_MAX_AGE_MS) {
            s.otpToken = o.optString("otpToken").takeIf { it.isNotBlank() && it != "null" }; s.otpFor = o.optString("otpFor").takeIf { it.isNotBlank() && it != "null" }; s.otpAt = at
        }
        return o.optInt("page")
    }

    fun clear(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("draft").apply() }
}
