package com.dreamdisplays.platform.server.proxy

import com.dreamdisplays.core.protocol.proxy.packets.ClockReply
import java.util.concurrent.atomic.AtomicLong

/** Tracks the NTP-style clock offset between this backend and the proxy via sampled RTT measurements. */
object ProxyClock {
    private const val NO_SAMPLE = Long.MIN_VALUE

    /**
     * How long a sustained run of rejected outlier samples is tolerated before the next one is
     * force-accepted as a fresh baseline instead.
     */
    private const val STALE_REBASE_MS = 15_000L

    /** `proxyEpoch - localEpoch`, EWMA-smoothed (7:1) after the first sample. */
    private val offsetMs = AtomicLong(0L)

    /** Round-trip time of the last accepted sample; used to reject later, worse-than-2x outliers. */
    private val lastRttMs = AtomicLong(NO_SAMPLE)

    /** Local-clock instant of the last accepted sample; drives the [STALE_REBASE_MS] recovery above. */
    private val lastAcceptedAtMs = AtomicLong(NO_SAMPLE)

    /** Feeds one [ClockReply] round trip, sampled against [nowLocal] (this backend's clock at receipt). */
    fun onClockReply(reply: ClockReply, nowLocal: Long) {
        val rtt = nowLocal - reply.backendSendMs
        if (rtt < 0) return // Clock stepped backwards mid-flight; skip rather than poison the average

        val previousRtt = lastRttMs.get()
        val lastAccepted = lastAcceptedAtMs.get()
        val stale = lastAccepted == NO_SAMPLE || nowLocal - lastAccepted >= STALE_REBASE_MS
        val rebase = previousRtt == NO_SAMPLE || stale
        if (!rebase && rtt >= previousRtt * 2) return // discard latency-spike outliers

        // Midpoint estimate of proxy-vs-local offset, same derivation as classic NTP: average how far
        // ahead the proxy looked on receipt and on send, assuming symmetric one-way latency.
        val sample = ((reply.proxyRecvMs - reply.backendSendMs) + (reply.proxySendMs - nowLocal)) / 2
        val previousOffset = offsetMs.get()
        offsetMs.set(if (rebase) sample else (previousOffset * 7 + sample) / 8)
        lastRttMs.set(rtt)
        lastAcceptedAtMs.set(nowLocal)
    }

    /** Translates a proxy-epoch timestamp (e.g. a network broadcast's anchor) to this backend's local clock. */
    fun toLocal(proxyMs: Long): Long = proxyMs - offsetMs.get()

    /** Translates one of this backend's own local timestamps into the proxy's epoch, for outbound reports. */
    fun toProxy(localMs: Long): Long = localMs + offsetMs.get()

    /** This backend's best current estimate of the proxy's wall clock. */
    fun proxyNow(): Long = System.currentTimeMillis() + offsetMs.get()

    /** Whether at least one [ClockReply] has been accepted yet. */
    fun hasSample(): Boolean = lastRttMs.get() != NO_SAMPLE

    /** Resets to the unsynced state — called when the proxy connection is (re)initialized. */
    fun reset() {
        offsetMs.set(0L)
        lastRttMs.set(NO_SAMPLE)
        lastAcceptedAtMs.set(NO_SAMPLE)
    }
}
