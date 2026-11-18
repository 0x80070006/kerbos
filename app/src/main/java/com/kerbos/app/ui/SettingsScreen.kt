package com.kerbos.app.ui

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
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScaffold(title: String, nav: Nav, content: @Composable (PaddingValues) -> Unit) {
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
private fun Section(title: String) = Text(
    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
)

@Composable
private fun SettingRow(
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

private val lockOptions = listOf(0 to "Immédiatement", 30 to "Après 30 s", 60 to "Après 1 min", 300 to "Après 5 min", 900 to "Après 15 min", -1 to "Jamais")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(activity: MainActivity, nav: Nav) {
    val prefs = app.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bioHw = remember { Biometric.available(ctx) }
    var bioOn by remember { mutableStateOf(app.vault.hasBiometricSlot()) }
    var lockMenu by remember { mutableStateOf(false) }
    var pwDialog by remember { mutableStateOf(false) }
    val sync by app.sync.status.collectAsState()

    SubScaffold("Paramètres", nav) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item { Section("Synchronisation") }
            item {
                val (sub, icon, tint) = syncSummary(sync)
                SettingRow(icon, tint, "Nextcloud", sub, onClick = { nav.push(Route.Sync) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }

            item { Section("Apparence") }
            item {
                SettingRow(Icons.Rounded.Palette, Violet, "Thème") {}
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    listOf("Système", "Clair", "Sombre").forEachIndexed { i, l ->
                        SegmentedButton(selected = prefs.theme == i, onClick = { prefs.updateTheme(i) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
            }
            item {
                SettingRow(Icons.Rounded.VisibilityOff, Teal, "Masquer les codes", "Toucher un compte pour afficher son code") {
                    Switch(prefs.tapToReveal, { prefs.updateTapToReveal(it) })
                }
            }

            item { Section("Sécurité") }
            item {
                SettingRow(
                    Icons.Rounded.Fingerprint, Coral, "Déverrouillage biométrique",
                    if (bioHw) "Empreinte / visage (classe forte)" else "Aucune biométrie forte configurée sur l'appareil",
                ) {
                    Switch(bioOn, enabled = bioHw, onCheckedChange = { on ->
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
                    })
                }
            }
            if (bioOn) item {
                SettingRow(Icons.Rounded.ScreenLockPortrait, Coral, "Biométrie par défaut au lancement", "Affiche directement l'invite biométrique à l'ouverture") {
                    Switch(prefs.bioAutoPrompt, { prefs.setBioAuto(it) })
                }
            }
            item {
                Box {
                    SettingRow(
                        Icons.Rounded.Timer, Amber, "Verrouillage automatique",
                        lockOptions.firstOrNull { it.first == prefs.autoLockSeconds }?.second ?: "",
                        onClick = { lockMenu = true },
                    )
                    DropdownMenu(expanded = lockMenu, onDismissRequest = { lockMenu = false }) {
                        lockOptions.forEach { (v, l) ->
                            DropdownMenuItem(text = { Text(l) }, onClick = { prefs.setAutoLock(v); lockMenu = false })
                        }
                    }
                }
            }
            item {
                SettingRow(Icons.Rounded.Screenshot, Indigo, "Bloquer les captures d'écran", "Masque aussi l'app dans les tâches récentes") {
                    Switch(prefs.blockScreenshots, { prefs.setSecure(it); activity.applySecure(it) })
                }
            }
            item {
                SettingRow(Icons.Rounded.Password, Teal, "Changer le mot de passe", onClick = { pwDialog = true })
            }

            item { Section("À propos") }
            item {
                SettingRow(
                    Icons.Rounded.Info, MaterialTheme.colorScheme.outline, "Kerbos ${BuildConfig.VERSION_NAME}",
                    "Coffre AES-256-GCM · synchro chiffrée de bout en bout · icônes Simple Icons (CC0)",
                )
            }
        }
    }

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
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && a.length >= 6 && a == b, onClick = {
                    busy = true
                    scope.launch {
                        app.vault.changePassword(a.toCharArray())
                        busy = false; pwDialog = false
                        Toast.makeText(ctx, "Mot de passe modifié", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { pwDialog = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun syncSummary(s: SyncStatus): Triple<String, ImageVector, Color> = when (s) {
    SyncStatus.Disabled -> Triple("Non configurée — touchez pour configurer", Icons.Rounded.CloudSync, MaterialTheme.colorScheme.outline)
    SyncStatus.Idle -> Triple("En attente", Icons.Rounded.CloudSync, Indigo)
    SyncStatus.Running -> Triple("Synchronisation en cours…", Icons.Rounded.CloudSync, Indigo)
    is SyncStatus.Ok -> Triple(
        "Synchronisé ${DateUtils.getRelativeTimeSpanString(s.at)} · ${s.count} comptes",
        Icons.Rounded.CloudDone, Teal,
    )
    is SyncStatus.Error -> Triple(s.message, Icons.Rounded.SyncProblem, MaterialTheme.colorScheme.error)
}

@Composable
fun SyncScreen(nav: Nav) {
    val vault = app.vault
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
        folder = folder.trim().ifEmpty { "Kerbos" }, passphrase = phrase,
    )

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
                            "Chaque appareil fusionne automatiquement les ajouts, modifications et suppressions des autres — rien n'est écrasé. " +
                            "Utilisez la même phrase et le même dossier sur tous vos appareils.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val (sub, icon, tint) = syncSummary(status)
            SettingRow(icon, tint, "État", sub)

            SyncField("Adresse du serveur", server, "https://cloud.exemple.fr") { server = it }
            SyncField("Nom d'utilisateur", user) { user = it }
            SyncField("Mot de passe d'application", appPw, secret = true) { appPw = it }
            Text(
                "Nextcloud → Paramètres personnels → Sécurité → « Créer un nouveau mot de passe d'application ».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            SyncField("Dossier", folder, "Kerbos") { folder = it }
            SyncField("Phrase de synchronisation", phrase, secret = true) { phrase = it }
            Text(
                "Identique sur tous vos appareils. Elle chiffre le fichier sur le serveur : Nextcloud ne voit jamais vos secrets.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            SettingRow(Icons.Rounded.CloudSync, Teal, "Synchronisation automatique", "À l'ouverture, après chaque modification et chaque minute") {
                Switch(enabled, { enabled = it })
            }
            Spacer(Modifier.height(12.dp))
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
                    enabled = server.isNotBlank() && user.isNotBlank() && appPw.isNotBlank() && phrase.length >= 8,
                    modifier = Modifier.weight(1.4f).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(6.dp))
                    Text("Enregistrer & synchroniser", fontWeight = FontWeight.Bold)
                }
            }
            if (phrase.isNotEmpty() && phrase.length < 8) Text("Phrase : au moins 8 caractères", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            testMsg?.let {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(it, modifier = Modifier.padding(14.dp))
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SyncField(label: String, value: String, placeholder: String? = null, secret: Boolean = false, onChange: (String) -> Unit) {
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
