package com.zeny.desktop.app

import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.zeny.desktop.app.catalog.ComponentCatalog
import com.zeny.desktop.app.catalog.isDevMode
import com.zeny.desktop.design.theme.ZenyTheme

const val APP_TITLE = "Zeny Hotel Manager"

fun main() =
    application {
        val dev = isDevMode()
        Window(onCloseRequest = ::exitApplication, title = APP_TITLE) {
            ZenyTheme {
                if (dev) ComponentCatalog() else Surface {}
            }
        }
    }
