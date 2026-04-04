package com.deepfakedetector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── Palette principale ───────────────────────────────────────────────────────
object DeepfakeColors {
    // Primaires
    val Electric        = Color(0xFF00E5FF)   // cyan vif (scan / accent)
    val ElectricDark    = Color(0xFF009BB5)
    val ElectricContainer = Color(0xFF003540)

    // Danger / Fake détecté
    val DangerRed       = Color(0xFFFF3D3D)
    val DangerContainer = Color(0xFF3D0000)

    // Warning
    val WarnAmber       = Color(0xFFFFAB00)
    val WarnContainer   = Color(0xFF3D2800)

    // Safe / Réel
    val SafeGreen       = Color(0xFF00E676)
    val SafeContainer   = Color(0xFF003D1A)

    // Fond sombre (thème principal)
    val Surface0        = Color(0xFF050A0F)   // fond le plus profond
    val Surface1        = Color(0xFF0D1520)   // cards
    val Surface2        = Color(0xFF131E2E)   // éléments surélevés
    val Surface3        = Color(0xFF1A2840)   // contours légers

    // Texte
    val OnDark          = Color(0xFFE8F4FF)
    val OnDarkSecondary = Color(0xFF8BA5BE)
}

private val DarkColorScheme = darkColorScheme(
    primary            = DeepfakeColors.Electric,
    onPrimary          = Color(0xFF00141A),
    primaryContainer   = DeepfakeColors.ElectricContainer,
    onPrimaryContainer = DeepfakeColors.Electric,

    secondary          = DeepfakeColors.SafeGreen,
    onSecondary        = Color(0xFF00200D),
    secondaryContainer = DeepfakeColors.SafeContainer,
    onSecondaryContainer = DeepfakeColors.SafeGreen,

    tertiary           = DeepfakeColors.WarnAmber,
    onTertiary         = Color(0xFF201400),
    tertiaryContainer  = DeepfakeColors.WarnContainer,
    onTertiaryContainer = DeepfakeColors.WarnAmber,

    error              = DeepfakeColors.DangerRed,
    onError            = Color(0xFF200000),
    errorContainer     = DeepfakeColors.DangerContainer,
    onErrorContainer   = DeepfakeColors.DangerRed,

    background         = DeepfakeColors.Surface0,
    onBackground       = DeepfakeColors.OnDark,

    surface            = DeepfakeColors.Surface1,
    onSurface          = DeepfakeColors.OnDark,
    surfaceVariant     = DeepfakeColors.Surface2,
    onSurfaceVariant   = DeepfakeColors.OnDarkSecondary,

    outline            = DeepfakeColors.Surface3,
    outlineVariant     = Color(0xFF0D1520),
)

private val LightColorScheme = lightColorScheme(
    primary            = DeepfakeColors.ElectricDark,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFB3F0FF),
    onPrimaryContainer = Color(0xFF001F27),

    secondary          = Color(0xFF006E30),
    onSecondary        = Color.White,
    secondaryContainer = Color(0xFF98F5BC),
    onSecondaryContainer = Color(0xFF00210C),

    tertiary           = Color(0xFF7B5700),
    onTertiary         = Color.White,
    tertiaryContainer  = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),

    error              = Color(0xFFBA1A1A),
    onError            = Color.White,

    background         = Color(0xFFF6FAFB),
    onBackground       = Color(0xFF0D1C20),

    surface            = Color(0xFFFFFFFF),
    onSurface          = Color(0xFF0D1C20),
)

@Composable
fun DeepfakeDetectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
