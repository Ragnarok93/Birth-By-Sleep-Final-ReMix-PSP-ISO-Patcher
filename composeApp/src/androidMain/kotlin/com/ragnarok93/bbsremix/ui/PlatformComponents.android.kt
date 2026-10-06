package com.ragnarok93.bbsremix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.oneui.compose.progress.ProgressIndicator
import org.oneui.compose.progress.ProgressIndicatorType
import org.oneui.compose.theme.OneUITheme
import org.oneui.compose.widgets.box.RoundedCornerBox
import org.oneui.compose.widgets.buttons.Button as OneUiButton
import org.oneui.compose.widgets.buttons.Checkbox as OneUiCheckbox

@Composable
actual fun PatcherTheme(content: @Composable () -> Unit) {
    OneUITheme {
        val colors = OneUITheme.colors
        val scheme = if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = colors.seslPrimaryColor,
                background = colors.seslRoundAndBgcolor,
                surface = colors.seslBackgroundColor,
                onBackground = colors.seslPrimaryTextColor,
                onSurface = colors.seslPrimaryTextColor,
            )
        } else {
            lightColorScheme(
                primary = colors.seslPrimaryColor,
                background = colors.seslRoundAndBgcolor,
                surface = colors.seslBackgroundColor,
                onBackground = colors.seslPrimaryTextColor,
                onSurface = colors.seslPrimaryTextColor,
            )
        }
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
actual fun PatcherSurface(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    RoundedCornerBox(modifier = modifier, content = content)
}

@Composable
actual fun PatcherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    OneUiButton(label = label, onClick = onClick, modifier = modifier, enabled = enabled)
}

@Composable
actual fun PatcherCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    OneUiCheckbox(
        modifier = modifier,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        label = { Text(label) },
    )
}

@Composable
actual fun PatcherProgress(progress: Float, modifier: Modifier) {
    ProgressIndicator(
        modifier = modifier,
        type = ProgressIndicatorType.HorizontalDeterminate(progress.coerceIn(0f, 1f)),
    )
}
