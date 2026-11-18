package com.kerbos.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kerbos.app.icons.IconLibrary
import com.kerbos.app.sync.SyncManager
import com.kerbos.app.vault.VaultRepository
import com.kerbos.app.vault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow

/** Non-sensitive preferences (readable before unlock), observable from Compose. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("kerbos_prefs", Context.MODE_PRIVATE)

    inner class BoolPref(private val key: String, def: Boolean) {
        var value by mutableStateOf(sp.getBoolean(key, def)); private set
        fun set(v: Boolean) { value = v; sp.edit().putBoolean(key, v).apply() }
    }

    inner class IntPref(private val key: String, def: Int) {
        var value by mutableIntStateOf(sp.getInt(key, def)); private set
        fun set(v: Int) { value = v; sp.edit().putInt(key, v).apply() }
    }

    inner class LongPref(private val key: String, def: Long) {
        var value by mutableLongStateOf(sp.getLong(key, def)); private set
        fun set(v: Long) { value = v; sp.edit().putLong(key, v).apply() }
    }

    // Apparence
    val theme = IntPref("theme", 0)                 // 0 système, 1 clair, 2 sombre
    val amoled = BoolPref("amoled", false)          // fond noir pur en mode sombre
    val dynamicColor = BoolPref("dynamic", false)   // couleurs Material You (Android 12+)
    val compact = BoolPref("compact", false)        // liste compacte
    val showIcons = BoolPref("icons", true)
    val codeSize = IntPref("code_size", 1)          // 0 petit, 1 normal, 2 grand

    // Codes
    val tapToReveal = BoolPref("reveal", false)
    val groupDigits = BoolPref("group_digits", true)
    val showNextCode = BoolPref("next_code", false)
    val sortMode = IntPref("sort", 0)               // 0 émetteur, 1 compte, 2 plus récents
    val clipboardClear = IntPref("clip_clear", 45)  // secondes, 0 = jamais
    val minimizeOnCopy = BoolPref("minimize_copy", false)
    val haptics = BoolPref("haptics", true)

    // Sécurité
    val autoLockSeconds = IntPref("autolock", 30)   // -1 = jamais
    val bioAutoPrompt = BoolPref("bio_auto", true)
    val blockScreenshots = BoolPref("secure", true)
    val passwordReminderDays = IntPref("pw_reminder", 14) // 0 = jamais
    val lastPasswordUnlock = LongPref("pw_last", 0L)

    // Synchronisation
    val syncInterval = IntPref("sync_interval", 60) // secondes
    val syncWifiOnly = BoolPref("sync_wifi", false)
}

class KerbosApp : Application() {
    lateinit var prefs: Prefs
    lateinit var vault: VaultRepository
    lateinit var sync: SyncManager

    /** otpauth:// link received from another app, handled once unlocked. */
    val pendingUri = MutableStateFlow<String?>(null)

    private var stoppedAt = 0L
    /** Set before opening a system picker so leaving the app briefly does not lock the vault. */
    private var externalUntil = 0L

    fun expectExternalActivity() {
        externalUntil = System.currentTimeMillis() + 5 * 60_000
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        vault = VaultRepository(this)
        sync = SyncManager(this, vault, prefs)
        IconLibrary.init(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                stoppedAt = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                val now = System.currentTimeMillis()
                val external = now < externalUntil
                externalUntil = 0
                val t = prefs.autoLockSeconds.value
                if (!external && stoppedAt > 0 && t >= 0 && vault.state.value == VaultState.UNLOCKED &&
                    now - stoppedAt >= t * 1000L
                ) vault.lock()
                else if (vault.state.value == VaultState.UNLOCKED) sync.requestSync()
            }
        })
    }

    companion object {
        lateinit var instance: KerbosApp
            private set
    }
}

val app: KerbosApp get() = KerbosApp.instance
