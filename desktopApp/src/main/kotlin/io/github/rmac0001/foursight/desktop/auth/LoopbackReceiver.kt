package io.github.rmac0001.foursight.desktop.auth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.time.Duration

/**
 * Receives one OAuth callback on `http://127.0.0.1:<random port><path>` (spec: Architecture,
 * "Sign-in", Windows loopback redirect). TikTok's registered redirect uses the path /callback/;
 * Google's loopback redirect is the bare address, path `/`, which the redirect URI leaves off.
 * It listens on the loopback interface only and stops after the first callback or on [close].
 */
class LoopbackReceiver(
    private val path: String,
    /** The host name in the redirect URI. Meta's development mode allows `localhost` automatically. */
    redirectHost: String = "127.0.0.1",
    /** What follows the port in the redirect URI (see SignInFlow.loopbackRedirectSuffix). */
    redirectSuffix: String = path,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val callback = CompletableDeferred<Map<String, String>>()

    val redirectUri: String = "http://$redirectHost:${server.address.port}$redirectSuffix"

    init {
        server.createContext(path) { exchange ->
            exchange.use {
                val params = parseQuery(exchange.requestURI.rawQuery)
                // Only the callback itself counts; a stray request (a favicon, say) doesn't.
                if (exchange.requestURI.path != path || ("code" !in params && "error" !in params)) {
                    exchange.sendResponseHeaders(404, -1)
                    return@use
                }
                val ok = params.containsKey("code") && !params.containsKey("error")
                val page =
                    if (ok) {
                        "<h1>FourSight is signed in.</h1><p>You can close this tab and go back to the terminal.</p>"
                    } else {
                        "<h1>Sign-in wasn't completed.</h1><p>Go back to the terminal for details.</p>"
                    }
                val bytes = "<!doctype html><meta charset=utf-8><title>FourSight</title>$page".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                callback.complete(params)
            }
        }
        server.start()
    }

    /** The callback's query parameters. Throws a timeout exception if the browser never comes back. */
    suspend fun await(timeout: Duration): Map<String, String> = withTimeout(timeout) { callback.await() }

    override fun close() = server.stop(0)

    private fun parseQuery(raw: String?): Map<String, String> =
        raw
            .orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
}
