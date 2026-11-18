package com.kerbos.app.transfer

import com.kerbos.app.otp.Algo
import com.kerbos.app.otp.Base32
import com.kerbos.app.otp.OtpType
import com.kerbos.app.vault.Entry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PasswordRequired(val source: String) : Exception("Ce fichier $source est chiffré : mot de passe requis")
class WrongPassword : Exception("Mot de passe incorrect")
class ImportResult(val source: String, val entries: List<Entry>, val skipped: Int = 0)

/** Supported import sources, shown in the UI. Detection is automatic from the file content. */
object Formats {
    data class Source(val name: String, val hint: String)

    val importSources = listOf(
        Source("Aegis", "Export .json, chiffré ou non"),
        Source("2FAS", "Sauvegarde .2fas, chiffrée ou non"),
        Source("andOTP", "Sauvegarde .json ou .json.aes"),
        Source("Bitwarden / Bitwarden Authenticator", "Export .json non chiffré"),
        Source("FreeOTP+", "Export .json"),
        Source("Raivo OTP", "Fichier .json extrait de l'archive"),
        Source("Ente Auth, Proton Authenticator, Proton Pass…", "Fichier texte / JSON contenant des liens otpauth://"),
        Source("Kerbos", "Sauvegarde chiffrée Kerbos"),
    )
}

private val rng = SecureRandom()
private fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
private val b64d: Base64.Decoder = Base64.getDecoder()
private val b64e: Base64.Encoder = Base64.getEncoder()

private fun gcmDecrypt(key: ByteArray, nonce: ByteArray, ctAndTag: ByteArray): ByteArray = try {
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        doFinal(ctAndTag)
    }
} catch (e: AEADBadTagException) {
    throw WrongPassword()
}

private fun gcmEncrypt(key: ByteArray, nonce: ByteArray, pt: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        doFinal(pt)
    }

private fun jcaPbkdf2(alg: String, pw: String, salt: ByteArray, iter: Int): ByteArray {
    val spec = PBEKeySpec(pw.toCharArray(), salt, iter, 256)
    try {
        return SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private fun algoOf(s: String?): Algo? = when (s?.uppercase()?.replace("-", "")?.removePrefix("HMAC")) {
    null, "", "SHA1" -> Algo.SHA1
    "SHA256" -> Algo.SHA256
    "SHA512" -> Algo.SHA512
    else -> null
}

private fun mk(
    type: OtpType, issuer: String, name: String, secret: String, algo: Algo = Algo.SHA1,
    digits: Int = 6, period: Int = 30, counter: Long = 0, group: String = "", favorite: Boolean = false, note: String = "",
): Entry {
    val s = Base32.normalize(secret)
    require(Base32.isValid(s)) { "secret invalide" }
    return Entry(
        type = type, issuer = issuer.trim(), name = name.trim(), secret = s, algo = algo,
        digits = if (type == OtpType.STEAM) 5 else digits.coerceIn(4, 10), period = period.takeIf { it > 0 } ?: 30,
        counter = counter, group = group.trim(), favorite = favorite, note = note,
    )
}

object Importer {

    /**
     * Detects the format and returns the entries. Throws [PasswordRequired] when the file is
     * encrypted and no password was given, [WrongPassword] on a bad password.
     */
    fun parse(bytes: ByteArray, password: String?, uriParser: (String) -> List<Entry>): ImportResult {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿").trim()
        val json = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when (json) {
            is JSONObject -> when {
                json.has("header") && json.has("db") -> aegis(json, password)
                json.optString("format") == KERBOS_FORMAT -> kerbos(json, password)
                json.has("servicesEncrypted") || (json.has("services") && json.has("schemaVersion")) -> twoFas(json, password)
                json.has("tokens") -> freeOtpPlus(json)
                json.has("items") -> bitwarden(json, uriParser)
                else -> scan(text, "JSON", uriParser)
            }
            is JSONArray -> {
                val first = json.optJSONObject(0)
                when {
                    first == null -> throw IllegalArgumentException("Fichier vide")
                    first.has("kind") && first.has("secret") -> raivo(json)
                    first.has("secret") -> andOtp(json)
                    else -> scan(text, "JSON", uriParser)
                }
            }
            else -> when {
                text.contains("otpauth") -> scan(text, "Liste otpauth", uriParser)
                bytes.size > 44 -> andOtpEncrypted(bytes, password)
                else -> throw IllegalArgumentException("Format non reconnu")
            }
        }
    }

    // ---------------- Aegis ----------------

    private fun aegis(o: JSONObject, pw: String?): ImportResult {
        val header = o.getJSONObject("header")
        val dbAny = o.get("db")
        val db: JSONObject = if (dbAny is JSONObject) dbAny else {
            pw ?: throw PasswordRequired("Aegis")
            val slots = header.getJSONArray("slots")
            var master: ByteArray? = null
            for (i in 0 until slots.length()) {
                val s = slots.getJSONObject(i)
                if (s.optInt("type") != 1) continue
                val key = Kdf.scrypt(pw.toByteArray(Charsets.UTF_8), hex(s.getString("salt")), s.getInt("n"), s.getInt("r"), s.getInt("p"), 32)
                val kp = s.getJSONObject("key_params")
                master = try {
                    gcmDecrypt(key, hex(kp.getString("nonce")), hex(s.getString("key")) + hex(kp.getString("tag")))
                } catch (e: WrongPassword) { null }
                if (master != null) break
            }
            master ?: throw WrongPassword()
            val params = header.getJSONObject("params")
            val pt = gcmDecrypt(master, hex(params.getString("nonce")), b64d.decode(dbAny as String) + hex(params.getString("tag")))
            JSONObject(String(pt, Charsets.UTF_8))
        }
        val groups = HashMap<String, String>()
        db.optJSONArray("groups")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { groups[it.getString("uuid")] = it.optString("name") } }
        val out = ArrayList<Entry>()
        var skipped = 0
        val arr = db.getJSONArray("entries")
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val info = e.getJSONObject("info")
            val type = when (e.optString("type").lowercase()) {
                "totp" -> OtpType.TOTP; "hotp" -> OtpType.HOTP; "steam" -> OtpType.STEAM
                else -> { skipped++; continue }
            }
            val algo = algoOf(info.optString("algo")) ?: run { skipped++; null } ?: continue
            val group = e.optJSONArray("groups")?.let { g -> if (g.length() > 0) groups[g.getString(0)] else null }
                ?: e.optString("group", "").takeIf { it != "null" } ?: ""
            runCatching {
                mk(
                    type, e.optString("issuer"), e.optString("name"), info.getString("secret"), algo,
                    info.optInt("digits", 6), info.optInt("period", 30), info.optLong("counter"),
                    group, e.optBoolean("favorite"), e.optString("note"),
                )
            }.onSuccess { out += it }.onFailure { skipped++ }
        }
        return ImportResult("Aegis", out, skipped)
    }

    // ---------------- 2FAS ----------------

    private fun twoFas(o: JSONObject, pw: String?): ImportResult {
        val enc = o.optString("servicesEncrypted")
        val services: JSONArray = if (enc.isNotEmpty()) {
            pw ?: throw PasswordRequired("2FAS")
            val parts = enc.split(':')
            require(parts.size >= 3) { "Sauvegarde 2FAS invalide" }
            val key = jcaPbkdf2("PBKDF2WithHmacSHA256", pw, b64d.decode(parts[1]), 10_000)
            JSONArray(String(gcmDecrypt(key, b64d.decode(parts[2]), b64d.decode(parts[0])), Charsets.UTF_8))
        } else o.getJSONArray("services")
        val groups = HashMap<String, String>()
        o.optJSONArray("groups")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { groups[it.optString("id")] = it.optString("name") } }
        val out = ArrayList<Entry>()
        var skipped = 0
        for (i in 0 until services.length()) {
            val s = services.getJSONObject(i)
            val otp = s.optJSONObject("otp") ?: JSONObject()
            val type = when (otp.optString("tokenType", "TOTP").uppercase()) {
                "", "TOTP" -> OtpType.TOTP; "HOTP" -> OtpType.HOTP; "STEAM" -> OtpType.STEAM
                else -> { skipped++; continue }
            }
            runCatching {
                mk(
                    type, s.optString("name").ifEmpty { otp.optString("issuer") }, otp.optString("account"),
                    s.getString("secret"), algoOf(otp.optString("algorithm")) ?: Algo.SHA1,
                    otp.optInt("digits", 6), otp.optInt("period", 30), otp.optLong("counter"),
                    groups[s.optString("groupId")] ?: "",
                )
            }.onSuccess { out += it }.onFailure { skipped++ }
        }
        return ImportResult("2FAS", out, skipped)
    }

    // ---------------- andOTP ----------------

    private fun andOtp(a: JSONArray): ImportResult {
        val out = ArrayList<Entry>()
        var skipped = 0
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            val type = when (e.optString("type").lowercase()) {
                "totp" -> OtpType.TOTP; "hotp" -> OtpType.HOTP; "steam" -> OtpType.STEAM
                else -> { skipped++; continue }
            }
            var issuer = e.optString("issuer")
            var name = e.optString("label")
            if (!e.has("issuer")) {
                val parts = name.split(" - ", limit = 2)
                if (parts.size == 2) { issuer = parts[0]; name = parts[1] }
            }
            val group = e.optJSONArray("tags")?.let { if (it.length() > 0) it.getString(0) else "" } ?: ""
            runCatching {
                mk(type, issuer, name, e.getString("secret"), algoOf(e.optString("algorithm")) ?: Algo.SHA1,
                    e.optInt("digits", 6), e.optInt("period", 30), e.optLong("counter"), group)
            }.onSuccess { out += it }.onFailure { skipped++ }
        }
        return ImportResult("andOTP", out, skipped)
    }

    private fun andOtpEncrypted(data: ByteArray, pw: String?): ImportResult {
        pw ?: throw PasswordRequired("andOTP")
        val iter = ByteBuffer.wrap(data, 0, 4).int
        val plain: ByteArray = if (iter in 1..10_000_000) {
            val salt = data.copyOfRange(4, 16)
            val nonce = data.copyOfRange(16, 28)
            val key = jcaPbkdf2("PBKDF2WithHmacSHA1", pw, salt, iter)
            try {
                gcmDecrypt(key, nonce, data.copyOfRange(28, data.size))
            } catch (e: WrongPassword) {
                andOtpOld(data, pw)
            }
        } else andOtpOld(data, pw)
        return andOtp(JSONArray(String(plain, Charsets.UTF_8)))
    }

    /** Legacy andOTP format: key = SHA-256(password), nonce = first 12 bytes. */
    private fun andOtpOld(data: ByteArray, pw: String): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(pw.toByteArray(Charsets.UTF_8))
        return gcmDecrypt(key, data.copyOfRange(0, 12), data.copyOfRange(12, data.size))
    }

    // ---------------- Bitwarden ----------------

    private fun bitwarden(o: JSONObject, uriParser: (String) -> List<Entry>): ImportResult {
        if (o.optBoolean("encrypted")) throw IllegalArgumentException(
            "Export Bitwarden chiffré non pris en charge : exportez au format .json non chiffré"
        )
        val items = o.getJSONArray("items")
        val out = ArrayList<Entry>()
        var skipped = 0
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val login = it.optJSONObject("login") ?: continue
            val totp = login.optString("totp").trim().takeIf { t -> t.isNotEmpty() && t != "null" } ?: continue
            val title = it.optString("name")
            val user = login.optString("username").takeIf { u -> u != "null" } ?: ""
            runCatching {
                when {
                    totp.startsWith("otpauth", true) -> uriParser(totp).map { e ->
                        e.copy(issuer = e.issuer.ifBlank { title }, name = e.name.ifBlank { user }, favorite = it.optBoolean("favorite"))
                    }
                    totp.startsWith("steam://", true) -> listOf(mk(OtpType.STEAM, title.ifBlank { "Steam" }, user, totp.substring(8)))
                    else -> listOf(mk(OtpType.TOTP, title, user, totp, favorite = it.optBoolean("favorite")))
                }
            }.onSuccess { out += it }.onFailure { skipped++ }
        }
        return ImportResult("Bitwarden", out, skipped)
    }

    // ---------------- FreeOTP+ ----------------

    private fun freeOtpPlus(o: JSONObject): ImportResult {
        val tokens = o.getJSONArray("tokens")
        val out = ArrayList<Entry>()
        var skipped = 0
        for (i in 0 until tokens.length()) {
            val t = tokens.getJSONObject(i)
            runCatching {
                val arr = t.getJSONArray("secret")
                val secret = ByteArray(arr.length()) { k -> arr.getInt(k).toByte() }
                val issuer = t.optString("issuerExt")
                val type = when {
                    t.optString("type").equals("hotp", true) -> OtpType.HOTP
                    issuer == "Steam" -> OtpType.STEAM
                    else -> OtpType.TOTP
                }
                mk(type, issuer, t.optString("label"), Base32.encode(secret), algoOf(t.optString("algo")) ?: Algo.SHA1,
                    t.optInt("digits", 6), t.optInt("period", 30), t.optLong("counter"))
            }.onSuccess { out += it }.onFailure { skipped++ }
        }
        return ImportResult("FreeOTP+", out, skipped)
    }

    // ---------------- Raivo ----------------

    private fun raivo(a: JSONArray): ImportResult {
        val out = ArrayList<Entry>()
        var skipped = 0
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            runCatching {
                val type = if (e.optString("kind").equals("HOTP", true)) OtpType.HOTP else OtpType.TOTP
                mk(type, e.optString("issuer"), e.optString("account"), e.getString("secret"),
                    algoOf(e.optString("algorithm")) ?: Algo.SHA1, e.optString("digits").toIntOrNull() ?: 6,
                    e.optString("timer").toIntOrNull() ?: 30, e.optString("counter").toLongOrNull() ?: 0,
                    favorite = e.optString("pinned") == "true")
            }.onSuccess { out += it }.onFailure { skipped++ }
        }
        return ImportResult("Raivo", out, skipped)
    }

    // ---------------- Generic otpauth:// scan (Ente, Proton, KeePassXC, text lists…) ----------------

    private val uriRegex = Regex("otpauth(?:-migration)?://[^\\s\"'<>,]+")

    private fun scan(text: String, label: String, uriParser: (String) -> List<Entry>): ImportResult {
        val clean = text.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=")
        val out = ArrayList<Entry>()
        var skipped = 0
        for (m in uriRegex.findAll(clean)) {
            runCatching { uriParser(m.value) }.onSuccess { out += it }.onFailure { skipped++ }
        }
        if (out.isEmpty()) throw IllegalArgumentException("Aucun compte OTP trouvé dans ce fichier")
        return ImportResult(label, out, skipped)
    }

    // ---------------- Kerbos backup ----------------

    const val KERBOS_FORMAT = "kerbos-backup"

    private fun kerbos(o: JSONObject, pw: String?): ImportResult {
        pw ?: throw PasswordRequired("Kerbos")
        val key = jcaPbkdf2("PBKDF2WithHmacSHA256", pw, b64d.decode(o.getString("salt")), o.getInt("iter"))
        val pt = gcmDecrypt(key, b64d.decode(o.getString("nonce")), b64d.decode(o.getString("data")))
        val entries = Entry.listFromJson(JSONObject(String(pt, Charsets.UTF_8)).optJSONArray("entries")).filter { !it.deleted }
        return ImportResult("Kerbos", entries)
    }
}

object Exporter {

    private fun live(entries: List<Entry>) = entries.filter { !it.deleted }

    fun kerbos(entries: List<Entry>, pw: String): String {
        val salt = random(16)
        val iter = 210_000
        val key = jcaPbkdf2("PBKDF2WithHmacSHA256", pw, salt, iter)
        val nonce = random(12)
        val payload = JSONObject().put("entries", Entry.listToJson(live(entries))).put("exportedAt", System.currentTimeMillis())
        val ct = gcmEncrypt(key, nonce, payload.toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("format", Importer.KERBOS_FORMAT).put("version", 1).put("kdf", "pbkdf2-sha256")
            .put("iter", iter).put("salt", b64e.encodeToString(salt)).put("nonce", b64e.encodeToString(nonce))
            .put("data", b64e.encodeToString(ct)).toString(2)
    }

    private fun aegisDb(entries: List<Entry>): JSONObject {
        val groupIds = LinkedHashMap<String, String>()
        live(entries).forEach { if (it.group.isNotBlank()) groupIds.getOrPut(it.group) { UUID.randomUUID().toString() } }
        val arr = JSONArray()
        for (e in live(entries)) {
            val info = JSONObject().put("secret", e.secret).put("algo", e.algo.name).put("digits", e.digits)
            if (e.type == OtpType.HOTP) info.put("counter", e.counter) else info.put("period", e.period)
            arr.put(
                JSONObject()
                    .put("type", e.type.name.lowercase()).put("uuid", e.uuid).put("name", e.name).put("issuer", e.issuer)
                    .put("note", e.note).put("favorite", e.favorite).put("icon", JSONObject.NULL)
                    .put("info", info)
                    .put("groups", JSONArray().apply { groupIds[e.group]?.let { put(it) } })
            )
        }
        val groups = JSONArray().apply { groupIds.forEach { (n, id) -> put(JSONObject().put("uuid", id).put("name", n)) } }
        return JSONObject().put("version", 3).put("entries", arr).put("groups", groups)
    }

    fun aegisPlain(entries: List<Entry>): String =
        JSONObject().put("version", 1)
            .put("header", JSONObject().put("slots", JSONObject.NULL).put("params", JSONObject.NULL))
            .put("db", aegisDb(entries)).toString(4)

    fun aegisEncrypted(entries: List<Entry>, pw: String, n: Int = 32768): String {
        val master = random(32)
        val salt = random(32)
        val derived = Kdf.scrypt(pw.toByteArray(Charsets.UTF_8), salt, n, 8, 1, 32)
        val keyNonce = random(12)
        val encKey = gcmEncrypt(derived, keyNonce, master)
        val slot = JSONObject().put("type", 1).put("uuid", UUID.randomUUID().toString())
            .put("key", hex(encKey.copyOfRange(0, encKey.size - 16)))
            .put("key_params", JSONObject().put("nonce", hex(keyNonce)).put("tag", hex(encKey.copyOfRange(encKey.size - 16, encKey.size))))
            .put("n", n).put("r", 8).put("p", 1).put("salt", hex(salt)).put("repaired", true).put("is_backup", false)
        val dbNonce = random(12)
        val enc = gcmEncrypt(master, dbNonce, aegisDb(entries).toString().toByteArray(Charsets.UTF_8))
        val header = JSONObject()
            .put("slots", JSONArray().put(slot))
            .put("params", JSONObject().put("nonce", hex(dbNonce)).put("tag", hex(enc.copyOfRange(enc.size - 16, enc.size))))
        return JSONObject().put("version", 1).put("header", header)
            .put("db", b64e.encodeToString(enc.copyOfRange(0, enc.size - 16))).toString(4)
    }

    fun uriList(entries: List<Entry>, toUri: (Entry) -> String): String =
        live(entries).joinToString("\n", postfix = "\n") { toUri(it) }

    /** Google Authenticator transfer QR payloads (10 accounts per QR). Returns URIs and the number of skipped entries. */
    fun googleMigration(entries: List<Entry>): Pair<List<String>, Int> {
        val ok = live(entries).filter {
            it.type != OtpType.STEAM && (it.type == OtpType.HOTP || it.period == 30) && (it.digits == 6 || it.digits == 8)
        }
        val skipped = live(entries).size - ok.size
        val batches = ok.chunked(10)
        val batchId = rng.nextInt() and 0x7fffffff
        val uris = batches.mapIndexed { idx, batch ->
            val p = Pb()
            for (e in batch) {
                val q = Pb()
                q.bytes(1, Base32.decode(e.secret))
                q.bytes(2, (if (e.issuer.isNotBlank()) "${e.issuer}:${e.name}" else e.name).toByteArray())
                q.bytes(3, e.issuer.toByteArray())
                q.varint(4, when (e.algo) { Algo.SHA1 -> 1; Algo.SHA256 -> 2; Algo.SHA512 -> 3 })
                q.varint(5, if (e.digits == 8) 2 else 1)
                q.varint(6, if (e.type == OtpType.HOTP) 1 else 2)
                if (e.type == OtpType.HOTP) q.varint(7, e.counter)
                p.bytes(1, q.out.toByteArray())
            }
            p.varint(2, 1); p.varint(3, batches.size.toLong()); p.varint(4, idx.toLong()); p.varint(5, batchId.toLong())
            "otpauth-migration://offline?data=" + URLEncoder.encode(b64e.encodeToString(p.out.toByteArray()), "UTF-8")
        }
        return uris to skipped
    }

    private class Pb {
        val out = java.io.ByteArrayOutputStream()
        private fun raw(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7
            }
        }
        fun varint(field: Int, v: Long) { raw((field shl 3).toLong()); raw(v) }
        fun varint(field: Int, v: Int) = varint(field, v.toLong())
        fun bytes(field: Int, b: ByteArray) { raw(((field shl 3) or 2).toLong()); raw(b.size.toLong()); out.write(b) }
    }
}

/** Removes entries already present (same secret + issuer + name). */
fun dedupe(existing: List<Entry>, incoming: List<Entry>): List<Entry> {
    fun k(e: Entry) = "${Base32.normalize(e.secret)}|${e.issuer.lowercase()}|${e.name.lowercase()}"
    val seen = existing.filter { !it.deleted }.map(::k).toHashSet()
    return incoming.filter { seen.add(k(it)) }
}
