package com.kerbos.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DensitySmall
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kerbos.app.BuildConfig
import com.kerbos.app.MainActivity
import com.kerbos.app.Nav
import com.kerbos.app.Route
import com.kerbos.app.app
import com.kerbos.app.sync.SyncStatus
import com.kerbos.app.vault.Biometric
import com.kerbos.app.vault.BiometricKey
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- building blocks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScaffold(title: String, nav: Nav, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        content = content,
    )
}

@Composable
fun SectionTitle(title: String) = Text(
    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
)

@Composable
fun SettingRow(
    icon: ImageVector, tint: Color, title: String, sub: String? = null,
    onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun SwitchRow(icon: ImageVector, tint: Color, title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    SettingRow(icon, tint, title, sub, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(checked, onChange, enabled = enabled)
    }

@Composable
fun ChoiceRow(icon: ImageVector, tint: Color, title: String, options: List<Pair<Int, String>>, current: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(icon, tint, title, options.firstOrNull { it.first == current }?.second ?: "", onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(
                    text = { Text(l, fontWeight = if (v == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onSelect(v); open = false },
                )
            }
        }
    }
}

@Composable
private fun Segments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(selected = selected == i, onClick = { onSelect(i) }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
        }
    }
}

// ---------------------------------------------------------------- main page

private data class Category(val id: String, val icon: ImageVector, val tint: Color, val title: String, val sub: String)

@Composable
fun SettingsScreen(activity: MainActivity, nav: Nav) {
    val sync by app.sync.status.collectAsState()
    val count = app.vault.all.collectAsState().value.count { !it.deleted }
    val cats = listOf(
        Category("appearance", Icons.Rounded.Palette, Violet, "Apparence", "Thème, couleurs, taille des codes, liste compacte"),
        Category("codes", Icons.Rounded.Code, Teal, "Codes et copie", "Tri, affichage, presse-papier, vibrations"),
        Category("security", Icons.Rounded.Security, Coral, "Sécurité", "Biométrie, verrouillage, captures d'écran, mot de passe"),
        Category("sync", Icons.Rounded.CloudSync, Indigo, "Synchronisation Nextcloud", syncSummary(sync).first),
        Category("transfer", Icons.Rounded.ImportExport, Amber, "Importer / Exporter", "Aegis, 2FAS, andOTP, Bitwarden, Google Authenticator…"),
        Category("about", Icons.Rounded.Info, MaterialTheme.colorScheme.outline, "À propos", "Kerbos ${BuildConfig.VERSION_NAME} · $count comptes"),
    )
    SubScaffold("Paramètres", nav) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(cats.size) { i ->
                val c = cats[i]
                SettingRow(c.icon, c.tint, c.title, c.sub, onClick = {
                    nav.push(
                        when (c.id) {
                            "sync" -> Route.Sync
                            "transfer" -> Route.Transfer
                            else -> Route.Section(c.id)
                        }
                    )
                }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

// ---------------------------------------------------------------- sub pages

@Composable
fun SettingsSection(id: String, activity: MainActivity, nav: Nav) {
    val title = when (id) {
        "appearance" -> "Apparence"; "codes" -> "Codes et copie"; "security" -> "Sécurité"; else -> "À propos"
    }
    SubScaffold(title, nav) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            when (id) {
                "appearance" -> AppearanceSettings()
                "codes" -> CodeSettings()
                "security" -> SecuritySettings(activity)
                else -> AboutSettings()
            }
        }
    }
}

@Composable
private fun AppearanceSettings() {
    val p = app.prefs
    SectionTitle("Thème")
    Segments(listOf("Système", "Clair", "Sombre"), p.theme.value) { p.theme.set(it) }
    SwitchRow(Icons.Rounded.DarkMode, Indigo, "Noir AMOLED", "Fond noir pur en mode sombre (économise la batterie)", p.amoled.value) { p.amoled.set(it) }
    val dynOk = Build.VERSION.SDK_INT >= 31
    SwitchRow(
        Icons.Rounded.AutoAwesome, Violet, "Couleurs Material You",
        if (dynOk) "Couleurs dérivées de votre fond d'écran" else "Nécessite Android 12 ou plus",
        p.dynamicColor.value, enabled = dynOk,
    ) { p.dynamicColor.set(it) }

    SectionTitle("Liste des comptes")
    ChoiceRow(Icons.Rounded.FormatSize, Teal, "Taille des codes", listOf(0 to "Petite", 1 to "Normale", 2 to "Grande"), p.codeSize.value) { p.codeSize.set(it) }
    SwitchRow(Icons.Rounded.DensitySmall, Coral, "Liste compacte", "Affiche plus de comptes à l'écran", p.compact.value) { p.compact.set(it) }
    SwitchRow(Icons.Rounded.Image, Amber, "Afficher les icônes", "Logos de marques et avatars colorés", p.showIcons.value) { p.showIcons.set(it) }
}

@Composable
private fun CodeSettings() {
    val p = app.prefs
    SectionTitle("Affichage")
    ChoiceRow(
        Icons.AutoMirrored.Rounded.Sort, Indigo, "Trier par",
        listOf(0 to "Émetteur (A → Z)", 1 to "Nom du compte (A → Z)", 2 to "Ajout le plus récent"), p.sortMode.value,
    ) { p.sortMode.set(it) }
    SwitchRow(Icons.Rounded.Pin, Teal, "Grouper les chiffres", "123 456 au lieu de 123456", p.groupDigits.value) { p.groupDigits.set(it) }
    SwitchRow(Icons.Rounded.SkipNext, Violet, "Afficher le code suivant", "Pratique quand le code va expirer", p.showNextCode.value) { p.showNextCode.set(it) }
    SwitchRow(Icons.Rounded.VisibilityOff, Coral, "Masquer les codes", "Toucher un compte pour révéler son code", p.tapToReveal.value) { p.tapToReveal.set(it) }

    SectionTitle("Copie")
    ChoiceRow(
        Icons.Rounded.ContentCopy, Amber, "Effacer le presse-papier",
        listOf(15 to "Après 15 s", 30 to "Après 30 s", 45 to "Après 45 s", 60 to "Après 1 min", 0 to "Jamais"), p.clipboardClear.value,
    ) { p.clipboardClear.set(it) }
    SwitchRow(Icons.Rounded.Minimize, Indigo, "Réduire après copie", "Revenir à l'app précédente pour coller le code", p.minimizeOnCopy.value) { p.minimizeOnCopy.set(it) }
    SwitchRow(Icons.Rounded.Vibration, Teal, "Vibrations", "Retour haptique à la copie et à l'appui long", p.haptics.value) { p.haptics.set(it) }
}

private val lockOptions = listOf(0 to "Immédiatement", 30 to "Après 30 s", 60 to "Après 1 min", 300 to "Après 5 min", 900 to "Après 15 min", -1 to "Jamais")

@Composable
private fun SecuritySettings(activity: MainActivity) {
    val p = app.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bioHw = remember { Biometric.available(ctx) }
    var bioOn by remember { mutableStateOf(app.vault.hasBiometricSlot()) }
    var pwDialog by remember { mutableStateOf(false) }

    SectionTitle("Déverrouillage")
    SwitchRow(
        Icons.Rounded.Fingerprint, Coral, "Déverrouillage biométrique",
        if (bioHw) "Empreinte ou visage (biométrie forte)" else "Aucune biométrie forte configurée sur l'appareil",
        bioOn, enabled = bioHw,
    ) { on ->
        if (on) {
            runCatching {
                Biometric.prompt(
                    activity, BiometricKey.encryptCipher(), "Activer la biométrie", "Confirmez avec votre empreinte", "Annuler",
                    onSuccess = { c -> app.vault.enableBiometric(c); bioOn = true },
                    onError = { code, msg -> if (!Biometric.isCancel(code)) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() },
                )
            }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
        } else {
            app.vault.disableBiometric(); bioOn = false
        }
    }
    if (bioOn) SwitchRow(
        Icons.Rounded.ScreenLockPortrait, Coral, "Biométrie par défaut au lancement",
        "Ouvre directement l'invite biométrique à chaque ouverture", p.bioAutoPrompt.value,
    ) { p.bioAutoPrompt.set(it) }
    ChoiceRow(
        Icons.Rounded.Password, Teal, "Rappel du mot de passe",
        listOf(7 to "Toutes les semaines", 14 to "Toutes les 2 semaines", 30 to "Tous les mois", 0 to "Jamais"), p.passwordReminderDays.value,
    ) { p.passwordReminderDays.set(it) }
    SettingRow(Icons.Rounded.Key, Violet, "Changer le mot de passe", "Le mot de passe principal du coffre", onClick = { pwDialog = true })

    SectionTitle("Verrouillage")
    ChoiceRow(Icons.Rounded.Timer, Amber, "Verrouillage automatique", lockOptions, p.autoLockSeconds.value) { p.autoLockSeconds.set(it) }
    SettingRow(Icons.Rounded.Lock, Indigo, "Verrouiller maintenant", onClick = { app.vault.lock() })

    SectionTitle("Confidentialité")
    SwitchRow(
        Icons.Rounded.Screenshot, Indigo, "Bloquer les captures d'écran",
        "Masque aussi l'app dans les tâches récentes", p.blockScreenshots.value,
    ) { p.blockScreenshots.set(it); activity.applySecure(it) }

    if (pwDialog) {
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) pwDialog = false },
            title = { Text("Nouveau mot de passe") },
            text = {
                Column {
                    OutlinedTextField(a, { a = it }, label = { Text("Nouveau mot de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(b, { b = it }, label = { Text("Confirmer") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (a.isNotEmpty() && a.length < 6) Text("Au moins 6 caractères", color = MaterialTheme.colorScheme.error)
                    else if (b.isNotEmpty() && a != b) Text("Les mots de passe ne correspondent pas", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && a.length >= 6 && a == b, onClick = {
                    busy = true
                    scope.launch {
                        app.vault.changePassword(a.toCharArray())
                        p.lastPasswordUnlock.set(System.currentTimeMillis())
                        busy = false; pwDialog = false
                        Toast.makeText(ctx, "Mot de passe modifié", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(if (busy) "…" else "Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { pwDialog = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun AboutSettings() {
    val ctx = LocalContext.current
    val count = app.vault.all.collectAsState().value.count { !it.deleted }
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KerbosLogo(96.dp)
        Spacer(Modifier.height(12.dp))
        Text("Kerbos", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionTitle("Coffre")
    SettingRow(Icons.Rounded.Key, Indigo, "$count comptes", "Chiffrés en AES-256-GCM sur cet appareil")
    SettingRow(Icons.Rounded.Sync, Teal, "Identifiant de l'appareil", app.vault.deviceId.take(8))
    SectionTitle("Projet")
    SettingRow(Icons.Rounded.Code, Violet, "Code source", "github.com/0x80070006/kerbos", onClick = {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/0x80070006/kerbos"))) }
    })
    SettingRow(Icons.Rounded.Image, Amber, "Icônes", "Simple Icons (CC0). Les logos sont des marques de leurs propriétaires.")
    SettingRow(Icons.Rounded.Info, MaterialTheme.colorScheme.outline, "Inspiré d'Aegis Authenticator", "Format de sauvegarde compatible")
}

// ---------------------------------------------------------------- sync

@Composable
fun syncSummary(s: SyncStatus): Triple<String, ImageVector, Color> = when (s) {
    SyncStatus.Disabled -> Triple("Non configurée", Icons.Rounded.CloudSync, MaterialTheme.colorScheme.outline)
    SyncStatus.Idle -> Triple("En attente", Icons.Rounded.CloudSync, Indigo)
    SyncStatus.Running -> Triple("Synchronisation en cours…", Icons.Rounded.CloudSync, Indigo)
    SyncStatus.WaitingWifi -> Triple("En attente d'un réseau Wi-Fi", Icons.Rounded.WifiOff, MaterialTheme.colorScheme.outline)
    is SyncStatus.Ok -> Triple(
        "Synchronisé ${DateUtils.getRelativeTimeSpanString(s.at)} · ${s.count} comptes", Icons.Rounded.CloudDone, Teal,
    )
    is SyncStatus.Error -> Triple(s.message, Icons.Rounded.SyncProblem, MaterialTheme.colorScheme.error)
}

@Composable
fun SyncScreen(nav: Nav) {
    val vault = app.vault
    val p = app.prefs
    val cfg0 = vault.syncConfig.collectAsState().value
    val status by app.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(cfg0.server) }
    var user by remember { mutableStateOf(cfg0.user) }
    var appPw by remember { mutableStateOf(cfg0.appPassword) }
    var folder by remember { mutableStateOf(cfg0.folder) }
    var phrase by remember { mutableStateOf(cfg0.passphrase) }
    var enabled by remember { mutableStateOf(cfg0.enabled || !cfg0.complete) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun cfg() = cfg0.copy(
        enabled = enabled, server = server.trim(), user = user.trim(), appPassword = appPw.trim(),
        folder = folder.trim().trim('/').ifEmpty { "Kerbos" }, passphrase = phrase,
    )
    val canSave = server.isNotBlank() && user.isNotBlank() && appPw.isNotBlank() && phrase.length >= 8

    SubScaffold("Synchronisation Nextcloud", nav) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp)) {
                    Text("Synchro automatique et fusion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Vos comptes sont chiffrés avec la phrase de synchronisation avant d'être envoyés sur votre Nextcloud (WebDAV). " +
                            "Chaque appareil fusionne automatiquement les ajouts, modifications et suppressions des autres : rien n'est écrasé. " +
                            "Utilisez la même phrase et le même dossier sur tous vos appareils.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val (sub, icon, tint) = syncSummary(status)
            SettingRow(icon, tint, "État", sub, onClick = if (cfg0.complete) ({ scope.launch { app.sync.syncNow(force = true) } }) else null) {
                if (cfg0.complete) Text("Synchroniser", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            SectionTitle("Compte Nextcloud")
            SyncField("Adresse du serveur", server, "https://cloud.exemple.fr") { server = it }
            SyncField("Nom d'utilisateur ou e-mail", user) { user = it }
            SyncField("Mot de passe d'application", appPw, secret = true) { appPw = it }
            Hint("Nextcloud → Paramètres personnels → Sécurité → « Créer un nouveau mot de passe d'application ». Vous pouvez aussi coller l'adresse WebDAV complète (Fichiers → Paramètres → WebDAV).")
            SyncField("Dossier", folder, "Kerbos") { folder = it }
            Hint("Chemin depuis la racine de vos fichiers, ex. « Kerbos » ou « Documents/Sécurité/Kerbos ». Il est créé s'il n'existe pas.")

            SectionTitle("Chiffrement")
            SyncField("Phrase de synchronisation", phrase, secret = true) { phrase = it }
            Hint("Identique sur tous vos appareils (8 caractères min.). Nextcloud ne voit jamais vos secrets.")

            SectionTitle("Options")
            SwitchRow(Icons.Rounded.CloudSync, Teal, "Synchronisation automatique", "À l'ouverture, après chaque modification et périodiquement", enabled) { enabled = it }
            ChoiceRow(
                Icons.Rounded.Timer, Amber, "Fréquence",
                listOf(30 to "Toutes les 30 s", 60 to "Toutes les minutes", 300 to "Toutes les 5 min", 900 to "Toutes les 15 min"),
                p.syncInterval.value,
            ) { p.syncInterval.set(it) }
            SwitchRow(Icons.Rounded.Wifi, Indigo, "Wi-Fi uniquement", "Pas de synchro automatique sur réseau mobile", p.syncWifiOnly.value) { p.syncWifiOnly.set(it) }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        testing = true; testMsg = null
                        scope.launch { testMsg = app.sync.test(cfg()); testing = false }
                    },
                    enabled = !testing && server.isNotBlank() && user.isNotBlank() && appPw.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) { Text(if (testing) "Test…" else "Tester") }
                Button(
                    onClick = {
                        vault.updateSyncConfig(cfg())
                        app.sync.onConfigChanged()
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1.6f).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) { Text("Enregistrer et synchroniser", fontWeight = FontWeight.Bold) }
            }
            if (phrase.isNotEmpty() && phrase.length < 8) Text("Phrase : au moins 8 caractères", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            testMsg?.let {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(it, modifier = Modifier.padding(14.dp), fontFamily = FontFamily.Default)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Hint(t: String) = Text(
    t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
)

@Composable
fun SyncField(label: String, value: String, placeholder: String? = null, secret: Boolean = false, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        trailingIcon = if (secret) {
            { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } }
        } else null,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
