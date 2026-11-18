package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val KatemDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = OnEmeraldDark,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = OnEmeraldContainerDark,
    secondary = CyanAccent,
    onSecondary = ObsidianCanvas,
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = OnCyanContainerDark,
    tertiary = AmberWarm,
    onTertiary = ObsidianCanvas,
    background = ObsidianCanvas,
    onBackground = AlabasterText,
    surface = ObsidianSurface,
    onSurface = AlabasterText,
    surfaceVariant = ObsidianSurfaceContainer,
    onSurfaceVariant = SlateMutedText,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceElevated,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
    error = CoralDanger,
    onError = ObsidianCanvas,
    errorContainer = CoralContainerDark,
    onErrorContainer = CoralDanger
)

private val KatemLightColorScheme = lightColorScheme(
    primary = ForestEmeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = ForestEmeraldContainer,
    onPrimaryContainer = OnForestEmeraldContainer,
    secondary = TealAccentLight,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = OnTealContainerLight,
    tertiary = AmberWarm,
    onTertiary = Color.White,
    background = PearlCanvas,
    onBackground = InkPrimaryLight,
    surface = PearlSurface,
    onSurface = InkPrimaryLight,
    surfaceVariant = PearlSurfaceContainer,
    onSurfaceVariant = InkMutedLight,
    surfaceContainerLowest = PearlSurface,
    surfaceContainerLow = PearlCanvas,
    surfaceContainer = PearlSurfaceContainer,
    surfaceContainerHigh = PearlSurfaceElevated,
    outline = PearlOutline,
    outlineVariant = PearlSurfaceElevated,
    error = CrimsonDangerLight,
    onError = Color.White,
    errorContainer = CrimsonContainerLight,
    onErrorContainer = CrimsonDangerLight
)

val KatemShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

data class KatemChatColors(
    val outgoingStart: Color,
    val outgoingEnd: Color,
    val outgoingText: Color,
    val incomingBg: Color,
    val incomingText: Color,
    val readTick: Color,
    val onlineDot: Color,
    val isDark: Boolean
)

val LocalKatemChatColors = staticCompositionLocalOf {
    KatemChatColors(
        outgoingStart = BubbleOutgoingStartDark,
        outgoingEnd = BubbleOutgoingEndDark,
        outgoingText = Color.White,
        incomingBg = BubbleIncomingDark,
        incomingText = AlabasterText,
        readTick = EmeraldBright,
        onlineDot = EmeraldPrimary,
        isDark = true
    )
}

@Composable
fun KatemTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KatemDarkColorScheme else KatemLightColorScheme
    val chatColors = if (darkTheme) {
        KatemChatColors(
            outgoingStart = BubbleOutgoingStartDark,
            outgoingEnd = BubbleOutgoingEndDark,
            outgoingText = Color.White,
            incomingBg = BubbleIncomingDark,
            incomingText = AlabasterText,
            readTick = EmeraldBright,
            onlineDot = EmeraldPrimary,
            isDark = true
        )
    } else {
        KatemChatColors(
            outgoingStart = BubbleOutgoingStartLight,
            outgoingEnd = BubbleOutgoingEndLight,
            outgoingText = Color.White,
            incomingBg = BubbleIncomingLight,
            incomingText = InkPrimaryLight,
            readTick = Color(0xFF6EE7B7),
            onlineDot = ForestEmeraldPrimary,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalKatemChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KatemTypography,
            shapes = KatemShapes,
            content = content
        )
    }
}
