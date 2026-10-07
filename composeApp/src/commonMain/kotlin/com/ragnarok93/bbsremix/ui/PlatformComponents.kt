package com.ragnarok93.bbsremix.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
expect fun PatcherProgress(
    progress: Float,
    modifier: Modifier = Modifier,
)

@Composable
expect fun GameCoverArt(
    pngBytes: ByteArray?,
    modifier: Modifier = Modifier,
)
