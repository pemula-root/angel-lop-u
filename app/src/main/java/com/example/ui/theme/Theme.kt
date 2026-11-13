package com.example.ui.theme

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.example.core.data.repository.ThemeStyle

@Composable
fun UnMusicTheme(
    themeStyle: ThemeStyle = ThemeStyle.DYNAMIC,
    dominantColor: Color = Color(0xFFE4E4E7),
    content: @Composable () -> Unit
) {
    val isLightTheme = themeStyle == ThemeStyle.LIGHT
    if (UnColors.isLight != isLightTheme) UnColors.isLight = isLightTheme

    val normalizedPrimary = remember(dominantColor) { normalizeAccentColor(dominantColor) }

    val animatedPrimary by animateColorAsState(
        targetValue = normalizedPrimary,
        animationSpec = tween(durationMillis = 400),
        label = "primaryThemeColor"
    )

    // Blend dominant color into background and surface for organic album tint (6% - 10%)
    val tintedBackground = remember(animatedPrimary) {
        blendColors(DarkBackground, animatedPrimary, 0.06f)
    }
    val tintedSurface = remember(animatedPrimary) {
        blendColors(DarkSurface, animatedPrimary, 0.09f)
    }
    val tintedSurfaceVariant = remember(animatedPrimary) {
        blendColors(DarkSurfaceVariant, animatedPrimary, 0.12f)
    }

    val onPrimaryColor = if (animatedPrimary.luminance() > 0.5f) Color.Black else Color.White

    val colorScheme = when (themeStyle) {
        ThemeStyle.AMOLED -> {
            darkColorScheme(
                primary = animatedPrimary,
                onPrimary = onPrimaryColor,
                primaryContainer = animatedPrimary.copy(alpha = 0.2f),
                onPrimaryContainer = animatedPrimary,
                background = AmoledBackground,
                onBackground = Color(0xFFF4F4F5),
                surface = AmoledSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = AmoledSurfaceVariant,
                onSurfaceVariant = SecondaryText
            )
        }
        ThemeStyle.LIGHT -> {
            lightColorScheme(
                primary = animatedPrimary,
                onPrimary = onPrimaryColor,
                primaryContainer = animatedPrimary.copy(alpha = 0.15f),
                onPrimaryContainer = Color.Black,
                background = LightBackground,
                onBackground = Color(0xFF09090B),
                surface = LightSurface,
                onSurface = Color(0xFF09090B),
                surfaceVariant = LightSurfaceVariant,
                onSurfaceVariant = Color(0xFF71717A)
            )
        }
        ThemeStyle.DYNAMIC -> {
            darkColorScheme(
                primary = animatedPrimary,
                onPrimary = onPrimaryColor,
                primaryContainer = animatedPrimary.copy(alpha = 0.25f),
                onPrimaryContainer = Color.White,
                background = tintedBackground,
                onBackground = Color(0xFFF4F4F5),
                surface = tintedSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = tintedSurfaceVariant,
                onSurfaceVariant = SecondaryText
            )
        }
        ThemeStyle.DARK -> {
            darkColorScheme(
                primary = animatedPrimary,
                onPrimary = onPrimaryColor,
                primaryContainer = animatedPrimary.copy(alpha = 0.2f),
                onPrimaryContainer = Color.White,
                background = DarkBackground,
                onBackground = Color(0xFFF4F4F5),
                surface = DarkSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = DarkSurfaceVariant,
                onSurfaceVariant = SecondaryText
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private fun blendColors(base: Color, tint: Color, ratio: Float): Color {
    val r = base.red + (tint.red - base.red) * ratio
    val g = base.green + (tint.green - base.green) * ratio
    val b = base.blue + (tint.blue - base.blue) * ratio
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)
}

/**
 * Menormalkan warna aksen agar selalu terbaca di latar gelap.
 * Warna abu-abu / netral dijadikan putih, warna lain dibuat cukup terang dan jenuh.
 */
fun normalizeAccentColor(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < 0.18f) return Color(0xFFF4F4F5)
    hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
    hsv[2] = hsv[2].coerceIn(0.78f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Versi gelap dari aksen untuk latar gradien, supaya teks putih tetap kontras. */
fun deepenAccent(color: Color, valueFactor: Float = 0.45f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    val factor = if (hsv[1] < 0.18f) 0.28f else valueFactor
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Warna teks yang mengikuti tema untuk layar daftar (Library, Settings, dst).
 * Putih di tema gelap, hampir hitam di tema terang. Layar yang selalu di atas latar gelap
 * (pemutar penuh, mode mobil) tetap memakai putih langsung.
 */
object UnColors {
    var isLight by mutableStateOf(false)

    val text: Color
        get() = if (isLight) Color(0xFF09090B) else Color.White
}
