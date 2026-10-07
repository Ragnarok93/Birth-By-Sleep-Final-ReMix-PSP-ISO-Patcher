package com.ragnarok93.bbsremix.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ragnarok93.bbsremix.platform.DesktopFileGateway
import com.ragnarok93.bbsremix.ui.PatcherApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Birth By Sleep - Final ReMix PSP ISO Patcher",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        PatcherApp(fileGateway = DesktopFileGateway())
    }
}
