package com.ragnarok93.bbsremix.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import org.oneui.compose.components.buttons.OneUiFilledButton
import org.oneui.compose.components.progress.OneUiCircularProgress
import org.oneui.compose.components.progress.OneUiCircularProgressSize
import org.oneui.compose.components.progress.OneUiLinearProgress
import org.oneui.compose.components.selection.OneUiCheckbox
import org.oneui.compose.components.selection.OneUiSwitch
import org.oneui.compose.components.navigation.OneUiNavigationItem
import org.oneui.compose.components.navigation.OneUiTabStyle
import org.oneui.compose.components.navigation.OneUiTabs
import org.oneui.compose.components.slider.OneUiSlider
import org.oneui.compose.components.slider.OneUiSliderMode
import org.oneui.compose.oneui8.components.OneUI8Card
import org.oneui.compose.oneui8.theme.OneUI8Theme
import androidx.compose.runtime.remember

@Composable
actual fun PatcherTheme(content: @Composable () -> Unit) {
    OneUI8Theme(darkTheme = true) {
        MaterialTheme(
            colorScheme = PatcherDarkColorScheme,
            content = content,
        )
    }
}

@Composable
actual fun PatcherSurface(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    OneUI8Card(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Box(content = content)
    }
}

@Composable
actual fun PatcherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    OneUiFilledButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
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
    Row(modifier) {
        OneUiCheckbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
actual fun PatcherSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
) {
    OneUiSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

@Composable
actual fun PatcherProgress(progress: Float, modifier: Modifier) {
    OneUiLinearProgress(progress = progress, modifier = modifier)
}

@Composable
actual fun PatcherSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier,
) {
    OneUiSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        mode = OneUiSliderMode.Expand,
    )
}

@Composable
actual fun PatcherLevelSlider(
    level: Int,
    onLevelChange: (Int) -> Unit,
    minLevel: Int,
    maxLevel: Int,
    enabled: Boolean,
    modifier: Modifier,
) {
    OneUiSlider(
        value = level.coerceIn(minLevel, maxLevel).toFloat(),
        onValueChange = {
            onLevelChange(kotlin.math.round(it).toInt().coerceIn(minLevel, maxLevel))
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = minLevel.toFloat()..maxLevel.toFloat(),
        steps = (maxLevel - minLevel - 1).coerceAtLeast(0),
        mode = OneUiSliderMode.Expand,
    )
}

@Composable
actual fun PatcherIndeterminateProgress(modifier: Modifier) {
    OneUiCircularProgress(
        progress = null,
        modifier = modifier,
        size = OneUiCircularProgressSize.Medium,
    )
}

@Composable
actual fun PatcherNavigationBar(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val destinations = remember(items) {
        items.mapIndexed { index, label -> OneUiNavigationItem("patcher-$index", label) }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(OneUI8Theme.dimensions.cardRadius),
        color = OneUI8Theme.colors.surfaceElevated,
        tonalElevation = 4.dp,
    ) {
        OneUiTabs(
            items = destinations,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            showIcons = false,
            style = OneUiTabStyle.Main,
        )
    }
}

actual fun decodeCoverArt(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
