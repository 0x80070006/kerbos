package com.kerbos.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kerbos.app.Nav
import com.kerbos.app.app
import com.kerbos.app.icons.BrandIcon
import com.kerbos.app.icons.IconLibrary
import com.kerbos.app.otp.Algo
import com.kerbos.app.otp.Base32
import com.kerbos.app.otp.OtpType
import com.kerbos.app.vault.Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(initial: Entry, isNew: Boolean, nav: Nav) {
    var e by remember { mutableStateOf(initial) }
    var showSecret by remember { mutableStateOf(isNew && initial.secret.isEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded by IconLibrary.loaded.collectAsState()
    val suggestions = remember(e.issuer, e.name, loaded) { if (loaded) IconLibrary.suggest(e.issuer, e.name, 10).map { it.first } else emptyList() }
    val groups = app.vault.all.collectAsState().value.filter { !it.deleted }.map { it.group }.filter { it.isNotBlank() }.distinct()

    fun save() {
        val secret = Base32.normalize(e.secret)
        when {
            e.issuer.isBlank() && e.name.isBlank() -> { error = "Indiquez au moins un émetteur ou un nom"; return }
            !Base32.isValid(secret) -> { error = "Clé secrète invalide (base32 attendu : A-Z, 2-7)"; return }
        }
        app.vault.upsert(e.copy(secret = secret, issuer = e.issuer.trim(), name = e.name.trim(), group = e.group.trim()))
        nav.pop()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Nouveau compte" else "Modifier le compte") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = { e = e.copy(favorite = !e.favorite) }) {
                        Icon(if (e.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favori", tint = if (e.favorite) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            // ---- Icon header ----
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.clip(CircleShape).clickable { picker = true }) {
                    EntryAvatar(e.icon, e.title, 96.dp)
                }
                TextButton(onClick = { picker = true }) {
                    Icon(Icons.Rounded.GridView, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Bibliothèque d'icônes")
                }
            }
            if (suggestions.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Suggestions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(suggestions, key = { it.slug }) { ic ->
                        IconChoice(ic, selected = e.icon == "si:${ic.slug}") { e = e.copy(icon = "si:${ic.slug}") }
                    }
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { e = e.copy(icon = null) }) {
                            Box(
                                if (e.icon == null) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp) else Modifier.padding(4.dp),
                            ) { LetterAvatar(e.title, 48.dp) }
                            Text("Initiale", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- Fields ----
            Field("Émetteur (ex : GitHub)", e.issuer) { e = e.copy(issuer = it) }
            Field("Nom du compte (ex : moi@exemple.fr)", e.name) { e = e.copy(name = it) }
            Field("Groupe (optionnel)", e.group) { e = e.copy(group = it) }
            if (groups.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups) { g -> AssistChip(onClick = { e = e.copy(group = g) }, label = { Text(g) }) }
                }
            }
            OutlinedTextField(
                value = e.secret, onValueChange = { e = e.copy(secret = it); error = null },
                label = { Text("Clé secrète") }, singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Field("Note (optionnel)", e.note, single = false) { e = e.copy(note = it) }

            // ---- Advanced ----
            TextButton(onClick = { advanced = !advanced }) {
                Text("Paramètres avancés"); Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(advanced) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Type", style = MaterialTheme.typography.labelLarge)
                        Segmented(OtpType.entries.map { it.name }, e.type.ordinal) {
                            val t = OtpType.entries[it]
                            e = e.copy(type = t, digits = if (t == OtpType.STEAM) 5 else if (e.digits == 5) 6 else e.digits)
                        }
                        if (e.type != OtpType.STEAM) {
                            Text("Algorithme", style = MaterialTheme.typography.labelLarge)
                            Segmented(Algo.entries.map { it.name }, e.algo.ordinal) { e = e.copy(algo = Algo.entries[it]) }
                            Text("Chiffres", style = MaterialTheme.typography.labelLarge)
                            val opts = listOf(6, 7, 8)
                            Segmented(opts.map { "$it" }, opts.indexOf(e.digits).coerceAtLeast(0)) { e = e.copy(digits = opts[it]) }
                        }
                        if (e.type == OtpType.HOTP) Field("Compteur", e.counter.toString(), KeyboardType.Number) {
                            e = e.copy(counter = it.filter(Char::isDigit).toLongOrNull() ?: 0)
                        } else Field("Période (secondes)", e.period.toString(), KeyboardType.Number) {
                            e = e.copy(period = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 3600) ?: 30)
                        }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = ::save, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp))
                Text(if (isNew) "Ajouter le compte" else "Enregistrer", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (picker) IconPickerDialog(e.issuer, e.name, e.icon, onDismiss = { picker = false }) {
        e = e.copy(icon = it); picker = false
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer ce compte ?") },
        text = { Text("« ${e.title} » sera supprimé de cet appareil et de tous les appareils synchronisés. Assurez-vous d'avoir désactivé la 2FA ou d'avoir un autre moyen d'accès.") },
        confirmButton = {
            TextButton(onClick = { app.vault.delete(e.uuid); confirmDelete = false; nav.pop() }) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

@Composable
private fun Field(label: String, value: String, kb: KeyboardType = KeyboardType.Text, single: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = single,
        keyboardOptions = KeyboardOptions(keyboardType = kb), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected, onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun IconChoice(ic: BrandIcon, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable(onClick = onClick)) {
        Box(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp) else Modifier.padding(4.dp)) {
            BrandIconView(ic, 48.dp)
        }
        Text(ic.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun IconPickerDialog(issuer: String, name: String, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val suggestions = remember(issuer, name) { IconLibrary.suggest(issuer, name, 12).map { it.first } }
    val results = remember(q) { IconLibrary.search(q) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.94f).fillMaxSize(0.9f)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bibliothèque d'icônes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                }
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, placeholder = { Text("Rechercher (${IconLibrary.icons.size} icônes)") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(GridCells.Adaptive(76.dp), modifier = Modifier.weight(1f)) {
                    if (q.isBlank() && suggestions.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Header("Suggérées pour « ${issuer.ifBlank { name }} »") }
                        items(suggestions, key = { "s_" + it.slug }) { ic -> PickerCell(ic, current == "si:${ic.slug}") { onPick("si:${ic.slug}") } }
                        item(span = { GridItemSpan(maxLineSpan) }) { Header("Toutes les icônes") }
                    }
                    items(results, key = { it.slug }) { ic -> PickerCell(ic, current == "si:${ic.slug}") { onPick("si:${ic.slug}") } }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { onPick(null) }) { Text("Utiliser l'initiale") }
                }
                Text(
                    "Logos : Simple Icons (CC0). Marques déposées de leurs propriétaires.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.widthIn(max = 600.dp).padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(t: String) =
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 8.dp))

@Composable
private fun PickerCell(ic: BrandIcon, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(6.dp),
    ) {
        Box(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp) else Modifier.padding(3.dp)) {
            BrandIconView(ic, 50.dp)
        }
        Text(ic.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
