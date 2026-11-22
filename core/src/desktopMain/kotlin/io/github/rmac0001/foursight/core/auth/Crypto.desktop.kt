package io.github.rmac0001.foursight.core.auth

import java.security.MessageDigest
import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
