package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDocGeometryTest {
    @Test fun usesCurrentAbsoluteZoom() {
        assertEquals(3f, AutoDocGeometry.targetZoom(0.3f, 0.7f, 0.3f, 0.7f, 1.5f, 1f, 4f), 0.002f)
    }

    @Test fun offCenterLabelStaysInsideFrame() {
        val zoom = AutoDocGeometry.targetZoom(0.55f, 0.95f, 0.2f, 0.6f, 1f, 1f, 8f)
        assertEquals(1.1111112f, zoom, 0.002f)
        assertTrue(0.5f + (0.95f - 0.5f) * zoom <= 1.0001f)
    }

    @Test fun respectsHardwareLimit() {
        assertEquals(1.2f, AutoDocGeometry.targetZoom(0.2f, 0.8f, 0.2f, 0.8f, 1f, 1f, 1.2f), 0.002f)
        assertEquals(1f, AutoDocGeometry.targetZoom(0.3f, 0.7f, 0.3f, 0.7f, 1f, 1f, 1f), 0.002f)
    }

    @Test fun nearTargetDoesNotOscillate() {
        assertEquals(1f, AutoDocGeometry.targetZoom(0.1f, 0.89f, 0.1f, 0.89f, 1f, 1f, 4f), 0.002f)
    }

    @Test fun longVerticalLabelLimitsBothAxes() {
        assertEquals(1f, AutoDocGeometry.targetZoom(0.4f, 0.6f, 0.1f, 0.9f, 1f, 1f, 4f), 0.002f)
    }

    @Test fun cameraAndAnalyzerShareTheSameDeadband() {
        assertFalse(AutoDocGeometry.zoomSettled(1.07f, 1f))
        assertFalse(AutoDocGeometry.zoomSettled(2.10f, 2f))
        assertTrue(AutoDocGeometry.zoomSettled(1.05f, 1f))
        assertTrue(AutoDocGeometry.zoomSettled(2.07f, 2f))
    }

    @Test fun invalidGeometryDoesNotChangeZoom() {
        assertEquals(1.5f, AutoDocGeometry.targetZoom(0.5f, 0.5f, 0.2f, 0.8f, 1.5f, 1f, 4f), 0.002f)
        assertEquals(1.5f, AutoDocGeometry.targetZoom(Float.NaN, 0.8f, 0.2f, 0.8f, 1.5f, 1f, 4f), 0.002f)
    }

    @Test fun requiresPreviousStableGeometry() {
        val corners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        assertTrue(AutoDocGeometry.geometryStable(corners,
            listOf(0.001f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), 0.01f))
        assertFalse(AutoDocGeometry.geometryStable(corners, null, 0.01f))
        assertFalse(AutoDocGeometry.geometryStable(corners,
            listOf(0.1f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), 0.01f))
    }
}
