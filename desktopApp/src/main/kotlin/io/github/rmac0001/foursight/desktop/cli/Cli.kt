package io.github.rmac0001.foursight.desktop.cli

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.auth.SignInException
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.PlatformRunResult
import io.github.rmac0001.foursight.desktop.DesktopServices
import io.github.rmac0001.foursight.desktop.auth.LoopbackReceiver
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.io.PrintStream
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The command line (spec: MCP server and CLI). Step 3 has `connect` and `collect`; the rest of the
 * spec's commands arrive in step 4. Output never contains tokens, secrets or authorization codes.
 */
class Cli(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    fun run(args: List<String>): Int =
        when (args.firstOrNull()) {
            "connect" -> {
                withServices { connect(it, args.drop(1)) }
            }

            "collect" -> {
                withServices { collect(it, args.drop(1)) }
            }

            null, "help", "--help", "-h" -> {
                usage(0)
            }

            else -> {
                err.println("Unknown command: ${args.first()}")
                usage(2)
            }
        }

    private fun withServices(body: (DesktopServices) -> Int): Int = DesktopServices().use(body)

    private fun connect(
        services: DesktopServices,
        args: List<String>,
    ): Int {
        val platform = args.singleOrNull()?.let(::parsePlatform) ?: return usage(2)
        val flow =
            services.signInFlows[platform] ?: run {
                err.println("Signing in to ${platform.displayName} isn't built yet.")
                return 1
            }
        return LoopbackReceiver(flow.loopbackPath).use { receiver ->
            try {
                val request = flow.begin(receiver.redirectUri)
                out.println("Opening ${platform.displayName} sign-in in your browser...")
                val opened =
                    runCatching {
                        Desktop.isDesktopSupported() &&
                            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                            Desktop.getDesktop().browse(URI(request.authorizeUrl)).let { true }
                    }.getOrDefault(false)
                if (!opened) out.println("Open this address in your browser:\n${request.authorizeUrl}")
                out.println("Waiting for the sign-in to finish (up to 5 minutes)...")
                val callback = runBlocking { receiver.await(5.minutes) }
                val result = runBlocking { flow.complete(request, callback, Clock.System.now()) }
                services.repo.setTokenExpiry(platform, result.signInExpiresAt)
                out.println(
                    "Signed in to ${platform.displayName}. Granted: ${result.grantedScopes.sorted().joinToString()}",
                )
                if (result.missingScopes.isNotEmpty()) {
                    err.println(
                        "Not granted: ${result.missingScopes.sorted().joinToString()}. " +
                            "Some numbers will be missing; run connect again and allow every permission.",
                    )
                }
                result.signInExpiresAt?.let {
                    out.println(
                        "This sign-in lasts until $it unless it's renewed by collecting.",
                    )
                }
                0
            } catch (e: TimeoutCancellationException) {
                err.println("Timed out waiting for the browser. Run connect again.")
                1
            } catch (e: SignInException) {
                err.println(e.message)
                1
            } catch (e: CollectionException) {
                err.println("${e.message}\n${e.kind.advice}")
                1
            }
        }
    }

    private fun collect(
        services: DesktopServices,
        args: List<String>,
    ): Int {
        var only: Set<SocialPlatform>? = null
        var day7Due = false
        val rest = ArrayDeque(args)
        while (rest.isNotEmpty()) {
            when (val arg = rest.removeFirst()) {
                "--platform" -> {
                    only = setOf(rest.removeFirstOrNull()?.let(::parsePlatform) ?: return usage(2))
                }

                "--day7-due" -> {
                    day7Due = true
                }

                else -> {
                    err.println("Unknown option: $arg")
                    return usage(2)
                }
            }
        }
        val results =
            runBlocking {
                if (day7Due) services.runner.runDay7Due() else services.runner.run(only)
            }
        if (day7Due && results.isEmpty()) {
            out.println("No post is waiting for its day-7 capture.")
            return 0
        }
        results.forEach(::report)
        return if (results.all { it.ok }) 0 else 1
    }

    private fun report(r: PlatformRunResult) {
        if (r.ok) {
            out.println("${r.platform.displayName}: ${r.posts} posts, ${r.newSnapshots} new snapshots")
        } else {
            err.println("${r.error}\n  ${r.failure?.advice?.replace("<platform>", r.platform.name.lowercase())}")
        }
        r.warnings.forEach { err.println("  warning: $it") }
    }

    private fun parsePlatform(name: String): SocialPlatform? {
        val platform = SocialPlatform.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (platform == null) err.println("Unknown platform: $name (tiktok, youtube, facebook, instagram)")
        return platform
    }

    private fun usage(code: Int): Int {
        (if (code == 0) out else err).println(
            """
            Usage:
              foursight connect <platform>             Sign in (opens the browser)
              foursight collect [--platform <name>]    Collect today's numbers
              foursight collect --day7-due             Collect only platforms with a post due for day 7
            Platforms: tiktok, youtube, facebook, instagram
            """.trimIndent(),
        )
        return code
    }
}
