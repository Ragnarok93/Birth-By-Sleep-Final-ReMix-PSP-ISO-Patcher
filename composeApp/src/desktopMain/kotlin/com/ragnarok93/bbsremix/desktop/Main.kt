package com.ragnarok93.bbsremix.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ragnarok93.bbsremix.platform.DesktopFileGateway
import com.ragnarok93.bbsremix.ui.PatcherApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Birth By Sleep - Final ReMix PSP ISO Patcher",
    ) {
        PatcherApp(fileGateway = DesktopFileGateway())
    }
}
