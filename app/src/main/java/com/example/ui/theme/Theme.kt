package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.ThemePalette

fun buildChatsoColorScheme(
    darkTheme: Boolean,
    palette: ThemePalette,
    advanced: Boolean = false
): ColorScheme {
    val spec = palette.spec()
    return if (darkTheme) {
        val accent = spec.dark
        val tintSource = accent.primaryContainer
        val background = if (advanced) {
            lerp(SoftCharcoalBackgroundDark, tintSource, 0.46f)
        } else {
            SoftCharcoalBackgroundDark
        }
        val surface = if (advanced) {
            lerp(DeepSurfaceDark, tintSource, 0.56f)
        } else {
            DeepSurfaceDark
        }
        val surfaceVariant = if (advanced) {
            lerp(SurfaceVariantDark, tintSource, 0.64f)
        } else {
            SurfaceVariantDark
        }
        val surfaceLow = if (advanced) {
            lerp(Color(0xFF202430), tintSource, 0.48f)
        } else {
            Color(0xFF202430)
        }
        val surfaceContainer = if (advanced) {
            lerp(Color(0xFF262B3A), tintSource, 0.58f)
        } else {
            Color(0xFF262B3A)
        }
        val surfaceHigh = if (advanced) {
            lerp(Color(0xFF2E3446), tintSource, 0.68f)
        } else {
            Color(0xFF2E3446)
        }
        val surfaceHighest = if (advanced) {
            lerp(Color(0xFF363D52), tintSource, 0.78f)
        } else {
            Color(0xFF363D52)
        }
        val secondary = if (advanced) {
            lerp(SlateSecondaryDark, accent.primary, 0.58f)
        } else {
            SlateSecondaryDark
        }
        val secondaryContainer = if (advanced) {
            lerp(SlateSecondaryContainerDark, tintSource, 0.72f)
        } else {
            SlateSecondaryContainerDark
        }
        val onSecondaryContainer = if (advanced) {
            accent.onPrimaryContainer
        } else {
            OnSlateSecondaryContainerDark
        }
        val outline = if (advanced) {
            lerp(OutlineDark, accent.primary, 0.42f)
        } else {
            OutlineDark
        }
        val outlineVariant = if (advanced) {
            lerp(OutlineVariantDark, accent.primary, 0.28f)
        } else {
            OutlineVariantDark
        }
        val onSurfaceVariant = if (advanced) {
            lerp(OnSurfaceVariantDark, accent.primary, 0.22f)
        } else {
            OnSurfaceVariantDark
        }

        darkColorScheme(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            primaryContainer = accent.primaryContainer,
            onPrimaryContainer = accent.onPrimaryContainer,
            secondary = secondary,
            onSecondary = OnSlateSecondaryDark,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = accent.tertiary,
            onTertiary = accent.onTertiary,
            tertiaryContainer = accent.tertiaryContainer,
            onTertiaryContainer = accent.onTertiaryContainer,
            background = background,
            onBackground = OnSoftCharcoalBackgroundDark,
            surface = surface,
            onSurface = OnDeepSurfaceDark,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
            outline = outline,
            outlineVariant = outlineVariant,
            error = DangerCoral,
            onError = Color.White
        )
    } else {
        val accent = spec.light
        val tintSource = accent.primaryContainer
        val background = if (advanced) {
            lerp(IceBackgroundLight, tintSource, 0.65f)
        } else {
            IceBackgroundLight
        }
        val surface = if (advanced) {
            lerp(PureSurfaceLight, tintSource, 0.34f)
        } else {
            PureSurfaceLight
        }
        val surfaceVariant = if (advanced) {
            lerp(SurfaceVariantLight, tintSource, 0.72f)
        } else {
            SurfaceVariantLight
        }
        val surfaceLow = if (advanced) {
            lerp(Color(0xFFF3F6F9), tintSource, 0.48f)
        } else {
            Color(0xFFF3F6F9)
        }
        val surfaceContainer = if (advanced) {
            lerp(Color(0xFFEEF2F6), tintSource, 0.64f)
        } else {
            Color(0xFFEEF2F6)
        }
        val surfaceHigh = if (advanced) {
            lerp(Color(0xFFE6ECF2), tintSource, 0.78f)
        } else {
            Color(0xFFE6ECF2)
        }
        val surfaceHighest = if (advanced) {
            lerp(Color(0xFFDCE4EC), tintSource, 0.88f)
        } else {
            Color(0xFFDCE4EC)
        }
        val secondary = if (advanced) {
            lerp(SlateSecondaryLight, accent.primary, 0.56f)
        } else {
            SlateSecondaryLight
        }
        val secondaryContainer = if (advanced) {
            lerp(SlateSecondaryContainerLight, tintSource, 0.80f)
        } else {
            SlateSecondaryContainerLight
        }
        val onSecondaryContainer = if (advanced) {
            accent.onPrimaryContainer
        } else {
            OnSlateSecondaryContainerLight
        }
        val outline = if (advanced) {
            lerp(OutlineLight, accent.primary, 0.36f)
        } else {
            OutlineLight
        }
        val outlineVariant = if (advanced) {
            lerp(OutlineVariantLight, accent.primary, 0.22f)
        } else {
            OutlineVariantLight
        }
        val onSurfaceVariant = if (advanced) {
            lerp(OnSurfaceVariantLight, accent.onPrimaryContainer, 0.28f)
        } else {
            OnSurfaceVariantLight
        }

        lightColorScheme(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            primaryContainer = accent.primaryContainer,
            onPrimaryContainer = accent.onPrimaryContainer,
            secondary = secondary,
            onSecondary = OnSlateSecondaryLight,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = accent.tertiary,
            onTertiary = accent.onTertiary,
            tertiaryContainer = accent.tertiaryContainer,
            onTertiaryContainer = accent.onTertiaryContainer,
            background = background,
            onBackground = OnIceBackgroundLight,
            surface = surface,
            onSurface = OnPureSurfaceLight,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
            outline = outline,
            outlineVariant = outlineVariant,
            error = DangerCoral,
            onError = Color.White
        )
    }
}

val ChatsoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ChatsoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.PURPLE,
    advanced: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = buildChatsoColorScheme(
        darkTheme = darkTheme,
        palette = palette,
        advanced = advanced
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ChatsoShapes,
        content = content
    )
}
