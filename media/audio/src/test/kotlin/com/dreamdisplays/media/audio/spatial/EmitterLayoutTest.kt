package com.dreamdisplays.media.audio.spatial

import com.dreamdisplays.media.audio.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmitterLayoutTest {
    @Test
    fun `distance gain is 1 inside the reference distance, no matter how large the screen is`() {
        // A giant screen (refDistance scales with sqrt(width * height)) never "blows up" standing close to it
        val hugeScreenRef = 8.0
        assertEquals(1.0, EmitterLayout.distanceGain(0.0, hugeScreenRef), 1e-9)
        assertEquals(1.0, EmitterLayout.distanceGain(hugeScreenRef, hugeScreenRef), 1e-9)
    }

    @Test
    fun `distance gain rolls off toward zero far beyond the reference distance`() {
        val g = EmitterLayout.distanceGain(1000.0, 2.0)
        assertTrue(g in 0.0..0.05, "Expected a near-silent gain far away, got $g.")
    }

    @Test
    fun `directivity is near-full in front of the screen and floored directly behind it`() {
        val normal = Vec3(0.0, 0.0, -1.0)
        val front = EmitterLayout.directivityGain(normal, Vec3(0.0, 0.0, -1.0))
        val behind = EmitterLayout.directivityGain(normal, Vec3(0.0, 0.0, 1.0))
        assertTrue(front > 0.95, "Expected near-full gain directly in front, got $front.")
        assertEquals(0.6, behind, 1e-6, "Expected the back floor directly behind the screen.")
        assertTrue(front > behind)
    }
}
