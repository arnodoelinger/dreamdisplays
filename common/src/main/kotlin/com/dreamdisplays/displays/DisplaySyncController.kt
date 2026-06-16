//package com.dreamdisplays.displays
//
//import com.dreamdisplays.player.util.daemon
//import kotlin.math.abs
//import kotlin.math.max
//
///**
// * Owns the playback-synchronisation state and decisions for a single [DisplayScreen]: tracking the last
// * server-reported target, deciding whether the owner made a real seek (vs. natural clock drift), and the
// * one-shot bootstrap that primes an empty server clock.
// *
// * Pulled out of [DisplayScreen] so the screen no longer interleaves sync timing math with texture and
// * playback management. The controller drives the screen back through a small set of `internal` hooks.
// */
//internal class DisplaySyncController(private val screen: DisplayScreen) {
//    @Volatile private var serverSyncReceivedAt: Long = 0L
//    @Volatile private var lastSyncTargetNs: Long = -1L
//    @Volatile private var lastSyncRecvWallNs: Long = 0L
//
//    /** Clears sync bookkeeping when a new video is loaded. */
//    fun reset() {
//        lastSyncTargetNs = -1L
//        serverSyncReceivedAt = 0L
//    }
//
//    /**
//     * Applies an incoming server Sync packet: starts the video if needed, matches pause state, and seeks to
//     * [currentTime] only when the owner made a genuine jump that exceeds the drift tolerance.
//     */
//    fun onSyncPacket(currentTime: Long, desiredPaused: Boolean) {
//        serverSyncReceivedAt = System.nanoTime()
//        val nanos = System.nanoTime()
//
//        screen.waitForMFInit {
//            if (!screen.videoStarted) screen.beginPlaybackPaused(desiredPaused)
//
//            val lostTime = System.nanoTime() - nanos
//            val targetTime = max(0L, currentTime + lostTime)
//            val drift = abs(targetTime - screen.currentTimeNanos)
//            val canSeek = screen.canSeek()
//
//            if (desiredPaused && !screen.isPaused) screen.setPaused(true)
//            val clockRunning = screen.isClockRunning()
//            val recvWallNs = System.nanoTime()
//            val ownerSeeked = if (lastSyncTargetNs < 0) {
//                true
//            } else {
//                val elapsed = recvWallNs - lastSyncRecvWallNs
//                abs(targetTime - (lastSyncTargetNs + elapsed)) > SYNC_JUMP_THRESHOLD_NS
//            }
//            lastSyncTargetNs = targetTime
//            lastSyncRecvWallNs = recvWallNs
//            if (ownerSeeked && canSeek && clockRunning && drift > SYNC_SEEK_TOLERANCE_NS)
//                screen.seekVideoTo(targetTime)
//            if (!desiredPaused && screen.isPaused) screen.setPaused(false)
//        }
//    }
//
//    /**
//     * For sync displays, the server's clock only exists once someone (the owner) has sent a Sync packet.
//     * If a fresh server / no-one's-been-owner-yet situation leaves playStates empty, RequestSync returns
//     * nothing and clients sit at 0 forever. So the owner sends a Sync ~1.5s after startVideo, but only if no
//     * server Sync arrived in that window (otherwise we'd overwrite the server's ticking clock with our local
//     * time=0 on every reconnect).
//     */
//    fun bootstrapIfNeeded() {
//        if (!screen.owner || !screen.isSync) return
//        val gen = screen.mediaGeneration
//        daemon({
//            try { Thread.sleep(1500) } catch (_: InterruptedException) { return@daemon }
//            if (gen != screen.mediaGeneration) return@daemon
//            if (serverSyncReceivedAt > 0L) return@daemon
//            if (!screen.owner || !screen.isSync) return@daemon
//            screen.sendSync()
//        }, "MediaPlayer-bootstrap-sync").start()
//    }
//
//    companion object {
//        private const val SYNC_SEEK_TOLERANCE_NS = 750_000_000L
//        private const val SYNC_JUMP_THRESHOLD_NS = 1_500_000_000L
//    }
//}
