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
        return map.values.filter { !(it.deleted && now - it.updatedAt > TOMBSTONE_TTL) }
    }

    fun same(a: List<Entry>, b: List<Entry>): Boolean = a.associateBy { it.uuid } == b.associateBy { it.uuid }
}
