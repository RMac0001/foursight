package io.github.rmac0001.foursight.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.rmac0001.foursight.ui.App

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "FourSight") {
            App()
        }
    }
