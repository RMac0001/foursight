package io.github.rmac0001.foursight.desktop.cli

import io.github.rmac0001.foursight.core.SocialPlatform

/** A command's arguments: positional values plus `--name value` options and `--flag` switches. */
class Args private constructor(
    val positional: List<String>,
    private val options: Map<String, String>,
    private val flags: Set<String>,
) {
    fun option(name: String): String? = options[name]

    fun flag(name: String): Boolean = name in flags

    class UsageError(
        message: String,
    ) : Exception(message)

    /** Platforms from `--platform tiktok,youtube` (or repeated); all four when absent. */
    fun platforms(): Set<SocialPlatform> {
        val raw = option("platform") ?: return SocialPlatform.entries.toSet()
        return raw
            .split(',')
            .filter { it.isNotBlank() }
            .map { name ->
                SocialPlatform.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                    ?: throw UsageError("Unknown platform: $name (tiktok, youtube, facebook, instagram)")
            }.toSet()
    }

    fun singlePlatform(): SocialPlatform? = if (option("platform") == null) null else platforms().singleOrNull()

    fun int(
        name: String,
        default: Int,
        range: IntRange,
    ): Int {
        val raw = option(name) ?: return default
        val n = raw.removeSuffix("d").toIntOrNull()
        if (n == null || n !in range) throw UsageError("--$name must be a number from ${range.first} to ${range.last}")
        return n
    }

    companion object {
        /**
         * Parses [args] against the options that take a value ([valued]) and the switches ([switches]).
         * Anything else starting with `--` is a usage error.
         */
        fun parse(
            args: List<String>,
            valued: Set<String> = emptySet(),
            switches: Set<String> = emptySet(),
        ): Args {
            val positional = mutableListOf<String>()
            val options = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            val rest = ArrayDeque(args)
            while (rest.isNotEmpty()) {
                val arg = rest.removeFirst()
                if (!arg.startsWith("--")) {
                    positional += arg
                    continue
                }
                val name = arg.removePrefix("--")
                when (name) {
                    in valued -> {
                        val value = rest.removeFirstOrNull() ?: throw UsageError("$arg needs a value")
                        options[name] =
                            if (name == "platform" && name in options) options[name] + "," + value else value
                    }

                    in switches -> {
                        flags += name
                    }

                    else -> {
                        throw UsageError("Unknown option: $arg")
                    }
                }
            }
            return Args(positional, options, flags)
        }
    }
}
