package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.store.StoredEvent
import io.github.rmac0001.foursight.core.sync.MatchEvents
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** How sure a suggestion is. Nothing is ever linked without Roger confirming it. */
enum class MatchConfidence { HIGH, MEDIUM, LOW }

/** A suggested group of unmatched posts that look like the same original video. */
data class MatchSuggestion(
    val posts: List<PlatformPost>,
    /** 0 to 1: half publish-time closeness, half caption word overlap. */
    val score: Double,
    val confidence: MatchConfidence,
    val reason: String,
)

/**
 * Suggests and records cross-platform matches (spec: Content library). Suggestions come from publish
 * time and caption; confirming and unlinking write events, which sync to the other device.
 */
object Matching {
    /** Posts more than this far apart in publish time are never suggested together. */
    val MAX_GAP = 48.hours
    const val MIN_SCORE = 0.35

    const val DEFINITION =
        "Score = 0.5 × (1 − publish-time gap ÷ 48 hours) + 0.5 × caption word overlap (shared words ÷ all " +
            "words, ignoring words under 3 letters). High is 0.75 or more, medium 0.5 or more."

    fun suggest(
        unmatched: List<PlatformPost>,
        existing: Map<PostRef, String> = emptyMap(),
    ): List<MatchSuggestion> {
        val pool = unmatched.filter { it.ref !in existing }.sortedBy { it.publishedAt }.toMutableList()
        val out = mutableListOf<MatchSuggestion>()
        while (pool.isNotEmpty()) {
            val anchor = pool.removeAt(0)
            val picks =
                pool
                    .filter { it.platform != anchor.platform && gap(anchor, it) <= MAX_GAP.inWholeMinutes }
                    .map { it to score(anchor, it) }
                    .filter { it.second >= MIN_SCORE }
                    .groupBy { it.first.platform }
                    .map { (_, candidates) -> candidates.maxBy { it.second } }
            if (picks.isEmpty()) continue
            pool.removeAll(picks.map { it.first }.toSet())
            val score = picks.minOf { it.second }
            val confidence =
                when {
                    score >= 0.75 -> MatchConfidence.HIGH
                    score >= 0.5 -> MatchConfidence.MEDIUM
                    else -> MatchConfidence.LOW
                }
            val maxGapHours = picks.maxOf { gap(anchor, it.first) } / 60.0
            out +=
                MatchSuggestion(
                    posts = (listOf(anchor) + picks.map { it.first }).sortedBy { it.platform.ordinal },
                    score = score,
                    confidence = confidence,
                    reason =
                        "Published within ${formatHours(maxGapHours)} of each other; lowest pair score " +
                            "${(score * 100).toInt()}%.",
                )
        }
        return out.sortedByDescending { it.score }
    }

    /**
     * Events that put [refs] into one video. With [videoId] they join that video; otherwise a new
     * video id is made. Each platform may appear only once in a video.
     */
    fun confirm(
        refs: List<PostRef>,
        videoId: String,
        deviceId: String,
        at: Instant,
        newEventId: () -> String,
    ): List<StoredEvent> {
        require(refs.isNotEmpty()) { "Name at least one post." }
        require(refs.distinct().size == refs.size) { "A post is named twice." }
        return refs.map { MatchEvents.link(newEventId(), deviceId, at, videoId, it) }
    }

    fun unlink(
        ref: PostRef,
        deviceId: String,
        at: Instant,
        newEventId: () -> String,
    ): StoredEvent = MatchEvents.unlink(newEventId(), deviceId, at, ref)

    private fun gap(
        a: PlatformPost,
        b: PlatformPost,
    ): Long = abs((a.publishedAt - b.publishedAt).inWholeMinutes)

    internal fun score(
        a: PlatformPost,
        b: PlatformPost,
    ): Double {
        val time = (1.0 - gap(a, b).toDouble() / MAX_GAP.inWholeMinutes).coerceIn(0.0, 1.0)
        return 0.5 * time + 0.5 * overlap(a.title, b.title)
    }

    internal fun overlap(
        a: String?,
        b: String?,
    ): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return (wa intersect wb).size.toDouble() / (wa union wb).size
    }

    private fun words(text: String?): Set<String> =
        text
            .orEmpty()
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && !it.startsWith("http") }
            .toSet()

    private fun formatHours(h: Double): String =
        if (h < 1.0) "${(h * 60).toInt()} minutes" else "${(h * 10).toInt() / 10.0} hours"
}
