package com.kerbos.app.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kerbos.app.MainActivity
import com.kerbos.app.Nav
import com.kerbos.app.Route
import com.kerbos.app.app
import com.kerbos.app.importOtpText
import com.kerbos.app.otp.Otp
import com.kerbos.app.otp.OtpType
import com.kerbos.app.sync.SyncStatus
import com.kerbos.app.vault.Entry
import kotlinx.coroutines.delay

private const val FAV = "\u0000fav"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(activity: MainActivity, nav: Nav) {
    val all by app.vault.all.collectAsState()
    val syncStatus by app.sync.status.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(250) }
    }

    val sort = app.prefs.sortMode.value
    val entries = remember(all, sort) {
        all.filter { !it.deleted }.sortedWith(
            when (sort) {
                1 -> compareByDescending<Entry> { it.favorite }.thenBy { it.name.lowercase() }.thenBy { it.title.lowercase() }
                2 -> compareByDescending<Entry> { it.favorite }.thenByDescending { it.createdAt }
                else -> compareByDescending<Entry> { it.favorite }.thenBy { it.title.lowercase() }.thenBy { it.name.lowercase() }
            }
        )
    }
    val groups = remember(entries) { entries.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }
    val visible = entries.filter { e ->
        (group == null || (group == FAV && e.favorite) || e.group == group) &&
            (query.isBlank() || e.issuer.contains(query, true) || e.name.contains(query, true) || e.group.contains(query, true))
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }?.let { decodeQrFromBitmap(it) }
            }.getOrNull()
            if (text == null) Toast.makeText(ctx, "Aucun QR code trouvé dans l'image", Toast.LENGTH_SHORT).show()
            else importOtpText(ctx, text, nav, replaceTop = false)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KerbosLogo(36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Kerbos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    SyncBadge(syncStatus) {
                        if (syncStatus is SyncStatus.Disabled) nav.push(Route.Sync) else app.sync.requestSync()
                    }
                    IconButton(onClick = { app.vault.lock() }) { Icon(Icons.Rounded.Lock, "Verrouiller") }
                    IconButton(onClick = { nav.push(Route.Settings) }) { Icon(Icons.Rounded.Settings, "Paramètres") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Ajouter", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(if (app.prefs.compact.value) 6.dp else 10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Rechercher un compte") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) } },
                    singleLine = true, shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (entries.any { it.favorite } || groups.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { GroupChip("Tous", group == null) { group = null } }
                    if (entries.any { it.favorite }) item { GroupChip("★ Favoris", group == FAV) { group = if (group == FAV) null else FAV } }
                    items(groups) { g -> GroupChip(g, group == g) { group = if (group == g) null else g } }
                }
            }
            if (entries.isEmpty()) item { EmptyState { showAdd = true } }
            items(visible, key = { it.uuid }) { e ->
                EntryCard(e, now, onEdit = { nav.push(Route.Edit(e, false)) }, modifier = Modifier.animateItem())
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text("Ajouter un compte", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                AddOption(Icons.Rounded.QrCodeScanner, MaterialTheme.colorScheme.primary, "Scanner un QR code", "Avec l'appareil photo") {
                    showAdd = false; nav.push(Route.Scan)
                }
                AddOption(Icons.Rounded.Image, MaterialTheme.colorScheme.secondary, "Importer une image", "Capture d'écran contenant un QR code") {
                    showAdd = false
                    app.expectExternalActivity()
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                AddOption(Icons.Rounded.Edit, MaterialTheme.colorScheme.tertiary, "Saisie manuelle", "Entrer la clé secrète") {
                    showAdd = false; nav.push(Route.Edit(Entry(), true))
                }
            }
        }
    }
}

@Composable
private fun AddOption(icon: ImageVector, color: Color, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(sub) },
        leadingContent = {
            Surface(shape = CircleShape, color = color.copy(alpha = 0.15f), modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun SyncBadge(s: SyncStatus, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when (s) {
            SyncStatus.Running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            is SyncStatus.Ok -> Icon(Icons.Rounded.CloudDone, "Synchronisé", tint = MaterialTheme.colorScheme.tertiary)
            is SyncStatus.Error -> Icon(Icons.Rounded.SyncProblem, "Erreur de synchronisation", tint = MaterialTheme.colorScheme.error)
            SyncStatus.Disabled -> Icon(Icons.Rounded.CloudOff, "Synchronisation désactivée", tint = MaterialTheme.colorScheme.outline)
            SyncStatus.Idle -> Icon(Icons.Rounded.CloudSync, "Synchroniser")
            SyncStatus.WaitingWifi -> Icon(Icons.Rounded.WifiOff, "En attente du Wi-Fi", tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KerbosLogo(110.dp)
        Spacer(Modifier.height(18.dp))
        Text("Aucun compte pour l'instant", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("Scannez un QR code 2FA pour commencer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("Ou configurez la synchro Nextcloud pour récupérer vos comptes.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(e: Entry, now: Long, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = app.prefs
    val compact = prefs.compact.value
    var revealed by remember { mutableStateOf(false) }
    val step = if (e.type == OtpType.HOTP) 0L else (now / 1000) / e.period.coerceAtLeast(1)
    val code = remember(e, step) { Otp.generate(e, now) }
    val next = remember(e, step) {
        if (e.type == OtpType.HOTP) Otp.generate(e.copy(counter = e.counter + 1)) else Otp.generate(e, now + e.period * 1000L)
    }
    val (frac, secs) = Otp.remaining(e, now)
    val urgent = e.type != OtpType.HOTP && secs <= 5
    val codeColor by animateColorAsState(
        if (urgent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, label = "code",
    )
    val hidden = prefs.tapToReveal.value && !revealed
    val fmt: (String) -> String = { if (prefs.groupDigits.value) Otp.format(it) else it }
    val codeSp = when (prefs.codeSize.value) { 0 -> 24; 2 -> 36; else -> 30 }.let { if (compact) it - 4 else it }

    Card(
        shape = RoundedCornerShape(if (compact) 16.dp else 22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().combinedClickable(
            onClick = {
                if (hidden) revealed = true
                else {
                    copyToClipboard(ctx, e.title, code)
                    if (prefs.haptics.value) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (prefs.minimizeOnCopy.value) (ctx as? android.app.Activity)?.moveTaskToBack(true)
                }
            },
            onLongClick = { if (prefs.haptics.value) haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEdit() },
        ),
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefs.showIcons.value) {
                EntryAvatar(e, if (compact) 38.dp else 50.dp)
                Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.title.ifBlank { "Sans nom" }, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (e.favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Star, null, tint = Amber, modifier = Modifier.size(16.dp))
                    }
                    if (compact && e.subtitle.isNotBlank()) Text(
                        "  ·  " + e.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact && e.subtitle.isNotBlank()) Text(
                    e.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!compact) Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (hidden) "•••  •••" else fmt(code),
                        fontSize = codeSp.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp, color = codeColor,
                    )
                    if (prefs.showNextCode.value && !hidden) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            fmt(next), fontFamily = FontFamily.Monospace, fontSize = (codeSp * 0.5f).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (e.type == OtpType.HOTP) {
                IconButton(onClick = { app.vault.upsert(e.copy(counter = e.counter + 1)) }) {
                    Icon(Icons.Rounded.Refresh, "Code suivant", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                val ring = if (compact) 36.dp else 44.dp
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ring)) {
                    CircularProgressIndicator(
                        progress = { frac }, modifier = Modifier.size(ring), strokeWidth = if (compact) 3.dp else 4.dp,
                        color = codeColor, trackColor = codeColor.copy(alpha = 0.15f),
                    )
                    Text("$secs", fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 14.sp, color = codeColor)
                }
            }
        }
    }
}
