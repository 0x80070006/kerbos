package com.kerbos.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** Non-sensitive preferences (readable before unlock). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kerbos_prefs", Context.MODE_PRIVATE)

    var theme by mutableIntStateOf(sp.getInt("theme", 0)) // 0 système, 1 clair, 2 sombre
        private set
    var autoLockSeconds by mutableIntStateOf(sp.getInt("autolock", 30)) // -1 = jamais
        private set
    var bioAutoPrompt by mutableStateOf(sp.getBoolean("bio_auto", true))
        private set
    var blockScreenshots by mutableStateOf(sp.getBoolean("secure", true))
        private set
    var tapToReveal by mutableStateOf(sp.getBoolean("reveal", false))
        private set

    fun updateTheme(v: Int) { theme = v; sp.edit().putInt("theme", v).apply() }
    fun setAutoLock(v: Int) { autoLockSeconds = v; sp.edit().putInt("autolock", v).apply() }
    fun setBioAuto(v: Boolean) { bioAutoPrompt = v; sp.edit().putBoolean("bio_auto", v).apply() }
    fun setSecure(v: Boolean) { blockScreenshots = v; sp.edit().putBoolean("secure", v).apply() }
    fun updateTapToReveal(v: Boolean) { tapToReveal = v; sp.edit().putBoolean("reveal", v).apply() }
}

class KerbosApp : Application() {
    lateinit var prefs: Prefs
    lateinit var vault: VaultRepository
    lateinit var sync: SyncManager

    /** otpauth:// link received from another app, handled once unlocked. */
    val pendingUri = MutableStateFlow<String?>(null)

    private var stoppedAt = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        vault = VaultRepository(this)
        sync = SyncManager(vault)
        IconLibrary.init(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                stoppedAt = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                val t = prefs.autoLockSeconds
                if (stoppedAt > 0 && t >= 0 && vault.state.value == VaultState.UNLOCKED &&
                    System.currentTimeMillis() - stoppedAt >= t * 1000L
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
