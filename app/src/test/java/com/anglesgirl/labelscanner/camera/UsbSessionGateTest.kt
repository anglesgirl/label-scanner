package com.anglesgirl.labelscanner.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UsbSessionGateTest {
    @Test fun duplicatePermissionCallbacksOpenOnlyOnce() {
        val gate = UsbSessionGate()
        val session = gate.reset()
        val ticket = gate.claim(session, "/dev/bus/usb/001/002")!!
        assertNull(gate.claim(session, ticket.deviceName))
        assertNull(gate.claim(session, "other-camera"))
        assertTrue(gate.isCurrent(ticket))
    }

    @Test fun switchingSourceInvalidatesQueuedOpenAndPreview() {
        val gate = UsbSessionGate()
        val first = gate.reset()
        val old = gate.claim(first, "camera")!!
        val next = gate.reset()
        assertFalse(gate.isCurrent(old))
        assertNull(gate.claim(first, "camera"))
        val fresh = gate.claim(next, "camera")!!
        assertTrue(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(old))
    }

    @Test fun finishedScanThenIdleReopenOnSameDeviceUsesFreshSession() {
        val gate = UsbSessionGate()
        val firstScan = gate.reset()
        val previous = gate.claim(firstScan, "/dev/bus/usb/001/002")!!

        gate.reset() // The first scan Activity closes after returning its result.
        val secondScan = gate.reset() // A later scan must not reuse the old ticket.
        val reopened = gate.claim(secondScan, previous.deviceName)!!

        assertNotEquals(previous.session, reopened.session)
        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(reopened))
    }

    @Test fun unplugWhileOpeningInvalidatesResultEvenWhenDevicePathIsReused() {
        val gate = UsbSessionGate()
        val session = gate.reset()
        val old = gate.claim(session, "same-path")!!
        gate.disconnect()
        assertFalse(gate.isCurrent(old))
        assertNull(gate.currentTicket())
        val fresh = gate.claim(session, "same-path")!!
        assertNotEquals(old, fresh)
        assertTrue(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(old))
    }

    @Test fun rapidSwitchAndRepeatedDetachNeverReviveOldTickets() {
        val gate = UsbSessionGate()
        val stale = mutableListOf<UsbSessionGate.Ticket>()
        repeat(100) {
            val session = gate.reset()
            val ticket = gate.claim(session, "camera")!!
            stale.forEach { assertFalse(gate.isCurrent(it)) }
            gate.disconnect()
            gate.disconnect()
            assertFalse(gate.isCurrent(ticket))
            stale += ticket
        }
    }

    @Test fun simultaneousCallbacksHaveOnlyOneWinner() {
        val gate = UsbSessionGate()
        val session = gate.reset()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val attempts = (1..16).map {
                pool.submit<UsbSessionGate.Ticket?> { start.await(); gate.claim(session, "camera") }
            }
            start.countDown()
            val tickets = attempts.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, tickets.size)
            assertTrue(gate.isCurrent(tickets.single()))
        } finally {
            pool.shutdownNow()
        }
    }
}
