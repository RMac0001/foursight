package io.github.rmac0001.foursight.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SocialPlatformTest {
    @Test
    fun coversTheFourPlatformsInTheSpec() {
        assertEquals(
            listOf("TikTok", "YouTube", "Facebook", "Instagram"),
            SocialPlatform.entries.map { it.displayName },
        )
    }
}
