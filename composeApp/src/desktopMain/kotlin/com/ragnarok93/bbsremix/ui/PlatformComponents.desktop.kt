package com.ragnarok93.bbsremix.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private val RemixBlue = Color(0xFF1E8FFF)
private val RemixBlueSoft = Color(0xFF6AAEFF)
private val RemixBackground = Color(0xFF06111F)
private val RemixSurface = Color(0xFF101D2D)
private val RemixSurfaceVariant = Color(0xFF18283B)
private val RemixText = Color(0xFFF4F7FF)
private val RemixTextMuted = Color(0xFFA7B4C9)
private val RemixOutline = Color(0xFF334965)

@Composable
actual fun PatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RemixBlue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF123E6D),
            onPrimaryContainer = RemixText,
            secondary = RemixBlueSoft,
            background = RemixBackground,
            onBackground = RemixText,
            surface = RemixSurface,
            onSurface = RemixText,
            surfaceVariant = RemixSurfaceVariant,
            onSurfaceVariant = RemixTextMuted,
            outline = RemixOutline,
        ),
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
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(18.dp), content = content)
    }
}

@Composable
actual fun PatcherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(label)
    }
}

@Composable
actual fun PatcherCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label)
    }
}

@Composable
actual fun PatcherProgress(progress: Float, modifier: Modifier) {
    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier)
}

@Composable
actual fun GameCoverArt(
    pngBytes: ByteArray?,
    modifier: Modifier,
) {
    val bitmap = remember(pngBytes) {
        runCatching { pngBytes?.let { Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = "PSP game cover art",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(18.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "PSP",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
