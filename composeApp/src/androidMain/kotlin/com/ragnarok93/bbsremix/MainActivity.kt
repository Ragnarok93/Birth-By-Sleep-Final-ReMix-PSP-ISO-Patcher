package com.ragnarok93.bbsremix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ragnarok93.bbsremix.platform.AndroidFileGateway
import com.ragnarok93.bbsremix.ui.PatcherApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileGateway = AndroidFileGateway(this)
        setContent { PatcherApp(fileGateway = fileGateway) }
    }
}
