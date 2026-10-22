package com.karelisio.speech.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Palette Speech (indigo + sarcelle), utilisée quand les couleurs Material You sont désactivées. */
val SpeechLight = lightColorScheme(
    primary = Color(0xFF5144D3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF15005E),
    secondary = Color(0xFF5D5B72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3DFF9),
    onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Color(0xFF00897F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8F0E8),
    onTertiaryContainer = Color(0xFF00201D),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE5E0EF),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF7),
    surfaceContainerHigh = Color(0xFFEAE6F1),
    surfaceContainerHighest = Color(0xFFE5E1EB),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFC9C4D0),
)

val SpeechDark = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF250F9A),
    primaryContainer = Color(0xFF3C2EBD),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFC7C3DD),
    onSecondary = Color(0xFF2F2E42),
    secondaryContainer = Color(0xFF454459),
    onSecondaryContainer = Color(0xFFE3DFF9),
    tertiary = Color(0xFF5DDBCD),
    onTertiary = Color(0xFF003732),
    tertiaryContainer = Color(0xFF005049),
    onTertiaryContainer = Color(0xFFB8F0E8),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131218),
    onBackground = Color(0xFFE5E1EA),
    surface = Color(0xFF131218),
    onSurface = Color(0xFFE5E1EA),
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceContainerLowest = Color(0xFF0E0D13),
    surfaceContainerLow = Color(0xFF1C1B21),
    surfaceContainer = Color(0xFF201F25),
    surfaceContainerHigh = Color(0xFF2A2930),
    surfaceContainerHighest = Color(0xFF35343B),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F),
)

/** Couleur d'enregistrement (rouge corail), commune aux deux thèmes. */
val RecordRed = Color(0xFFE5484D)
val RecordOrange = Color(0xFFFF8A4C)
