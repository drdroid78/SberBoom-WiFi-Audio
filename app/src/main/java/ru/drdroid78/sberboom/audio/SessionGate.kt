package ru.drdroid78.sberboom.audio

/** Exclusive ownership of one local/remote streaming attempt, including cleanup. */
class SessionGate {
    enum class Phase { IDLE, STARTING, ACTIVE, STOPPING }
    data class Ticket internal constructor(val id: Long)
    private var sequence = 0L
    private var current: Ticket? = null
    private var phase = Phase.IDLE
    private var startMayHaveReachedPeer = false
    private var closed = false

    @Synchronized fun begin(): Ticket? {
        if (closed || phase != Phase.IDLE) return null
        val t = Ticket(++sequence)
        current = t; phase = Phase.STARTING; startMayHaveReachedPeer = false
        return t
    }
    @Synchronized fun isStarting(t: Ticket) = current == t && phase == Phase.STARTING
    @Synchronized fun isActive(t: Ticket) = current == t && phase == Phase.ACTIVE
    @Synchronized fun isCurrent(t: Ticket) = current == t
    @Synchronized fun markStartSent(t: Ticket): Boolean {
        if (!isStarting(t)) return false
        startMayHaveReachedPeer = true
        return true
    }
    @Synchronized fun activate(t: Ticket): Boolean {
        if (!isStarting(t) || !startMayHaveReachedPeer) return false
        phase = Phase.ACTIVE
        return true
    }
    @Synchronized fun requestStop(): Ticket? {
        val t = current ?: return null
        phase = Phase.STOPPING
        return t
    }
    @Synchronized fun requestStop(t: Ticket): Boolean {
        if (current != t) return false
        phase = Phase.STOPPING
        return true
    }
    @Synchronized fun shouldSendStop(t: Ticket) = current == t && startMayHaveReachedPeer
    @Synchronized fun finish(t: Ticket): Boolean {
        if (current != t) return false
        current = null; phase = Phase.IDLE; startMayHaveReachedPeer = false
        return true
    }
    @Synchronized fun close(): Ticket? { closed = true; return requestStop() }
}
