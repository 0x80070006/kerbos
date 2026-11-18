package com.kerbos.app.vault

import com.kerbos.app.otp.Algo
import com.kerbos.app.otp.OtpType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One OTP account. [updatedAt] is the logical timestamp used for last-writer-wins merging.
 * Deleted entries are kept as tombstones ([deleted] = true, secret wiped) so deletions propagate.
 */
data class Entry(
    val uuid: String = UUID.randomUUID().toString(),
    val type: OtpType = OtpType.TOTP,
    val name: String = "",
    val issuer: String = "",
    val secret: String = "",
    val algo: Algo = Algo.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long = 0,
    val icon: String? = null,
    val group: String = "",
    val favorite: Boolean = false,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
) {
    val title: String get() = issuer.ifBlank { name }
    val subtitle: String get() = if (issuer.isBlank()) "" else name

    fun toJson(): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("updatedAt", updatedAt)
        if (deleted) {
            put("deleted", true)
            return@apply
        }
        put("type", type.name); put("name", name); put("issuer", issuer); put("secret", secret)
        put("algo", algo.name); put("digits", digits); put("period", period); put("counter", counter)
        icon?.let { put("icon", it) }
        put("group", group); put("favorite", favorite); put("note", note); put("createdAt", createdAt)
    }

    fun tombstone(at: Long) = Entry(uuid = uuid, createdAt = createdAt, updatedAt = at, deleted = true)

    companion object {
        fun fromJson(o: JSONObject): Entry {
            val uuid = o.getString("uuid")
            val updatedAt = o.optLong("updatedAt")
            if (o.optBoolean("deleted")) return Entry(uuid = uuid, createdAt = updatedAt, updatedAt = updatedAt, deleted = true)
            return Entry(
                uuid = uuid,
                type = runCatching { OtpType.valueOf(o.optString("type")) }.getOrDefault(OtpType.TOTP),
                name = o.optString("name"),
                issuer = o.optString("issuer"),
                secret = o.optString("secret"),
                algo = runCatching { Algo.valueOf(o.optString("algo")) }.getOrDefault(Algo.SHA1),
                digits = o.optInt("digits", 6),
                period = o.optInt("period", 30),
                counter = o.optLong("counter"),
                icon = if (o.has("icon")) o.getString("icon") else null,
                group = o.optString("group"),
                favorite = o.optBoolean("favorite"),
                note = o.optString("note"),
                createdAt = o.optLong("createdAt", updatedAt),
                updatedAt = updatedAt,
            )
        }

        fun listToJson(list: Collection<Entry>) = JSONArray().apply { list.forEach { put(it.toJson()) } }
        fun listFromJson(a: JSONArray?): List<Entry> =
            if (a == null) emptyList() else (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/** Conflict-free merge: union by uuid, last writer wins per entry, deterministic tie-break. */
object Merge {
    private const val TOMBSTONE_TTL = 180L * 24 * 3600 * 1000

    private fun winner(a: Entry, b: Entry): Entry = when {
        a.updatedAt > b.updatedAt -> a
        b.updatedAt > a.updatedAt -> b
        a == b -> a
        a.deleted != b.deleted -> if (a.deleted) a else b
        else -> if (a.toJson().toString() >= b.toJson().toString()) a else b
    }

    fun merge(local: List<Entry>, remote: List<Entry>, now: Long = System.currentTimeMillis()): List<Entry> {
        val map = LinkedHashMap<String, Entry>()
        for (e in local) map[e.uuid] = map[e.uuid]?.let { winner(it, e) } ?: e
        for (e in remote) map[e.uuid] = map[e.uuid]?.let { winner(it, e) } ?: e
        return collapseDuplicates(map.values.toList())
            .filter { !(it.deleted && now - it.updatedAt > TOMBSTONE_TTL) }
    }

    /** Same OTP account = same secret and same generation parameters. */
    private fun accountKey(e: Entry) =
        "${com.kerbos.app.otp.Base32.normalize(e.secret)}|${e.type}|${e.algo}|${e.digits}|${e.period}"

    /**
     * Collapses the same account stored under several uuids (e.g. added or imported on two
     * devices before they synced). Deterministic, so every device reaches the same result:
     * the smallest uuid is kept, it takes the content of the most recently edited copy (empty
     * fields completed by the others), and the other copies become tombstones that propagate.
     */
    fun collapseDuplicates(list: List<Entry>): List<Entry> {
        val groups = list.filter { !it.deleted && it.secret.isNotBlank() }
            .groupBy(::accountKey).values.filter { it.size > 1 }
        if (groups.isEmpty()) return list
        val replace = HashMap<String, Entry>()
        for (g0 in groups) {
            val g = g0.sortedBy { it.uuid }
            val keep = g.first()
            val latest = g.maxWith(compareBy<Entry> { it.updatedAt }.thenBy { it.toJson().toString() })
            // +1: the merged copy must win over every original copy on devices that have not collapsed yet.
            val ts = latest.updatedAt + 1
            replace[keep.uuid] = latest.copy(
                uuid = keep.uuid,
                issuer = latest.issuer.ifBlank { g.firstOrNull { it.issuer.isNotBlank() }?.issuer ?: "" },
                name = latest.name.ifBlank { g.firstOrNull { it.name.isNotBlank() }?.name ?: "" },
                icon = latest.icon ?: g.firstNotNullOfOrNull { it.icon },
                group = latest.group.ifBlank { g.firstOrNull { it.group.isNotBlank() }?.group ?: "" },
                note = latest.note.ifBlank { g.firstOrNull { it.note.isNotBlank() }?.note ?: "" },
                counter = if (latest.type == com.kerbos.app.otp.OtpType.HOTP) g.maxOf { it.counter } else latest.counter,
                createdAt = g.minOf { it.createdAt },
                updatedAt = ts,
            )
            for (o in g.drop(1)) replace[o.uuid] = o.tombstone(ts)
        }
        return list.map { replace[it.uuid] ?: it }
    }

    fun same(a: List<Entry>, b: List<Entry>): Boolean = a.associateBy { it.uuid } == b.associateBy { it.uuid }
}
