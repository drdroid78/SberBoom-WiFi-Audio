package ru.drdroid78.sberboom.protocol

/** Offset local minus remote and symmetric path delay, in microseconds.
 * Wire timestamps use CLOCK_MONOTONIC_RAW (Salute TV 0153ea44/0153f8e0).
 * The local clock is injected so tests and Android clock selection stay explicit.
 */
class StarPtpClock(private val maxAgeUs: Long = 2_000_000, private val minSamples: Int = 3) {
    init { require(maxAgeUs > 0 && minSamples in 1..9) }
    data class Estimate(val localMinusRemoteUs: Long, val pathDelayUs: Long, val samples: Int, val measuredAtUs: Long) {
        fun remoteToLocal(remoteUs: Long): Long = Math.addExact(remoteUs, localMinusRemoteUs)
    }
    private data class Sync(val delta: Long, val receivedUs: Long)
    private data class Pending(val sequence: Int, val sentUs: Long)
    private data class Sample(val offset: Long, val delay: Long, val measured: Long)
    private var sync: Sync? = null
    private var request: Pending? = null
    private val samples = java.util.ArrayDeque<Sample>()

    @Synchronized fun onSync(remoteSentUs: Long, localReceivedUs: Long) {
        sync = try { Sync(Math.subtractExact(localReceivedUs, remoteSentUs), localReceivedUs) }
        catch (_: ArithmeticException) { null }
    }
    @Synchronized fun onDelayRequest(sequence: Int, localSentUs: Long) {
        require(sequence in 0..65535)
        request = Pending(sequence, localSentUs)
    }
    @Synchronized fun onDelayResponse(sequence: Int, remoteReceivedUs: Long, localReceivedUs: Long): Boolean {
        val pending = request ?: return false
        if (pending.sequence != sequence) return false
        request = null
        val forward = sync ?: return false
        if (localReceivedUs < pending.sentUs || localReceivedUs < forward.receivedUs ||
            localReceivedUs - pending.sentUs > maxAgeUs || localReceivedUs - forward.receivedUs > maxAgeUs) return false
        val sample = try {
            val reverse = Math.subtractExact(remoteReceivedUs, pending.sentUs)
            val roundTrip = Math.addExact(forward.delta, reverse)
            if (roundTrip < 0 || roundTrip > maxAgeUs) return false
            Sample(Math.subtractExact(forward.delta, reverse) / 2, roundTrip / 2, localReceivedUs)
        } catch (_: ArithmeticException) { return false }
        while (samples.size >= 9) samples.removeFirst()
        samples.addLast(sample)
        return true
    }
    @Synchronized fun estimate(nowUs: Long): Estimate? {
        val fresh = samples.filter { nowUs >= it.measured && nowUs - it.measured <= maxAgeUs }
        if (fresh.size < minSamples) return null
        fun median(values: List<Long>) = values.sorted()[values.size / 2]
        return Estimate(median(fresh.map { it.offset }), median(fresh.map { it.delay }), fresh.size, fresh.maxOf { it.measured })
    }
}
