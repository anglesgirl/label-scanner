package com.anglesgirl.labelscanner.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldCaptureGateTest {
    private fun box(dx: Float = 0f, dy: Float = 0f, edgeJitter: Float = 0f) = listOf(
        (0.2f + dx - edgeJitter) to (0.3f + dy),
        (0.8f + dx + edgeJitter) to (0.3f + dy),
        (0.8f + dx + edgeJitter) to (0.7f + dy),
        (0.2f + dx - edgeJitter) to (0.7f + dy),
    )
    private fun sample(g: HandheldCaptureGate, t: Long, dx: Float = 0f, sharpness: Double = 180.0,
                       adjusting: Boolean = false) = g.observe(t, box(dx), sharpness, adjusting)

    @Test fun twoSharpHandheldFramesCanCaptureWithoutWaitingForStillness() {
        val g = HandheldCaptureGate()
        assertFalse(sample(g, 0, -0.02f).ready)
        assertTrue(sample(g, 150, 0.02f).ready)
    }
    @Test fun pronouncedOpposingHandTremorDoesNotResetClearEvidence() {
        val g = HandheldCaptureGate()
        assertFalse(g.observe(0, box(-0.08f, 0.06f), 180.0, false).ready)
        assertTrue(g.observe(150, box(0.08f, -0.06f), 180.0, false).ready)
    }
    @Test fun repeatedShakeCanCatchTheNextSharpMoment() {
        val g = HandheldCaptureGate()
        sample(g, 0, -.08f)
        assertFalse(sample(g, 100, .08f, sharpness = 20.0).ready)
        assertFalse(sample(g, 200, -.07f, sharpness = 30.0).ready)
        assertTrue(sample(g, 300, .07f).ready)
    }
    @Test fun oneBlurredSampleDoesNotEraseClearProgress() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        assertFalse(sample(g, 100, -.03f, sharpness = 20.0).ready)
        assertTrue(sample(g, 200, .03f).ready)
    }
    @Test fun shortDetectionMissDoesNotRestartFromZero() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        g.miss(100)
        assertTrue(sample(g, 200, .07f).ready)
    }
    @Test fun longDetectionLossClearsOldEvidence() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        g.miss(1000)
        assertFalse(sample(g, 1100).ready)
    }
    @Test fun persistentBlurAndCurrentBlurNeverTrigger() {
        val g = HandheldCaptureGate()
        for (i in 0..15) assertFalse(sample(g, i * 100L, sharpness = 119.0).ready)
        assertFalse(sample(g, 1700).ready)
        assertFalse(sample(g, 1850, sharpness = 20.0).ready)
        assertTrue(sample(g, 2000).ready)
    }
    @Test fun largeTargetSwitchStartsNewWindow() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        assertFalse(g.observe(150, box(dy = .26f), 180.0, false).ready)
        assertTrue(g.observe(300, box(dy = .26f), 180.0, false).ready)
    }
    @Test fun movingButClearLabelDoesNotNeedToBecomeStationary() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        assertFalse(sample(g, 100, .05f).ready)
        assertTrue(sample(g, 200, .10f).ready)
    }
    @Test fun cameraZoomOrFocusStillBlocksShutter() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        assertFalse(sample(g, 150, adjusting = true).ready)
        assertFalse(sample(g, 300).ready)
        assertTrue(sample(g, 450, .06f).ready)
    }
    @Test fun cornerOrderAndBoundaryJitterDoNotBlockCapture() {
        val g = HandheldCaptureGate()
        g.observe(0, box(), 180.0, false)
        assertTrue(g.observe(150, box(edgeJitter = .04f).reversed(), 180.0, false).ready)
    }
    @Test fun staleFramesAndTooFastBurstsCannotPass() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        sample(g, 10)
        assertFalse(sample(g, 20).ready)
        assertFalse(sample(g, 2000).ready)
    }
    @Test fun slowerPhoneStillUsesTwoFreshClearObservations() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        assertTrue(sample(g, 500, .07f).ready)
    }
    @Test fun suddenScaleChangeCannotReusePreviousWindow() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        val small = listOf(.4f to .4f, .6f to .4f, .6f to .6f, .4f to .6f)
        assertFalse(g.observe(150, small, 200.0, false).ready)
    }
    @Test fun nonOverlappingSmallTargetsCannotShareEvidence() {
        val g = HandheldCaptureGate()
        fun small(x: Float) = listOf(x to .4f, x + .12f to .4f, x + .12f to .6f, x to .6f)
        g.observe(0, small(.2f), 180.0, false)
        assertFalse(g.observe(150, small(.38f), 180.0, false).ready)
    }
    @Test fun expiredClearEvidenceCannotBeRescuedByPersistentBlur() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        for (i in 1..10) sample(g, i * 100L, sharpness = 20.0)
        assertFalse(sample(g, 1100).ready)
    }
    @Test fun resetAndInvalidGeometryCannotReuseEvidence() {
        val g = HandheldCaptureGate()
        sample(g, 0)
        g.reset()
        assertFalse(sample(g, 150).ready)
        assertFalse(g.observe(300, listOf(Float.NaN to 0f), 200.0, false).ready)
        assertFalse(sample(g, 450).ready)
        assertFalse(sample(g, 600, sharpness = Double.NaN).ready)
    }
}
