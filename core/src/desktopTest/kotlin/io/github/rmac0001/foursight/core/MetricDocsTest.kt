package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.metrics.Metrics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps docs/metrics.md in step with the metric catalog. */
class MetricDocsTest {
    private val doc =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/metrics.md") }
            .first { it.exists() }
            .readText()

    @Test
    fun everyMetricIsDocumented() {
        Metrics.all.forEach { assertTrue("`${it.id}`" in doc, "docs/metrics.md is missing `${it.id}`") }
    }

    @Test
    fun theDocNamesTheCurrentDefinitionsVersion() {
        val version = Regex("Definitions version: \\*\\*(\\d+)\\*\\*").find(doc)?.groupValues?.get(1)
        assertEquals(Metrics.DEFINITIONS_VERSION.toString(), version)
    }
}
