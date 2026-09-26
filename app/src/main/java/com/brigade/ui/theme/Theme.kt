package com.brigade.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

// Brigade's palette, measured from the Wulin campaign's background painting — Song-dynasty
// ink and watercolour on xuan paper — rather than chosen by eye.
//
// The painting is far more muted than it looks. Almost nothing in it clears 12 % saturation
// except the paper: the "blue-green" mountains measure as warm greys that only read cool
// against the yellower paper, and the lantern tassel, the one warm accent, is burnt sienna
// rather than red. So the accents here are sage and sienna, and the old cool blue — the one
// colour the painting has none of — is gone.
//
//   paper      #E4D6BF   the xuan ground        ink        #393022   branch ink, darkest
//   blossom    #EEE3CF   paper, highlighted     ink, mid   #6E6049   washes and wood
//   mountains  #A29D8C   warm grey              foliage    #5D6151   olive-grey green
//   tassel     #997150   burnt sienna
//
// Two schemes, following the tablet's dark mode: Papier to prepare in daylight, Encre at an
// evening table, where a bright tablet dazzles the GM and dulls the player screen beside it.
// Every role Material 3 components read is set explicitly, because a role left out falls
// back to Material's purple baseline — which the idle INFO and SABLIER buttons, reading
// `secondaryContainer`, had been showing all along.
//
// Faction colour is `tertiary` (sienna) and deliberately not `error` (cinnabar): the error
// red marks a missing slot in the control bar directly below the note bar, and one colour
// meaning "broken" beside another meaning "faction" would read as the same signal.

/** Papier: the painting's own paper, with ink for text. */
private val Papier = lightColorScheme(
    primary = Color(0xFF4F5A45),
    onPrimary = Color(0xFFF1E8D8),
    primaryContainer = Color(0xFFCBD1B8),
    onPrimaryContainer = Color(0xFF20271A),
    inversePrimary = Color(0xFFA9B79F),
    secondary = Color(0xFF6E6049),
    onSecondary = Color(0xFFF1E8D8),
    secondaryContainer = Color(0xFFDCCBA8),
    onSecondaryContainer = Color(0xFF2B2317),
    tertiary = Color(0xFF7E5130),
    onTertiary = Color(0xFFFBF0E4),
    tertiaryContainer = Color(0xFFE9CDB0),
    onTertiaryContainer = Color(0xFF2E1A0A),
    background = Color(0xFFE4D6BF),
    onBackground = Color(0xFF2E271C),
    surface = Color(0xFFEBDFCB),
    onSurface = Color(0xFF2E271C),
    surfaceVariant = Color(0xFFD8C8AC),
    onSurfaceVariant = Color(0xFF5E5140),
    surfaceTint = Color(0xFF4F5A45),
    inverseSurface = Color(0xFF332C22),
    inverseOnSurface = Color(0xFFEDE2CF),
    error = Color(0xFF9A3325),
    onError = Color(0xFFFFF1EC),
    errorContainer = Color(0xFFF3D2C9),
    onErrorContainer = Color(0xFF3E0E07),
    outline = Color(0xFF7F705A),
    outlineVariant = Color(0xFFC8B897),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF2E9DA),
    surfaceDim = Color(0xFFD6C7AE),
    surfaceContainerLowest = Color(0xFFF4ECDE),
    surfaceContainerLow = Color(0xFFEEE4D2),
    surfaceContainer = Color(0xFFE8DCC7),
    surfaceContainerHigh = Color(0xFFE2D4BC),
    surfaceContainerHighest = Color(0xFFDBCCB1),
)

/** Encre: the branch ink as ground, with paper for text. */
private val Encre = darkColorScheme(
    primary = Color(0xFFA9B79F),
    onPrimary = Color(0xFF1A1611),
    primaryContainer = Color(0xFF3A4334),
    onPrimaryContainer = Color(0xFFD9E3CC),
    inversePrimary = Color(0xFF4F5A45),
    secondary = Color(0xFFCDB894),
    onSecondary = Color(0xFF231E17),
    secondaryContainer = Color(0xFF463C2C),
    onSecondaryContainer = Color(0xFFEADBBE),
    tertiary = Color(0xFFC99467),
    onTertiary = Color(0xFF2A1A0E),
    tertiaryContainer = Color(0xFF5A3F28),
    onTertiaryContainer = Color(0xFFF2D9C0),
    background = Color(0xFF1A1611),
    onBackground = Color(0xFFE6D9C2),
    surface = Color(0xFF1F1A14),
    onSurface = Color(0xFFE6D9C2),
    surfaceVariant = Color(0xFF2C261D),
    onSurfaceVariant = Color(0xFFB9AA8E),
    surfaceTint = Color(0xFFA9B79F),
    inverseSurface = Color(0xFFE6D9C2),
    inverseOnSurface = Color(0xFF2A241C),
    // Redder and more saturated than the sienna tertiary, so a missing slot and a faction
    // name stay two different signals in dim light.
    error = Color(0xFFE26D5C),
    onError = Color(0xFF2A0F0A),
    errorContainer = Color(0xFF5C2219),
    onErrorContainer = Color(0xFFFAD8D0),
    outline = Color(0xFF6B5F4B),
    outlineVariant = Color(0xFF3B3327),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3228),
    surfaceDim = Color(0xFF1A1611),
    surfaceContainerLowest = Color(0xFF15120E),
    surfaceContainerLow = Color(0xFF1D1913),
    surfaceContainer = Color(0xFF221D16),
    surfaceContainerHigh = Color(0xFF2A241C),
    surfaceContainerHighest = Color(0xFF332C22),
)

/**
 * The strip behind a caption laid over an image — a thumbnail's name, the preview's zoom.
 *
 * The same in both schemes, because what lies under it is a picture and not the theme: warm
 * ink rather than pure black, with paper-white text rather than pure white, so a caption over
 * a painting reads as part of it.
 */
val ImageCaptionScrim = Color(0xA61A1611)
val OnImageCaption = Color(0xFFEEE3CF)

/**
 * Serif for headings and titles — the folder path, dialog titles — and the default sans for
 * everything dense.
 *
 * `FontFamily.Serif` is the system's own serif (Noto Serif on the tablet), so this bundles no
 * font file and fetches nothing: Brigade has no network permission and wants none.
 */
private val BrigadeTypography = Typography().let { base ->
    fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)
    base.copy(
        displayLarge = base.displayLarge.serif(),
        displayMedium = base.displayMedium.serif(),
        displaySmall = base.displaySmall.serif(),
        headlineLarge = base.headlineLarge.serif(),
        headlineMedium = base.headlineMedium.serif(),
        headlineSmall = base.headlineSmall.serif(),
        titleLarge = base.titleLarge.serif(),
        titleMedium = base.titleMedium.serif(),
        titleSmall = base.titleSmall.serif(),
    )
}

@Composable
fun BrigadeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) Encre else Papier,
        typography = BrigadeTypography,
        content = content,
    )
}
