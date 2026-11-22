package io.github.rmac0001.foursight.core.query

import kotlin.time.Instant

/**
 * Two videos side by side (spec: `compare_videos`). Day-7 numbers compare the videos at the same
 * age; the latest numbers are shown too, with their ages, because two videos of different ages
 * aren't comparable on lifetime counts. Describes; never claims causes.
 */
data class VideoComparison(
    val a: VideoStats,
    val b: VideoStats,
    val aLatest: VideoStats,
    val bLatest: VideoStats,
    /** Metric or computed ids to show; empty means all. */
    val metrics: List<String>,
    val generatedAt: Instant,
)

fun QueryService.compare(
    a: LibraryEntry,
    b: LibraryEntry,
    now: Instant,
    metrics: List<String> = emptyList(),
): VideoComparison =
    VideoComparison(
        a = videoStats(a, StatsBasis.DAY_7, now),
        b = videoStats(b, StatsBasis.DAY_7, now),
        aLatest = videoStats(a, StatsBasis.LATEST, now),
        bLatest = videoStats(b, StatsBasis.LATEST, now),
        metrics = metrics,
        generatedAt = now,
    )
