package com.kerbos.app.vault

import android.content.Context
import com.kerbos.app.sync.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

enum class VaultState { NONE, LOCKED, UNLOCKED }

/**
 * Encrypted local vault (Aegis-like design):
 * a random 256-bit master key encrypts the payload with AES-GCM; the master key is wrapped by
 * "slots" — one derived from the password (PBKDF2-SHA256) and optionally one from a
 * biometric-bound Android Keystore key.
 */
class VaultRepository(ctx: Context) {
    private val file = File(ctx.filesDir, "vault.json")
    private val lock = Any()

    private val _state = MutableStateFlow(if (file.exists()) VaultState.LOCKED else VaultState.NONE)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** All entries, including tombstones. */
    private val _all = MutableStateFlow<List<Entry>>(emptyList())
    val all: StateFlow<List<Entry>> = _all.asStateFlow()

    private val _syncConfig = MutableStateFlow(SyncConfig())
    val syncConfig: StateFlow<SyncConfig> = _syncConfig.asStateFlow()

    /** Emits after each local (user) modification — used to trigger a sync. */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes = _changes.asSharedFlow()

    private var masterKey: ByteArray? = null
    private var slots = JSONArray()
    var deviceId: String = ""
        private set

    private fun readRoot(): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun findSlot(type: String): JSONObject? {
        val a = readRoot()?.optJSONArray("slots") ?: return null
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("type") == type }
    }

    fun hasBiometricSlot() = findSlot("biometric") != null

    // ---------- creation / unlock ----------

    private fun passwordSlot(mk: ByteArray, pw: CharArray): JSONObject {
        val salt = Crypto.random(16)
        val k = Crypto.pbkdf2(pw, salt, Crypto.PBKDF2_ITER)
        val s = Crypto.encrypt(k, mk)
        k.fill(0)
        return JSONObject().put("type", "password").put("salt", Crypto.b64(salt))
            .put("iter", Crypto.PBKDF2_ITER).put("nonce", Crypto.b64(s.nonce)).put("key", Crypto.b64(s.ct))
    }

    /** Creates the vault. Call [finishSetup] afterwards to enter the unlocked state. */
    suspend fun create(password: CharArray) = withContext(Dispatchers.Default) {
        val mk = Crypto.random(32)
        val slot = passwordSlot(mk, password)
        synchronized(lock) {
            masterKey = mk
            deviceId = UUID.randomUUID().toString()
            slots = JSONArray().put(slot)
            _all.value = emptyList()
            _syncConfig.value = SyncConfig()
            persist()
        }
    }

    fun finishSetup() {
        _state.value = VaultState.UNLOCKED
    }

    suspend fun unlockWithPassword(pw: CharArray): Boolean = withContext(Dispatchers.Default) {
        val root = readRoot() ?: return@withContext false
        val slot = findSlot("password") ?: return@withContext false
        try {
            val k = Crypto.pbkdf2(pw, Crypto.unb64(slot.getString("salt")), slot.getInt("iter"))
            val mk = Crypto.decrypt(k, Crypto.unb64(slot.getString("nonce")), Crypto.unb64(slot.getString("key")))
            k.fill(0)
            load(root, mk)
            true
        } catch (e: AEADBadTagException) {
            false
        }
    }

    fun biometricCipher(): Cipher? {
        val slot = findSlot("biometric") ?: return null
        return BiometricKey.decryptCipher(Crypto.unb64(slot.getString("nonce")))
    }

    suspend fun unlockWithCipher(c: Cipher): Boolean = withContext(Dispatchers.Default) {
        val root = readRoot() ?: return@withContext false
        val slot = findSlot("biometric") ?: return@withContext false
        try {
            val mk = c.doFinal(Crypto.unb64(slot.getString("key")))
            load(root, mk)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun load(root: JSONObject, mk: ByteArray) {
        val pt = Crypto.decrypt(mk, Crypto.unb64(root.getString("nonce")), Crypto.unb64(root.getString("data")), AAD)
        val payload = JSONObject(String(pt))
        synchronized(lock) {
            masterKey = mk
            slots = root.getJSONArray("slots")
            deviceId = payload.optString("deviceId").ifEmpty { UUID.randomUUID().toString() }
            _all.value = Entry.listFromJson(payload.optJSONArray("entries"))
            _syncConfig.value = SyncConfig.fromJson(payload.optJSONObject("sync"))
        }
        _state.value = VaultState.UNLOCKED
    }

    fun lock() {
        synchronized(lock) {
            masterKey?.fill(0)
            masterKey = null
            _all.value = emptyList()
            _syncConfig.value = SyncConfig()
        }
        if (_state.value == VaultState.UNLOCKED) _state.value = VaultState.LOCKED
    }

    // ---------- slots management ----------

    fun enableBiometric(c: Cipher) = synchronized(lock) {
        val mk = masterKey ?: return
        val ct = c.doFinal(mk)
        val slot = JSONObject().put("type", "biometric").put("nonce", Crypto.b64(c.iv)).put("key", Crypto.b64(ct))
        slots = JSONArray().apply {
            for (i in 0 until slots.length()) slots.getJSONObject(i).let { if (it.optString("type") != "biometric") put(it) }
            put(slot)
        }
        persist()
    }

    fun disableBiometric() = synchronized(lock) {
        slots = JSONArray().apply {
            for (i in 0 until slots.length()) slots.getJSONObject(i).let { if (it.optString("type") != "biometric") put(it) }
        }
        BiometricKey.delete()
        persist()
    }

    suspend fun changePassword(pw: CharArray) = withContext(Dispatchers.Default) {
        val mk = synchronized(lock) { masterKey } ?: return@withContext
        val slot = passwordSlot(mk, pw)
        synchronized(lock) {
            slots = JSONArray().apply {
                put(slot)
                for (i in 0 until slots.length()) slots.getJSONObject(i).let { if (it.optString("type") != "password") put(it) }
            }
            persist()
        }
    }

    // ---------- entries ----------

    private fun stamp(prev: Long) = maxOf(System.currentTimeMillis(), prev + 1)

    fun upsert(e: Entry) {
        synchronized(lock) {
            if (masterKey == null) return
            val list = _all.value.toMutableList()
            val i = list.indexOfFirst { it.uuid == e.uuid }
            val updated = e.copy(updatedAt = stamp(if (i >= 0) list[i].updatedAt else 0))
            if (i >= 0) list[i] = updated else list += updated
            _all.value = list
            persist()
        }
        _changes.tryEmit(Unit)
    }

    fun addAll(entries: List<Entry>) {
        synchronized(lock) {
            if (masterKey == null) return
            val now = System.currentTimeMillis()
            _all.value = _all.value + entries.map { it.copy(updatedAt = now) }
            persist()
        }
        _changes.tryEmit(Unit)
    }

    fun delete(uuid: String) {
        synchronized(lock) {
            if (masterKey == null) return
            _all.value = _all.value.map { if (it.uuid == uuid) it.tombstone(stamp(it.updatedAt)) else it }
            persist()
        }
        _changes.tryEmit(Unit)
    }

    /** Merges entries coming from the sync server into the local vault. Returns the merged set. */
    fun mergeRemote(remote: List<Entry>): List<Entry> = synchronized(lock) {
        if (masterKey == null) throw IllegalStateException("Coffre verrouillé")
        val local = _all.value
        val merged = Merge.merge(local, remote)
        if (!Merge.same(merged, local)) {
            _all.value = merged
            persist()
        }
        merged
    }

    fun updateSyncConfig(cfg: SyncConfig) = synchronized(lock) {
        if (masterKey == null) return
        _syncConfig.value = cfg
        persist()
    }

    // ---------- persistence ----------

    private fun persist() {
        val mk = masterKey ?: return
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("entries", Entry.listToJson(_all.value))
            .put("sync", _syncConfig.value.toJson())
        val s = Crypto.encrypt(mk, payload.toString().toByteArray(), AAD)
        val root = JSONObject().put("version", 1).put("slots", slots)
            .put("nonce", Crypto.b64(s.nonce)).put("data", Crypto.b64(s.ct))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    companion object {
        private const val AAD = "kerbos-vault-v1"
    }
}
