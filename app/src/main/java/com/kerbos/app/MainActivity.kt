package com.kerbos.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.kerbos.app.icons.IconLibrary
import com.kerbos.app.otp.OtpUri
import com.kerbos.app.ui.EditScreen
import com.kerbos.app.ui.HomeScreen
import com.kerbos.app.ui.KerbosTheme
import com.kerbos.app.ui.LockScreen
import com.kerbos.app.ui.ScanScreen
import com.kerbos.app.ui.SettingsScreen
import com.kerbos.app.ui.SetupScreen
import com.kerbos.app.ui.SyncScreen
import com.kerbos.app.vault.Entry
import com.kerbos.app.vault.VaultState

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecure(app.prefs.blockScreenshots)
        handleIntent(intent)
        setContent {
            KerbosTheme(app.prefs.theme) { Root(this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.data?.toString()?.let { if (it.startsWith("otpauth")) app.pendingUri.value = it }
    }

    fun applySecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

sealed interface Route {
    data object Home : Route
    data object Scan : Route
    data object Settings : Route
    data object Sync : Route
    data class Edit(val entry: Entry, val isNew: Boolean) : Route
}

class Nav(private val get: () -> List<Route>, private val set: (List<Route>) -> Unit) {
    fun push(r: Route) = set(get() + r)
    fun pop() { if (get().size > 1) set(get().dropLast(1)) }
    fun replace(r: Route) = set(get().dropLast(1) + r)
    fun home() = set(listOf(Route.Home))
}

@Composable
private fun Root(activity: MainActivity) {
    val state by app.vault.state.collectAsState()
    when (state) {
        VaultState.NONE -> SetupScreen(activity)
        VaultState.LOCKED -> LockScreen(activity)
        VaultState.UNLOCKED -> MainNav(activity)
    }
}

@Composable
private fun MainNav(activity: MainActivity) {
    var stack by remember { mutableStateOf(listOf<Route>(Route.Home)) }
    val nav = remember { Nav({ stack }, { stack = it }) }
    BackHandler(enabled = stack.size > 1) { nav.pop() }

    val pending by app.pendingUri.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            app.pendingUri.value = null
            importOtpText(activity, it, nav, replaceTop = false)
        }
    }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "nav",
    ) { route ->
        when (route) {
            Route.Home -> HomeScreen(activity, nav)
            Route.Scan -> ScanScreen(nav)
            Route.Settings -> SettingsScreen(activity, nav)
            Route.Sync -> SyncScreen(nav)
            is Route.Edit -> EditScreen(route.entry, route.isNew, nav)
        }
    }
}

/** Handles text from a QR code / link. Returns false if not an OTP code. */
fun importOtpText(ctx: Context, text: String, nav: Nav, replaceTop: Boolean): Boolean {
    val list = try {
        OtpUri.parse(text)
    } catch (e: Exception) {
        Toast.makeText(ctx, e.message ?: "QR code non reconnu", Toast.LENGTH_SHORT).show()
        return false
    }
    val withIcons = list.map { e -> e.copy(icon = IconLibrary.bestMatch(e.issuer, e.name)?.let { "si:${it.slug}" }) }
    if (withIcons.size == 1) {
        val r = Route.Edit(withIcons[0], isNew = true)
        if (replaceTop) nav.replace(r) else nav.push(r)
    } else {
        app.vault.addAll(withIcons)
        Toast.makeText(ctx, "${withIcons.size} comptes importés", Toast.LENGTH_LONG).show()
        nav.home()
    }
    return true
}
