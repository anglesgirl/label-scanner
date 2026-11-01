package com.anglesgirl.labelscanner.camera

import org.junit.Assert.*
import org.junit.Test

class DetectionFrameTrackerTest {
    private fun box(dx: Float = 0f, dy: Float = 0f) =
        DetectionFrameTracker.Box(.2f + dx, .3f + dy, .6f + dx, .7f + dy)
    private fun acquire(t: DetectionFrameTracker): DetectionFrameTracker.Box {
        assertNull(t.observe(0, box(), 0))
        assertNull(t.observe(100, box(), 0))
        return requireNotNull(t.observe(200, box(), 0))
    }

    @Test fun singleFrameAndFastBurstsNeverShowBox() {
        val t = DetectionFrameTracker()
        assertNull(t.observe(0, box(), 0))
        assertNull(t.observe(10, box(), 0))
        assertNull(t.observe(20, box(), 0))
    }
    @Test fun confirmedTargetAppears() { assertEquals(box(), acquire(DetectionFrameTracker())) }
    @Test fun tinyJitterIsIgnored() {
        val t = DetectionFrameTracker(); val b = acquire(t)
        assertEquals(b, t.observe(300, box(.003f, -.003f), 0))
    }
    @Test fun ordinaryHandMotionIsSmoothedAndBounded() {
        val t = DetectionFrameTracker(); val b = acquire(t)
        val next = requireNotNull(t.observe(300, box(.06f), 0))
        assertTrue(next.left > b.left)
        assertTrue(next.left - b.left <= .01801f)
        assertTrue(next.right < box(.06f).right)
    }
    @Test fun unrelatedTargetNeverFliesAcrossScreen() {
        val t = DetectionFrameTracker(); acquire(t)
        assertNull(t.observe(300, box(.3f), 0))
        assertNull(t.observe(400, box(.3f), 0))
        assertEquals(box(.3f), t.observe(500, box(.3f), 0))
    }
    @Test fun alternatingLabelsNeverAcquire() {
        val t = DetectionFrameTracker()
        for (i in 0..15) assertNull(t.observe(i * 100L, box(if (i % 2 == 0) 0f else .3f), 0))
    }
    @Test fun switchingPaperAndFieldSourcesRequiresConfirmation() {
        val t = DetectionFrameTracker(); acquire(t)
        assertNull(t.observe(300, box(), 1))
        assertNull(t.observe(400, box(), 1))
        assertEquals(box(), t.observe(500, box(), 1))
    }
    @Test fun briefMissKeepsBoxButLongMissRemovesIt() {
        val t = DetectionFrameTracker(); val b = acquire(t)
        assertEquals(b, t.miss(400))
        assertNull(t.miss(651))
        assertNull(t.observe(700, box(), 0))
    }
    @Test fun zoomHidesOldCoordinatesImmediately() {
        val t = DetectionFrameTracker(); acquire(t)
        assertNull(t.observe(300, box(), 0, adjusting = true))
        assertNull(t.observe(400, box(), 0))
        assertNull(t.observe(500, box(), 0))
        assertNotNull(t.observe(600, box(), 0))
    }
    @Test fun largeScaleChangeDoesNotReuseLock() {
        val t = DetectionFrameTracker(); acquire(t)
        assertNull(t.observe(300, DetectionFrameTracker.Box(.1f, .1f, .8f, .9f), 0))
    }
    @Test fun staleInvalidAndReversedGeometryClearLock() {
        val t = DetectionFrameTracker(); acquire(t)
        assertNull(t.observe(1000, box(), 0))
        assertNull(t.observe(1100, DetectionFrameTracker.Box(Float.NaN, .2f, .5f, .8f), 0))
        assertNull(t.observe(1200, DetectionFrameTracker.Box(.8f, .3f, .2f, .6f), 0))
        assertNull(t.observe(1300, box(), 0))
    }
    @Test fun slowPanFollowsInsteadOfFreezingAtOriginalAnchor() {
        val t = DetectionFrameTracker(); acquire(t)
        for (i in 1..9) assertNotNull(t.observe(200 + i * 100L, box(i * .01f), 0))
    }
}
