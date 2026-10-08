package com.ragnarok93.bbsremix.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp

internal val PatcherDarkColorScheme = darkColorScheme(
    primary = Color(0xFF258BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0D4F9D),
    onPrimaryContainer = Color(0xFFD9E9FF),
    secondary = Color(0xFFA8BAD5),
    onSecondary = Color(0xFF152238),
    secondaryContainer = Color(0xFF263750),
    onSecondaryContainer = Color(0xFFDCE8FA),
    background = Color(0xFF071321),
    onBackground = Color(0xFFF2F5FB),
    surface = Color(0xFF101B2C),
    onSurface = Color(0xFFF2F5FB),
    surfaceVariant = Color(0xFF1B2940),
    onSurfaceVariant = Color(0xFFA9B8CC),
    outline = Color(0xFF40536F),
    error = Color(0xFFFF8D9B),
    onError = Color(0xFF3F0710),
)

internal val PatcherLightColorScheme = lightColorScheme(
    primary = Color(0xFF006DDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E8FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF53657F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E4F8),
    onSecondaryContainer = Color(0xFF101C30),
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF101A2A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101A2A),
    surfaceVariant = Color(0xFFE5ECF7),
    onSurfaceVariant = Color(0xFF4A5A70),
    outline = Color(0xFF73839B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

internal val PatcherSuccess = Color(0xFF49E0A7)

@Composable
expect fun PatcherTheme(content: @Composable () -> Unit)

@Composable
expect fun PatcherSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
)

@Composable
expect fun PatcherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
expect fun PatcherCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
expect fun PatcherSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
expect fun PatcherProgress(
    progress: Float,
    modifier: Modifier = Modifier,
)

@Composable
expect fun PatcherSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)

@Composable
expect fun PatcherLevelSlider(
    level: Int,
    onLevelChange: (Int) -> Unit,
    minLevel: Int = 1,
    maxLevel: Int = 5,
    enabled: Boolean,
    modifier: Modifier = Modifier,
)

@Composable
expect fun PatcherIndeterminateProgress(modifier: Modifier = Modifier)

@Composable
expect fun PatcherNavigationBar(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
)

expect fun decodeCoverArt(bytes: ByteArray): ImageBitmap?
