package com.zeny.desktop.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

const val APP_TITLE = "Zeny Hotel Manager"

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = APP_TITLE) {
            MaterialTheme {
                Surface {}
            }
        }
    }
