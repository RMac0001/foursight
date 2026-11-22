package io.github.rmac0001.foursight.desktop.auth

import io.github.rmac0001.foursight.core.auth.SignInException
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.desktop.DesktopServices
import kotlinx.coroutines.TimeoutCancellationException
import java.awt.Desktop
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * One sign-in on Windows, shared by `foursight connect` and the app's Accounts section: opens the
 * browser on the platform's sign-in page, waits for the loopback redirect and stores the result.
 * [say] gets progress and results, [warn] problems; neither ever gets a token or code.
 */
suspend fun signIn(
    services: DesktopServices,
    name: String,
    say: (String) -> Unit,
    warn: (String) -> Unit,
): Boolean {
    val flow =
        services.signInFlows[name] ?: run {
            warn("Can't sign in to \"$name\" yet. Available: ${services.signInFlows.keys.joinToString()}")
            return false
        }
    return LoopbackReceiver(flow.loopbackPath, flow.loopbackHost, flow.loopbackRedirectSuffix).use { receiver ->
        try {
            val request = flow.begin(receiver.redirectUri)
            say("Opening ${flow.displayName} sign-in in your browser...")
            val opened =
                runCatching {
                    Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                        Desktop.getDesktop().browse(URI(request.authorizeUrl)).let { true }
                }.getOrDefault(false)
            if (!opened) say("Open this address in your browser:\n${request.authorizeUrl}")
            say("Waiting for the sign-in to finish (up to 5 minutes)...")
            val callback = receiver.await(5.minutes)
            val result = flow.complete(request, callback, Clock.System.now())
            flow.platform?.let { services.repo.setTokenExpiry(it, result.signInExpiresAt) }
            say("Signed in to ${flow.displayName}. Granted: ${result.grantedScopes.sorted().joinToString()}")
            if (result.missingScopes.isNotEmpty()) {
                warn(
                    "Not granted: ${result.missingScopes.sorted().joinToString()}. " +
                        "Some numbers will be missing; sign in again and allow every permission.",
                )
            }
            result.signInExpiresAt?.let { say("This sign-in lasts until $it unless it's renewed by collecting.") }
            true
        } catch (e: TimeoutCancellationException) {
            warn("Timed out waiting for the browser. Try the sign-in again.")
            false
        } catch (e: SignInException) {
            warn(e.message ?: "The sign-in failed.")
            false
        } catch (e: CollectionException) {
            warn("${e.message}\n${e.kind.advice}")
            false
        }
    }
}
