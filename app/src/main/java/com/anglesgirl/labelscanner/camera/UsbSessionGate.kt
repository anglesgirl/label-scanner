package com.anglesgirl.labelscanner.camera

/** Independent session and connection generations: unplug/replug may reuse the same device path. */
internal class UsbSessionGate {
    data class Ticket(val session: Int, val connection: Int, val deviceName: String)
    private var session = 0
    private var connection = 0
    private var ticket: Ticket? = null

    @Synchronized fun reset(): Int {
        session++
        connection++
        ticket = null
        return session
    }

    @Synchronized fun disconnect() {
        connection++
        ticket = null
    }

    @Synchronized fun claim(expectedSession: Int, deviceName: String): Ticket? {
        if (expectedSession != session || ticket != null) return null
        return Ticket(session, connection, deviceName).also { ticket = it }
    }

    @Synchronized fun isCurrent(candidate: Ticket): Boolean = ticket == candidate
    @Synchronized fun currentTicket(): Ticket? = ticket
}
