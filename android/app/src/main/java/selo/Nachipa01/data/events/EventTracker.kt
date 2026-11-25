package selo.Nachipa01.data.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Infinity Memory keys on the device — identical rules to functions/src/algorithm.ts. */
object Keys {
    private val rnd = SecureRandom()
    const val SHARDS = 64

    fun uuidv7(now: Long = System.currentTimeMillis()): String {
        val b = ByteArray(16)
        for (i in 0 until 6) b[i] = (now ushr (8 * (5 - i))).toByte()
        val r = ByteArray(10).also(rnd::nextBytes)
        System.arraycopy(r, 0, b, 6, 10)
        b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** Jump consistent hash over the first 64 bits of SHA-256 (same as the server). */
    fun jumpHash(key: String, buckets: Int): Int {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        var k = BigInteger(1, d.copyOfRange(0, 8)).toLong().toULong()
        var b = -1L; var j = 0L
        while (j < buckets) {
            b = j
            k = k * 2862933555777941757UL + 1UL
            j = ((b + 1) * ((1L shl 31).toDouble() / ((k shr 33) + 1UL).toDouble())).toLong()
        }
        return b.toInt()
    }

    /** entity_type + UUIDv7 + shard (events are co-located by user id). */
    fun eventKey(userId: String) = "evt_${uuidv7()}_s${jumpHash(userId, SHARDS).toString().padStart(2, '0')}"
}

/** A behaviour event exactly as stored in the append-only behaviourEvents log (PRD v6.1 §6.2). */
data class BehaviourEvent(
    val type: String, val listingId: String? = null, val sellerUid: String? = null, val category: String? = null,
    val mediaType: String? = null, val source: String? = null, val watchMs: Long? = null, val totalMs: Long? = null,
    val replayed: Boolean? = null, val undone: Boolean? = null, val channel: String? = null, val skipSpeed: String? = null,
    val wishlistId: String? = null, val query: String? = null, val resultsCount: Int? = null, val firstClickListingId: String? = null,
    val durationMs: Long? = null, val device: String? = null, val appVersion: String? = null, val ts: Long = System.currentTimeMillis(),
) {
    fun toMap(userId: String, variant: String?): Map<String, Any> = buildMap {
        put("type", type); put("userId", userId); put("ts", ts)
        listingId?.let { put("listingId", it) }; sellerUid?.let { put("sellerUid", it) }; category?.let { put("category", it) }
        mediaType?.let { put("mediaType", it) }; source?.let { put("source", it) }; watchMs?.let { put("watchMs", it) }
        totalMs?.let { put("totalMs", it) }; replayed?.let { put("replayed", it) }; undone?.let { put("undone", it) }
        channel?.let { put("channel", it) }; skipSpeed?.let { put("skipSpeed", it) }; wishlistId?.let { put("wishlistId", it) }
        query?.let { put("query", it) }; resultsCount?.let { put("resultsCount", it) }; firstClickListingId?.let { put("firstClickListingId", it) }
        durationMs?.let { put("durationMs", it) }; device?.let { put("device", it) }; appVersion?.let { put("appVersion", it) }
        variant?.let { put("variant", it) }
    }
}

/**
 * Buffers events and flushes every 5 s or 10 events, whichever comes first. Every tap, view, scroll stop
 * and watch is captured; the sink writes to Firestore (live) or an in-memory log (demo).
 */
class EventTracker(private val sink: suspend (List<BehaviourEvent>) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = mutableListOf<BehaviourEvent>()
    private var timer: Job? = null
    val sessionStartedAt get() = started
    private var started = 0L

    fun log(e: BehaviourEvent) {
        val flushNow: Boolean
        synchronized(buffer) { buffer += e; flushNow = buffer.size >= 10 }
        if (flushNow) flush() else if (timer?.isActive != true) timer = scope.launch { delay(5_000); flush() }
    }

    fun flush() {
        val batch = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (batch.isEmpty()) return
        scope.launch { runCatching { sink(batch) }.onFailure { synchronized(buffer) { buffer.addAll(0, batch) } } }
    }

    fun sessionStart(device: String, appVersion: String) { started = System.currentTimeMillis(); log(BehaviourEvent("session_start", device = device, appVersion = appVersion)) }
    fun sessionEnd() {
        if (started == 0L) return
        log(BehaviourEvent("session_end", durationMs = System.currentTimeMillis() - started)); started = 0L; flush()
    }
}
