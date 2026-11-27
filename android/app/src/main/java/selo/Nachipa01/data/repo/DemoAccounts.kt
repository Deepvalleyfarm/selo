package selo.Nachipa01.data.repo

import android.content.Context
import org.json.JSONObject
import selo.Nachipa01.data.model.Role

/**
 * Demo build only: accounts registered on this phone, so logging in again returns the same role and name.
 * Demo accounts are approved instantly — no admin review — so every rider, seller and agent feature is usable straight away.
 */
object DemoAccounts {
    data class Saved(val role: Role, val firstName: String, val fullName: String, val storeName: String)
    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }
    private fun prefs() = ctx?.getSharedPreferences("selo_demo_accounts", Context.MODE_PRIVATE)
    fun save(msisdn: String, s: Saved) {
        prefs()?.edit()?.putString(msisdn, JSONObject().put("role", s.role.name).put("first", s.firstName).put("full", s.fullName).put("store", s.storeName).toString())?.apply()
    }
    fun get(msisdn: String): Saved? = prefs()?.getString(msisdn, null)?.let { runCatching { JSONObject(it).let { o ->
        Saved(Role.valueOf(o.getString("role")), o.optString("first"), o.optString("full"), o.optString("store")) } }.getOrNull() }
}
