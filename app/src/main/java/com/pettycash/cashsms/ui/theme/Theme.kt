package com.pettycash.cashsms.ui.theme

import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Couleurs locales (pas de conflit avec Color.kt)
private val PrimaryLight = Blue600
private val OnPrimaryLight = White
private val PrimaryContainerLight = BlueContainerLight
private val OnPrimaryContainerLight = Color(0xFF001D36)

private val SecondaryLight = Color(0xFF5E5E5E)
private val OnSecondaryLight = White
private val SecondaryContainerLight = Color(0xFFE8EAED)
private val OnSecondaryContainerLight = Color(0xFF1A1C1E)

private val TertiaryLight = Color(0xFF00BFA5)
private val OnTertiaryLight = White

private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceVariantLightTheme = Color(0xFFF8F9FA)
private val BackgroundLight = Color(0xFFF5F5F5)
private val OnSurfaceLight = Color(0xFF1A1C1E)
private val OnSurfaceVariantLight = Color(0xFF5F6368)

private val ErrorLight = ErrorRed
private val OnErrorLight = White
private val ErrorContainerLight = Color(0xFFFFDAD6)

private val PrimaryDark = Color(0xFF8AB4F8)
private val OnPrimaryDark = Color(0xFF062E6F)
private val PrimaryContainerDark = Color(0xFF1B6EF3)
private val OnPrimaryContainerDark = Color(0xFFD3E4FF)

private val SecondaryDark = Color(0xFFBDC1C6)
private val OnSecondaryDark = Color(0xFF2E3133)
private val SecondaryContainerDark = Color(0xFF3C4043)
private val OnSecondaryContainerDark = Color(0xFFE8EAED)

private val TertiaryDark = Color(0xFF4DB6AC)
private val OnTertiaryDark = Color(0xFF003630)

private val SurfaceDark = Color(0xFF1F1F1F)
private val SurfaceVariantDarkTheme = Color(0xFF2C2C2E)
private val BackgroundDark = Color(0xFF121212)
private val OnSurfaceDark = Color(0xFFE2E2E6)
private val OnSurfaceVariantDark = Color(0xFF9AA0A6)

private val ErrorDark = Color(0xFFFF897D)
private val OnErrorDark = Color(0xFF601410)

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLightTheme,
    onSurfaceVariant = OnSurfaceVariantLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
    scrim = Color(0xFF000000)
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDarkTheme,
    onSurfaceVariant = OnSurfaceVariantDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    error = ErrorDark,
    onError = OnErrorDark,
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
    scrim = Color(0xFF000000)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun GoogleMessagesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && VERSION.SDK_INT >= VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = GoogleTypography,
        content = content
    )
}
