package com.kerbos.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kerbos.app.MainActivity
import com.kerbos.app.Nav
import com.kerbos.app.Route
import com.kerbos.app.app
import com.kerbos.app.icons.IconLibrary
import com.kerbos.app.otp.OtpUri
import com.kerbos.app.transfer.Exporter
import com.kerbos.app.transfer.Formats
import com.kerbos.app.transfer.ImportResult
import com.kerbos.app.transfer.Importer
import com.kerbos.app.transfer.PasswordRequired
import com.kerbos.app.transfer.WrongPassword
import com.kerbos.app.transfer.dedupe
import com.kerbos.app.vault.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class ExportKind(val title: String, val encrypted: Boolean, val ext: String) {
    KERBOS("Sauvegarde Kerbos", true, "kerbos.json"),
    AEGIS_ENC("Aegis (chiffré)", true, "json"),
    AEGIS_PLAIN("Aegis (non chiffré)", false, "json"),
    URIS("Liste otpauth://", false, "txt"),
}

private fun qrBitmap(text: String, size: Int = 900): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { if (m[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun TransferScreen(activity: MainActivity, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // ---- import state ----
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var askPassword by remember { mutableStateOf<String?>(null) }
    var pwError by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<ImportResult, List<Entry>>?>(null) }

    fun parse(pw: String?) {
        val bytes = fileBytes ?: return
        busy = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { Importer.parse(bytes, pw, OtpUri::parse) }
                val existing = app.vault.all.value
                val ids = existing.map { it.uuid }.toHashSet()
                val fresh = dedupe(existing, r.entries).map { e ->
                    e.copy(
                        uuid = if (e.uuid in ids) UUID.randomUUID().toString() else e.uuid,
                        icon = e.icon ?: IconLibrary.bestMatch(e.issuer, e.name)?.let { "si:${it.slug}" },
                    )
                }
                askPassword = null; pwError = false
                preview = r to fresh
            } catch (e: PasswordRequired) {
                askPassword = e.source; pwError = false
            } catch (e: WrongPassword) {
                pwError = true
            } catch (e: Exception) {
                askPassword = null
                Toast.makeText(ctx, "Import impossible : ${e.message ?: "format non reconnu"}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileBytes = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            if (fileBytes == null) Toast.makeText(ctx, "Lecture du fichier impossible", Toast.LENGTH_SHORT).show() else parse(null)
        }
    }
    fun pickFile() {
        app.expectExternalActivity()
        openDoc.launch(arrayOf("*/*"))
    }

    // ---- export state ----
    var exportKind by remember { mutableStateOf<ExportKind?>(null) }
    var exportPw by remember { mutableStateOf<String?>(null) }
    var askExportPw by remember { mutableStateOf<ExportKind?>(null) }
    var warnPlain by remember { mutableStateOf<ExportKind?>(null) }
    var qrCodes by remember { mutableStateOf<List<String>?>(null) }

    fun writeExport(uri: android.net.Uri) {
        val kind = exportKind ?: return
        val pw = exportPw
        busy = true
        scope.launch {
            try {
                val entries = app.vault.all.value
                val content = withContext(Dispatchers.Default) {
                    when (kind) {
                        ExportKind.KERBOS -> Exporter.kerbos(entries, pw!!)
                        ExportKind.AEGIS_ENC -> Exporter.aegisEncrypted(entries, pw!!)
                        ExportKind.AEGIS_PLAIN -> Exporter.aegisPlain(entries)
                        ExportKind.URIS -> Exporter.uriList(entries, OtpUri::toUri)
                    }
                }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content.toByteArray()) }
                }
                Toast.makeText(ctx, "Export « ${kind.title} » enregistré", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Échec de l'export : ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false; exportPw = null; exportKind = null
            }
        }
    }

    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(::writeExport) }
    val createTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(::writeExport) }

    fun launchSave(kind: ExportKind) {
        exportKind = kind
        val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val name = when (kind) {
            ExportKind.KERBOS -> "kerbos-backup-$date.json"
            ExportKind.AEGIS_ENC -> "aegis-export-$date.json"
            ExportKind.AEGIS_PLAIN -> "aegis-export-plain-$date.json"
            ExportKind.URIS -> "kerbos-otpauth-$date.txt"
        }
        app.expectExternalActivity()
        if (kind == ExportKind.URIS) createTxt.launch(name) else createJson.launch(name)
    }

    fun startExport(kind: ExportKind) {
        if (app.vault.all.value.none { !it.deleted }) {
            Toast.makeText(ctx, "Aucun compte à exporter", Toast.LENGTH_SHORT).show(); return
        }
        if (kind.encrypted) askExportPw = kind else warnPlain = kind
    }

    SubScaffold("Importer / Exporter", nav) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        "Le format est détecté automatiquement. Les doublons sont ignorés et les icônes sont choisies automatiquement. " +
                            "Les comptes importés sont ensuite synchronisés sur vos autres appareils.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                SectionTitle("Importer depuis un fichier")
                val tints = listOf(Indigo, Teal, Coral, Violet, Amber, Indigo, Teal, Coral)
                Formats.importSources.forEachIndexed { i, s ->
                    SettingRow(Icons.Rounded.FileOpen, tints[i % tints.size], s.name, s.hint, onClick = ::pickFile) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
                SectionTitle("Importer depuis un QR code")
                SettingRow(
                    Icons.Rounded.QrCodeScanner, Coral, "Google Authenticator",
                    "Transférer des comptes → Exporter, puis scannez le QR code", onClick = { nav.push(Route.Scan) },
                ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline) }

                SectionTitle("Exporter (sauvegarde chiffrée)")
                SettingRow(Icons.Rounded.Lock, Indigo, "Sauvegarde Kerbos", "Fichier chiffré par mot de passe, réimportable dans Kerbos", onClick = { startExport(ExportKind.KERBOS) })
                SettingRow(Icons.Rounded.Archive, Teal, "Aegis (chiffré)", "Importable dans Aegis : Importer → Aegis", onClick = { startExport(ExportKind.AEGIS_ENC) })

                SectionTitle("Exporter (non chiffré)")
                SettingRow(Icons.Rounded.LockOpen, Coral, "Aegis (JSON non chiffré)", "Compatible Aegis et la plupart des apps", onClick = { startExport(ExportKind.AEGIS_PLAIN) })
                SettingRow(Icons.Rounded.Description, Amber, "Liste otpauth:// (.txt)", "Universel : Ente Auth, Proton, 2FAS, Bitwarden, KeePassXC…", onClick = { startExport(ExportKind.URIS) })
                SettingRow(Icons.Rounded.QrCode2, Violet, "Google Authenticator (QR codes)", "À scanner depuis Google Authenticator → Importer", onClick = {
                    val (uris, skipped) = Exporter.googleMigration(app.vault.all.value)
                    if (uris.isEmpty()) Toast.makeText(ctx, "Aucun compte compatible avec Google Authenticator", Toast.LENGTH_SHORT).show()
                    else {
                        if (skipped > 0) Toast.makeText(ctx, "$skipped compte(s) incompatible(s) ignoré(s) (Steam, période ≠ 30 s…)", Toast.LENGTH_LONG).show()
                        qrCodes = uris
                    }
                })
            }
            if (busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    // ---- dialogs ----
    askPassword?.let { source ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = null; fileBytes = null },
            title = { Text("Fichier $source chiffré") },
            text = {
                Column {
                    Text("Saisissez le mot de passe utilisé lors de l'export.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        pw, { pw = it; pwError = false }, label = { Text("Mot de passe") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), isError = pwError,
                        supportingText = if (pwError) ({ Text("Mot de passe incorrect") }) else null,
                    )
                }
            },
            confirmButton = { TextButton(enabled = pw.isNotEmpty() && !busy, onClick = { parse(pw) }) { Text("Déchiffrer") } },
            dismissButton = { TextButton(onClick = { askPassword = null; fileBytes = null }) { Text("Annuler") } },
        )
    }

    preview?.let { (r, fresh) ->
        val dup = r.entries.size - fresh.size
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Importer depuis ${r.source}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.entries.size} compte(s) trouvé(s)", fontWeight = FontWeight.Bold)
                    if (dup > 0) Text("$dup déjà présent(s) : ignoré(s)")
                    if (r.skipped > 0) Text("${r.skipped} non pris en charge : ignoré(s)")
                    Spacer(Modifier.height(4.dp))
                    fresh.take(6).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EntryAvatar(e, 28.dp)
                            Text("  ${e.title}" + if (e.subtitle.isNotBlank()) " · ${e.subtitle}" else "", maxLines = 1)
                        }
                    }
                    if (fresh.size > 6) Text("… et ${fresh.size - 6} autre(s)")
                }
            },
            confirmButton = {
                TextButton(enabled = fresh.isNotEmpty(), onClick = {
                    app.vault.addAll(fresh)
                    preview = null; fileBytes = null
                    Toast.makeText(ctx, "${fresh.size} compte(s) importé(s)", Toast.LENGTH_LONG).show()
                    nav.home()
                }) { Text(if (fresh.isEmpty()) "Rien à importer" else "Importer ${fresh.size}") }
            },
            dismissButton = { TextButton(onClick = { preview = null; fileBytes = null }) { Text("Annuler") } },
        )
    }

    askExportPw?.let { kind ->
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askExportPw = null },
            title = { Text(kind.title) },
            text = {
                Column {
                    Text("Choisissez un mot de passe pour chiffrer la sauvegarde. Il sera demandé à l'import.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(a, { a = it }, label = { Text("Mot de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(b, { b = it }, label = { Text("Confirmer") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (a.isNotEmpty() && a.length < 8) Text("Au moins 8 caractères", color = MaterialTheme.colorScheme.error)
                    else if (b.isNotEmpty() && a != b) Text("Les mots de passe ne correspondent pas", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = a.length >= 8 && a == b, onClick = {
                    exportPw = a; askExportPw = null; launchSave(kind)
                }) { Text("Choisir l'emplacement") }
            },
            dismissButton = { TextButton(onClick = { askExportPw = null }) { Text("Annuler") } },
        )
    }

    warnPlain?.let { kind ->
        AlertDialog(
            onDismissRequest = { warnPlain = null },
            icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Export non chiffré") },
            text = { Text("Ce fichier contiendra tous vos secrets en clair : quiconque y accède peut générer vos codes. Supprimez-le dès l'import terminé.") },
            confirmButton = { TextButton(onClick = { warnPlain = null; launchSave(kind) }) { Text("Continuer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { warnPlain = null }) { Text("Annuler") } },
        )
    }

    qrCodes?.let { uris ->
        var idx by remember { mutableIntStateOf(0) }
        val bmp = remember(idx) { qrBitmap(uris[idx]) }
        Dialog(onDismissRequest = { qrCodes = null }) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Google Authenticator", style = MaterialTheme.typography.titleLarge)
                    Text("QR code ${idx + 1} / ${uris.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Image(bmp.asImageBitmap(), "QR code", modifier = Modifier.size(280.dp).background(Color.White, RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Dans Google Authenticator : Transférer des comptes → Importer. Ne montrez ces QR codes à personne.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = idx > 0, onClick = { idx-- }) { Text("Précédent") }
                        if (idx < uris.size - 1) TextButton(onClick = { idx++ }) { Text("Suivant") }
                        else TextButton(onClick = { qrCodes = null }) { Text("Terminé") }
                    }
                }
            }
        }
    }
}
