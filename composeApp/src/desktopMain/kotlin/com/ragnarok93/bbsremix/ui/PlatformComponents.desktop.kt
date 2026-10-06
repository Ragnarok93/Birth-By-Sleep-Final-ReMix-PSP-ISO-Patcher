package com.ragnarok93.bbsremix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val OneUiBlue = Color(0xFF2F6FED)
private val OneUiLightBackground = Color(0xFFF7F7F7)
private val OneUiDarkBackground = Color(0xFF121212)

@Composable
actual fun PatcherTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(primary = OneUiBlue, background = OneUiDarkBackground, surface = Color(0xFF202124))
        } else {
            lightColorScheme(primary = OneUiBlue, background = OneUiLightBackground, surface = Color.White)
        },
        content = content,
    )
}

@Composable
actual fun PatcherSurface(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 1.dp,
        content = { Box(content = content) },
    )
}

@Composable
actual fun PatcherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
}

@Composable
actual fun PatcherCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    androidx.compose.foundation.layout.Row(modifier) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
    }
}

@Composable
actual fun PatcherProgress(progress: Float, modifier: Modifier) {
    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier)
}
