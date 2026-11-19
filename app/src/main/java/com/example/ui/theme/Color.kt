package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.ThemePalette

data class PaletteAccentTokens(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color
)

data class PaletteSpec(
    val light: PaletteAccentTokens,
    val dark: PaletteAccentTokens,
    val swatchPrimary: Color,
    val swatchSecondary: Color
)

// Neutral Light & Softer Dark Surfaces (no harsh pitch black)
val SlateSecondaryLight = Color(0xFF334155)
val OnSlateSecondaryLight = Color(0xFFFFFFFF)
val SlateSecondaryContainerLight = Color(0xFFE2E8F0)
val OnSlateSecondaryContainerLight = Color(0xFF0F172A)

val IceBackgroundLight = Color(0xFFF6F8FB)
val OnIceBackgroundLight = Color(0xFF0F172A)
val PureSurfaceLight = Color(0xFFFFFFFF)
val OnPureSurfaceLight = Color(0xFF0F172A)
val SurfaceVariantLight = Color(0xFFEEF2F6)
val OnSurfaceVariantLight = Color(0xFF475569)
val OutlineLight = Color(0xFFCBD5E1)
val OutlineVariantLight = Color(0xFFE2E8F0)

val SlateSecondaryDark = Color(0xFF9AA5BD)
val OnSlateSecondaryDark = Color(0xFF131722)
val SlateSecondaryContainerDark = Color(0xFF282E3F)
val OnSlateSecondaryContainerDark = Color(0xFFE5EAF5)

val SoftCharcoalBackgroundDark = Color(0xFF1A1D26)
val OnSoftCharcoalBackgroundDark = Color(0xFFE8ECF5)
val DeepSurfaceDark = Color(0xFF222634)
val OnDeepSurfaceDark = Color(0xFFECEFF7)
val SurfaceVariantDark = Color(0xFF2A2F40)
val OnSurfaceVariantDark = Color(0xFFA7B0C4)
val OutlineDark = Color(0xFF454D66)
val OutlineVariantDark = Color(0xFF30364A)

// Semantic Status Accents
val OnlineEmerald = Color(0xFF10B981)
val StealthIndigo = Color(0xFF6366F1)
val WarningAmber = Color(0xFFF59E0B)
val DangerCoral = Color(0xFFEF4444)

private val PurplePaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF6D28D9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEDE9FE),
        onPrimaryContainer = Color(0xFF2E1065),
        tertiary = Color(0xFFA21CAF),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFAE8FF),
        onTertiaryContainer = Color(0xFF4A044E)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFA78BFA),
        onPrimary = Color(0xFF1E0B4B),
        primaryContainer = Color(0xFF4C1D95),
        onPrimaryContainer = Color(0xFFEDE9FE),
        tertiary = Color(0xFFF0ABFC),
        onTertiary = Color(0xFF4A044E),
        tertiaryContainer = Color(0xFF701A75),
        onTertiaryContainer = Color(0xFFFAE8FF)
    ),
    swatchPrimary = Color(0xFF7C3AED),
    swatchSecondary = Color(0xFFC4B5FD)
)

private val LilacPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF9D4EDD),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF5E8FF),
        onPrimaryContainer = Color(0xFF3C096C),
        tertiary = Color(0xFFDB2777),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFCE7F3),
        onTertiaryContainer = Color(0xFF500724)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFD8B4FE),
        onPrimary = Color(0xFF2E1065),
        primaryContainer = Color(0xFF6B21A8),
        onPrimaryContainer = Color(0xFFFAF5FF),
        tertiary = Color(0xFFF9A8D4),
        onTertiary = Color(0xFF500724),
        tertiaryContainer = Color(0xFF831843),
        onTertiaryContainer = Color(0xFFFCE7F3)
    ),
    swatchPrimary = Color(0xFFC084FC),
    swatchSecondary = Color(0xFFF3E8FF)
)

private val IndigoPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF4338CA),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0E7FF),
        onPrimaryContainer = Color(0xFF1E1B4B),
        tertiary = Color(0xFF0284C7),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0F2FE),
        onTertiaryContainer = Color(0xFF082F49)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFF818CF8),
        onPrimary = Color(0xFF1E1B4B),
        primaryContainer = Color(0xFF3730A3),
        onPrimaryContainer = Color(0xFFE0E7FF),
        tertiary = Color(0xFF38BDF8),
        onTertiary = Color(0xFF082F49),
        tertiaryContainer = Color(0xFF075985),
        onTertiaryContainer = Color(0xFFE0F2FE)
    ),
    swatchPrimary = Color(0xFF4F46E5),
    swatchSecondary = Color(0xFFC7D2FE)
)

private val BluePaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF0284C7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0F2FE),
        onPrimaryContainer = Color(0xFF082F49),
        tertiary = Color(0xFF4F46E5),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E7FF),
        onTertiaryContainer = Color(0xFF1E1B4B)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF082F49),
        primaryContainer = Color(0xFF075985),
        onPrimaryContainer = Color(0xFFE0F2FE),
        tertiary = Color(0xFF818CF8),
        onTertiary = Color(0xFF1E1B4B),
        tertiaryContainer = Color(0xFF312E81),
        onTertiaryContainer = Color(0xFFE0E7FF)
    ),
    swatchPrimary = Color(0xFF0EA5E9),
    swatchSecondary = Color(0xFFBAE6FD)
)

private val CyanPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF0891B2),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCFFAFE),
        onPrimaryContainer = Color(0xFF083344),
        tertiary = Color(0xFF2563EB),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDBEAFE),
        onTertiaryContainer = Color(0xFF1E3A8A)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFF22D3EE),
        onPrimary = Color(0xFF083344),
        primaryContainer = Color(0xFF155E75),
        onPrimaryContainer = Color(0xFFCFFAFE),
        tertiary = Color(0xFF60A5FA),
        onTertiary = Color(0xFF1E3A8A),
        tertiaryContainer = Color(0xFF1E40AF),
        onTertiaryContainer = Color(0xFFDBEAFE)
    ),
    swatchPrimary = Color(0xFF06B6D4),
    swatchSecondary = Color(0xFFA5F3FC)
)

private val TealPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF0F766E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCCFBF1),
        onPrimaryContainer = Color(0xFF042F2E),
        tertiary = Color(0xFF4F46E5),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E7FF),
        onTertiaryContainer = Color(0xFF1E1B4B)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFF2DD4BF),
        onPrimary = Color(0xFF042F2E),
        primaryContainer = Color(0xFF115E59),
        onPrimaryContainer = Color(0xFFF0FDFA),
        tertiary = Color(0xFFA5B4FC),
        onTertiary = Color(0xFF1E1B4B),
        tertiaryContainer = Color(0xFF312E81),
        onTertiaryContainer = Color(0xFFE0E7FF)
    ),
    swatchPrimary = Color(0xFF0F766E),
    swatchSecondary = Color(0xFF99F6E4)
)

private val EmeraldPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF059669),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD1FAE5),
        onPrimaryContainer = Color(0xFF022C22),
        tertiary = Color(0xFF0284C7),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0F2FE),
        onTertiaryContainer = Color(0xFF082F49)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFF34D399),
        onPrimary = Color(0xFF022C22),
        primaryContainer = Color(0xFF065F46),
        onPrimaryContainer = Color(0xFFD1FAE5),
        tertiary = Color(0xFF38BDF8),
        onTertiary = Color(0xFF082F49),
        tertiaryContainer = Color(0xFF075985),
        onTertiaryContainer = Color(0xFFE0F2FE)
    ),
    swatchPrimary = Color(0xFF10B981),
    swatchSecondary = Color(0xFFA7F3D0)
)

private val LimePaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFF4D7C0F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFECFCCB),
        onPrimaryContainer = Color(0xFF1A2E05),
        tertiary = Color(0xFF0D9488),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFCCFBF1),
        onTertiaryContainer = Color(0xFF042F2E)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFA3E635),
        onPrimary = Color(0xFF1A2E05),
        primaryContainer = Color(0xFF3F6212),
        onPrimaryContainer = Color(0xFFECFCCB),
        tertiary = Color(0xFF2DD4BF),
        onTertiary = Color(0xFF042F2E),
        tertiaryContainer = Color(0xFF115E59),
        onTertiaryContainer = Color(0xFFF0FDFA)
    ),
    swatchPrimary = Color(0xFF84CC16),
    swatchSecondary = Color(0xFFD9F99D)
)

private val AmberPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFFB45309),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFEF3C7),
        onPrimaryContainer = Color(0xFF451A03),
        tertiary = Color(0xFFEA580C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFEDD5),
        onTertiaryContainer = Color(0xFF431407)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFFBBF24),
        onPrimary = Color(0xFF451A03),
        primaryContainer = Color(0xFF92400E),
        onPrimaryContainer = Color(0xFFFEF3C7),
        tertiary = Color(0xFFFB923C),
        onTertiary = Color(0xFF431407),
        tertiaryContainer = Color(0xFF9A3412),
        onTertiaryContainer = Color(0xFFFFEDD5)
    ),
    swatchPrimary = Color(0xFFF59E0B),
    swatchSecondary = Color(0xFFFDE68A)
)

private val OrangePaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFFEA580C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFEDD5),
        onPrimaryContainer = Color(0xFF431407),
        tertiary = Color(0xFFD97706),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFEF3C7),
        onTertiaryContainer = Color(0xFF451A03)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFFB923C),
        onPrimary = Color(0xFF431407),
        primaryContainer = Color(0xFF9A3412),
        onPrimaryContainer = Color(0xFFFFEDD5),
        tertiary = Color(0xFFFBBF24),
        onTertiary = Color(0xFF451A03),
        tertiaryContainer = Color(0xFF78350F),
        onTertiaryContainer = Color(0xFFFEF3C7)
    ),
    swatchPrimary = Color(0xFFF97316),
    swatchSecondary = Color(0xFFFED7AA)
)

private val CoralPaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFFDC2626),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFEE2E2),
        onPrimaryContainer = Color(0xFF450A0A),
        tertiary = Color(0xFFEA580C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFEDD5),
        onTertiaryContainer = Color(0xFF431407)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFF87171),
        onPrimary = Color(0xFF450A0A),
        primaryContainer = Color(0xFF991B1B),
        onPrimaryContainer = Color(0xFFFEE2E2),
        tertiary = Color(0xFFFB923C),
        onTertiary = Color(0xFF431407),
        tertiaryContainer = Color(0xFF9A3412),
        onTertiaryContainer = Color(0xFFFFEDD5)
    ),
    swatchPrimary = Color(0xFFEF4444),
    swatchSecondary = Color(0xFFFECACA)
)

private val RosePaletteSpec = PaletteSpec(
    light = PaletteAccentTokens(
        primary = Color(0xFFE11D48),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE4E6),
        onPrimaryContainer = Color(0xFF4C0519),
        tertiary = Color(0xFF9333EA),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF3E8FF),
        onTertiaryContainer = Color(0xFF3B0764)
    ),
    dark = PaletteAccentTokens(
        primary = Color(0xFFFB7185),
        onPrimary = Color(0xFF4C0519),
        primaryContainer = Color(0xFF9F1239),
        onPrimaryContainer = Color(0xFFFFE4E6),
        tertiary = Color(0xFFC084FC),
        onTertiary = Color(0xFF3B0764),
        tertiaryContainer = Color(0xFF581C87),
        onTertiaryContainer = Color(0xFFF3E8FF)
    ),
    swatchPrimary = Color(0xFFF43F5E),
    swatchSecondary = Color(0xFFFECDD3)
)

fun ThemePalette.spec(): PaletteSpec = when (this) {
    ThemePalette.PURPLE -> PurplePaletteSpec
    ThemePalette.LILAC -> LilacPaletteSpec
    ThemePalette.INDIGO -> IndigoPaletteSpec
    ThemePalette.BLUE -> BluePaletteSpec
    ThemePalette.CYAN -> CyanPaletteSpec
    ThemePalette.TEAL -> TealPaletteSpec
    ThemePalette.EMERALD -> EmeraldPaletteSpec
    ThemePalette.LIME -> LimePaletteSpec
    ThemePalette.AMBER -> AmberPaletteSpec
    ThemePalette.ORANGE -> OrangePaletteSpec
    ThemePalette.CORAL -> CoralPaletteSpec
    ThemePalette.ROSE -> RosePaletteSpec
}
