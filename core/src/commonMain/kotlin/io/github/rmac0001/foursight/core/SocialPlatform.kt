package io.github.rmac0001.foursight.core

/** The four platforms FourSight reads from and posts to (see docs/SPEC.md). */
enum class SocialPlatform(
    val displayName: String,
) {
    TIKTOK("TikTok"),
    YOUTUBE("YouTube"),
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
}
