package com.kerbos.app.sync

import android.net.Uri
import com.kerbos.app.vault.Crypto
import com.kerbos.app.vault.Entry
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
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

/** Minimal WebDAV client (GET / PUT with ETag preconditions / MKCOL) for Nextcloud. */
class WebDav(private val cfg: SyncConfig) {
    private val auth = Credentials.basic(cfg.user.trim(), cfg.appPassword.trim())

    val folderUrl: String = run {
        var base = cfg.server.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        val dav = if (base.contains("/remote.php/")) base else "$base/remote.php/dav/files/${Uri.encode(cfg.user.trim())}"
        val segs = cfg.folder.split('/').filter { it.isNotBlank() }.joinToString("/") { Uri.encode(it.trim()) }
        if (segs.isEmpty()) dav else "$dav/$segs"
    }
    private val fileUrl = "$folderUrl/$FILE_NAME"

    class Remote(val body: String, val etag: String?)

    private fun req(url: String) = Request.Builder().url(url).header("Authorization", auth)

    private fun httpError(code: Int): SyncException = SyncException(
        when (code) {
            401 -> "Identifiants Nextcloud refusés (401). Vérifiez l'utilisateur et le mot de passe d'application."
            403 -> "Accès refusé (403)."
            404 -> "Dossier introuvable (404)."
            in 500..599 -> "Erreur du serveur ($code)."
            else -> "Erreur HTTP $code"
        }
    )

    fun get(): Remote? {
        client.newCall(req(fileUrl).get().build()).execute().use { r ->
            return when (r.code) {
                200 -> Remote(r.body?.string() ?: "", r.header("ETag"))
                404 -> null
                else -> throw httpError(r.code)
            }
        }
    }

    fun ensureFolder() {
        val base = folderUrl.substringBefore("/remote.php/dav/files/") + "/remote.php/dav/files/"
        if (!folderUrl.startsWith(base)) return
        val parts = folderUrl.removePrefix(base).split('/')
        var url = base + parts.first()
        for (p in parts.drop(1)) {
            url += "/$p"
            client.newCall(req(url).method("MKCOL", null).build()).execute().use { r ->
                if (r.code != 201 && r.code != 405 && r.code != 409) throw httpError(r.code)
            }
        }
    }

    /** Returns false when the remote file changed since [etag] (HTTP 412). */
    fun put(body: String, etag: String?): Boolean {
        val b = req(fileUrl).put(body.toRequestBody("application/json".toMediaType()))
        if (etag != null) b.header("If-Match", etag) else b.header("If-None-Match", "*")
        client.newCall(b.build()).execute().use { r ->
            return when (r.code) {
                200, 201, 204 -> true
                412 -> false
                else -> throw httpError(r.code)
            }
        }
    }

    companion object {
        const val FILE_NAME = "kerbos-vault.json"
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).build()
    }
}

sealed interface SyncStatus {
    data object Disabled : SyncStatus
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Ok(val at: Long, val count: Int) : SyncStatus
    data class Error(val message: String, val at: Long) : SyncStatus
}

/**
 * Automatic two-way sync. The remote file holds the full entry set (with tombstones), encrypted
 * with a key derived from the sync passphrase shared by all devices. Each sync downloads,
 * merges per entry (last writer wins), and uploads with an If-Match precondition so concurrent
 * writers never overwrite each other: on conflict the cycle simply restarts.
 */
class SyncManager(private val vault: VaultRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loop: Job? = null
    private var keyCache: Triple<String, String, ByteArray>? = null // passphrase, salt, key

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    init {
        scope.launch {
            vault.state.collect { s ->
                if (s == VaultState.UNLOCKED) start() else stop()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun start() {
        loop?.cancel()
        loop = scope.launch {
            launch { vault.changes.debounce(1200).collect { syncNow() } }
            while (isActive) {
                syncNow()
                delay(PERIOD_MS)
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
        requestSync()
    }

    private fun key(passphrase: String, salt: ByteArray, iter: Int): ByteArray {
        val s = Crypto.b64(salt)
        keyCache?.let { (p, cs, k) -> if (p == passphrase && cs == s) return k }
        val k = Crypto.pbkdf2(passphrase.toCharArray(), salt, iter)
        keyCache = Triple(passphrase, s, k)
        return k
    }

    suspend fun syncNow(): SyncStatus = mutex.withLock {
        val cfg = vault.syncConfig.value
        if (vault.state.value != VaultState.UNLOCKED) return@withLock _status.value
        if (!cfg.enabled || !cfg.complete) {
            _status.value = SyncStatus.Disabled
            return@withLock SyncStatus.Disabled
        }
        _status.value = SyncStatus.Running
        val result = try {
            runCycle(cfg)
        } catch (e: SyncException) {
            SyncStatus.Error(e.message ?: "Erreur", System.currentTimeMillis())
        } catch (e: java.net.UnknownHostException) {
            SyncStatus.Error("Serveur injoignable (hors ligne ?)", System.currentTimeMillis())
        } catch (e: javax.net.ssl.SSLException) {
            SyncStatus.Error("Erreur TLS : ${e.message}", System.currentTimeMillis())
        } catch (e: java.io.IOException) {
            SyncStatus.Error("Réseau : ${e.message ?: e.javaClass.simpleName}", System.currentTimeMillis())
        } catch (e: Exception) {
            SyncStatus.Error(e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
        }
        _status.value = result
        result
    }

    private fun runCycle(cfg: SyncConfig): SyncStatus {
        val dav = WebDav(cfg)
        repeat(6) {
            val remote = dav.get()
            val salt: ByteArray
            val iter: Int
            val remoteEntries: List<Entry>
            if (remote == null) {
                salt = Crypto.random(16); iter = Crypto.PBKDF2_ITER; remoteEntries = emptyList()
                dav.ensureFolder()
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

            if (remote == null || !com.kerbos.app.vault.Merge.same(merged, remoteEntries)) {
                val k = key(cfg.passphrase, salt, iter)
                val payload = JSONObject().put("entries", Entry.listToJson(merged))
                    .put("updatedBy", vault.deviceId).put("updatedAt", System.currentTimeMillis())
                val s = Crypto.encrypt(k, payload.toString().toByteArray(), AAD)
                val body = JSONObject().put("format", FORMAT).put("version", 1).put("kdf", "pbkdf2-sha256")
                    .put("iter", iter).put("salt", Crypto.b64(salt))
                    .put("nonce", Crypto.b64(s.nonce)).put("data", Crypto.b64(s.ct)).toString()
                if (!dav.put(body, remote?.etag)) return@repeat // someone else wrote meanwhile → retry
            }
            return SyncStatus.Ok(System.currentTimeMillis(), merged.count { !it.deleted })
        }
        throw SyncException("Conflit d'écriture persistant, nouvel essai plus tard.")
    }

    suspend fun test(cfg: SyncConfig): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val dav = WebDav(cfg)
            val r = dav.get()
            if (r == null) "Connexion OK — aucun coffre distant pour l'instant, il sera créé dans ${dav.folderUrl}"
            else "Connexion OK — coffre distant trouvé."
        } catch (e: Exception) {
            "Échec : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val FORMAT = "kerbos-sync"
        private const val AAD = "kerbos-sync-v1"
        private const val PERIOD_MS = 60_000L
    }
}
