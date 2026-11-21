package io.github.rmac0001.foursight.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.rmac0001.foursight.desktop.cli.Cli
import io.github.rmac0001.foursight.ui.App
import kotlin.system.exitProcess

/** With arguments it's the CLI; without, the desktop window. */
fun main(args: Array<String>) {
    if (args.isNotEmpty()) exitProcess(Cli().run(args.toList()))
    application {
        Window(onCloseRequest = ::exitApplication, title = "FourSight") {
            App()
        }
    }
}
