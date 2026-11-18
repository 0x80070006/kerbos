package com.kerbos.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.kerbos.app.Prefs
import com.kerbos.app.vault.Crypto
import com.kerbos.app.vault.Entry
import com.kerbos.app.vault.Merge
import com.kerbos.app.vault.VaultRepository
import com.kerbos.app.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

data class SyncConfig(
    val enabled: Boolean = false,
    val server: String = "",
    val user: String = "",
    val appPassword: String = "",
    val folder: String = "Kerbos",
    val passphrase: String = "",
) {
    val complete get() = server.isNotBlank() && user.isNotBlank() && appPassword.isNotBlank() && passphrase.isNotBlank()

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("server", server).put("user", user)
        .put("appPassword", appPassword).put("folder", folder).put("passphrase", passphrase)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) SyncConfig() else SyncConfig(
            enabled = o.optBoolean("enabled"), server = o.optString("server"), user = o.optString("user"),
            appPassword = o.optString("appPassword"), folder = o.optString("folder", "Kerbos"),
            passphrase = o.optString("passphrase"),
        )
    }
}

class SyncException(msg: String) : Exception(msg)

/**
 * Minimal WebDAV client for Nextcloud.
 *
 * Nextcloud's WebDAV root is /remote.php/dav/files/<USER ID>/ — the internal user id, which often
 * differs from the login (e-mail login, LDAP, SSO…). The id is therefore asked to the server through
 * the OCS API instead of trusting what the user typed. The server address is also cleaned up when it
 * was copied from the browser (…/index.php/apps/files, …/remote.php/…).
 */
class WebDav(private val cfg: SyncConfig) {
    private val auth = Credentials.basic(cfg.user.trim(), cfg.appPassword.trim(), Charsets.UTF_8)

    /** Server root, e.g. https://cloud.example.org or https://example.org/nextcloud */
    val baseUrl: String
    /** User id taken from a pasted WebDAV URL, if any. */
    private val idFromUrl: String?

    init {
        var s = cfg.server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        var id: String? = null
        val davMarker = "/remote.php/dav/files/"
        if (s.contains(davMarker)) {
            id = s.substringAfter(davMarker).substringBefore('/').takeIf { it.isNotBlank() }?.let { Uri.decode(it) }
        }
        for (marker in listOf("/remote.php", "/index.php", "/apps/", "/ocs/", "/login", "/s/", "/f/")) {
            val i = s.indexOf(marker)
            if (i > 0 && i > s.indexOf("://") + 3) s = s.substring(0, i)
        }
        baseUrl = s.trimEnd('/')
        idFromUrl = id
    }

    private val folderSegments: List<String> =
        cfg.folder.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private fun req(url: String) = Request.Builder().url(url).header("Authorization", auth)

    private fun httpError(code: Int, url: String): SyncException = SyncException(
        when (code) {
            401 -> "Identifiants refusés (401). Vérifiez le nom d'utilisateur et le mot de passe d'application."
            403 -> "Accès refusé (403) sur $url"
            404 -> "Introuvable (404) : $url"
            405 -> "Méthode refusée (405) sur $url"
            409 -> "Dossier parent manquant (409) : $url"
            in 500..599 -> "Erreur du serveur ($code)."
            else -> "Erreur HTTP $code sur $url"
        }
    )

    /** Asks Nextcloud for the real user id. Throws on bad credentials. */
    fun userId(): String {
        idFromUrl?.let { return it }
        val key = "$baseUrl|${cfg.user.trim()}"
        idCache[key]?.let { return it }
        val url = "$baseUrl/ocs/v2.php/cloud/user?format=json"
        val id = client.newCall(req(url).header("OCS-APIRequest", "true").header("Accept", "application/json").build())
            .execute().use { r ->
                when (r.code) {
                    200 -> runCatching {
                        JSONObject(r.body?.string() ?: "").getJSONObject("ocs").getJSONObject("data").getString("id")
                    }.getOrNull()
                    401 -> throw httpError(401, url)
                    else -> null
                }
            } ?: cfg.user.trim()
        idCache[key] = id
        return id
    }

    private fun userRoot() = "$baseUrl/remote.php/dav/files/${Uri.encode(userId())}"
    fun folderUrl(): String = userRoot() + folderSegments.joinToString("") { "/" + Uri.encode(it) }
    private fun fileUrl() = folderUrl() + "/" + FILE_NAME

    class Remote(val body: String, val etag: String?)

    /**
     * Downloads the vault. Compression is disabled on purpose: proxies (nginx/Apache) that gzip
     * the response turn the ETag into a weak one (W/"…") which never satisfies If-Match.
     * Nextcloud's own OC-ETag header is preferred when present.
     */
    fun get(): Remote? {
        val url = fileUrl()
        client.newCall(req(url).get().header("Accept-Encoding", "identity").build()).execute().use { r ->
            return when (r.code) {
                200 -> Remote(r.body?.string() ?: "", cleanEtag(r.header("OC-ETag") ?: r.header("ETag")))
                404 -> null
                else -> throw httpError(r.code, url)
            }
        }
    }

    /** ETag as reported by WebDAV (getetag), unaffected by response compression. */
    fun davEtag(): String? {
        val url = fileUrl()
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"""
        client.newCall(
            req(url).header("Depth", "0").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
        ).execute().use { r ->
            if (r.code != 207) return null
            val xml = r.body?.string() ?: return null
            return Regex("<[^>]*getetag[^>]*>([^<]+)<").find(xml)?.groupValues?.get(1)
                ?.replace("&quot;", "\"")?.let(::cleanEtag)
        }
    }

    private fun propfindExists(url: String): Boolean {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        client.newCall(
            req(url).header("Depth", "0").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
        ).execute().use { r ->
            return when (r.code) {
                207, 200 -> true
                404 -> false
                else -> throw httpError(r.code, url)
            }
        }
    }

    fun folderExists() = propfindExists(folderUrl())

    /** Creates every missing folder level (MKCOL), like `mkdir -p`. */
    fun ensureFolder() {
        val root = userRoot()
        if (!propfindExists(root)) throw SyncException(
            "Espace utilisateur introuvable : $root. Vérifiez l'adresse du serveur et le nom d'utilisateur."
        )
        var url = root
        for (seg in folderSegments) {
            url += "/" + Uri.encode(seg)
            client.newCall(req(url).method("MKCOL", null).build()).execute().use { r ->
                if (r.code != 201 && r.code != 405) throw httpError(r.code, url)
            }
        }
    }

    /**
     * Returns false when the precondition failed (HTTP 412).
     * [etag] null → only create (If-None-Match: *); [unconditional] → plain overwrite.
     */
    fun put(body: String, etag: String?, unconditional: Boolean = false): Boolean {
        val url = fileUrl()
        val b = req(url).put(body.toRequestBody("application/json".toMediaType()))
        if (!unconditional) {
            if (etag != null) b.header("If-Match", etag) else b.header("If-None-Match", "*")
        }
        client.newCall(b.build()).execute().use { r ->
            return when (r.code) {
                200, 201, 204 -> true
                412 -> false
                else -> throw httpError(r.code, url)
            }
        }
    }

    /** Human-readable connection diagnostic for the settings screen. */
    fun diagnose(): String {
        val sb = StringBuilder()
        val id = userId()
        sb.append("✓ Connexion à $baseUrl réussie\n")
        sb.append("✓ Compte : $id")
        if (!id.equals(cfg.user.trim(), ignoreCase = false)) sb.append(" (identifiant interne, différent du login « ${cfg.user.trim()} »)")
        sb.append('\n')
        if (!propfindExists(userRoot())) {
            sb.append("✗ Espace WebDAV introuvable : ${userRoot()}")
            return sb.toString()
        }
        val folderName = folderSegments.joinToString("/").ifEmpty { "(racine)" }
        if (folderExists()) {
            sb.append("✓ Dossier « $folderName » trouvé\n")
            sb.append(if (get() != null) "✓ Coffre synchronisé présent ($FILE_NAME)" else "• Aucun coffre pour l'instant : il sera créé à la première synchro")
        } else {
            sb.append("• Dossier « $folderName » absent : il sera créé automatiquement à la première synchro\n")
            sb.append("  (${folderUrl()})")
        }
        return sb.toString()
    }

    companion object {
        const val FILE_NAME = "kerbos-vault.json"

        /** Strong, quoted form of an ETag: strips the weak prefix and any compression suffix (-gzip, ;gzip). */
        fun cleanEtag(raw: String?): String? {
            var e = raw?.trim()?.removePrefix("W/")?.removePrefix("w/")?.trim() ?: return null
            if (e.isEmpty()) return null
            e = e.trim('"').replace(Regex("[-;](gzip|br|deflate)$"), "")
            return "\"$e\""
        }
        private val idCache = ConcurrentHashMap<String, String>()
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).build()
    }
}

sealed interface SyncStatus {
    data object Disabled : SyncStatus
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data object WaitingWifi : SyncStatus
    data class Ok(val at: Long, val count: Int) : SyncStatus
    data class Error(val message: String, val at: Long) : SyncStatus
}

/**
 * Automatic two-way sync. The remote file holds the full entry set (with tombstones), encrypted
 * with a key derived from the sync passphrase shared by all devices. Each sync downloads,
 * merges per entry (last writer wins), and uploads with an If-Match precondition so concurrent
 * writers never overwrite each other: on conflict the cycle simply restarts.
 */
class SyncManager(private val ctx: Context, private val vault: VaultRepository, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loop: Job? = null
    private var keyCache: Triple<String, String, ByteArray>? = null // passphrase, salt, key
    @Volatile private var etagUnreliable = false

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    init {
        scope.launch {
            vault.state.collect { s -> if (s == VaultState.UNLOCKED) start() else stop() }
        }
    }

    @OptIn(FlowPreview::class)
    private fun start() {
        loop?.cancel()
        loop = scope.launch {
            launch { vault.changes.debounce(1200).collect { syncNow() } }
            while (isActive) {
                syncNow()
                delay(prefs.syncInterval.value.coerceAtLeast(15) * 1000L)
            }
        }
    }

    private fun stop() {
        loop?.cancel(); loop = null
        keyCache = null
    }

    fun requestSync() {
        scope.launch { syncNow() }
    }

    fun onConfigChanged() {
        keyCache = null
        etagUnreliable = false
        requestSync()
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun key(passphrase: String, salt: ByteArray, iter: Int): ByteArray {
        val s = Crypto.b64(salt)
        keyCache?.let { (p, cs, k) -> if (p == passphrase && cs == s) return k }
        val k = Crypto.pbkdf2(passphrase.toCharArray(), salt, iter)
        keyCache = Triple(passphrase, s, k)
        return k
    }

    suspend fun syncNow(force: Boolean = false): SyncStatus = mutex.withLock {
        val cfg = vault.syncConfig.value
        if (vault.state.value != VaultState.UNLOCKED) return@withLock _status.value
        if (!cfg.enabled || !cfg.complete) {
            _status.value = SyncStatus.Disabled
            return@withLock SyncStatus.Disabled
        }
        if (!force && prefs.syncWifiOnly.value && !onUnmeteredNetwork()) {
            _status.value = SyncStatus.WaitingWifi
            return@withLock SyncStatus.WaitingWifi
        }
        _status.value = SyncStatus.Running
        val now = System.currentTimeMillis()
        val result = try {
            runCycle(cfg)
        } catch (e: SyncException) {
            SyncStatus.Error(e.message ?: "Erreur", now)
        } catch (e: java.net.UnknownHostException) {
            SyncStatus.Error("Serveur injoignable (hors ligne ou adresse erronée)", now)
        } catch (e: javax.net.ssl.SSLException) {
            SyncStatus.Error("Erreur TLS : ${e.message}", now)
        } catch (e: java.io.IOException) {
            SyncStatus.Error("Réseau : ${e.message ?: e.javaClass.simpleName}", now)
        } catch (e: IllegalArgumentException) {
            SyncStatus.Error("Adresse du serveur invalide", now)
        } catch (e: Exception) {
            SyncStatus.Error(e.message ?: e.javaClass.simpleName, now)
        }
        _status.value = result
        result
    }

    /**
     * One sync cycle: download → merge (local ∪ remote, newest wins, duplicates collapsed) → upload.
     *
     * Upload is adaptive:
     *  1. If-Match with the ETag from the download (normal, race-free path);
     *  2. on 412, retry with the ETag read through PROPFIND (servers/proxies that rewrite ETags);
     *  3. if the server still refuses preconditions, re-download, re-merge and overwrite without
     *     precondition, then the next pass re-reads the file to verify nothing was lost (any entry
     *     written by another device in between is merged back and uploaded again).
     */
    private fun runCycle(cfg: SyncConfig): SyncStatus {
        val dav = WebDav(cfg)
        // Servers whose ETags never satisfy If-Match go straight to write-then-verify.
        var refused = if (etagUnreliable) 3 else 0
        repeat(10) { attempt ->
            val remote = dav.get()
            val salt: ByteArray
            val iter: Int
            val remoteEntries: List<Entry>
            if (remote == null) {
                salt = Crypto.random(16); iter = Crypto.PBKDF2_ITER; remoteEntries = emptyList()
                if (!dav.folderExists()) dav.ensureFolder()
            } else {
                val o = try { JSONObject(remote.body) } catch (e: Exception) { throw SyncException("Fichier distant illisible") }
                if (o.optString("format") != FORMAT) throw SyncException("Fichier distant inconnu")
                salt = Crypto.unb64(o.getString("salt")); iter = o.getInt("iter")
                val k = key(cfg.passphrase, salt, iter)
                val pt = try {
                    Crypto.decrypt(k, Crypto.unb64(o.getString("nonce")), Crypto.unb64(o.getString("data")), AAD)
                } catch (e: AEADBadTagException) {
                    keyCache = null
                    throw SyncException("Phrase de synchronisation incorrecte (différente de celle des autres appareils).")
                }
                remoteEntries = Entry.listFromJson(JSONObject(String(pt)).optJSONArray("entries"))
            }

            val merged = vault.mergeRemote(remoteEntries)

            if (remote == null || !Merge.same(merged, remoteEntries)) {
                val k = key(cfg.passphrase, salt, iter)
                val payload = JSONObject().put("entries", Entry.listToJson(merged))
                    .put("updatedBy", vault.deviceId).put("updatedAt", System.currentTimeMillis())
                val s = Crypto.encrypt(k, payload.toString().toByteArray(), AAD)
                val body = JSONObject().put("format", FORMAT).put("version", 1).put("kdf", "pbkdf2-sha256")
                    .put("iter", iter).put("salt", Crypto.b64(salt))
                    .put("nonce", Crypto.b64(s.nonce)).put("data", Crypto.b64(s.ct)).toString()
                val ok = when {
                    remote == null -> dav.put(body, null)
                    refused >= 3 -> dav.put(body, null, unconditional = true)
                    refused >= 1 -> dav.put(body, dav.davEtag() ?: remote.etag)
                    else -> dav.put(body, remote.etag)
                }
                if (!ok) {
                    refused++
                    if (refused >= 3) etagUnreliable = true
                    Thread.sleep(250L * (attempt + 1))
                    return@repeat // someone else wrote meanwhile, or the server rejects the ETag → retry
                }
                if (refused >= 3) {
                    refused = 0 // forced write done: next pass re-reads to verify
                    return@repeat
                }
            }
            return SyncStatus.Ok(System.currentTimeMillis(), merged.count { !it.deleted })
        }
        throw SyncException("Le serveur refuse l'écriture du coffre après plusieurs essais, nouvel essai plus tard.")
    }

    suspend fun test(cfg: SyncConfig): String = withContext(Dispatchers.IO) {
        try {
            WebDav(cfg).diagnose()
        } catch (e: java.net.UnknownHostException) {
            "✗ Serveur introuvable : vérifiez l'adresse (${e.message})"
        } catch (e: IllegalArgumentException) {
            "✗ Adresse du serveur invalide"
        } catch (e: Exception) {
            "✗ ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val FORMAT = "kerbos-sync"
        private const val AAD = "kerbos-sync-v1"
    }
}
