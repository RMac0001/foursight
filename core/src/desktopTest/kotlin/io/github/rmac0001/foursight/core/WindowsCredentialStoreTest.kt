package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.secrets.WindowsCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Runs only on Windows (the CI Windows runner, or Roger's PC). Uses a throwaway target name, so it
 * never reads or changes Roger's real FourSight entries.
 */
class WindowsCredentialStoreTest {
    private val onWindows = System.getProperty("os.name").startsWith("Windows")
    private val target = "FourSight/test/roundtrip-${System.nanoTime()}"

    @Test
    fun writeReadDeleteRoundTrip() {
        if (!onWindows) return
        val store = WindowsCredentialStore()
        try {
            assertNull(store.readTarget(target))
            store.writeTarget(target, "test-value-ünïcødé")
            assertEquals("test-value-ünïcødé", store.readTarget(target))
            store.writeTarget(target, "replaced")
            assertEquals("replaced", store.readTarget(target))
        } finally {
            store.deleteTarget(target)
        }
        assertNull(store.readTarget(target))
        store.deleteTarget(target) // deleting twice is fine
    }
}
