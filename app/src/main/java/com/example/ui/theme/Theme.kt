package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = SpatialMint,
    onPrimary = OnSpatialMint,
    primaryContainer = SpatialMintContainer,
    onPrimaryContainer = SpatialMint,
    secondary = OpticalCobalt,
    onSecondary = ObsidianBackground,
    tertiary = WarmAmber,
    onTertiary = ObsidianBackground,
    error = ErrorCrimson,
    onError = PureChalk,
    background = ObsidianBackground,
    onBackground = PureChalk,
    surface = CharcoalSurface,
    onSurface = PureChalk,
    surfaceVariant = CharcoalElevated,
    onSurfaceVariant = SoftSlate,
    outline = HairlineBorder
)

val StudioShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ThreeDCameraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        shapes = StudioShapes,
        content = content
    )
}
