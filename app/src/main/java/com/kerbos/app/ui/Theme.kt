package com.kerbos.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val Indigo = Color(0xFF3B1FCC)
val IndigoDeep = Color(0xFF1E0F7A)
val Violet = Color(0xFF7A4DF5)
val Coral = Color(0xFFFF6B4A)
val Cream = Color(0xFFFBF9F3)
val Teal = Color(0xFF00A896)
val Amber = Color(0xFFFFB020)

val BrandGradient = Brush.linearGradient(listOf(IndigoDeep, Indigo, Violet))

private val LightColors = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF), onPrimaryContainer = Color(0xFF140067),
    secondary = Coral, onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBD1), onSecondaryContainer = Color(0xFF3B0900),
    tertiary = Teal, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB9F2EA), onTertiaryContainer = Color(0xFF00201C),
    background = Cream, onBackground = Color(0xFF1B1A22),
    surface = Cream, onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFE6E1F0), onSurfaceVariant = Color(0xFF494658),
    outline = Color(0xFF7A7690), outlineVariant = Color(0xFFCBC5DA),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F3FB),
    surfaceContainer = Color(0xFFF0ECF8), surfaceContainerHigh = Color(0xFFEAE5F5),
    surfaceContainerHighest = Color(0xFFE4DFF0),
    error = Color(0xFFD32F45),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC1B5FF), onPrimary = Color(0xFF240098),
    primaryContainer = Color(0xFF3B1FCC), onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFFF8F73), onSecondary = Color(0xFF5E1500),
    secondaryContainer = Color(0xFF8A2A10), onSecondaryContainer = Color(0xFFFFDBD1),
    tertiary = Color(0xFF5BDBC9), onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048), onTertiaryContainer = Color(0xFFB9F2EA),
    background = Color(0xFF0E0C18), onBackground = Color(0xFFE6E1F2),
    surface = Color(0xFF0E0C18), onSurface = Color(0xFFE6E1F2),
    surfaceVariant = Color(0xFF2A2740), onSurfaceVariant = Color(0xFFC9C4DC),
    outline = Color(0xFF938EA8), outlineVariant = Color(0xFF3F3B55),
    surfaceContainerLowest = Color(0xFF09080F), surfaceContainerLow = Color(0xFF16132A),
    surfaceContainer = Color(0xFF1B1832), surfaceContainerHigh = Color(0xFF24203D),
    surfaceContainerHighest = Color(0xFF2E2A49),
    error = Color(0xFFFF6B81),
)

private val KerbosTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val KerbosShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun KerbosTheme(themePref: Int, amoled: Boolean = false, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (themePref) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    val ctx = LocalContext.current
    var scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    if (dark && amoled) scheme = scheme.copy(
        background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0D0B14), surfaceContainer = Color(0xFF12101C),
        surfaceContainerHigh = Color(0xFF1A1727), surfaceContainerHighest = Color(0xFF221E33),
    )
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = KerbosTypography,
        shapes = KerbosShapes,
        content = content,
    )
}
