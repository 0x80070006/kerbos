package com.kerbos.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kerbos.app.R
import com.kerbos.app.icons.BrandIcon
import com.kerbos.app.icons.IconLibrary
import com.kerbos.app.vault.Entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val pathCache = HashMap<String, Path>()

private fun pathFor(icon: BrandIcon): Path = synchronized(pathCache) {
    pathCache.getOrPut(icon.slug) { runCatching { PathParser().parsePathString(icon.path).toPath() }.getOrElse { Path() } }
}

val AvatarPalette = listOf(
    Color(0xFF3B1FCC), Color(0xFFFF6B4A), Color(0xFF00A896), Color(0xFFE0377D),
    Color(0xFF2D7FF9), Color(0xFFFFB020), Color(0xFF7A4DF5), Color(0xFF27AE60),
)

fun avatarColor(seed: String): Color = AvatarPalette[(seed.lowercase().hashCode() and 0x7fffffff) % AvatarPalette.size]

@Composable
fun BrandIconView(icon: BrandIcon, size: Dp, modifier: Modifier = Modifier) {
    val bg = Color(icon.color)
    val fg = if (bg.luminance() > 0.72f) Color(0xFF1B1A22) else Color.White
    val path = pathFor(icon)
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.56f)) {
            scale(this.size.width / 24f, pivot = Offset.Zero) { drawPath(path, fg) }
        }
    }
}

@Composable
fun LetterAvatar(text: String, size: Dp, modifier: Modifier = Modifier) {
    val c = avatarColor(text.ifBlank { "?" })
    Box(modifier.size(size).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(
            text.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
fun EntryAvatar(icon: String?, title: String, size: Dp, modifier: Modifier = Modifier) {
    val loaded by IconLibrary.loaded.collectAsState()
    val brand = icon?.removePrefix("si:")?.let { if (loaded) IconLibrary.get(it) else null }
    if (brand != null) BrandIconView(brand, size, modifier) else LetterAvatar(title, size, modifier)
}

@Composable
fun EntryAvatar(e: Entry, size: Dp, modifier: Modifier = Modifier) = EntryAvatar(e.icon, e.title, size, modifier)

@Composable
fun KerbosLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Cream), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.kerbos_logo), contentDescription = "Kerbos", modifier = Modifier.size(size).padding(size * 0.02f))
    }
}

private val clipScope = CoroutineScope(Dispatchers.Main)

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        else putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Code copié", Toast.LENGTH_SHORT).show()
    clipScope.launch {
        delay(45_000)
        runCatching {
            val cur = cm.primaryClip
            if (cur != null && cur.itemCount > 0 && cur.getItemAt(0).text?.toString() == text) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}
