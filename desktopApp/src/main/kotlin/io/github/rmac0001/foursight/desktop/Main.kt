package io.github.rmac0001.foursight.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.rmac0001.foursight.desktop.cli.Cli
import io.github.rmac0001.foursight.ui.App
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * With arguments it's the CLI; without, the desktop window. `--log <file>` appends the CLI's output
 * to a file instead of the console: the scheduled tasks run windowless (javaw) and log there. The
 * CLI never prints tokens or secrets, so the log holds none.
 */
fun main(args: Array<String>) {
    // kotlin-logging (from the MCP SDK) prints a startup line to stdout unless told not to.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    if (args.isNotEmpty()) {
        val list = args.toMutableList()
        val logAt = list.indexOf("--log")
        if (logAt >= 0) {
            val file = list.getOrNull(logAt + 1)?.let(::File)
            if (file == null) {
                System.err.println("--log needs a file path")
                exitProcess(2)
            }
            list.subList(logAt, logAt + 2).clear()
            file.parentFile?.mkdirs()
            val log = PrintStream(FileOutputStream(file, true), true, Charsets.UTF_8)
            log.println("--- ${Clock.System.now()} foursight ${list.joinToString(" ")}")
            val code = Cli(out = log, err = log).run(list)
            log.println("--- exit $code")
            log.close()
            exitProcess(code)
        }
        exitProcess(Cli().run(list))
    }
    // Closing the window quits (Roger's decision, November 22, 2026): the scheduled tasks collect
    // without the app open, so there's no tray icon.
    val services = DesktopServices()
    application {
        Window(
            onCloseRequest = {
                services.close()
                exitApplication()
            },
            title = "FourSight",
            icon = painterResource("foursight-icon.png"),
            state = rememberWindowState(width = 1360.dp, height = 900.dp),
        ) {
            App(DesktopAppServices(services))
        }
    }
}
