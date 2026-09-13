package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioMasterClockTest {
    private val ms = 1_000_000L
    private val second = 1_000_000_000L

    private class FakeNanos(var now: Long = 0L) {
        fun advance(nanos: Long) {
            now += nanos
        }
    }

    private fun sample(nanos: Long, epoch: Int = 1, originKnown: Boolean = true) =
        AudioSink.ClockSample(nanos, epoch, originKnown)

    @Test
    fun `a known content origin is used verbatim`() {
        val clock = AudioMasterClock("test")
        // The audio process was seeked to a real position, so its clock IS the content position:
        // any bias here would show up directly as a lip-sync error.
        assertEquals(30 * second, clock.nanos(sample(30 * second), wallNanos = 25 * second, suspended = false) { null })
        assertEquals(31 * second, clock.nanos(sample(31 * second), wallNanos = 26 * second, suspended = false) { null })
    }

    @Test
    fun `an unknown origin is anchored onto the wall clock once per session`() {
        val clock = AudioMasterClock("test")
        // Live HLS: the line clock starts near zero wherever the segmenter joined, while video is
        // already at 40 s of wall time. The offset is measured once and then held.
        val first = clock.nanos(sample(0, originKnown = false), wallNanos = 40 * second, suspended = false) { null }
        assertEquals(40 * second, first)

        val later = clock.nanos(sample(5 * second, originKnown = false), wallNanos = 99 * second, suspended = false) {
            null
        }
        assertEquals(45 * second, later, "The anchor must be computed once, not re-derived per sample.")
    }

    @Test
    fun `an exact stream-PTS offset wins over the wall clock for an unknown origin`() {
        val clock = AudioMasterClock("test")
        // Live: the audio line came up 600 ms after video started, and the shared segmenter PTS say
        // the audio actually joined 250 ms ahead of the video. The exact figure must win over the
        // 600 ms the wall clock would have guessed.
        val out = clock.nanos(sample(0, originKnown = false), wallNanos = 600 * ms, suspended = false) { 250 * ms }
        assertEquals(250 * ms, out)
    }

    @Test
    fun `an exact offset that would rewind live video too far is floored`() {
        val clock = AudioMasterClock("test")
        // Audio claims to be 5 s ahead of the video join, i.e. the clock would have to sit 5 s behind
        // the wall position. Video can't rewind, so the anchor is floored to what pacing can absorb.
        val out = clock.nanos(sample(5 * second, originKnown = false), wallNanos = 5 * second, suspended = false) {
            -5 * second
        }
        assertTrue(out > 4 * second, "Expected the anchor to be floored near the wall position, got $out.")
        assertTrue(out <= 5 * second, "The floor must not push the clock past the wall position, got $out.")
    }

    @Test
    fun `an implausible exact offset is ignored in favour of the wall clock`() {
        val clock = AudioMasterClock("test")
        val out = clock.nanos(sample(0, originKnown = false), wallNanos = 12 * second, suspended = false) {
            10 * 60 * second // A PTS wrap or a different clock entirely
        }
        assertEquals(12 * second, out)
    }

    @Test
    fun `a new session re-anchors instead of carrying the previous bias`() {
        val clock = AudioMasterClock("test")
        clock.nanos(sample(0, epoch = 1, originKnown = false), wallNanos = 40 * second, suspended = false) { null }
        // The line restarted (audio-track switch, live audio recovery): its position is back near zero,
        // and this time the origin is known exactly, so no bias may survive from the previous session.
        val out = clock.nanos(sample(60 * second, epoch = 2), wallNanos = 61 * second, suspended = false) { null }
        assertEquals(60 * second, out)
    }

    @Test
    fun `a stuck line clock hands over to wall time and back again`() {
        val fake = FakeNanos()
        val clock = AudioMasterClock("test", fake::now)

        var wall = 10 * second
        assertEquals(10 * second, clock.nanos(sample(10 * second), wall, suspended = false) { null })

        // The device stops reporting progress. A short stall must ride through untouched
        fake.advance(300 * ms)
        wall += 300 * ms
        assertEquals(10 * second, clock.nanos(sample(10 * second), wall, suspended = false) { null })

        // A long one must not freeze the picture: playback keeps moving on wall time
        fake.advance(600 * ms)
        wall += 600 * ms
        val duringTakeover = clock.nanos(sample(10 * second), wall, suspended = false) { null }
        assertEquals(10 * second, duringTakeover, "Takeover starts from where the audio clock froze.")

        fake.advance(200 * ms)
        wall += 200 * ms
        val advancing = clock.nanos(sample(10 * second), wall, suspended = false) { null }
        assertEquals(10 * second + 200 * ms, advancing, "The clock must keep advancing during takeover.")

        // The line comes back, but behind where the takeover carried the clock to. It must not be
        // rewound to meet it: every already-decoded frame would then sit in the future, wait out the
        // pacing budget and be dropped — a drop-storm far worse than the stall that caused it.
        fake.advance(50 * ms)
        wall += 50 * ms
        val recovered = clock.nanos(sample(10 * second + 40 * ms), wall, suspended = false) { null }
        assertEquals(10 * second + 250 * ms, recovered, "The master clock must never run backwards.")
    }

    @Test
    fun `recovering behind the picture asks the audio to skip the gap`() {
        val fake = FakeNanos()
        val skips = ArrayList<Long>()
        val clock = AudioMasterClock("test", fake::now, requestAudioResync = { skips.add(it) })

        var wall = 10 * second
        clock.nanos(sample(10 * second), wall, suspended = false) { null }

        // Stall until the takeover engages, let wall time carry the clock a further 500 ms, then let
        // the line come back where it left off. The sound is now half a second behind the picture,
        // and only dropping that half second can put the two back together.
        fake.advance(second)
        wall += second
        clock.nanos(sample(10 * second), wall, suspended = false) { null }
        fake.advance(500 * ms)
        wall += 500 * ms
        clock.nanos(sample(10 * second + 10 * ms), wall, suspended = false) { null }

        assertEquals(1, skips.size, "Exactly one re-sync should have been asked for.")
        assertTrue(
            skips[0] in (450 * ms)..(550 * ms),
            "Expected the wall-time lead to be skipped, got ${skips[0] / 1_000_000} ms.",
        )
    }

    @Test
    fun `a line that is alive but lagging never freezes the picture`() {
        val fake = FakeNanos()
        val clock = AudioMasterClock("test", fake::now)

        var wall = 10 * second
        clock.nanos(sample(10 * second), wall, suspended = false) { null }
        fake.advance(second)
        wall += second
        clock.nanos(sample(10 * second), wall, suspended = false) { null } // takeover engages here
        fake.advance(second)
        wall += second
        assertEquals(11 * second, clock.nanos(sample(10 * second), wall, suspended = false) { null })

        // The line starts ticking again, but a whole second behind. Handing the clock back to it
        // here would rewind playback; holding the clock flat until it climbs back would freeze the
        // picture for that same second. It does neither — wall time keeps it moving.
        fake.advance(10 * ms)
        wall += 10 * ms
        val lagging = clock.nanos(sample(10 * second + ms), wall, suspended = false) { null }
        assertEquals(11 * second + 10 * ms, lagging, "A lagging line must not stop the clock.")

        // Once the skip has closed the gap the line takes over again, still without a step back
        fake.advance(50 * ms)
        wall += 50 * ms
        val handedBack = clock.nanos(sample(11 * second + 50 * ms), wall, suspended = false) { null }
        assertEquals(11 * second + 50 * ms, handedBack, "A caught-up line should drive the clock again.")
        assertTrue(handedBack >= lagging, "The hand-back must not step backwards.")
    }

    @Test
    fun `a suspended session never trips the stall takeover`() {
        val fake = FakeNanos()
        val clock = AudioMasterClock("test", fake::now)
        clock.nanos(sample(10 * second), 10 * second, suspended = false) { null }

        // Parked out of render distance: a frozen line clock is exactly what is supposed to happen
        repeat(10) {
            fake.advance(second)
            assertEquals(10 * second, clock.nanos(sample(10 * second), 20 * second, suspended = true) { null })
        }
        // And it must still be trusted immediately on resume, not treated as a fresh stall
        fake.advance(100 * ms)
        assertEquals(10 * second + 20 * ms, clock.nanos(sample(10 * second + 20 * ms), 20 * second, false) { null })
    }

    @Test
    fun `no line clock falls through to the wall clock`() {
        val clock = AudioMasterClock("test")
        assertEquals(7 * second, clock.nanos(AudioSink.ClockSample.NONE, 7 * second, suspended = false) { null })
        assertEquals(-1L, clock.nanos(AudioSink.ClockSample.NONE, -1L, suspended = false) { null })
    }

    @Test
    fun `the clock never steps backwards inside a session`() {
        val clock = AudioMasterClock("test")
        clock.nanos(sample(10 * second), 10 * second, suspended = false) { null }
        // A jittery line report must not un-due a frame that pacing already considered due
        val out = clock.nanos(sample(10 * second - 5 * ms), 10 * second, suspended = false) { null }
        assertEquals(10 * second, out)
    }

    @Test
    fun `reset forgets the session so the next one re-anchors`() {
        val clock = AudioMasterClock("test")
        clock.nanos(sample(0, epoch = 1, originKnown = false), 40 * second, suspended = false) { null }
        clock.reset()
        // Same epoch number as before, but nothing from the old session may survive the reset
        val out = clock.nanos(sample(0, epoch = 1, originKnown = false), 5 * second, suspended = false) { null }
        assertEquals(5 * second, out)
    }
}
