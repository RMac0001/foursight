package io.github.rmac0001.foursight.core.auth

import kotlin.io.encoding.Base64

/** SHA-256 of [input]. Both targets are JVMs, so both actuals use java.security.MessageDigest. */
internal expect fun sha256(input: ByteArray): ByteArray

/** [size] bytes from a cryptographically secure random source (java.security.SecureRandom). */
internal expect fun secureRandomBytes(size: Int): ByteArray

/** PKCE helpers (RFC 7636 verifier rules; TikTok's challenge encoding differs, see [tiktokChallenge]). */
object Pkce {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** A new 64-character verifier from the unreserved characters (allowed length is 43 to 128). */
    fun newVerifier(): String = randomString(64, UNRESERVED)

    /** A random alphanumeric `state` value for the CSRF check on the callback. */
    fun newState(): String = randomString(32, UNRESERVED.substring(0, 62))

    /**
     * TikTok's code challenge: the **hex** encoding of SHA-256(verifier), not RFC 7636's base64url.
     * Login Kit for Desktop: "You must use hex encoding of SHA256 to generate the code challenge."
     */
    fun tiktokChallenge(verifier: String): String = sha256(verifier.encodeToByteArray()).toHex()

    /** RFC 7636 S256: base64url without padding of SHA-256(verifier). Used by Google. */
    fun s256Challenge(verifier: String): String =
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(sha256(verifier.encodeToByteArray()))

    private fun randomString(
        length: Int,
        alphabet: String,
    ): String {
        // Rejection sampling keeps every character equally likely.
        val limit = 256 - 256 % alphabet.length
        val out = StringBuilder(length)
        while (out.length < length) {
            for (b in secureRandomBytes(length)) {
                val v = b.toInt() and 0xFF
                if (v < limit && out.length < length) out.append(alphabet[v % alphabet.length])
            }
        }
        return out.toString()
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
