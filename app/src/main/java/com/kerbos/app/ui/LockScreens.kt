package com.kerbos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.kerbos.app.app
import com.kerbos.app.vault.Biometric
import com.kerbos.app.vault.BiometricKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun BrandBackground(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(BrandGradient).systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun whiteFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Coral, unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
    focusedLabelColor = Color.White, unfocusedLabelColor = Color.White.copy(alpha = 0.75f),
    cursorColor = Coral, focusedTrailingIconColor = Color.White, unfocusedTrailingIconColor = Color.White.copy(alpha = 0.8f),
)

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, onDone: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
            }
        },
        shape = RoundedCornerShape(16.dp),
        colors = whiteFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LockScreen(activity: FragmentActivity) {
    val vault = app.vault
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val bioAvailable = remember { vault.hasBiometricSlot() && Biometric.available(activity) }

    fun startBio() {
        val cipher = vault.biometricCipher()
        if (cipher == null) {
            error = "La biométrie a été invalidée (nouvelle empreinte ?). Déverrouillez avec le mot de passe puis réactivez-la."
            return
        }
        Biometric.prompt(
            activity, cipher,
            title = "Déverrouiller Kerbos", subtitle = "Accédez à vos codes",
            negative = "Utiliser le mot de passe",
            onSuccess = { c ->
                scope.launch {
                    busy = true
                    if (!vault.unlockWithCipher(c)) error = "Échec du déverrouillage biométrique"
                    busy = false
                }
            },
            onError = { code, msg -> if (!Biometric.isCancel(code)) error = msg },
        )
    }

    fun unlockPw() {
        if (pw.isEmpty() || busy) return
        scope.launch {
            busy = true; error = null
            val ok = vault.unlockWithPassword(pw.toCharArray())
            busy = false
            if (!ok) error = "Mot de passe incorrect" else pw = ""
        }
    }

    // Biometrics as default unlock: prompt automatically as soon as the screen is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        if (bioAvailable && app.prefs.bioAutoPrompt) {
            lifecycleOwner.lifecycle.withResumed { }
            delay(200)
            startBio()
        }
    }

    BrandBackground {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KerbosLogo(132.dp, Modifier.shadow(24.dp, CircleShape))
            Spacer(Modifier.height(20.dp))
            Text("Kerbos", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text("Votre coffre est verrouillé", color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(36.dp))

            if (bioAvailable) {
                Surface(
                    onClick = { error = null; startBio() },
                    shape = CircleShape, color = Coral, shadowElevation = 10.dp,
                    modifier = Modifier.size(84.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Fingerprint, "Biométrie", tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Toucher pour utiliser la biométrie", color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(28.dp))
                Text("ou", color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.height(12.dp))
            }

            PasswordField(pw, { pw = it; error = null }, "Mot de passe", ::unlockPw)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = ::unlockPw, enabled = !busy && pw.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Indigo),
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Indigo)
                else {
                    Icon(Icons.Rounded.LockOpen, null); Spacer(Modifier.width(8.dp))
                    Text("Déverrouiller", fontWeight = FontWeight.Bold)
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Surface(color = Coral.copy(alpha = 0.22f), shape = RoundedCornerShape(12.dp)) {
                    Text(it, color = Color.White, modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun SetupScreen(activity: FragmentActivity) {
    val vault = app.vault
    val scope = rememberCoroutineScope()
    val bioHw = remember { Biometric.available(activity) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var useBio by remember { mutableStateOf(bioHw) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun create() {
        when {
            pw.length < 6 -> { error = "Au moins 6 caractères"; return }
            pw != pw2 -> { error = "Les mots de passe ne correspondent pas"; return }
        }
        busy = true; error = null
        scope.launch {
            vault.create(pw.toCharArray())
            if (useBio) {
                try {
                    Biometric.prompt(
                        activity, BiometricKey.encryptCipher(),
                        title = "Activer la biométrie", subtitle = "Kerbos se déverrouillera avec votre empreinte",
                        negative = "Plus tard",
                        onSuccess = { c -> vault.enableBiometric(c); app.prefs.setBioAuto(true); vault.finishSetup() },
                        onError = { _, _ -> vault.finishSetup() },
                    )
                } catch (e: Exception) {
                    vault.finishSetup()
                }
            } else vault.finishSetup()
        }
    }

    BrandBackground {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KerbosLogo(120.dp, Modifier.shadow(24.dp, CircleShape))
            Spacer(Modifier.height(20.dp))
            Text("Bienvenue dans Kerbos", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "Vos codes 2FA, chiffrés sur l'appareil et synchronisés avec votre propre Nextcloud.",
                color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            PasswordField(pw, { pw = it; error = null }, "Mot de passe du coffre")
            Spacer(Modifier.height(12.dp))
            PasswordField(pw2, { pw2 = it; error = null }, "Confirmer le mot de passe", ::create)
            if (bioHw) {
                Spacer(Modifier.height(16.dp))
                Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Fingerprint, null, tint = Coral, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Déverrouillage biométrique", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Proposé automatiquement à chaque ouverture", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = useBio, onCheckedChange = { useBio = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Coral, checkedThumbColor = Color.White),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::create, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Créer mon coffre", fontWeight = FontWeight.Bold)
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Color(0xFFFFC2B5)) }
            Spacer(Modifier.height(16.dp))
            Text(
                "Ce mot de passe ne peut pas être récupéré. Ne l'oubliez pas.",
                color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
    }
}
